#!/usr/bin/env python3
"""Offline integration test: real seven JVMs + MCP SDK, deterministic provider fixtures.

Run after ./gradlew installDist: python3 scripts/smoke.py
No API keys, paid requests, or network outside localhost.
"""
import base64
from datetime import datetime, timedelta, timezone
from email.utils import format_datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import threading
import time
from urllib.error import HTTPError
from urllib.parse import urlparse, parse_qs, urlencode
from urllib.request import Request, urlopen
import uuid
import zlib

ROOT = Path(__file__).resolve().parents[1]
IMAGE_CALLS = []
FIXTURE_REQUESTS = []

def png():
    def chunk(kind, body):
        return struct.pack('!I', len(body)) + kind + body + struct.pack('!I', zlib.crc32(kind + body) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', 2, 2, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(b'\x00\xff\x00\x00\xff\x00\x00' * 2)) + chunk(b'IEND', b'')

def rss(old=False):
    now = datetime.now(timezone.utc)
    items = []
    for i in range(12):
        date = now - timedelta(days=2) if old or i == 11 else now - timedelta(seconds=i)
        items.append(f'<item><title>Kotlin событие {i}</title><link>https://habr.com/ru/news/{1000+i}/</link><guid>https://habr.com/ru/news/{1000+i}/</guid><pubDate>{format_datetime(date)}</pubDate><description><![CDATA[<p>Достоверный анонс Kotlin номер {i}.</p>]]></description><category>Kotlin</category></item>')
    return ('<?xml version="1.0"?><rss version="2.0"><channel>' + ''.join(items) + '</channel></rss>').encode()

class Fixtures(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass
    def reply(self, value, status=200, kind='application/json'):
        data = json.dumps(value, ensure_ascii=False).encode() if not isinstance(value, bytes) else value
        self.send_response(status)
        self.send_header('Content-Type', kind)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def do_GET(self):
        FIXTURE_REQUESTS.append(self.path)
        route = urlparse(self.path)
        if route.path == '/geo':
            self.reply({'results': [{'name': 'Москва', 'country': 'Россия', 'latitude': 55.75, 'longitude': 37.62, 'timezone': 'Europe/Moscow'}]})
        elif route.path == '/forecast':
            self.reply({'timezone': 'Europe/Moscow', 'current': {'temperature_2m': 12, 'weather_code': 61, 'wind_speed_10m': 2},
                        'daily': {'time': [datetime.now(timezone(timedelta(hours=3))).date().isoformat()], 'temperature_2m_max': [14], 'temperature_2m_min': [7], 'weather_code': [61], 'precipitation_probability_max': [80]}})
        elif route.path.startswith('/ru/rss/'):
            self.reply(rss(parse_qs(route.query).get('q') == ['old']), kind='application/rss+xml')
        else:
            self.reply({'message': 'missing fixture'}, 404)
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if self.path == '/images':
            IMAGE_CALLS.append(body)
            if 'FAIL' in body['prompt']:
                self.reply({'error': {'message': 'private provider detail'}}, 502)
            else:
                self.reply({'data': [{'b64_json': base64.b64encode(png()).decode(), 'media_type': 'image/png'}], 'usage': {'cost': 0}})
            return
        if self.path != '/chat/completions':
            return self.reply({}, 404)
        messages = body['messages']
        question = next(m['content'] for m in reversed(messages) if m['role'] == 'user')
        current = messages[max(i for i,m in enumerate(messages) if m['role'] == 'user') + 1:]
        calls = [c for m in current for c in m.get('tool_calls', [])]
        called = [c['function']['name'] for c in calls]
        names = [d['function']['name'] for d in body.get('tools', [])]
        assert set(names) == {'weather__search_city', 'weather__today', 'news__headlines', 'images__generate'}, names
        plans = {
            'WEATHER': [('weather__search_city', {'query': 'Москва'}), ('weather__today', {'latitude': 55.75, 'longitude': 37.62})],
            'NEWS': [('news__headlines', {'topic': 'Kotlin', 'mode': 'mixed', 'limit': 10, 'todayOnly': True, 'timezone': 'Europe/Moscow'})],
            'IMAGE': [('images__generate', {'prompt': 'Красный квадрат', 'aspectRatio': '1:1'})],
            'BADARGS': [('news__headlines', {'limit': 'ten'})],
            'UNKNOWN': [('weather__unknown', {})],
            'RETRY': [('images__generate', {'prompt': 'FAIL'}), ('images__generate', {'prompt': 'FAIL retry'})],
        }
        if question in ('DAY', 'SUMMARY'):
            plan = plans['WEATHER'] + plans['NEWS']
            if question == 'DAY':
                plan += [('images__generate', {'prompt': 'Москва, дождь 12°C, Kotlin событие 0. Редакционный коллаж.', 'aspectRatio': '16:9'})]
        elif question == 'FOLLOWUP':
            assert any('Результаты инструментов из прошлого хода' in m.get('content', '') for m in messages)
            plan = []
        else:
            plan = plans[question]
        if len(called) < len(plan):
            name, args = plan[len(called)]
            if question == 'DAY' and name == 'images__generate':
                results = [m['content'] for m in current if m['role'] == 'tool']
                assert any('temperature_2m' in r for r in results)
                assert any('Kotlin событие 0' in r for r in results)
            reply = {'role': 'assistant', 'content': None, 'tool_calls': [{'id': str(uuid.uuid4()), 'type': 'function', 'function': {'name': name, 'arguments': json.dumps(args, ensure_ascii=False)}}]}
        else:
            reply = {'role': 'assistant', 'content': 'Проверка завершена. [Источник](https://habr.com/ru/news/1000/).'}
        self.reply({'choices': [{'message': reply, 'finish_reason': 'stop'}]})

def request(url, data=None, status=200):
    req = Request(url, json.dumps(data, ensure_ascii=False).encode() if data is not None else None,
                  {'Content-Type': 'application/json'} if data is not None else {})
    try:
        response = urlopen(req, timeout=10)
    except HTTPError as exc:
        response = exc
    with response:
        payload = response.read()
        assert response.status == status, (url, response.status, payload[:400])
        return json.loads(payload) if 'json' in response.headers.get('Content-Type', '') else payload

def main():
    server = ThreadingHTTPServer(('127.0.0.1', 0), Fixtures)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    fixtures = 'http://127.0.0.1:' + str(server.server_port)
    with tempfile.TemporaryDirectory(prefix='day20-smoke-') as folder:
        env = dict(os.environ)
        ports = []
        # Reserve ephemeral ports as a group, then release immediately before startup.
        sockets = []
        for _ in range(7):
            sock = socket.socket(); sock.bind(('127.0.0.1', 0)); sockets.append(sock); ports.append(sock.getsockname()[1])
        keys = ['CHAT_PORT','WEATHER_PORT','NEWS_PORT','IMAGE_PORT','WEATHER_MCP_PORT','NEWS_MCP_PORT','IMAGE_MCP_PORT']
        env.update(dict(zip(keys, map(str, ports))))
        env.update(DATA_DIR=folder, OPENROUTER_API_KEY='fixture', AGENT_API_KEY='fixture', AGENT_BASE_URL=fixtures,
                   OPENROUTER_BASE_URL=fixtures, GEOCODING_URL=fixtures+'/geo', FORECAST_URL=fixtures+'/forecast', HABR_BASE_URL=fixtures)
        for sock in sockets: sock.close()
        output = open(Path(folder)/'supervisor.log', 'w')
        process = subprocess.Popen(['python3', 'scripts/run.py'], cwd=ROOT, env=env, stdout=output, stderr=output)
        base = f'http://127.0.0.1:{ports[0]}'
        try:
            deadline = time.monotonic()+60
            while True:
                try:
                    request(base+'/health');break
                except (OSError, AssertionError):
                    if process.poll() is not None or time.monotonic()>deadline:
                        raise RuntimeError(Path(output.name).read_text())
                    time.sleep(.25)
            servers = request(base+'/api/servers')
            assert all(s['online'] for s in servers), servers
            def ask(question, expected, session=None):
                sid = session or str(uuid.uuid4())
                job = request(base+'/api/chat', {'sessionId':sid,'message':question,'city':'Москва'}, 202)
                request(base+'/api/chat', {'sessionId':sid,'message':question,'city':'Москва'}, 409)
                deadline = time.monotonic()+40
                while True:
                    result = request(base+'/api/jobs/'+job['jobId'])
                    if result['done']:break
                    assert time.monotonic()<deadline, result
                    time.sleep(.1)
                reply = result['result']
                assert not reply.get('error'), reply
                assert [s['tool'] for s in reply['steps']] == expected, reply
                assert len([e for e in result['events'] if e.get('state') == 'running']) == len(expected)
                print('PASS', question, ' → '.join(expected) or '(no tools)', flush=True)
                return sid, reply
            weather = ['weather__search_city','weather__today']
            _, result = ask('WEATHER', weather)
            sid, result = ask('NEWS', ['news__headlines'])
            news = result['steps'][0]['result']
            assert news['count'] == 10 and len({i['id'] for i in news['items']}) == 10
            assert all(i['id'] != '1011' for i in news['items'])
            ask('FOLLOWUP', [], sid)
            ask('SUMMARY', weather+['news__headlines'])
            _, result = ask('IMAGE', ['images__generate'])
            image_url = result['images'][0]['url']
            assert request(base+image_url).startswith(b'\x89PNG')
            assert request(f'http://127.0.0.1:{ports[3]}'+image_url).startswith(b'\x89PNG')
            _, result = ask('DAY', weather+['news__headlines','images__generate'])
            assert 'Kotlin событие 0' in IMAGE_CALLS[-1]['prompt'] and '12°C' in IMAGE_CALLS[-1]['prompt']
            assert 'context' not in request(base+'/api/chats/'+sid)['messages'][-1]
            count = len(IMAGE_CALLS)
            _, result = ask('RETRY', ['images__generate','images__generate'])
            assert len(IMAGE_CALLS) == count+1, 'Repeated paid generation was not blocked'
            assert all(s['state']=='error' for s in result['steps'])
            assert 'private provider detail' not in json.dumps(result)
            _, result = ask('BADARGS', ['news__headlines'])
            assert result['steps'][0]['state']=='error'
            _, result = ask('UNKNOWN', ['weather__unknown'])
            assert result['steps'][0]['state']=='error'
            newsbase = f'http://127.0.0.1:{ports[2]}'
            empty = request(newsbase+'/api/news?'+urlencode({'topic':'old','mode':'new','todayOnly':'true'}))
            assert empty['count']==0
            request(newsbase+'/api/news?limit=11', status=400)
            request(newsbase+'/api/news?todayOnly=wrong', status=400)
            request(newsbase+'/api/news?timezone=invalid', status=400)
            request(f'http://127.0.0.1:{ports[1]}/api/weather/today?latitude=NaN&longitude=1', status=400)
            imagebase = f'http://127.0.0.1:{ports[3]}'
            request(imagebase+'/api/images', {'prompt':''}, 400)
            request(imagebase+'/api/images', {'prompt':'cat','aspectRatio':'bad'}, 400)
            request(base+'/api/chats/not-an-id', status=400)
            doc=request(imagebase+'/openapi.json')
            assert doc['paths']['/api/images']['post']['requestBody']['required']
            assert b'SwaggerUIBundle' in request(imagebase+'/swagger')
            assert len(request(imagebase+'/swagger-assets/swagger-ui-bundle.js'))>10000
            print('PASS API validation, dates, deduplication, PNG delivery, history, Swagger, no paid retries', flush=True)
        finally:
            process.terminate()
            try:process.wait(timeout=20)
            except subprocess.TimeoutExpired:process.kill();process.wait()
            output.close()
            server.shutdown()

if __name__ == '__main__':
    main()
