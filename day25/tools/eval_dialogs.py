#!/usr/bin/env python3
"""Прогоняет длинные диалоги из data/eval/dialogs.json через API мини-чата.

Каждый сценарий — новый диалог; сообщения отправляются по очереди, как в чате.
Режим (с памятью задачи или без) берётся из GET /api/settings, поэтому для контрольного
прогона сервис перезапускают с TASK_MEMORY_ENABLED=false.

Автоматические проверки:
  • у каждого ответа есть источники и хотя бы одна ссылка [n], все номера — среди источников;
  • какие ключевые факты сценария (из ранних реплик, давно вне окна истории) есть в итоговом ответе;
  • сколько раз менялась цель в памяти задачи.
Соответствие ответа цели оценивается вручную по сохранённым стенограммам.
"""
import json
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8260"
ROOT = Path(__file__).resolve().parent.parent
SCENARIOS = ROOT / "data/eval/dialogs.json"
CITATIONS = re.compile(r"\[(\d+(?:\s*[,;]\s*\d+)*)]")


retries = 0


def call(method, path, body=None):
    global retries
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(BASE + path, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            return json.loads(response.read() or "null")
    except urllib.error.HTTPError as e:
        # 502 — внешний сервис не ответил (обычно connect timeout к DeepSeek): один повтор того же сообщения.
        if e.code != 502 or retries > 5:
            raise
        retries += 1
        print(f"  ↻ 502: {json.loads(e.read() or '{}').get('message')}; повторяю", flush=True)
        with urllib.request.urlopen(request, timeout=300) as response:
            return json.loads(response.read() or "null")


def cited(answer):
    return sorted({int(n) for group in CITATIONS.findall(answer) for n in re.split(r"\s*[,;]\s*", group)})


def run(scenario):
    conversation = call("POST", "/api/conversations")
    turns = []
    for i, message in enumerate(scenario["messages"], 1):
        started = time.monotonic()
        turn = call("POST", f"/api/conversations/{conversation['id']}/messages", {"message": message})
        numbers = cited(turn["answer"])
        valid = bool(turn["sources"]) and bool(numbers) and all(1 <= n <= len(turn["sources"]) for n in numbers)
        turns.append({
            "number": turn["number"], "question": message, "answer": turn["answer"],
            "sources": [{k: s[k] for k in ("number", "chunkId", "title", "section", "used", "rerankScore")} for s in turn["sources"]],
            "cited": numbers, "sourcesValid": valid, "warnings": turn["warnings"],
            "memory": turn["memory"], "memoryUpdate": turn["memoryUpdate"],
            "promptTokens": turn["llm"]["promptTokens"], "traceId": turn["traceId"],
            "seconds": round(time.monotonic() - started, 1),
        })
        goal = (turn["memory"]["goal"] or "—")[:70]
        print(f"  {scenario['id']} #{i:>2} источников {len(turn['sources'])}, ссылки {numbers or 'нет'}"
              f"{'' if valid else '  ⚠ без допустимых ссылок'} · память {turn['memoryUpdate']['status']} · цель: {goal}", flush=True)
    final = turns[-1]["answer"].lower()
    facts = [{"name": f["name"], "found": any(v.lower() in final for v in f["any"])} for f in scenario["facts"]]
    goals = [t["memory"]["goal"] for t in turns if t["memory"]["goal"]]
    return {
        "id": scenario["id"], "title": scenario["title"], "conversationId": conversation["id"], "turns": turns,
        "checks": {
            "answersWithValidSources": sum(t["sourcesValid"] for t in turns), "answers": len(turns),
            "finalFacts": facts, "goalChanges": sum(1 for a, b in zip(goals, goals[1:]) if a != b),
            "finalGoal": turns[-1]["memory"]["goal"],
        },
    }


def main():
    settings = call("GET", "/api/settings")
    label = "memory" if settings["memoryEnabled"] else "nomemory"
    print(f"Режим: {'с памятью задачи' if settings['memoryEnabled'] else 'без памяти'}, окно истории — {settings['historyTurns']} обмена")
    results = [run(s) for s in json.loads(SCENARIOS.read_text())]
    out = ROOT / f"data/eval/dialogs-run-{label}.json"
    out.write_text(json.dumps({"settings": settings, "retries": retries, "scenarios": results}, ensure_ascii=False, indent=2))
    print()
    for r in results:
        c = r["checks"]
        found = sum(f["found"] for f in c["finalFacts"])
        print(f"{r['id']}: ответы с источниками и ссылками {c['answersWithValidSources']}/{c['answers']}, "
              f"факты в итоговом ответе {found}/{len(c['finalFacts'])}, смен цели {c['goalChanges']}")
        for f in c["finalFacts"]:
            print(f"    {'✓' if f['found'] else '✗'} {f['name']}")
    print(f"\nСохранено: {out.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
