#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -f .env ]]; then set -a; source .env; set +a; fi
export MCP_PORT="${MCP_PORT:-8331}"
export PORT="${PORT:-8330}"
export CRM_MCP_URL="${CRM_MCP_URL:-http://127.0.0.1:${MCP_PORT}/mcp}"
export CRM_FILE="${CRM_FILE:-product/crm/crm.json}"
if curl -fsS --max-time 2 "http://127.0.0.1:${PORT}/api/status" >/dev/null 2>&1; then
  echo "Ассистент уже запущен: http://localhost:${PORT}"; exit 1
fi
if curl -fsS --max-time 2 "http://127.0.0.1:${MCP_PORT}/health" >/dev/null 2>&1; then
  echo "Порт MCP ${MCP_PORT} уже занят. Остановите старый экземпляр или задайте MCP_PORT."; exit 1
fi
if ! curl -fsS --max-time 3 "${OLLAMA_BASE_URL:-http://localhost:11434}/api/version" >/dev/null 2>&1; then
  echo "Ollama не отвечает: поиск по документации работать не будет. Запустите: brew services start ollama"
fi
./gradlew :assistant:bootJar :crm-mcp:installDist
mkdir -p data/logs
pids=()
cleanup() {
  trap - EXIT INT TERM
  for pid in "${pids[@]}"; do kill -TERM "$pid" 2>/dev/null || true; done
  for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT INT TERM
crm-mcp/build/install/crm-mcp/bin/crm-mcp >data/logs/crm-mcp.log 2>&1 &
pids+=("$!")
java -jar assistant/build/libs/assistant-0.0.1-SNAPSHOT.jar >data/logs/assistant.log 2>&1 &
pids+=("$!")
echo "Запускаем CRM MCP и ассистента. Журналы: day33/data/logs/"
ready=0
for attempt in {1..120}; do
  if ! kill -0 "${pids[${#pids[@]}-1]}" 2>/dev/null; then
    tail -40 data/logs/assistant.log; exit 1
  fi
  if curl -fsS --max-time 2 "http://127.0.0.1:${PORT}/api/status" >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done
if [[ "$ready" != 1 ]]; then echo "Сервис не поднялся за 120 секунд. Проверьте data/logs/assistant.log"; exit 1; fi
echo "Готово: http://localhost:${PORT} · CRM MCP: ${CRM_MCP_URL}"
wait "${pids[${#pids[@]}-1]}"
