#!/usr/bin/env bash
# WSL2 Docker의 Jira 8.13.0(UPM)에 jar를 업로드한다. atlas-run은 쓰지 않는다.
#
#   JIRA_USER=bskim JIRA_PASS='...' ./deploy.sh
#
set -euo pipefail

cd "$(dirname "$0")"

BASE="${JIRA_BASE:-http://localhost:18080}"
USER="${JIRA_USER:-bskim}"
PASS="${JIRA_PASS:?JIRA_PASS 환경변수에 관리자 비밀번호를 넣어야 한다}"

JAR="$(ls -t target/*.jar | head -1)"
echo "업로드 대상: $JAR → $BASE"

# UPM은 업로드 요청마다 일회성 토큰을 요구한다.
TOKEN="$(curl -s -u "$USER:$PASS" -I "$BASE/rest/plugins/1.0/?os_authType=basic" \
    | tr -d '\r' | awk -F': ' 'tolower($1)=="upm-token"{print $2}')"

if [ -z "$TOKEN" ]; then
    echo "UPM 토큰을 못 받았다. 계정/비밀번호 또는 $BASE 접근을 확인할 것." >&2
    exit 1
fi

curl -s -u "$USER:$PASS" \
    -H "Accept: application/json" \
    -F "plugin=@$JAR" \
    "$BASE/rest/plugins/1.0/?token=$TOKEN" > /tmp/upm-upload.json

echo "업로드 요청 전송 완료. 설치 진행 상태:"
for _ in $(seq 1 30); do
    sleep 2
    STATUS="$(curl -s -u "$USER:$PASS" \
        "$BASE/rest/plugins/1.0/co.bskim.jira.inactive-user-search-key" 2>/dev/null || true)"
    if echo "$STATUS" | grep -q '"enabled":true'; then
        echo "설치·활성화 완료"
        exit 0
    fi
done
echo "제한 시간 안에 활성화를 확인하지 못했다. UPM 화면에서 상태를 확인할 것." >&2
exit 1
