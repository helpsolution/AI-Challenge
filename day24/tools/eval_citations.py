#!/usr/bin/env python3
"""Прогон контрольных вопросов: у каждого ответа есть источники и дословные цитаты, а слабый контекст даёт «не знаю».

Смысл ответа против цитат здесь не оценивается — это ручной разбор по сохранённому JSON.
"""
import argparse
import json
import re
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CITATIONS = re.compile(r'\[(\d+(?:\s*[,;]\s*\d+)*)]')
# Вопросы сверх десяти контрольных: уточнение с историей и два заведомо слабых контекста для режима «не знаю».
EXTRA = [
    {'id': 'followup', 'question': 'А когда он не нужен?', 'expectAnswer': True,
     'history': [{'role': 'user', 'content': 'Что такое ADR?'},
                 {'role': 'assistant', 'content': 'ADR — Architecture Decision Record, запись архитектурного решения.'}]},
    {'id': 'offtopic', 'question': 'Как приготовить борщ?', 'expectAnswer': False},
    {'id': 'vague', 'question': 'Расскажи про него подробнее', 'expectAnswer': False},
]


def request(url, body=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=300) as response:
        return json.loads(response.read())


def utf16_slice(text, start, end):
    # Сервер считает offsets в UTF-16, как Kotlin String.
    raw = text.encode('utf-16-le')
    return raw[start * 2:end * 2].decode('utf-16-le')


def check(server, answer):
    numbers = {s['number']: s for s in answer['sources']}
    referenced = sorted({int(n) for m in CITATIONS.finditer(answer['answer']) for n in re.split(r'\s*[,;]\s*', m.group(1))})
    verbatim = []
    for quote in answer['quotes']:
        source = numbers.get(quote['number'])
        indexed = None
        if source:
            query = urllib.parse.urlencode({'source': source['source'], 'limit': 100})
            indexed = next((c for c in request(f'{server}/api/knowledge/chunks?{query}') if c['chunkId'] == source['chunkId']), None)
        # Независимо от сервера: цитата — это ровно тот кусок проиндексированного чанка, на который она указывает.
        verbatim.append(bool(source and indexed and indexed['content'] == source['text'] and
                             utf16_slice(indexed['content'], quote['start'], quote['end']) == quote['text']))
    return {
        'hasSources': bool(answer['sources']),
        'hasQuotes': bool(answer['quotes']),
        'quotesVerbatim': bool(verbatim) and all(verbatim),
        'referencesQuoted': all(n in {q['number'] for q in answer['quotes']} for n in referenced),
        'sourcesMatchQuotes': sorted(numbers) == sorted({q['number'] for q in answer['quotes']}),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--server', default='http://127.0.0.1:8250')
    parser.add_argument('--output', type=Path, default=ROOT / 'data/eval/citations-run.json')
    args = parser.parse_args()
    if not request(args.server + '/api/knowledge/status')['ready']:
        raise RuntimeError('Индекс не готов')
    questions = [{**q, 'expectAnswer': bool(q['sources'])} for q in json.loads((ROOT / 'data/eval/questions.json').read_text())] + EXTRA
    report = {'at': datetime.now(timezone.utc).isoformat(), 'results': []}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    for q in questions:
        answer = request(args.server + '/api/ask', {'question': q['question'], 'history': q.get('history', [])})
        trace = request(args.server + '/api/traces/' + answer['traceId'])
        answered = answer['status'] == 'answered'
        checks = check(args.server, answer) if answered else {}
        report['results'].append({'question': q, 'response': answer, 'trace': trace, 'checks': checks})
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        ok = 'ok' if all(checks.values()) else 'FAIL ' + ', '.join(k for k, v in checks.items() if not v)
        expected = 'ожидаемо' if answered == q['expectAnswer'] else 'НЕОЖИДАННО'
        print(f"{q['id']:>8} · {answer['status']:<12} · {expected:<10} · BGE {answer['relevance']['bestScore']:6.2f} · "
              f"{len(answer['sources'])} ист. · {len(answer['quotes'])} цит. · {ok if answered else answer['reason'][:70]}", flush=True)
    results = report['results']
    answered = [r for r in results if r['response']['status'] == 'answered']
    print(f"\nОтветов с цитатами: {len(answered)}/{len(results)}; все проверки пройдены: "
          f"{sum(all(r['checks'].values()) for r in answered)}/{len(answered)}. Сохранено: {args.output}")


if __name__ == '__main__':
    main()
