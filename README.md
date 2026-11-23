# Live Streaming Platform

OBS로 송출한 방송을 웹에서 실시간으로 보고 채팅하는 1인 라이브 플랫폼.

> 개발 중입니다. 기획은 [docs/plan.html](docs/plan.html)에 있습니다.

## 로컬 실행

필요한 것: Docker, (개발 시) JDK 21, Node 22

```bash
cp .env.example .env   # 비밀번호 값 바꾸기
docker compose up --build
```

| 주소 | 내용 |
| --- | --- |
| http://localhost:8080 | 웹 |
| http://localhost:8080/api/actuator/health | 백엔드 상태 (DB·Redis 포함) |
| rtmp://localhost:1935/live/{이름} | 송출 (OBS 등) |
| http://localhost:8080/hls/live/{이름}/index.m3u8 | 시청 (HLS) |

처음 실행할 때 Oracle 이미지를 받느라 시간이 걸립니다.

### 데모 방송

방송하지 않을 때도 사이트에 방송이 떠 있도록, 데모 스트리머 3개가 영상 파일을 반복 송출합니다. 데모도 실제 방송과 같은 경로(가입 → 신청 → 운영자 승인 → 키 발급 → 키 확인 → 송출)를 탑니다.

```bash
# .env에 OPERATOR_EMAIL·OPERATOR_PASSWORD·DEMO_PASSWORD를 채우고 docker compose up --build 로 띄운 뒤
node --env-file=.env demo/setup.mjs            # 데모 계정·채널 준비, 키를 demo/streams.txt에(git 제외)
docker compose --profile demo up -d demo       # 시뮬레이터: 채널마다 demo/video.mp4를 반복 송출
```

준비 스크립트를 다시 돌리면 키가 재발급되고, 시뮬레이터가 새 키로 알아서 다시 붙습니다.

**영상 출처:** `demo/video.mp4`는 시뮬레이터가 처음 뜰 때 ffmpeg의 테스트 패턴(`testsrc`)과 440Hz 소리로 직접 만든 30초 영상입니다. 외부 영상을 쓰지 않습니다. 이 파일을 직접 녹화한 영상으로 바꾸면 그 영상을 송출합니다.

### 개발 모드

인프라만 컨테이너로 띄우고 백엔드·프론트는 직접 실행합니다.

```bash
docker compose up oracle redis mediamtx
cd backend && ./gradlew bootRun     # :8080, 루트의 .env를 읽는다
cd frontend && npm install && npm run dev   # :5173, /api·/ws·/hls는 프록시
```
