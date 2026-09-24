#!/usr/bin/env bash
# Сборка на своей машине и выкладка на VPS:
#
#   ./deploy/deploy.sh user@host          — собрать, залить jar-ы и юниты, перезапустить
#   ./deploy/deploy.sh user@host --env    — то же и заодно заменить секреты на сервере содержимым .env
#
# Jar не зависит от архитектуры, поэтому собираем здесь: Gradle на сервере с одним ядром и 2 ГБ
# шёл бы долго и мог упасть по памяти. На сервере нужны только JRE и systemd, Docker не нужен.
# .env уходит на сервер при первой выкладке или с --env и лежит там в /etc/habr-pipeline с правами 600.
set -euo pipefail

HOST="${1:?укажите сервер: ./deploy/deploy.sh user@host [--env]}"
UPDATE_ENV="${2:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE="/tmp/habr-pipeline-deploy"
SERVICES="habr-pipeline-service habr-pipeline-mcp habr-pipeline-bot"

cd "$ROOT"
echo "→ сборка"
./gradlew :habr-service:bootJar :habr-mcp:jar :bot:jar --quiet

echo "→ копирование на $HOST"
ssh "$HOST" "rm -rf $STAGE && mkdir -m 700 $STAGE"
scp -q habr-service/build/libs/habr-service.jar habr-mcp/build/libs/habr-mcp.jar bot/build/libs/bot.jar \
    deploy/*.service "$HOST:$STAGE/"

if [[ "$UPDATE_ENV" == "--env" ]] || ! ssh "$HOST" "sudo test -f /etc/habr-pipeline/habr-pipeline.env"; then
    [[ -f .env ]] || { echo "нет файла .env — заполните его по образцу .env.example"; exit 1; }
    echo "→ секреты из .env"
    scp -q .env "$HOST:$STAGE/habr-pipeline.env"
fi

echo "→ установка"
ssh "$HOST" "sudo bash -s" <<REMOTE
set -euo pipefail
if ! command -v java >/dev/null; then
    echo "  ставлю JRE 21"
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-21-jre-headless >/dev/null
fi
install -d -m 755 /opt/habr-pipeline
install -m 644 $STAGE/*.jar /opt/habr-pipeline/
install -m 644 $STAGE/*.service /etc/systemd/system/
install -d -m 700 /etc/habr-pipeline
if [[ -f $STAGE/habr-pipeline.env ]]; then
    install -m 600 -o root -g root $STAGE/habr-pipeline.env /etc/habr-pipeline/habr-pipeline.env
fi
rm -rf $STAGE
systemctl daemon-reload
systemctl enable --quiet $SERVICES
systemctl restart $SERVICES
sleep 15
systemctl --no-pager --lines=0 status $SERVICES | grep -E '●|Active:'
REMOTE

echo "→ готово. Логи: ssh $HOST journalctl -fu habr-pipeline-bot"
