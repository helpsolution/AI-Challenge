#!/usr/bin/env python3
"""Живые прогоны LLM + MCP. Полные трассы и результаты: ignored data/eval/."""
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
BASE = next((arg for arg in sys.argv[1:] if arg.startswith('http')), 'http://127.0.0.1:8350')
OUTPUT = ROOT / 'data/eval'
OUTPUT.mkdir(parents=True, exist_ok=True)
DOCS = 'Сверь документацию проекта с актуальным кодом. Исправь подтверждённые расхождения в README и docs. Сохрани отчёт docs/sync-report.md с доказательствами в формате путь:строка и ограничениями.'


def project_info():
    with urllib.request.urlopen(BASE + '/api/project', timeout=30) as response:
        return json.load(response)


def seed_hashes():
    info = project_info()
    source = pathlib.Path(info['directory'])
    return {f['path']: hashlib.sha256((source / f['path']).read_bytes()).hexdigest() for f in info['files']}


def check_patch(result):
    # Патч должен воспроизводить каждое сохранённое изменение на чистом примере.
    with tempfile.TemporaryDirectory(prefix='day35-patch-') as directory:
        project = pathlib.Path(directory) / 'project'
        shutil.copytree(pathlib.Path(result['directory']).parent / 'original', project)
        patch = pathlib.Path(directory) / 'changes.patch'
        patch.write_text(''.join(f['diff'] for f in result['changes']))
        subprocess.run(['git', 'apply', '--check', str(patch)], cwd=project, check=True, capture_output=True)
        subprocess.run(['git', 'apply', str(patch)], cwd=project, check=True, capture_output=True)
        assert all((project / f['path']).read_text() == f['content'] for f in result['changes'])


def check_answer(answer):
    assert answer and answer['status'] == 'completed', answer
    result = answer['result']
    assert len(result['readFiles']) >= 3
    assert result['patchApplicable'], result['patchError']
    assert result['sourceChangedPaths'] == []
    assert result['sourceDirectory'] == project_info()['directory']
    changed = {f['path']: f for f in result['changes']}
    assert 'docs/sync-report.md' in changed
    report = changed['docs/sync-report.md']['content']
    assert len(report) > 500
    # В отчёте должна быть хотя бы одна реальная ссылка на прочитанный исходник.
    import re
    references = re.findall(r'([A-Za-z0-9_./-]+\.(?:kt|java|py|ts|js|go|rs)):(\d+)', report)
    original = pathlib.Path(result['directory']).parent / 'original'
    valid = []
    for reference, line in references:
        # Допускаем сокращённое имя файла, если среди прочитанных оно однозначно.
        paths = [path for path in result['readFiles'] if path == reference or path.endswith('/' + reference)]
        if len(paths) == 1 and (original / paths[0]).is_file() and 1 <= int(line) <= len((original / paths[0]).read_text().splitlines()):
            valid.append((paths[0], int(line)))
    assert valid, 'Нет проверяемых ссылок на прочитанные исходники'
    # Для встроенного примера известен пропущенный в README метод PATCH.
    if pathlib.Path(result['sourceDirectory']).resolve() == (ROOT.parent / 'day32/sandbox').resolve():
        assert 'README.md' in changed, 'Не обновлён README с отсутствующим PATCH'
        assert 'PATCH' in changed['README.md']['content']
        assert 'BookingController' in report
        assert '`BookingController` — только HTTP:' not in changed['README.md']['content'], 'Сохранено исходное неверное описание контроллера'
        assert 'длительност' in report.lower(), 'Не описана проверка длительности'
    check_patch(result)
    read = urllib.request.urlopen(BASE + '/api/runs/' + result['workspaceId'] + '/diff', timeout=30).read().decode()
    assert read == ''.join(f['diff'] for f in result['changes'])


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
        check_answer(answer)
        result_data = {'case': name, 'passed': True, 'seconds': round(time.monotonic()-started, 2), 'answer': answer, 'events': events}
    except Exception as error:
        result_data = {'case': name, 'passed': False, 'error': str(error) or type(error).__name__, 'answer': answer, 'events': events}
    (OUTPUT / (name + '.json')).write_text(json.dumps(result_data, ensure_ascii=False, indent=2))
    print(('PASS ' if result_data['passed'] else 'FAIL ') + name +
          (f" · {answer['durationMs']/1000:.1f} s · ${answer['costUsd']:.4f}" if answer else '') +
          (' · ' + result_data['error'][:500] if not result_data['passed'] else ''), flush=True)
    return result_data


if __name__ == '__main__':
    before = seed_hashes()
    if '--check-saved' in sys.argv:
        # Повторная проверка документов, ссылок, скачивания и применения diff без нового запроса к AI.
        saved = json.loads((OUTPUT / 'docs.json').read_text())
        check_answer(saved['answer'])
        result = saved['answer']['result']
        original = pathlib.Path(result['directory']).parent / 'original'
        snapshot_hashes = {str(p.relative_to(original)): hashlib.sha256(p.read_bytes()).hexdigest()
                           for p in original.rglob('*') if p.is_file()}
        assert before == snapshot_hashes, 'Исходный проект отличается от сохранённого снимка'
        saved['passed'] = True
        saved.pop('error', None)
        saved['validation'] = 'Сохранённый ответ живого прогона проверен повторно без нового вызова AI.'
        (OUTPUT / 'docs.json').write_text(json.dumps(saved, ensure_ascii=False, indent=2))
        results = [saved]
        print('PASS saved live result · patch, references, source, download', flush=True)
    else:
        results = [run_case('docs', DOCS)]
    assert before == seed_hashes(), 'Исходный проект изменился'
    summary = [{k: v for k, v in r.items() if k not in ('events', 'answer')} for r in results]
    (OUTPUT / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    print(str(sum(r['passed'] for r in results)) + '/' + str(len(results)) + ' passed; исходный проект не изменён', flush=True)
    sys.exit(0 if all(r['passed'] for r in results) else 1)
