#!/usr/bin/env python3
"""One foreground supervisor, seven independent JVMs. Ctrl+C stops only our children."""
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

def environment():
    result = dict(os.environ)
    dotenv = ROOT / '.env'
    if dotenv.exists():
        for raw in dotenv.read_text().splitlines():
            line = raw.strip()
            if not line or line.startswith('#') or '=' not in line:
                continue
            key, value = line.removeprefix('export ').split('=', 1)
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            result.setdefault(key.strip(), value)
    return result

def main():
    env = environment()
    specs = [
        ('weather-service', [], 'WEATHER_PORT', 8211),
        ('news-service', [], 'NEWS_PORT', 8212),
        ('image-service', [], 'IMAGE_PORT', 8213),
        ('mcp-server', ['weather'], 'WEATHER_MCP_PORT', 8221),
        ('mcp-server', ['news'], 'NEWS_MCP_PORT', 8222),
        ('mcp-server', ['images'], 'IMAGE_MCP_PORT', 8223),
        ('agent', [], 'CHAT_PORT', 8210),
    ]
    ports = [int(env.get(key) or default) for _, _, key, default in specs]
    if len(set(ports)) != len(ports):
        sys.exit('У каждого процесса должен быть отдельный порт.')
    for port in ports:
        with socket.socket() as sock:
            try:
                sock.bind(('127.0.0.1', port))
            except OSError:
                sys.exit(f'Порт {port} занят. Измените порты в .env или остановите предыдущий запуск.')
    logs = Path(env.get('DATA_DIR', str(ROOT / 'data'))) / 'logs'
    logs.mkdir(parents=True, exist_ok=True)
    children, files = [], []
    def stop(*_):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        for (module, args, _, _), port in zip(specs, ports):
            label = module + ('-' + args[0] if args else '')
            binary = ROOT / module / 'build/install' / module / 'bin' / module
            if not binary.exists():
                raise RuntimeError('Нет сборки. Запустите ./run.sh без --no-build')
            log = (logs / (label + '.log')).open('a')
            files.append(log)
            process = subprocess.Popen([str(binary), *args], cwd=ROOT, env=env, stdout=log, stderr=log)
            children.append(process)
            deadline = time.monotonic() + 45
            while True:
                if process.poll() is not None:
                    raise RuntimeError(f'{label} завершился. Лог: {log.name}')
                try:
                    with urllib.request.urlopen(f'http://127.0.0.1:{port}/health', timeout=1) as response:
                        if response.status == 200:
                            break
                except (OSError, TimeoutError):
                    pass
                if time.monotonic() > deadline:
                    raise RuntimeError(f'{label} не запустился. Лог: {log.name}')
                time.sleep(.25)
            print(f'✓ {label:22} http://127.0.0.1:{port}', flush=True)
        print(f'\nЧат: http://localhost:{ports[-1]}\nSwagger изображений: http://localhost:{ports[2]}/swagger\nCtrl+C — остановить все семь процессов. Логи: {logs}', flush=True)
        while True:
            for process in children:
                if process.poll() is not None:
                    raise RuntimeError('Один из процессов завершился. Подробности в data/logs.')
            time.sleep(1)
    except KeyboardInterrupt:
        print('\nОстанавливаю day20…', flush=True)
    finally:
        for process in reversed(children):
            if process.poll() is None:
                process.terminate()
        for process in reversed(children):
            try:
                process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        for file in files:
            file.close()

if __name__ == '__main__':
    main()
