#!/usr/bin/env python3
"""Живые прогоны LLM + MCP. Полные трассы и результаты: ignored data/eval/."""
import concurrent.futures
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:8340'
OUTPUT = ROOT / 'data/eval'
OUTPUT.mkdir(parents=True, exist_ok=True)
DOCS = 'Проверь документацию API бронирований и обнови её по текущему коду. Исправь README и создай подробное описание API.'
AUDIT = 'Проверь модуль бронирований на соответствие правилам проекта и сохрани отчёт с доказательствами и номерами строк.'


def seed_hashes():
    return {str(p.relative_to(ROOT / 'demo/project')): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in (ROOT / 'demo/project').rglob('*') if p.is_file()}


def check_patch(result):
    # Патч должен воспроизводить каждое сохранённое изменение на чистом примере.
    with tempfile.TemporaryDirectory(prefix='day34-patch-') as directory:
        project = pathlib.Path(directory) / 'project'
        shutil.copytree(ROOT / 'demo/project', project)
        patch = pathlib.Path(directory) / 'changes.patch'
        patch.write_text(''.join(f['diff'] for f in result['changes']))
        subprocess.run(['git', 'apply', '--check', str(patch)], cwd=project, check=True, capture_output=True)
        subprocess.run(['git', 'apply', str(patch)], cwd=project, check=True, capture_output=True)
        assert all((project / f['path']).read_text() == f['content'] for f in result['changes'])


def run_case(name, message):
    started = time.monotonic()
    events = []
    answer = None
    try:
        request = urllib.request.Request(BASE + '/api/tasks', data=json.dumps({'message': message}).encode(),
                                         headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=600) as response:
            event = 'message'
            data = []
            for raw in response:
                line = raw.decode().rstrip('\r\n')
                if line.startswith('event:'):
                    event = line[6:].strip()
                elif line.startswith('data:'):
                    data.append(line[5:].lstrip())
                elif not line and data:
                    value = json.loads('\n'.join(data)); data = []
                    if event != 'delta':
                        events.append({'event': event, 'data': value})
                    if event == 'step' and value['kind'] in ('write', 'check'):
                        print(name + ': ' + value['title'] + ' · ' + value['status'], flush=True)
                    if event == 'answer':
                        answer = value
        assert answer and answer['status'] == 'completed', answer
        result = answer['result']
        assert len(result['readFiles']) >= 3
        changed = {f['path']: f for f in result['changes']}
        if name.startswith('docs'):
            assert 'README.md' in changed and 'docs/api.md' in changed
            for path in ('README.md', 'docs/api.md'):
                assert 'PATCH' in changed[path]['content'] and 'bookings/{id}' in changed[path]['content'], path
        else:
            assert 'docs/audit.md' in changed
            audit = changed['docs/audit.md']['content']
            assert 'BookingController.kt' in audit
            assert any(word in audit.lower() for word in ['длительност', 'бизнес-проверк', 'бизнес-валид'])
            assert len(audit) > 800
        check_patch(result)
        read = urllib.request.urlopen(BASE + '/api/runs/' + result['workspaceId'] + '/diff', timeout=30).read().decode()
        assert read == ''.join(f['diff'] for f in result['changes'])
        result_data = {'case': name, 'passed': True, 'seconds': round(time.monotonic()-started, 2), 'answer': answer, 'events': events}
    except Exception as error:
        result_data = {'case': name, 'passed': False, 'error': str(error), 'answer': answer, 'events': events}
    (OUTPUT / (name + '.json')).write_text(json.dumps(result_data, ensure_ascii=False, indent=2))
    print(('PASS ' if result_data['passed'] else 'FAIL ') + name +
          (f" · {answer['durationMs']/1000:.1f} s · ${answer['costUsd']:.4f}" if answer else '') +
          (' · ' + result_data['error'][:500] if not result_data['passed'] else ''), flush=True)
    return result_data


if __name__ == '__main__':
    before = seed_hashes()
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(run_case, name, message) for name, message in [('docs', DOCS), ('audit', AUDIT)]]
        results = [f.result() for f in futures]
    # Повторный сценарий с новой копией исходников.
    results.append(run_case('docs-repeat', DOCS))
    assert before == seed_hashes(), 'Исходный пример изменился'
    ids = [r['answer']['workspaceId'] for r in results if r.get('answer')]
    assert len(ids) == len(set(ids)), 'Запуски используют одну рабочую копию'
    summary = [{k: v for k, v in r.items() if k not in ('events', 'answer')} for r in results]
    (OUTPUT / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    print(str(sum(r['passed'] for r in results)) + '/' + str(len(results)) + ' passed; исходники не изменены', flush=True)
    sys.exit(0 if all(r['passed'] for r in results) else 1)
