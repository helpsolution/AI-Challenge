#!/usr/bin/env python3
import argparse
import hashlib
import json
import math
import os
import re
import sqlite3
import ssl
import struct
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def request(url, body=None, key=None):
    headers = {'Content-Type': 'application/json'}
    if key:
        headers['Authorization'] = 'Bearer ' + key
    payload = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    started = time.monotonic()
    cafile = os.environ.get('SSL_CERT_FILE') or ('/etc/ssl/cert.pem' if Path('/etc/ssl/cert.pem').exists() else None)
    context = ssl.create_default_context(cafile=cafile)
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=payload, headers=headers), timeout=120, context=context) as response:
            return json.load(response), round((time.monotonic() - started) * 1000)
    except urllib.error.HTTPError as error:
        detail = error.read().decode(errors='replace')[:500]
        if key:
            detail = detail.replace(key, '***')
        raise RuntimeError(f'HTTP {error.code}: {detail}') from None


def load_env():
    values = {}
    for line in (ROOT / '.env').read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            values[key.strip()] = value.strip().strip('"\'')
    values.update(os.environ)
    return values


def load_fixed(path):
    db = sqlite3.connect(f'file:{path}?mode=ro', uri=True)
    db.row_factory = sqlite3.Row
    with db:
        meta = dict(db.execute('SELECT key, value FROM meta').fetchall())
        chunks = []
        for row in db.execute('SELECT * FROM chunks ORDER BY source, chunk_index'):
            chunk = dict(row)
            blob = chunk.pop('embedding')
            chunk['vector'] = struct.unpack('<' + 'f' * (len(blob) // 4), blob)
            chunks.append(chunk)
    db.close()
    return meta, chunks


def cosine(a, b):
    if len(a) != len(b):
        raise RuntimeError('Несовместимая размерность векторов')
    return sum(x * y for x, y in zip(a, b)) / math.sqrt(sum(x * x for x in a) * sum(y * y for y in b))


def historical_answers(path):
    text = path.read_text()
    entries = re.split(r'(?m)^## (\d+)\. (.+)$', text)[1:]
    result = {}
    for offset in range(0, len(entries), 3):
        ident, _, body = entries[offset:offset + 3]
        result[int(ident)] = {'plain': body.split('### Без RAG\n\n', 1)[1].split('\n\n### С RAG', 1)[0].strip(),
                              'rag': body.split('### С RAG\n\n', 1)[1].strip()}
    return result


def source_hit(question, sources):
    expected = {Path(p).name for p in question['sources']}
    return any(Path(s['source']).name in expected for s in sources) if expected else None


def context_message(question, sources):
    blocks = [f"[{s['number']}] {s['title']}\nРаздел: Введение\n{s['text']}" for s in sources]
    return 'Фрагменты лекций:\n' + '\n\n'.join(blocks) + '\n\nТекущий вопрос: ' + question


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--server', default='http://localhost:8241')
    parser.add_argument('--output', type=Path, default=ROOT / 'data/eval/chunking-run.json')
    args = parser.parse_args()
    env = load_env()
    key = env.get('DEEPSEEK_API_KEY', '')
    if not key:
        raise RuntimeError('Нет DEEPSEEK_API_KEY')
    day22 = ROOT.parent / 'day22'
    questions = json.loads((ROOT / 'data/eval/questions.json').read_text())
    fixed_meta, fixed_chunks = load_fixed(day22 / 'data/index/knowledge.db')
    status, _ = request(args.server + '/api/knowledge/status')
    if not status['ready'] or fixed_meta['model'] != status['meta']['model']:
        raise RuntimeError('Оба индекса должны быть готовы и использовать одну embedding-модель')
    for path in (ROOT / 'data/corpus').glob('*.md'):
        if path.read_bytes() != (day22 / 'data/corpus' / path.name).read_bytes():
            raise RuntimeError(f'Корпусы различаются: {path.name}')
    historical = historical_answers(day22 / 'data/eval/report.md')
    report = {'at': datetime.now(timezone.utc).isoformat(), 'fixedMeta': fixed_meta,
              'structuralMeta': status['meta'], 'questionsSha256': hashlib.sha256((ROOT / 'data/eval/questions.json').read_bytes()).hexdigest(),
              'controls': {'topK': 5, 'history': [], 'mode': 'rag', 'rewrite': False,
                           'filter': False, 'reranker': False, 'sameGenerationRequestExceptContext': True},
              'results': []}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    for question in questions:
        structural, _ = request(args.server + '/api/ask', {'question': question['question'], 'mode': 'rag', 'history': [], 'rerank': False, 'rewrite': False})
        trace, _ = request(args.server + '/api/traces/' + structural['traceId'])
        steps = {step['kind']: step for step in trace['steps']}
        query = steps['embedding']
        if query['input'] != 'search_query: ' + question['question']:
            raise RuntimeError('Неожиданный поисковый префикс или переписанный вопрос')
        ranked = sorted(fixed_chunks, key=lambda chunk: cosine(query['vector'], chunk['vector']), reverse=True)
        sources = [{'number': index + 1, 'chunkId': chunk['chunk_id'], 'source': chunk['source'], 'title': chunk['title'],
                    'url': chunk['url'], 'section': None, 'text': chunk['text'], 'score': cosine(query['vector'], chunk['vector'])}
                   for index, chunk in enumerate(ranked[:5])]
        payload = json.loads(steps['llm']['exchange']['requestBody'])
        payload['messages'][-1]['content'] = context_message(question['question'], sources)
        completion, duration = request(steps['llm']['exchange']['url'], payload, key)
        if completion['model'] != structural['llm']['model']:
            raise RuntimeError('DeepSeek вернул разные модели в паре')
        fixed = {'answer': completion['choices'][0]['message']['content'], 'sources': sources, 'prompt': payload['messages'],
                 'llm': {'model': completion['model'], 'promptTokens': completion.get('usage', {}).get('prompt_tokens'),
                         'completionTokens': completion.get('usage', {}).get('completion_tokens'), 'durationMs': duration},
                 'finishReason': completion['choices'][0].get('finish_reason')}
        report['results'].append({'question': question, 'historical': historical[question['id']], 'fixed': fixed,
                                  'structural': structural, 'queryVector': query['vector'],
                                  'generationSettings': {k: v for k, v in payload.items() if k != 'messages'},
                                  'fixedSourceHit': source_hit(question, sources),
                                  'structuralSourceHit': source_hit(question, structural['sources'])})
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print(f"{len(report['results'])}/{len(questions)}: {question['question']}", flush=True)
    print(f'Сохранено: {args.output}', flush=True)


if __name__ == '__main__':
    main()
