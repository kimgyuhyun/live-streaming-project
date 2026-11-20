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

### 개발 모드

인프라만 컨테이너로 띄우고 백엔드·프론트는 직접 실행합니다.

```bash
docker compose up oracle redis mediamtx
cd backend && ./gradlew bootRun     # :8080, 루트의 .env를 읽는다
cd frontend && npm install && npm run dev   # :5173, /api·/ws·/hls는 프록시
```
