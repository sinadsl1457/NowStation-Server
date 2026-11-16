#!/usr/bin/env bash
# 서버를 다시 배포한다: 맥에서 x86 이미지를 만들어 EC2로 보내고, 서버 컨테이너만 새 이미지로 바꿔 켠다(MySQL·데이터는 그대로).
#   NOWSTATION_HOST=<EC2 퍼블릭 IP> scripts/deploy.sh
# EC2 처음 준비(Docker 설치, ~/nowstation/.env)는 README "배포".
set -euo pipefail

HOST="${NOWSTATION_HOST:?NOWSTATION_HOST=<EC2 퍼블릭 IP>가 필요하다}"
KEY="${NOWSTATION_KEY:-$HOME/.ssh/nowstation.pem}"
SSH=(ssh -i "$KEY" "ec2-user@$HOST")
cd "$(dirname "$0")/.."

echo "1/4 테스트"
./gradlew test -q

echo "2/4 x86 이미지 만들기(EC2가 x86)"
docker build -q --platform linux/amd64 -t nowstation-server:amd64 . >/dev/null

echo "3/4 EC2로 보내기"
scp -q -i "$KEY" compose.prod.yaml "ec2-user@$HOST:nowstation/"
docker save nowstation-server:amd64 | gzip | "${SSH[@]}" 'gunzip | docker load -q && docker tag nowstation-server:amd64 nowstation-server:latest'

echo "4/4 서버 컨테이너 바꿔 켜기"
"${SSH[@]}" 'cd ~/nowstation && docker compose -f compose.prod.yaml up -d --no-build server && docker image prune -f >/dev/null'

for _ in $(seq 1 60); do
  if curl -s -m 3 "http://$HOST:8080/actuator/health" | grep -q UP; then
    echo "완료: http://$HOST:8080 UP"
    exit 0
  fi
  sleep 2
done
echo "서버가 2분 안에 UP이 되지 않았다. 로그: ssh -i $KEY ec2-user@$HOST 'docker logs nowstation-prod-server-1 --tail 50'" >&2
exit 1
