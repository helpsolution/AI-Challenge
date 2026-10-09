#!/usr/bin/env bash
# Выкладка на VPS с Ubuntu: Ollama, Java, Caddy, сервис. Повторный запуск обновляет сервис и не трогает ключи.
#   deploy/deploy.sh user@203.0.113.10
#   LLM_MODEL=qwen3:0.6b deploy/deploy.sh user@203.0.113.10
set -euo pipefail

TARGET=${1:?"Использование: deploy/deploy.sh user@host"}
HOST=${TARGET#*@}
DOMAIN=${DOMAIN:-${HOST//./-}.sslip.io}
MODEL=${LLM_MODEL:-gemma3:1b}
cd "$(dirname "$0")/.."

./gradlew :llm-service:bootJar -q
scp -q llm-service/build/libs/llm-service.jar deploy/llm-service.service deploy/ollama.conf "$TARGET":/tmp/
sed "s/__DOMAIN__/$DOMAIN/" deploy/Caddyfile | ssh "$TARGET" 'cat > /tmp/Caddyfile'

ssh "$TARGET" "MODEL='$MODEL' bash -s" <<'REMOTE'
set -euo pipefail

# Подкачка: страховка от OOM, когда модель и JVM вместе подбираются к 2 ГБ.
if [ ! -f /swapfile ]; then
  sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile >/dev/null && sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
  echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-swappiness.conf >/dev/null && sudo sysctl -q -p /etc/sysctl.d/99-swappiness.conf
fi

command -v ollama >/dev/null || curl -fsSL https://ollama.com/install.sh | sh
# Ollama перезапускается, только если поменялись её настройки: после рестарта модель грузится заново,
# и первые запросы ждут, пока её страницы вернутся из swap.
if ! cmp -s /tmp/ollama.conf /etc/systemd/system/ollama.service.d/override.conf; then
  sudo install -D -m 644 /tmp/ollama.conf /etc/systemd/system/ollama.service.d/override.conf
  sudo systemctl daemon-reload
  sudo systemctl restart ollama
fi
until curl -sf http://127.0.0.1:11434/api/version >/dev/null; do sleep 1; done
ollama pull "$MODEL" >/dev/null

dpkg -s openjdk-21-jre-headless caddy >/dev/null 2>&1 || {
  sudo apt-get update -q && sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -q openjdk-21-jre-headless caddy
}

id llm >/dev/null 2>&1 || sudo useradd --system --no-create-home --shell /usr/sbin/nologin llm
sudo install -D -m 644 /tmp/llm-service.jar /opt/llm-service/llm-service.jar
if [ ! -f /etc/llm-service.env ]; then
  sudo install -m 600 /dev/null /etc/llm-service.env
  printf 'API_KEYS=owner:%s,guest:%s\n' "$(openssl rand -hex 24)" "$(openssl rand -hex 24)" | sudo tee /etc/llm-service.env >/dev/null
fi
sudo sed -i '/^LLM_MODEL=/d' /etc/llm-service.env
echo "LLM_MODEL=$MODEL" | sudo tee -a /etc/llm-service.env >/dev/null

sudo install -m 644 /tmp/llm-service.service /etc/systemd/system/llm-service.service
sudo install -m 644 /tmp/Caddyfile /etc/caddy/Caddyfile
sudo systemctl daemon-reload
sudo systemctl enable -q llm-service caddy
sudo systemctl restart llm-service caddy
rm -f /tmp/llm-service.jar /tmp/llm-service.service /tmp/ollama.conf /tmp/Caddyfile
REMOTE

echo "Готово: https://$DOMAIN"
echo "Ключи: ssh $TARGET sudo cat /etc/llm-service.env"
