#!/usr/bin/env bash
# Сборка на своей машине и выкладка на VPS:
#
#   ./deploy/deploy.sh user@host          — собрать, залить jar-ы и юниты, перезапустить
#   ./deploy/deploy.sh user@host --env    — то же и заодно заменить секреты на сервере содержимым .env
#
# Jar не зависит от архитектуры, поэтому собираем здесь: Gradle на сервере с одним ядром и 2 ГБ
# шёл бы долго и мог упасть по памяти. На сервере нужны только JRE и systemd, Docker не нужен.
# .env уходит на сервер при первой выкладке или с --env и лежит там в /etc/habr-digest с правами 600.
set -euo pipefail

HOST="${1:?укажите сервер: ./deploy/deploy.sh user@host [--env]}"
UPDATE_ENV="${2:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE="/tmp/habr-digest-deploy"
SERVICES="habr-news-service habr-news-mcp habr-bot"

cd "$ROOT"
echo "→ сборка"
./gradlew :news-service:bootJar :news-mcp:jar :bot:jar --quiet

echo "→ копирование на $HOST"
ssh "$HOST" "rm -rf $STAGE && mkdir -m 700 $STAGE"
scp -q news-service/build/libs/news-service.jar news-mcp/build/libs/news-mcp.jar bot/build/libs/bot.jar \
    deploy/*.service "$HOST:$STAGE/"

if [[ "$UPDATE_ENV" == "--env" ]] || ! ssh "$HOST" "sudo test -f /etc/habr-digest/habr-digest.env"; then
    [[ -f .env ]] || { echo "нет файла .env — заполните его по образцу .env.example"; exit 1; }
    echo "→ секреты из .env"
    scp -q .env "$HOST:$STAGE/habr-digest.env"
fi

echo "→ установка"
ssh "$HOST" "sudo bash -s" <<REMOTE
set -euo pipefail
if ! command -v java >/dev/null; then
    echo "  ставлю JRE 21"
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-21-jre-headless >/dev/null
fi
install -d -m 755 /opt/habr-digest
install -m 644 $STAGE/*.jar /opt/habr-digest/
install -m 644 $STAGE/*.service /etc/systemd/system/
install -d -m 700 /etc/habr-digest
if [[ -f $STAGE/habr-digest.env ]]; then
    install -m 600 -o root -g root $STAGE/habr-digest.env /etc/habr-digest/habr-digest.env
fi
rm -rf $STAGE
systemctl daemon-reload
systemctl enable --quiet $SERVICES
systemctl restart $SERVICES
sleep 15
systemctl --no-pager --lines=0 status $SERVICES | grep -E '●|Active:'
REMOTE

echo "→ готово. Логи: ssh $HOST journalctl -fu habr-bot"
