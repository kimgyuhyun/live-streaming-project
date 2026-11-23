# 이슈 #14 — 데모 방송 3개가 뜬다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/14
> PRD: `docs/result/prd/channel.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

---

## 시그니처

### 관련 결정

- **채널 PRD C3** — 준비 스크립트가 공개 API로 가입 → 스트리머 신청 → 운영자 계정으로 승인 → 키 발급(이미 있으면 재발급)을 실제로 하고, 응답의 키를 시뮬레이터 설정 파일(git 제외)에 적는다. 시뮬레이터(FFmpeg 컨테이너)는 그 키로 `live/{채널ID}?key=…`에 송출한다. 데모 채널 표시는 운영자 API로 채널에 붙인다. 서버 코드는 데모 표시 말고 늘지 않는다.
- **채널 PRD C1** — 서버에는 키의 해시만 있다. 키 전체는 발급 응답에만 있으므로 스크립트가 그 응답을 파일에 옮긴다.
- **확정 사양 10절** — 데모 방송도 실제 방송과 같은 경로(키 확인 → 수신 → 시청)를 탄다. 영상은 직접 만든 것이나 CC BY만. 데모 채널에는 "데모 채널" 표시.
- **확정 사양 11절** — 스트림 키는 로그에 전체가 나오지 않는다. 시뮬레이터의 ffmpeg 출력에서도 키를 가린다.
- **채널 PRD 작은 결정(승인 후 로그인 상태)** — 승인되면 그 회원의 세션이 모두 지워진다. 스크립트는 승인 뒤 데모 계정으로 다시 로그인해 스트리머 권한을 받는다.
- **계정 PRD (위조 요청 방어)** — 상태를 바꾸는 요청은 `Origin`이 허용 목록에 있어야 한다. 스크립트는 `Origin: {기준 주소}`를 싣는다.

### 사용자 결정 (2026-11-23)

- **영상:** ffmpeg로 생성한 자체 제작 영상(컬러바 + 채널 번호 + 흐르는 시계, 30초). 저장소에 동영상을 넣지 않는다. 시뮬레이터가 `demo/video.mp4`가 없을 때만 만들고, 그 자리에 직접 녹화한 파일을 두면 그대로 바뀐다. README에 "ffmpeg로 생성한 자체 제작 영상"이라고 적는다.
- **데모 계정 비밀번호:** `.env`의 `DEMO_PASSWORD` 하나를 세 계정이 같이 쓴다. `.env.example`에 자리를 더한다.

### 타입

```sql
-- V7__add_demo_to_channel.sql
-- 데모 채널 표시(확정 사양 10절). 운영자가 붙인다(#14). 0 = 아님, 1 = 데모
ALTER TABLE channel ADD (demo NUMBER(1) DEFAULT 0 NOT NULL CONSTRAINT ck_channel_demo CHECK (demo IN (0, 1)));
```

```java
// ── com.livestream.channel ─────────────────────────────
public class Channel {
    // 운영자가 데모 표시를 붙였으면 true
    private boolean demo;   // getter/setter
}

// demo를 끝에 더한다. 기존 생성자 호출(테스트 포함)이 함께 바뀐다
public record ChannelResponse(long id, String name, String description, boolean live, String title, boolean demo) {}

public interface ChannelMapper {
    // channel.demo = 1. 이미 1이어도 그대로
    void markDemo(long id);
}

public class ChannelService {
    // 없는 채널이면 CHANNEL_NOT_FOUND. 이미 데모 채널이어도 같은 응답(멱등)
    public ChannelResponse markDemo(long channelId);
}

// 운영자만 부른다(SecurityConfig의 /api/operator/**)
@RestController
@RequestMapping("/api/operator/channels")
class ChannelOperatorController {
    @PutMapping("/{channelId}/demo")
    ChannelResponse markDemo(@PathVariable long channelId);   // 200 + 채널
}
```

```typescript
// frontend/src/api/channels.ts
export interface ChannelResponse {
  id: number
  name: string
  description: string
  live: boolean
  title: string
  demo: boolean   // 데모 채널이면 true
}
```

```tsx
// frontend/src/pages/ChannelPage.tsx — Props 없음(경로의 channelId). demo이면 "데모 채널" 표시를 보인다
```

### 준비 스크립트 · 시뮬레이터

```
demo/setup.mjs       Node 22, 의존성 없음(fetch)
  실행: node --env-file=.env demo/setup.mjs [개수=3]
  환경변수: DEMO_BASE_URL(기본 http://localhost:8080), OPERATOR_EMAIL, OPERATOR_PASSWORD, DEMO_PASSWORD
  i = 1..개수 마다
    1. 가입 POST /api/members  (demo{i}@demo.local, 닉네임 데모채널{i}) — 409(이미 가입)는 건너뛴다
    2. 데모 계정 로그인 → 이미 STREAMER면 3·4를 건너뛴다
    3. 신청 POST /api/streamer-applications — 409(대기 중)는 건너뛴다
    4. 운영자 로그인 → 대기 목록에서 이 회원의 신청을 찾아 승인
    5. 데모 계정 다시 로그인(승인이 세션을 지운다) → GET /api/studio/channel 로 채널 id
    6. 운영자: PUT /api/operator/channels/{id}/demo
    7. 데모 계정: POST /api/studio/stream-key (발급·재발급) → 키
  끝에 demo/streams.txt 를 한 번에 쓴다: "{채널ID} {키}" 한 줄씩 (git 제외)
  실패하면 어느 계정의 어느 단계인지 출력하고 종료 코드 1. 키·비밀번호는 출력하지 않는다

demo/simulator.sh    compose 서비스 demo 의 진입점
  demo/video.mp4 가 없으면 ffmpeg(lavfi)로 30초 영상을 만든다(키프레임 2초 간격 — HLS 조각 단위)
  streams.txt 의 채널마다 반복:
    ffmpeg -re -stream_loop -1 -i video.mp4 -c copy -f flv rtmp://mediamtx:1935/live/{id}?key={키}
    끊기면 2초 뒤 streams.txt 에서 그 채널의 키를 다시 읽어 재시작 (재발급 뒤 새 키로 다시 붙는다 — AC 5)
  ffmpeg 출력의 key=… 는 가린다
```

```yaml
# compose.yml
  demo:
    image: bluenviron/mediamtx:1.21.4-ffmpeg   # 이미 쓰는 이미지. ffmpeg·sh가 들어 있다
    profiles: [demo]                           # 평소 docker compose up 에는 뜨지 않는다
    entrypoint: ["/bin/sh", "/demo/simulator.sh"]
    volumes: [./demo:/demo]
    depends_on: [mediamtx]
    mem_limit / cpus                           # 모든 컨테이너에 건다(server-infra 규칙)
    restart: unless-stopped
```

`.gitignore`에 `demo/streams.txt`, `demo/video.mp4`. README에 데모 실행법과 영상 출처.

### 에러 케이스

| 상황 | 에러 코드 | HTTP |
| --- | --- | --- |
| 데모 표시: 없는 채널 | `CHANNEL_NOT_FOUND` | 404 |
| 데모 표시: 운영자가 아닌 회원(채널 주인인 스트리머 포함) | `FORBIDDEN` (기존 `/api/operator/**` 규칙) | 403 |
| 데모 표시: 로그인하지 않음 | `UNAUTHENTICATED` | 401 |

### 판단이 갈린 곳

1. **데모 표시는 채널에 둔다(회원이 아니라).** 표시가 보이는 곳이 채널 페이지이고, PRD C3가 "채널에 붙인다"고 적었다.
2. **PUT(멱등).** 스크립트를 다시 돌려도 같은 결과여야 한다(AC 5). POST로 두면 "이미 데모"를 에러로 볼지 정해야 한다.
3. **준비 스크립트는 Node.** 이 PC에 `jq`가 없고(Windows Git Bash), JSON 응답과 쿠키를 다뤄야 한다. Node 22는 프론트 때문에 이미 있다.
4. **재발급 뒤 재시작은 시뮬레이터가 스스로.** MediaMTX가 재발급 때 이전 송출을 끊으므로(#10) ffmpeg가 끝나고, 시뮬레이터가 파일에서 새 키를 다시 읽는다. 사람이 컨테이너를 재시작하지 않아도 된다.
5. **시뮬레이터 이미지는 MediaMTX 이미지 재사용.** ffmpeg·sh가 이미 들어 있고 이미 받아 둔 이미지라 새로 받을 것이 없다.

### 이 이슈에서 만들지 않는 것

- 데모 표시 떼기 — AC에 없다.
- 홈 방송 목록의 데모 배지 — 방송 목록은 탐색 도메인.
- 데모 개수 3~5 결정 — 서버 CPU 측정 뒤(확정 사양 10절). 스크립트 인자로만 받는다.
- 서버 배포(운영자 비밀번호로 서버에서 돌리기) — 배포 단계.

---

## 테스트 시나리오

### 정상

- [ ] [정상] `ChannelService.markDemo` — should return the channel as demo when an operator marks an existing channel
- [ ] [정상] `ChannelService.find` — should report the channel as demo when it is marked as demo
- [ ] [정상] `ChannelService.find` — should report the channel as not demo when it is not marked
- [ ] [정상] `ChannelOperatorController.markDemo` — should return 200 with the channel when an operator marks it as demo
- [ ] [정상] `ChannelIntegration` — should show the channel as demo to a visitor after an operator marks it
- [ ] [정상] `ChannelIntegration` — should report a newly approved channel as not demo
- [ ] [정상] `ChannelPage` — should show the demo channel label when a visitor opens a demo channel
- [ ] [정상] `ChannelPage` — should not show the demo channel label when a visitor opens a channel that is not a demo

### 경계

- [ ] [경계] `ChannelIntegration` — should keep the channel demo when an operator marks it twice
- [ ] [경계] `ChannelPage` — should show the demo channel label when the demo channel is offline

### 예외

- [ ] [예외] `ChannelService.markDemo` — should throw CHANNEL_NOT_FOUND when the channel does not exist
- [ ] [예외] `ChannelOperatorController.markDemo` — should return 403 when a member who is not an operator marks a channel as demo
- [ ] [예외] `ChannelOperatorController.markDemo` — should return 403 when the streamer who owns the channel marks it as demo
- [ ] [예외] `ChannelOperatorController.markDemo` — should return 401 when a visitor who is not logged in marks a channel as demo
- [ ] [예외] `ChannelOperatorController.markDemo` — should return 404 with CHANNEL_NOT_FOUND when an operator marks a channel that does not exist

### 수동 확인 (로컬 compose — 시나리오 수에 넣지 않는다)

AC 1·2·5·6. Green 뒤에 확인하고 결과를 아래 "확인 결과"와 PR에 남긴다. 키·비밀번호 값은 적지 않는다.

- AC 1: 운영자 계정이 있는 상태에서 `node --env-file=.env demo/setup.mjs` → 데모 스트리머·채널 3개, `demo/streams.txt`에 3줄, `git check-ignore demo/streams.txt` 통과
- AC 2: `docker compose --profile demo up -d demo` → 3개 채널 모두 `live: true`, 채널 페이지(브라우저)에서 재생
- AC 3(화면): 데모 채널 페이지에 "데모 채널" 표시
- AC 5: 시뮬레이터가 도는 중에 준비 스크립트를 다시 돌림 → 키가 바뀌고(앞 4자리 비교), 송출이 한 번 끊긴 뒤 3개 모두 다시 `live: true`. 시뮬레이터 재시작 없이
- AC 6: README의 출처 문구
- 키 노출: `docker compose logs demo mediamtx api-1 api-2`에서 키 문자열 0줄, 스크립트 출력에 키·비밀번호 없음

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 비어 있는 DB + 운영자 계정, 준비 스크립트 3개 → 데모 스트리머·채널 3개, 키가 git 제외 설정 파일에 | 수동 확인 (스크립트는 기존 공개 API만 부른다 — 각 API는 #1·#2·#6·#7·#9 테스트가 덮는다). 데모 표시 단계: `[정상] ChannelOperatorController.markDemo — should return 200 with the channel when an operator marks it as demo` |
| 2. 시뮬레이터를 띄우면 3개 모두 `방송 중`, 채널 페이지에서 재생 | 수동 확인 (방송 중 전환은 #11, 재생은 #12 테스트가 덮는다) |
| 3. 데모 채널 페이지에 "데모 채널" 표시 | `[정상] ChannelService.markDemo — should return the channel as demo when an operator marks an existing channel`<br>`[정상] ChannelService.find — should report the channel as demo when it is marked as demo`<br>`[정상] ChannelService.find — should report the channel as not demo when it is not marked`<br>`[정상] ChannelIntegration — should show the channel as demo to a visitor after an operator marks it`<br>`[정상] ChannelIntegration — should report a newly approved channel as not demo`<br>`[정상] ChannelPage — should show the demo channel label when a visitor opens a demo channel`<br>`[정상] ChannelPage — should not show the demo channel label when a visitor opens a channel that is not a demo`<br>`[경계] ChannelPage — should show the demo channel label when the demo channel is offline` |
| 4. 운영자가 아닌 회원이 데모 표시를 붙이려 하면 403 | `[예외] ChannelOperatorController.markDemo — should return 403 when a member who is not an operator marks a channel as demo`<br>`[예외] ChannelOperatorController.markDemo — should return 403 when the streamer who owns the channel marks it as demo`<br>`[예외] ChannelOperatorController.markDemo — should return 401 when a visitor who is not logged in marks a channel as demo` |
| 5. 돌고 있는 데모에 준비 스크립트를 다시 돌리면 키 재발급, 시뮬레이터가 새 키로 다시 송출해 3개 모두 다시 `방송 중` | 수동 확인. 다시 돌려도 데모 표시가 깨지지 않는 것: `[경계] ChannelIntegration — should keep the channel demo when an operator marks it twice` (재발급이 이전 송출을 끊는 것은 #10 테스트) |
| 6. 영상이 직접 만든 것이거나 CC BY, README에 출처 | 수동 확인 (README 문구) |

**AC에 없는데 넣은 시나리오**

| 시나리오 | 이유 |
| --- | --- |
| `[예외] ChannelService.markDemo — should throw CHANNEL_NOT_FOUND when the channel does not exist`<br>`[예외] ChannelOperatorController.markDemo — should return 404 with CHANNEL_NOT_FOUND when an operator marks a channel that does not exist` | 없는 채널에 표시를 붙이면 아무것도 안 바뀐 채 200이 나간다. 스크립트가 잘못된 채널 id를 넘겨도 조용히 지나가지 않게 |

**커버리지:** AC 6개 / 시나리오 15개 / 미커버 0개 (AC 1·2·5·6은 수동 확인이 주 근거, AC 1·5는 일부를 테스트가 덮는다)
