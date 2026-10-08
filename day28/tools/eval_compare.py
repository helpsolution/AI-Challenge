#!/usr/bin/env python3
"""Сравнение моделей на контрольных вопросах: качество, скорость и стабильность.

Каждый вопрос задаётся --runs раз. Поиск в каждом прогоне один, его фрагменты получают все модели.
Ручная сверка с эталоном лежит отдельно (--review) и подмешивается в сводку; --summarize пересчитывает
сводку по уже сохранённому прогону, не задавая вопросы заново.
"""
import argparse
import json
import re
import statistics
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CITATIONS = re.compile(r'\[(\d+(?:\s*[,;]\s*\d+)*)]')
# Вопросы сверх контрольных: уточнение с историей и два заведомо слабых контекста.
EXTRA = [
    {'id': 'followup', 'question': 'А когда он не нужен?', 'expectAnswer': True,
     'history': [{'role': 'user', 'content': 'Что такое ADR?'},
                 {'role': 'assistant', 'content': 'ADR — Architecture Decision Record, запись архитектурного решения.'}]},
    {'id': 'offtopic', 'question': 'Как приготовить борщ?', 'expectAnswer': False},
    {'id': 'vague', 'question': 'Расскажи про него подробнее', 'expectAnswer': False},
]
UNKNOWN = {'weak_context', 'no_answer', 'unverified'}


def request(url, body=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=900) as response:
        return json.loads(response.read())


def ask(server, question):
    """Читает NDJSON-стрим /api/ask: событие retrieval и по событию answer на каждую модель."""
    body = json.dumps({'question': question['question'], 'history': question.get('history', [])}, ensure_ascii=False).encode()
    req = urllib.request.Request(server + '/api/ask', data=body, headers={'Content-Type': 'application/json'})
    retrieval, answers, error = None, [], None
    with urllib.request.urlopen(req, timeout=900) as response:
        for line in response:
            if not line.strip():
                continue
            event = json.loads(line)
            if event['type'] == 'retrieval':
                retrieval = event['data']
            elif event['type'] == 'answer':
                answers.append(event['data'])
            elif event['type'] == 'error':
                error = event['data']['message']
    return retrieval, answers, error


def utf16_slice(text, start, end):
    # Сервер считает offsets в UTF-16, как Kotlin String.
    raw = text.encode('utf-16-le')
    return raw[start * 2:end * 2].decode('utf-16-le')


def check(server, answer, chunks):
    numbers = {s['number']: s for s in answer['sources']}
    referenced = sorted({int(n) for m in CITATIONS.finditer(answer['answer']) for n in re.split(r'\s*[,;]\s*', m.group(1))})
    verbatim = []
    for quote in answer['quotes']:
        source = numbers.get(quote['number'])
        indexed = None
        if source:
            key = (source['source'], source['chunkId'])
            if key not in chunks:
                query = urllib.parse.urlencode({'source': source['source'], 'limit': 100})
                chunks.update({(c['source'], c['chunkId']): c for c in request(f'{server}/api/knowledge/chunks?{query}')})
            indexed = chunks.get(key)
        # Независимо от сервера: цитата — ровно тот кусок проиндексированного чанка, на который указывают её offsets.
        verbatim.append(bool(source and indexed and indexed['content'] == source['text'] and
                             utf16_slice(indexed['content'], quote['start'], quote['end']) == quote['text']))
    return {
        'hasSources': bool(answer['sources']),
        'hasQuotes': bool(answer['quotes']),
        'quotesVerbatim': bool(verbatim) and all(verbatim),
        'referencesQuoted': all(n in {q['number'] for q in answer['quotes']} for n in referenced),
        'sourcesMatchQuotes': sorted(numbers) == sorted({q['number'] for q in answer['quotes']}),
    }


def median(values):
    return statistics.median(values) if values else None


def summarize(report, review):
    questions = {str(q['id']): q for q in report['questions']}
    runs = report['runs']
    summary = []
    for m in report['models']:
        model = m['model']
        rows = [(r, a) for r in report['results'] for a in r['answers'] if a['model'] == model]
        positive = [a for r, a in rows if questions[str(r['questionId'])]['expectAnswer']]
        negative = [a for r, a in rows if not questions[str(r['questionId'])]['expectAnswer']]
        answered = [a for a in positive if a['status'] == 'answered']
        calls = [a for _, a in rows if a['llm']]
        local = [a for a in calls if a['llm']['timings']]
        verdicts = [review.get(model, {}).get(f"{r['questionId']}#{r['run']}") for r, a in rows if a['status'] == 'answered']

        def status_count(status):
            return sum(a['status'] == status for _, a in rows)

        by_question = {}
        for r, a in rows:
            by_question.setdefault(str(r['questionId']), []).append(a)
        complete = {q: answers for q, answers in by_question.items() if len(answers) == runs}
        spreads = []
        for answers in complete.values():
            durations = [a['llm']['durationMs'] for a in answers if a['llm']]
            if len(durations) == runs and median(durations):
                spreads.append((max(durations) - min(durations)) / median(durations) * 100)

        summary.append({
            'provider': m['provider'], 'model': model,
            'quality': {
                'positive': len(positive),
                'answered': len(answered),
                'checksPassed': sum(all(a['checks'].values()) for a in answered),
                'negative': len(negative),
                'unknownOnNegative': sum(a['status'] in UNKNOWN for a in negative),
                'noAnswer': status_count('no_answer'),
                'unverified': status_count('unverified'),
                'weakContext': status_count('weak_context'),
                'reviewed': sum(v is not None for v in verdicts),
                'correct': verdicts.count('correct'),
                'partial': verdicts.count('partial'),
                'wrong': verdicts.count('wrong'),
            },
            'speed': {
                'calls': len(calls),
                'medianMs': median([a['llm']['durationMs'] for a in calls]),
                'maxMs': max((a['llm']['durationMs'] for a in calls), default=None),
                'medianCompletionTokens': median([a['llm']['completionTokens'] for a in calls if a['llm']['completionTokens']]),
                # Локально — чистая генерация по таймингам Ollama; у облака только сквозная: токены / всё время запроса.
                'medianTokensPerSecond': median([a['llm']['completionTokens'] / a['llm']['timings']['generationMs'] * 1000
                                                 for a in local if a['llm']['timings']['generationMs']]
                                                if local else [a['llm']['completionTokens'] / a['llm']['durationMs'] * 1000
                                                               for a in calls if a['llm']['durationMs'] and a['llm']['completionTokens']]),
                'tokensPerSecondKind': 'generation' if local else 'end_to_end',
                'medianPromptMs': median([a['llm']['timings']['promptMs'] for a in local]),
                'medianPromptTokensPerSecond': median([(a['llm']['promptTokens'] - (a['llm']['timings']['cachedPromptTokens'] or 0))
                                                       / a['llm']['timings']['promptMs'] * 1000
                                                       for a in local if a['llm']['timings']['promptMs']]),
                'maxLoadMs': max((a['llm']['timings']['loadMs'] for a in local), default=None),
            },
            'stability': {
                'questions': len(complete),
                'sameStatus': sum(len({a['status'] for a in answers}) == 1 for answers in complete.values()),
                'sameText': sum(len({a['answer'] for a in answers}) == 1 for answers in complete.values()),
                'failed': status_count('failed'),
                'medianSpreadPercent': median(spreads),
            },
        })

    retrieval = [r['retrieval'] for r in report['results'] if r['retrieval']]
    fragments = {}
    for r in report['results']:
        if r['retrieval']:
            fragments.setdefault(str(r['questionId']), set()).add(tuple(r['retrieval']['chunkIds']))
    report['summary'] = summary
    report['review'] = review
    report['retrievalSummary'] = {
        'medianRewriteMs': median([r['rewriteMs'] for r in retrieval]),
        'medianSearchMs': median([r['retrievalMs'] - r['rewriteMs'] for r in retrieval]),
        'sameFragments': sum(len(v) == 1 for v in fragments.values()),
        'questions': len(fragments),
    }
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--server', default='http://127.0.0.1:8290')
    parser.add_argument('--runs', type=int, default=3)
    parser.add_argument('--output', type=Path, default=ROOT / 'data/eval/compare-run.json')
    parser.add_argument('--review', type=Path, default=ROOT / 'data/eval/compare-review.json')
    parser.add_argument('--summarize', action='store_true', help='только пересчитать сводку по сохранённому прогону')
    args = parser.parse_args()
    review = json.loads(args.review.read_text()) if args.review.exists() else {}

    if args.summarize:
        report = summarize(json.loads(args.output.read_text()), review)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print_summary(report)
        return

    if not request(args.server + '/api/knowledge/status')['ready']:
        raise RuntimeError('Индекс не готов')
    models = [{'provider': m['provider'], 'model': m['model']} for m in request(args.server + '/api/models')]
    questions = [{**q, 'expectAnswer': bool(q['sources'])} for q in json.loads((ROOT / 'data/eval/questions.json').read_text())] + EXTRA
    report = {'at': datetime.now(timezone.utc).isoformat(), 'runs': args.runs, 'models': models,
              'questions': [{'id': q['id'], 'question': q['question'], 'expectAnswer': q['expectAnswer'],
                             'expectation': q.get('expectation')} for q in questions],
              'results': []}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    chunks = {}
    started = time.time()
    for run in range(1, args.runs + 1):
        for q in questions:
            retrieval, answers, error = ask(args.server, q)
            for a in answers:
                a['checks'] = check(args.server, a, chunks) if a['status'] == 'answered' else {}
            report['results'].append({
                'run': run, 'questionId': q['id'], 'error': error,
                'retrieval': retrieval and {
                    'query': retrieval['rewriting']['query'], 'rewriteStatus': retrieval['rewriting']['status'],
                    'rewriteMs': retrieval['rewriting']['durationMs'], 'retrievalMs': retrieval['retrievalMs'],
                    'bestScore': retrieval['relevance']['bestScore'], 'passed': retrieval['relevance']['passed'],
                    'chunkIds': [f['chunkId'] for f in retrieval['fragments']], 'traceId': retrieval['traceId'],
                },
                'answers': [{k: a[k] for k in ('provider', 'model', 'status', 'answer', 'reason', 'draft', 'quotes', 'sources', 'llm', 'checks')}
                            for a in answers],
            })
            summarize(report, review)
            args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
            cells = ' | '.join(f"{a['model']}: {a['status']}" + (f" {a['llm']['durationMs'] / 1000:.1f}с" if a['llm'] else '') +
                               ('' if not a['checks'] or all(a['checks'].values()) else ' ПРОВЕРКИ!') for a in answers)
            print(f"[{time.time() - started:6.0f}с] прогон {run} · {str(q['id']):>8} · {cells}{' · ОШИБКА ' + error if error else ''}", flush=True)
    print_summary(report)
    print(f'Сохранено: {args.output}')


def print_summary(report):
    for s in report['summary']:
        q, sp, st = s['quality'], s['speed'], s['stability']
        print(f"\n{s['model']} ({s['provider']})\n"
              f"  качество: ответ с цитатами {q['answered']}/{q['positive']}, проверки {q['checksPassed']}/{q['answered']}, "
              f"«не знаю» на посторонних {q['unknownOnNegative']}/{q['negative']}, отклонено проверкой {q['unverified']}, "
              f"ручная сверка: верно {q['correct']}, частично {q['partial']}, неверно {q['wrong']} из {q['reviewed']}\n"
              f"  скорость: медиана {fmt_ms(sp['medianMs'])}, максимум {fmt_ms(sp['maxMs'])}, "
              f"{sp['medianTokensPerSecond'] or 0:.0f} ток/с ({sp['tokensPerSecondKind']}), промпт {fmt_ms(sp['medianPromptMs'])}\n"
              f"  стабильность: одинаковый статус {st['sameStatus']}/{st['questions']}, одинаковый текст {st['sameText']}/{st['questions']}, "
              f"ошибок {st['failed']}, разброс времени {st['medianSpreadPercent'] or 0:.0f}%")
    r = report['retrievalSummary']
    print(f"\nпоиск: переформулировка {fmt_ms(r['medianRewriteMs'])}, эмбеддинг+косинус+BGE {fmt_ms(r['medianSearchMs'])}, "
          f"одинаковые фрагменты во всех прогонах {r['sameFragments']}/{r['questions']}")


def fmt_ms(ms):
    return '—' if ms is None else f'{ms / 1000:.1f} с'


if __name__ == '__main__':
    main()
