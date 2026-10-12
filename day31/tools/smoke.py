#!/usr/bin/env python3
"""Контрольный прогон живого RAG + MCP. Результаты остаются в ignored data/eval."""
import json
import pathlib
import subprocess
import sys
import time
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:8311'
ROOT = pathlib.Path(__file__).resolve().parents[1]

def call(message, scope='repo', history=None):
    request = urllib.request.Request(BASE + '/api/help', data=json.dumps({'message': message, 'scope': scope, 'history': history or []}).encode(), headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=180) as response:
        return json.load(response)

def contains(*words):
    return lambda answer: all(word.lower() in answer['answer'].lower() for word in words)

cases = [
    ('help', '/help', 'repo', lambda a: a['status'] == 'help'),
    ('branch', '/help На какой git-ветке я сейчас работаю?', 'repo', lambda a: a['status'] == 'answered' and a['model'] is None and a['branch'] == subprocess.check_output(['git', 'branch', '--show-current'], cwd=ROOT, text=True).strip()),
    ('files', '/help Какие файлы есть в day31?', 'day31', lambda a: a['status'] == 'answered' and a['model'] is None and 'day31/README.md' in a['answer']),
    ('status', '/help Что изменено в рабочем дереве?', 'repo', lambda a: a['status'] == 'answered' and a['model'] is None and any(t['name'] == 'git_status' for t in a['tools'])),
    ('structure', '/help Как устроен day31 и какие в нём модули?', 'day31', contains('assistant', 'project-mcp')),
    ('queue', '/help Сколько запросов одновременно генерирует модель и сколько ждут в очереди в day30?', 'day30', lambda a: a['status'] == 'answered' and ('3' in a['answer'] or 'три' in a['answer']) and ('1' in a['answer'] or 'одн' in a['answer'])),
    ('startup', '/help Как запустить day30 локально и где находится веб-чат?', 'day30', contains('8310', 'bootRun')),
    ('models', '/help Какие модели нужны для запуска day28?', 'day28', contains('nomic-embed-text', 'qwen3.5:9b')),
    ('explicit-scope', '/help Как устроена очередь запросов в day30?', 'day28', lambda a: a['scope'] == 'day30' and a['status'] == 'answered'),
    ('mixed-context', '/help На какой ветке я сейчас и как устроена очередь запросов в day30?', 'day30', lambda a: a['status'] == 'answered' and a['branch'] in a['answer'] and any(s['kind'] == 'mcp' and any(q['number'] == s['number'] for q in a['quotes']) for s in a['sources'])),
    ('unknown', '/help Какой любимый цвет у владельца проекта?', 'day31', lambda a: a['status'] in ['weak_context', 'no_answer']),
    ('isolation', '/help Сколько запросов в минуту разрешено на ключ в day28?', 'day28', lambda a: a['status'] in ['weak_context', 'no_answer']),
    ('missing-day', '/help Как устроен day999?', 'repo', lambda a: a['status'] == 'clarify'),
]
results = []
for name, question, scope, verify in cases:
    started = time.monotonic()
    try:
        answer = call(question, scope)
        passed = verify(answer)
        if answer['status'] == 'answered' and answer['model']:
            passed = passed and bool(answer['quotes']) and all(s['kind'] != 'document' or answer['scope'] == 'repo' or s['source'].startswith(answer['scope'] + '/') for s in answer['sources'])
        results.append({'case': name, 'passed': passed, 'seconds': round(time.monotonic()-started, 2), 'answer': answer})
        print(('PASS' if passed else 'FAIL') + ' ' + name + ': ' + answer['status'] + ' · ' + str(answer['durationMs']) + ' ms', flush=True)
        if not passed: print(answer['answer'] + '\n' + answer['reason'], flush=True)
    except Exception as error:
        results.append({'case': name, 'passed': False, 'error': str(error)})
        print('FAIL ' + name + ': ' + str(error), flush=True)
output = ROOT / 'data/eval/smoke.json'
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(results, ensure_ascii=False, indent=2))
print(str(sum(row['passed'] for row in results)) + '/' + str(len(results)) + ' passed. ' + str(output))
sys.exit(0 if all(row['passed'] for row in results) else 1)
