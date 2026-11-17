#!/usr/bin/env bash
# EC2에서 1분마다(systemd 타이머 nowstation-deploy.timer) 돈다: GHCR의 main 이미지가 바뀌었으면 받아서 교체한다.
# GitHub은 서버에 들어오지 않는다(pull 방식). 서버에는 이미지 읽기 전용 토큰만 있다(docker login ghcr.io).
set -euo pipefail
REPO=ghcr.io/sinadsl1457/nowstation-server
DIR=/home/ec2-user/nowstation
cd "$DIR"

# 교체에 1~2분 걸려 다음 확인과 겹칠 수 있다 → 하나만
exec 9>/tmp/nowstation-deploy.lock
flock -n 9 || exit 0

# 이름표가 그대로면 몇 KB짜리 확인만 하고 끝난다
docker pull -q "$REPO:main" >/dev/null
SHA=$(docker image inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$REPO:main")
NEW="$REPO:$SHA"
CURRENT=$(grep '^SERVER_IMAGE=' .env | cut -d= -f2- || true)
[ "$NEW" = "$CURRENT" ] && exit 0
# 실패해서 되돌린 버전은 다시 시도하지 않는다(안 그러면 매분 깔았다 되돌리며 서버가 끊긴다)
[ "$SHA" = "$(cat .failed-sha 2>/dev/null || true)" ] && exit 0

echo "새 버전 ${SHA:0:7} 발견 → 교체"
docker pull -q "$NEW" >/dev/null
# 배포 설정 파일을 이미지에서 꺼낸다. 덮어쓰지 않고 이름을 바꿔 넣어, 돌고 있는 이 스크립트가 깨지지 않게 한다
TMP=$(mktemp -d)
cid=$(docker create "$NEW")
docker cp "$cid:/deploy/." "$TMP/"
docker rm "$cid" >/dev/null
# site는 Caddy가 폴더째 붙여 쓰고 있다. 폴더를 지우고 새로 만들면 Caddy는 지워진 옛 폴더를 계속 봐서(404)
# 폴더는 두고 내용만 바꾼다(실제로 /privacy가 404가 나서 고침)
mkdir -p site && find site -mindepth 1 -delete && cp -a "$TMP/site/." site/
for f in compose.prod.yaml Caddyfile remote-deploy.sh auto-deploy.sh; do mv -f "$TMP/$f" "$f"; done
rm -rf "$TMP"
chmod +x remote-deploy.sh auto-deploy.sh

if bash remote-deploy.sh "$NEW"; then
  rm -f .failed-sha
else
  echo "$SHA" > .failed-sha
  echo "${SHA:0:7} 실패 → 이전 버전 유지, 이 버전은 다시 시도하지 않음" >&2
  exit 1
fi
