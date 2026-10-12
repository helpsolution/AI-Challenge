#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -f .env ]]; then set -a; source .env; set +a; fi
export PROJECT_ROOT="${PROJECT_ROOT:-..}"
export MCP_PORT="${MCP_PORT:-8312}"
export PORT="${PORT:-8311}"
export PROJECT_MCP_URL="${PROJECT_MCP_URL:-http://127.0.0.1:${MCP_PORT}/mcp}"
reranker_url="${RERANKER_BASE_URL:-http://127.0.0.1:8012}"
if curl -fsS --max-time 2 "http://127.0.0.1:${PORT}/api/project" >/dev/null 2>&1; then
  echo "Ассистент уже запущен: http://localhost:${PORT}"; exit 1
fi
if curl -fsS --max-time 2 "http://127.0.0.1:${MCP_PORT}/health" >/dev/null 2>&1; then
  echo "Порт MCP ${MCP_PORT} уже занят. Остановите старый экземпляр или задайте MCP_PORT."; exit 1
fi
./gradlew :assistant:bootJar :project-mcp:installDist
mkdir -p data/logs
pids=()
cleanup() {
  trap - EXIT INT TERM
  for pid in "${pids[@]}"; do kill -TERM "$pid" 2>/dev/null || true; done
  for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT INT TERM
if ! curl -fsS --max-time 3 "${reranker_url}/health" >/dev/null 2>&1; then
  if [[ "$reranker_url" != "http://127.0.0.1:8012" ]]; then
    echo "Реранкер недоступен: ${reranker_url}. Запустите его отдельно."; exit 1
  fi
  model="${RERANKER_MODEL_FILE:-data/models/bge-reranker-v2-m3-FP16.gguf}"
  if [[ ! -f "$model" && -f ../day23/data/models/bge-reranker-v2-m3-FP16.gguf ]]; then
    model=../day23/data/models/bge-reranker-v2-m3-FP16.gguf
  fi
  if [[ ! -f "$model" ]]; then echo "Не найден BGE-реранкер. Инструкция скачивания — в README."; exit 1; fi
  command -v llama-server >/dev/null || { echo "Установите llama.cpp: brew install llama.cpp"; exit 1; }
  llama-server -m "$model" --embedding --rerank --pooling rank -c 2048 -b 2048 -ub 2048 --host 127.0.0.1 --port 8012 >data/logs/reranker.log 2>&1 &
  pids+=("$!")
fi
project-mcp/build/install/project-mcp/bin/project-mcp >data/logs/mcp.log 2>&1 &
pids+=("$!")
java -jar assistant/build/libs/assistant-0.0.1-SNAPSHOT.jar >data/logs/assistant.log 2>&1 &
pids+=("$!")
echo "Запускаем ассистента. Журналы: day31/data/logs/"
ready=0
for attempt in {1..180}; do
  if ! kill -0 "${pids[${#pids[@]}-1]}" 2>/dev/null; then
    tail -40 data/logs/assistant.log; exit 1
  fi
  if curl -fsS --max-time 2 "http://127.0.0.1:${PORT}/api/project" >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done
if [[ "$ready" != 1 ]]; then echo "Сервис не поднялся за 180 секунд. Проверьте data/logs/assistant.log"; exit 1; fi
echo "Готово: http://localhost:${PORT} · MCP: ${PROJECT_MCP_URL}"
wait "${pids[${#pids[@]}-1]}"
