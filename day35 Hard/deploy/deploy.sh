#!/usr/bin/env bash
# Выкладка редактора постов на VPS с Ubuntu рядом с сервисом дня 30 (общие Java и Caddy).
# Повторный запуск обновляет приложение, ключ и сайт; база постов в /var/lib/post-editor остается.
#   deploy/deploy.sh user@203.0.113.10
#   DOMAIN=posts.example.com deploy/deploy.sh user@203.0.113.10
set -euo pipefail

TARGET=${1:?"Использование: deploy/deploy.sh user@host"}
HOST=${TARGET#*@}
DOMAIN=${DOMAIN:-posts.${HOST//./-}.sslip.io}
cd "$(dirname "$0")/.."

grep -q '^DEEPSEEK_API_KEY=.' .env 2>/dev/null || { echo "В .env нет DEEPSEEK_API_KEY" >&2; exit 1; }

./gradlew bootJar -q
scp -q build/libs/post-editor.jar deploy/post-editor.service "$TARGET":/tmp/
sed "s/__DOMAIN__/$DOMAIN/" deploy/post-editor.caddy | ssh "$TARGET" 'cat > /tmp/post-editor.caddy'
# Ключ идет через stdin, а не аргументом команды: так он не виден в списке процессов и не лежит в /tmp.
grep -E '^(DEEPSEEK_API_KEY|DEEPSEEK_MODEL)=' .env |
  ssh "$TARGET" 'sudo install -m 600 /dev/null /etc/post-editor.env && sudo tee /etc/post-editor.env >/dev/null'

ssh "$TARGET" bash -s <<'REMOTE'
set -euo pipefail

dpkg -s openjdk-21-jre-headless caddy >/dev/null 2>&1 || {
  sudo apt-get update -q && sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -q openjdk-21-jre-headless caddy
}

id posts >/dev/null 2>&1 || sudo useradd --system --no-create-home --shell /usr/sbin/nologin posts
sudo install -D -m 644 /tmp/post-editor.jar /opt/post-editor/post-editor.jar
sudo install -m 644 /tmp/post-editor.service /etc/systemd/system/post-editor.service
sudo systemctl daemon-reload
sudo systemctl enable -q post-editor
sudo systemctl restart post-editor

# Сайт лежит отдельным файлом: основной Caddyfile принадлежит выкладке дня 30, и в ее шаблоне
# есть тот же import. reload, а не restart, чтобы сервис дня 30 не моргал.
# Новый конфиг сначала проверяется копией: при ошибке рабочий Caddyfile остается прежним.
sudo install -D -m 644 /tmp/post-editor.caddy /etc/caddy/sites/post-editor.caddy
cp /etc/caddy/Caddyfile /tmp/Caddyfile.new
grep -q '^import /etc/caddy/sites/\*.caddy' /tmp/Caddyfile.new ||
  printf '\nimport /etc/caddy/sites/*.caddy\n' >> /tmp/Caddyfile.new
if ! out=$(sudo caddy validate --config /tmp/Caddyfile.new --adapter caddyfile 2>&1); then
  sudo rm -f /etc/caddy/sites/post-editor.caddy
  echo "$out" >&2
  exit 1
fi
sudo install -m 644 /tmp/Caddyfile.new /etc/caddy/Caddyfile
sudo systemctl reload caddy
rm -f /tmp/post-editor.jar /tmp/post-editor.service /tmp/post-editor.caddy /tmp/Caddyfile.new

for _ in $(seq 1 90); do
  curl -sf http://127.0.0.1:8360/api/agent >/dev/null && exit 0
  sleep 1
done
echo "post-editor не ответил за 90 с:" >&2
sudo journalctl -u post-editor -n 40 --no-pager >&2
exit 1
REMOTE

echo "Готово: https://$DOMAIN"
