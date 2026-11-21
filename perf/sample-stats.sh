#!/usr/bin/env bash
# 부하를 거는 동안 컨테이너 CPU·메모리를 표본으로 남긴다(#29). 대시보드가 없어 이것이 자원 지표다.
# 사용: perf/sample-stats.sh <출력 CSV> <초>   — 약 2초마다 한 번(docker stats 한 번에 1~2초 걸린다)
set -euo pipefail
OUT="$1"; SECONDS_TOTAL="$2"
echo "time,container,cpu,mem" > "$OUT"
END=$(( $(date +%s) + SECONDS_TOTAL ))
while [ "$(date +%s)" -lt "$END" ]; do
  T=$(date -u +%H:%M:%S)
  docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' \
    | grep live-streaming | sed "s/^/$T,/" >> "$OUT"
done
