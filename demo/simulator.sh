#!/bin/sh
# 데모 시뮬레이터 (#14, PRD channel C3). compose 서비스 demo의 진입점이다.
# demo/streams.txt("{채널ID} {키}" 한 줄씩, setup.mjs가 쓴다)의 채널마다 demo/video.mp4를 반복 송출한다.
# 송출이 끊기면(키를 재발급하면 MediaMTX가 이전 송출을 끊는다) 2초 뒤 파일에서 그 채널의 키를 다시 읽어 다시 붙는다.
set -u

STREAMS=/demo/streams.txt
VIDEO=/demo/video.mp4

if [ ! -f "$STREAMS" ]; then
  echo "demo/streams.txt가 없습니다. 먼저 node --env-file=.env demo/setup.mjs 를 돌리세요" >&2
  exit 1
fi

# 영상이 없으면 만든다: ffmpeg 테스트 패턴(흐르는 초가 그려진다) + 440Hz 소리, 30초.
# 직접 만든 영상이라 출처 문제가 없다(확정 사양 10절). 이 자리에 직접 녹화한 파일을 두면 그것을 쓴다.
# 키프레임을 2초 간격으로 고정한다. 송출은 다시 인코딩하지 않고(-c copy) MediaMTX가 HLS 조각을 키프레임 단위로 자른다
if [ ! -f "$VIDEO" ]; then
  ffmpeg -hide_banner -loglevel error \
    -f lavfi -i testsrc=size=1280x720:rate=30 -f lavfi -i sine=frequency=440:sample_rate=44100 \
    -t 30 -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 60 -keyint_min 60 -sc_threshold 0 \
    -c:a aac -b:a 64k "$VIDEO.tmp.mp4" && mv "$VIDEO.tmp.mp4" "$VIDEO" || exit 1
fi

key_of() {
  awk -v id="$1" '$1 == id { print $2 }' "$STREAMS"
}

stream() {
  id=$1
  while true; do
    key=$(key_of "$id")
    if [ -n "$key" ]; then
      # 송출 주소에 키가 들어 있어 ffmpeg 출력에서 가린다(확정 사양 11절 — 키는 로그에 남지 않는다)
      ffmpeg -hide_banner -loglevel warning -re -stream_loop -1 -i "$VIDEO" -c copy -f flv \
        "rtmp://mediamtx:1935/live/$id?key=$key" 2>&1 | sed -E "s/key=[^ '\"&]+/key=****/g"
      echo "채널 $id 송출이 끝났습니다. 2초 뒤 다시 붙습니다"
    fi
    sleep 2
  done
}

for id in $(awk '{ print $1 }' "$STREAMS"); do
  stream "$id" &
done
wait
