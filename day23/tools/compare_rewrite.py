#!/usr/bin/env python3
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

from compare_chunking import request, source_hit

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--server', default='http://127.0.0.1:8241')
    parser.add_argument('--output', type=Path, default=ROOT / 'data/eval/rewrite-run.json')
    args = parser.parse_args()
    status, _ = request(args.server + '/api/knowledge/status')
    if not status['ready']:
        raise RuntimeError('Индекс не готов')
    questions_path = ROOT / 'data/eval/questions.json'
    questions = json.loads(questions_path.read_text())
    questions.append({
        'id': 'followup', 'question': 'А когда он не нужен?',
        'history': [{'role': 'user', 'content': 'Что такое ADR?'},
                    {'role': 'assistant', 'content': 'ADR — Architecture Decision Record, запись архитектурного решения.'}],
        'expectation': 'Когда не нужен ADR: мелкая реализация, очевидное решение без trade-off, документ ради процесса.',
        'sources': ['data/corpus/01-10-adr-architecture-decision-records.md'],
    })
    report = {'at': datetime.now(timezone.utc).isoformat(), 'knowledge': status['meta'],
              'questionsSha256': hashlib.sha256(questions_path.read_bytes()).hexdigest(),
              'controls': {'mode': 'rag', 'rerank': True, 'filter': False, 'sameGenerationSettings': True}, 'results': []}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    for question in questions:
        row = {'question': question}
        for name, enabled in [('baseline', False), ('rewrite', True)]:
            answer, duration = request(args.server + '/api/ask', {
                'question': question['question'], 'history': question.get('history', []),
                'mode': 'rag', 'rerank': True, 'rewrite': enabled,
            })
            trace, _ = request(args.server + '/api/traces/' + answer['traceId'])
            steps = {step['kind']: step for step in trace['steps']}
            if answer['rewriting']['enabled'] != enabled or not answer['reranking']['enabled']:
                raise RuntimeError('Сервер не применил настройки эксперимента')
            if steps['embedding']['input'] != 'search_query: ' + answer['rewriting']['query']:
                raise RuntimeError('Эмбеддинг посчитан не по поисковому запросу')
            rerank_request = json.loads(steps['rerank']['exchange']['requestBody'])
            if rerank_request['query'] != answer['rewriting']['query']:
                raise RuntimeError('Реранкер получил другой поисковый запрос')
            if not answer['prompt'][-1]['content'].endswith('Текущий вопрос: ' + question['question']):
                raise RuntimeError('Вопрос генерации изменился')
            generation = json.loads(steps['llm']['exchange']['requestBody'])
            row[name] = {'response': answer, 'trace': trace, 'durationMs': duration,
                         'sourceHit': source_hit(question, answer['sources']),
                         'generationSettings': {k: v for k, v in generation.items() if k != 'messages'}}
        if (row['baseline']['generationSettings'] != row['rewrite']['generationSettings'] or
                row['baseline']['response']['llm']['model'] != row['rewrite']['response']['llm']['model']):
            raise RuntimeError('В паре отличаются настройки генерации или модель')
        report['results'].append(row)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        decision = row['rewrite']['response']['rewriting']
        print(f"{len(report['results'])}/{len(questions)}: {decision['status']} · {decision['query']}", flush=True)
    after, _ = request(args.server + '/api/knowledge/status')
    if after['meta']['fingerprint'] != status['meta']['fingerprint']:
        raise RuntimeError('Индекс изменился во время эксперимента')
    print(f'Сохранено: {args.output}', flush=True)


if __name__ == '__main__':
    main()
