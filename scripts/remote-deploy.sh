#!/usr/bin/env bash
# EC2에서 실행(자동 배포가 SSM으로 부름): 서버만 새 이미지로 바꿔 켜고, 2분 안에 UP이 아니면 이전 이미지로 되돌린다.
#   remote-deploy.sh <새 이미지>
# MySQL과 데이터는 건드리지 않는다. 이전 이미지는 .env의 SERVER_IMAGE에 남아 있다.
set -euo pipefail
NEW="$1"
cd "$(dirname "$0")"
PREV=$(grep '^SERVER_IMAGE=' .env | cut -d= -f2- || true)

set_image() { sed -i '/^SERVER_IMAGE=/d' .env; echo "SERVER_IMAGE=$1" >> .env; }
up() { docker compose -f compose.prod.yaml up -d --no-build server caddy; }
healthy() {
  for _ in $(seq 1 60); do
    curl -sf -m 3 http://localhost:8080/actuator/health | grep -q '"UP"' && return 0
    sleep 2
  done
  return 1
}

set_image "$NEW"
up
# Caddyfile·개인정보처리방침이 바뀌었을 수 있어 Caddy 설정을 다시 읽힌다(끊김 없음)
docker compose -f compose.prod.yaml exec -T caddy caddy reload --config /etc/caddy/Caddyfile >/dev/null 2>&1 || true

if healthy; then
  echo "배포 완료: $NEW"
  docker image prune -af --filter "until=168h" >/dev/null  # 1주일 넘은, 쓰지 않는 이미지 정리
  exit 0
fi

echo "새 버전이 2분 안에 UP이 되지 않음 → 이전 버전으로 되돌림" >&2
docker logs --tail 40 nowstation-prod-server-1 >&2 || true
if [ -n "$PREV" ] && [ "$PREV" != "$NEW" ]; then
  set_image "$PREV"
  up
  healthy && echo "되돌림 완료: $PREV" >&2 || echo "되돌림도 실패: 직접 확인 필요" >&2
fi
exit 1
