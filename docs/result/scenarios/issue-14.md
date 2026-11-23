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

- [x] [정상] `ChannelService.markDemo` — should return the channel as demo when an operator marks an existing channel
- [x] [정상] `ChannelService.markDemo` — should save the demo mark to the channel when an operator marks it
- [x] [정상] `ChannelService.find` — should report the channel as demo when it is marked as demo
- [x] [정상] `ChannelService.find` — should report the channel as not demo when it is not marked
- [x] [정상] `ChannelOperatorController.markDemo` — should return 200 with the channel when an operator marks it as demo
- [x] [정상] `ChannelIntegration` — should show the channel as demo to a visitor after an operator marks it
- [x] [정상] `ChannelIntegration` — should report a newly approved channel as not demo
- [x] [정상] `ChannelPage` — should show the demo channel label when a visitor opens a demo channel
- [x] [정상] `ChannelPage` — should not show the demo channel label when a visitor opens a channel that is not a demo

### 경계

- [x] [경계] `ChannelIntegration` — should keep the channel demo when an operator marks it twice
- [x] [경계] `ChannelPage` — should show the demo channel label when the demo channel is offline

### 예외

- [x] [예외] `ChannelService.markDemo` — should throw CHANNEL_NOT_FOUND when the channel does not exist
- [x] [예외] `ChannelOperatorController.markDemo` — should return 403 when a member who is not an operator marks a channel as demo
- [x] [예외] `ChannelOperatorController.markDemo` — should return 403 when the streamer who owns the channel marks it as demo
- [x] [예외] `ChannelOperatorController.markDemo` — should return 401 when a visitor who is not logged in marks a channel as demo
- [x] [예외] `ChannelOperatorController.markDemo` — should return 404 with CHANNEL_NOT_FOUND when an operator marks a channel that does not exist

### 수동 확인 (로컬 compose — 시나리오 수에 넣지 않는다)

AC 1·2·5·6. Green 뒤에 확인하고 결과를 아래 "확인 결과"와 PR에 남긴다. 키·비밀번호 값은 적지 않는다.

- AC 1: 운영자 계정이 있는 상태에서 `node --env-file=.env demo/setup.mjs` → 데모 스트리머·채널 3개, `demo/streams.txt`에 3줄, `git check-ignore demo/streams.txt` 통과
- AC 2: `docker compose --profile demo up -d demo` → 3개 채널 모두 `live: true`, 채널 페이지(브라우저)에서 재생
- AC 3(화면): 데모 채널 페이지에 "데모 채널" 표시
- AC 5: 시뮬레이터가 도는 중에 준비 스크립트를 다시 돌림 → 키가 바뀌고(앞 4자리 비교), 송출이 한 번 끊긴 뒤 3개 모두 다시 `live: true`. 시뮬레이터 재시작 없이
- AC 6: README의 출처 문구
- 키 노출: `docker compose logs demo mediamtx api-1 api-2`에서 키 문자열 0줄, 스크립트 출력에 키·비밀번호 없음

**Red 기록 (2026-11-23):** 시나리오 15개 → 테스트 15개(`check-scenarios.mjs` 불일치 0). 전체 백엔드 389건 중 7건, 프론트 31건 중 2건이 실패했고 모두 이번 테스트다. 기존 테스트는 전부 통과.

- 좋은 실패 9건: stub의 `UnsupportedOperationException`(서비스 `markDemo` 2, 컨트롤러 2, 통합 2), 단언 실패(서비스 `find`의 demo 1 — stub이 `toResponse`에 `false`를 넘긴다, 프론트 표시 2).
- 구현 없이 통과한 6건 — 변이로 실패를 확인하고 되돌렸다.
  - "데모가 아니다" 3건(서비스 `find`, 통합 새 채널, 프론트): `toResponse`가 `true`를 넘기게 / `ChannelPage`가 항상 "데모 채널"을 그리게 바꾸면 각각 실패.
  - 403 2건·401 1건(통합): 기존 `SecurityConfig`의 `/api/operator/**` 규칙이 이미 막는다. 이 규칙을 `permitAll()`로 바꾸면 세 건 모두 실패. 데모 표시 API가 운영자 경로 아래에 있다는 것을 지키는 테스트다.
- 기존 코드 변경: `ChannelResponse`에 필드가 늘어 기존 테스트의 생성자 호출 7곳에 `false`를 더했고, `StreamerApplicationReviewIntegrationTest`의 익명 `ChannelMapper`에 `markDemo` 위임을 더했다(컴파일).
- 통합 테스트 파일(`ChannelIntegrationTest`)은 컨테이너 기동 때문에 다 쓰고 파일 단위로 한 번 돌렸다. 나머지는 파일마다 돌렸다.

**Green 기록 (2026-11-23):** 백엔드 전체(`./gradlew test`)·프론트 Vitest 31건·`tsc -b`·lint 통과. 커버리지 명령은 설정에 없다(측정하지 않음). 백엔드는 조각(조회 → `markDemo`+SQL+V7 → 컨트롤러)마다 해당 테스트 파일을 돌리고, 백엔드 구현이 끝난 뒤 전체를 한 번 돌렸다(전체 스위트 2분 반).

**시그니처에서 바뀐 것 (Green 중):**
- 영상에 채널 번호를 넣지 않는다. MediaMTX 이미지에 폰트가 없어 ffmpeg `drawtext`를 쓸 수 없다. 대신 `testsrc` 패턴이 폰트 없이 흐르는 초를 그린다. 채널 이름은 채널 페이지에 있다.
- 영상 생성은 `-threads 1`. 기본(코어 수만큼 스레드)이면 컨테이너 메모리 제한 128MB를 넘어 ffmpeg가 죽었다(첫 확인에서 발견). 스레드 하나면 128MB 안에서 30초 영상을 18초에 만든다(710KB). 죽은 임시 파일이 남으면 다음 기동에서 ffmpeg가 덮어쓸지 묻고 끝나 버려서 `-y`를 더했다.

**확인 결과 (2026-11-23, 로컬 compose, `feat` 커밋 + 시뮬레이터 수정):** 개발 DB(데모 계정 없음)에 V7이 적용됐고, `.env`에 운영자·데모 비밀번호를 난수로 넣어 운영자 계정을 앱 시작 때 만들었다. 볼륨을 지워야 해서 빈 DB로는 하지 않았다 — 데모 계정이 없는 상태라 스크립트는 가입부터 모든 단계를 밟았다. 키·비밀번호는 적지 않는다.

| AC | 한 것 | 관찰 |
| --- | --- | --- |
| 1 | `node --env-file=.env demo/setup.mjs` | 종료 코드 0. `demo1~3@demo.local` 역할 `STREAMER`, 채널 62·63·64(`demo = 1`, 키 있음). `demo/streams.txt` 3줄(키 43자), `git check-ignore` 통과, `git status` 깨끗 ✅ |
| 2 | `docker compose --profile demo up -d demo` | 영상 생성 뒤 3개 채널 모두 `live: true`, HLS 재생 목록 응답. 브라우저에서 채널 62·63·64 영상이 재생 중(`paused: false`, `readyState: 4`) ✅ |
| 3 | 브라우저로 채널 62·63·64 | "데모 채널" 표시 ✅ |
| 5 | 시뮬레이터가 도는 중에 준비 스크립트를 다시 실행 | 종료 코드 0, `streams.txt` 바뀜. MediaMTX가 세 송출을 `closed: terminated`로 끊고, 시뮬레이터가 4초 안에 새 키로 다시 송출(`is publishing to path 'live/6x'`). 3개 모두 다시 `live: true`, 채널마다 방송 기록 2개(진행 중 1). 시뮬레이터 컨테이너 재시작 0회 ✅ |
| 6 | README "데모 방송" 절 | ffmpeg `testsrc`로 직접 만든 영상, 외부 영상 없음 ✅ |
| 키 노출 | demo·mediamtx·api-1·api-2·nginx 로그 전체에서 지금 키 3개 검색 | 0건. `key=` 문자열 자체가 0건(시뮬레이터가 가린다). 스크립트 출력은 채널 번호뿐 ✅ |

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 비어 있는 DB + 운영자 계정, 준비 스크립트 3개 → 데모 스트리머·채널 3개, 키가 git 제외 설정 파일에 | 수동 확인 (스크립트는 기존 공개 API만 부른다 — 각 API는 #1·#2·#6·#7·#9 테스트가 덮는다). 데모 표시 단계: `[정상] ChannelOperatorController.markDemo — should return 200 with the channel when an operator marks it as demo` |
| 2. 시뮬레이터를 띄우면 3개 모두 `방송 중`, 채널 페이지에서 재생 | 수동 확인 (방송 중 전환은 #11, 재생은 #12 테스트가 덮는다) |
| 3. 데모 채널 페이지에 "데모 채널" 표시 | `[정상] ChannelService.markDemo — should return the channel as demo when an operator marks an existing channel`<br>`[정상] ChannelService.markDemo — should save the demo mark to the channel when an operator marks it`<br>`[정상] ChannelService.find — should report the channel as demo when it is marked as demo`<br>`[정상] ChannelService.find — should report the channel as not demo when it is not marked`<br>`[정상] ChannelIntegration — should show the channel as demo to a visitor after an operator marks it`<br>`[정상] ChannelIntegration — should report a newly approved channel as not demo`<br>`[정상] ChannelPage — should show the demo channel label when a visitor opens a demo channel`<br>`[정상] ChannelPage — should not show the demo channel label when a visitor opens a channel that is not a demo`<br>`[경계] ChannelPage — should show the demo channel label when the demo channel is offline` |
| 4. 운영자가 아닌 회원이 데모 표시를 붙이려 하면 403 | `[예외] ChannelOperatorController.markDemo — should return 403 when a member who is not an operator marks a channel as demo`<br>`[예외] ChannelOperatorController.markDemo — should return 403 when the streamer who owns the channel marks it as demo`<br>`[예외] ChannelOperatorController.markDemo — should return 401 when a visitor who is not logged in marks a channel as demo` |
| 5. 돌고 있는 데모에 준비 스크립트를 다시 돌리면 키 재발급, 시뮬레이터가 새 키로 다시 송출해 3개 모두 다시 `방송 중` | 수동 확인. 다시 돌려도 데모 표시가 깨지지 않는 것: `[경계] ChannelIntegration — should keep the channel demo when an operator marks it twice` (재발급이 이전 송출을 끊는 것은 #10 테스트) |
| 6. 영상이 직접 만든 것이거나 CC BY, README에 출처 | 수동 확인 (README 문구) |

**AC에 없는데 넣은 시나리오**

| 시나리오 | 이유 |
| --- | --- |
| `[예외] ChannelService.markDemo — should throw CHANNEL_NOT_FOUND when the channel does not exist`<br>`[예외] ChannelOperatorController.markDemo — should return 404 with CHANNEL_NOT_FOUND when an operator marks a channel that does not exist` | 없는 채널에 표시를 붙이면 아무것도 안 바뀐 채 200이 나간다. 스크립트가 잘못된 채널 id를 넘겨도 조용히 지나가지 않게 |

**커버리지:** AC 6개 / 시나리오 16개(AC 검증 뒤 1개 추가) / 미커버 0개 (AC 1·2·5·6은 수동 확인이 주 근거, AC 1·5는 일부를 테스트가 덮는다)

---

## AC 검증

> `harness:ac-verifier` 출력 원문 (2026-11-23). 검증자의 표는 고치지 않는다.

## 이슈 #14 "데모 방송 3개가 뜬다" AC 검증 결과

**요약: AC 6개 중 ✅ 5 / ⚠️ 1 / ❌ 0**

이번 이슈 테스트는 백엔드 10건, 프론트 3건이고 지금 HEAD(6e07b03)에서 모두 통과한다. 지금 떠 있는 스택에서 읽기 전용으로 다시 확인한 결과도 수동 확인 기록과 맞는다. 유일한 ⚠️는 AC 6의 영상 출처 문구다. 코드는 고치지 않았다.

### AC별 판정

| AC | 판정 | 근거 |
| --- | --- | --- |
| 1. 비어 있는 DB + 운영자 계정에서 준비 스크립트를 3개로 돌리면 데모 스트리머·채널 3개가 생기고, 키가 git 제외 파일에 적힌다 | ✅ | 아래 참조 |
| 2. 시뮬레이터를 띄우면 3개 모두 `방송 중`이고 채널 페이지에서 재생된다 | ✅ | 아래 참조 |
| 3. 데모 채널 페이지에 "데모 채널" 표시가 보인다 | ✅ | 아래 참조 |
| 4. 운영자가 아닌 회원이 데모 표시를 붙이려 하면 403 | ✅ | 아래 참조 |
| 5. 데모가 도는 중에 스크립트를 다시 돌리면 키가 재발급되고, 새 키로 다시 송출해 3개 모두 다시 `방송 중` | ✅ | 아래 참조 |
| 6. 영상이 직접 녹화했거나 CC BY이고, README에 출처가 있다 | ⚠️ | AC 문장과 실제 영상이 어긋난다 |

**AC 1 — ✅**
- **구현:** `demo/setup.mjs`
  - 가입 409(`MEMBER_EMAIL_DUPLICATED`)는 건너뛴다(60–66줄).
  - 역할이 STREAMER가 아니면 신청 → 대기 목록에서 email로 찾기 → 승인 → 다시 로그인한다(68–85줄).
  - 그다음 채널 조회 → `PUT /operator/channels/{id}/demo` → 키 발급(87–96줄).
  - `streams.txt`는 마지막에 한 번에 쓴다(114줄).
- **스크립트가 쓰는 응답 필드가 실제 DTO에 있다:** `PendingApplicationResponse(id, …, email)`, `MemberResponse(…, role)`, `IssuedStreamKeyResponse(streamKey, …)`.
- **git 제외:** `.gitignore` 7·8줄.
- **데모 표시 단계 테스트:** `should return 200 with the channel when an operator marks it as demo`(ChannelOperatorControllerTest.java:37), `should show the channel as demo to a visitor after an operator marks it`(ChannelIntegrationTest.java:361).
- **지금 상태를 직접 확인했다:**
  - `/api/channels/62,63,64`가 모두 `"demo":true`다.
  - `streams.txt`는 3줄, 채널 ID 62·63·64, 키 길이 43이다. 키 값은 출력하지 않았다.
  - `git check-ignore -v`가 `.gitignore:7`·`:8`에 걸리고, `git status`는 깨끗하다.
- **문장과의 차이:** 수동 확인은 "비어 있는 DB"가 아니라 "데모 계정이 없는 개발 DB"에서 했다(볼륨 삭제를 피함). 스크립트의 분기는 데모 계정이 있는지에만 달려 있어서, 가입부터 모든 단계를 밟는 같은 경로다. 그래서 충족으로 본다. 다만 첫 실행의 출력은 재실행으로 덮였기 때문에, 첫 실행 자체는 기록에 기댄 판단이다.

**AC 2 — ✅**
- 자동 테스트는 없다. 시나리오 문서에서 수동 확인으로 정했고, 방송 중 전환은 #11, 재생은 #12 테스트가 덮는다.
- **직접 확인한 것:**
  - 세 채널 모두 `"live":true`다.
  - `/hls/live/{62,63,64}/index.m3u8`가 302 → 200이고 `#EXTM3U`를 준다.
  - MediaMTX 로그에 09:09:28 세 경로 `is publishing`이 있다.
  - `demo` 컨테이너가 Up 상태다.
- **기록에만 있는 것:** 브라우저 재생(`paused: false`, `readyState: 4`)은 브라우저가 없어 다시 보지 못했고, 기록에 기댄다. 위 관찰은 그 기록과 맞는다.

**AC 3 — ✅**
- **구현:**
  - `ChannelPage.tsx:31` `{channel.data.demo && <p>데모 채널</p>}`
  - `ChannelService.java:66`이 `isDemo()`를 응답에 싣는다.
  - `ChannelMapper.xml:14`가 `c.demo`를 읽는다.
  - 테이블 컬럼은 `V7__add_demo_to_channel.sql`.
- **테스트:**
  - ChannelPage.test.tsx:136 데모일 때 보임, :144 데모가 아닐 때 안 보임, :153 오프라인이어도 보임(경계)
  - ChannelServiceTest.java:93, :103
  - ChannelIntegrationTest.java:361, :371(실제 DB를 거쳐 방문자 조회 `$.demo`)
- 지금 API 응답도 `demo:true`다.

**AC 4 — ✅**
- **규칙:** `SecurityConfig.java:56` `/api/operator/**` → `hasRole("OPERATOR")`. 컨트롤러는 `@RequestMapping("/api/operator/channels")`.
- **테스트(실제 SecurityConfig를 타는 통합 테스트, 상태 코드와 `$.code=FORBIDDEN` 둘 다 단언):**
  - `should return 403 when a member who is not an operator marks a channel as demo`(ChannelIntegrationTest.java:391)
  - `should return 403 when the streamer who owns the channel marks it as demo`(:403)
  - 추가로 401(:414)
- 참고: 403 테스트만으로는 엔드포인트가 운영자 경로에 있다는 것을 보장하지 못한다. 같은 URL에서 운영자가 200을 받는 테스트(:361)와 함께 봐야 경로가 고정된다. 둘이 함께 있으므로 충족이다.

**AC 5 — ✅**
- **구현:**
  - `setup.mjs`는 매번 `POST /studio/stream-key`를 부른다(94줄).
  - `simulator.sh`는 ffmpeg가 끝나면 2초 뒤 `streams.txt`에서 키를 다시 읽는다(154–162줄).
- **테스트:** 다시 돌려도 데모 표시가 유지되는 것은 `should keep the channel demo when an operator marks it twice`(ChannelIntegrationTest.java:379). 재발급 → 다시 송출 → `방송 중`은 수동 확인에 기댄다.
- **기록과 맞는 로그·상태를 직접 확인했다:**
  - 09:10:18 MediaMTX가 세 RTMP 연결을 `closed: terminated`로 끊었다.
  - 09:10:22 세 경로가 새 연결로 `is publishing`이다.
  - `demo` 로그에 "채널 6x 송출이 끝났습니다. 2초 뒤 다시 붙습니다"가 있다.
  - `docker inspect` 결과 `RestartCount=0`, 시작 시각 09:09:08Z다. `streams.txt`는 18:10에 수정되어 시뮬레이터 기동보다 나중이다.
  - 지금 3개 모두 `live:true`다.
  - MediaMTX 로그에 auth·401·denied가 0건이다.
- **키 노출:** demo·mediamtx·api-1·api-2·nginx 로그에서 현재 키 3개가 0건, `key=` 문자열도 0건이다.
- **알아둘 점:** 스크립트는 파일을 맨 끝에 쓴다. 그래서 채널 수가 많거나 API가 느리면, 재연결이 잠깐 옛 키로 거절당하며 2초마다 다시 시도하다가 파일이 바뀐 뒤 붙는다. 결국 다시 붙으므로 AC 위반은 아니다. 이번 실행은 세 개가 같은 초에 끊겨 이 상황이 나타나지 않았다.

**AC 6 — ⚠️**
- README "데모 방송" 절(25–37줄, 출처 문구 37줄)에 출처가 있다: "ffmpeg의 테스트 패턴(`testsrc`)과 440Hz 소리로 직접 만든 30초 영상, 외부 영상을 쓰지 않음".
- **어긋나는 점:** AC와 확정 사양 10절(spec-fixed.md:191)은 "직접 녹화한 것이나 … CC BY만"이라고 쓴다. 생성한 테스트 패턴은 둘 중 어느 쪽도 아니다.
- 시나리오 문서는 이를 "직접 만든 것"으로 바꿔 적었고(16줄), 사용자 결정(2026-11-23, 23줄)으로 생성 영상을 골랐다. 그래서 저작권 위험이 없다는 의도와 출처 표기는 충족한다.
- 남은 일은 코드가 아니라 문장을 맞추는 것이다. 이슈 #14 AC와 spec-fixed.md:191을 "직접 만들었거나 녹화한 것"으로 고칠지, 이대로 둘지 사용자가 정하면 된다.

### 시나리오 문서에 붙일 제안

AC 6(코드 변경 없음):
- `[ ]` [문서] 이슈 #14 AC 6과 spec-fixed.md 10절의 "직접 녹화한 것"을 "직접 만들었거나 녹화한 것"으로 고친다(사용자 결정 2026-11-23 반영). 또는 사용자가 지금 문장을 유지할지 정한다.

단언을 조이는 제안(AC 판정에는 영향 없음):
- `[ ]` [정상] `ChannelService.markDemo` — should save the demo mark to the channel when an operator marks it. 단언: `verify(channelMapper).markDemo(CHANNEL_ID)`.

### 가짜이거나 약한 테스트

명확한 가짜 테스트는 없다.
- 예외 단언은 `BusinessException`과 `ErrorCode.CHANNEL_NOT_FOUND`까지 확인한다.
- 비동기 테스트는 모두 `await`가 있다.
- 단언이 없는 테스트는 없다.

약한 단언 두 건:
1. **ChannelServiceTest.java:222** `should return the channel as demo when an operator marks an existing channel`
   - 반환값의 `demo()`만 본다. 서비스는 `channel.setDemo(true)`를 메모리에서 하므로, `channelMapper.markDemo(...)` 호출(ChannelService.java:51)을 지워도 이 테스트는 통과한다.
   - DB 기록은 통합 테스트 :361이 잡아 준다.
   - 조이는 법: `verify(channelMapper).markDemo(CHANNEL_ID)`를 더한다.
2. **ChannelOperatorControllerTest.java:37** `should return 200 …`
   - 목이 돌려준 `demo:true`를 그대로 단언한다. `@WebMvcTest`라 라우팅과 직렬화만 본다.
   - 동작 검증은 통합 테스트 :361·:379가 맡으므로 그대로 두어도 된다.

문서 표기도 하나 어긋난다. 시나리오 문서는 :361·:371·:379를 `ChannelIntegration — …`으로 적었다. 실제로는 ChannelIntegrationTest 안의 `@Nested @DisplayName("ChannelOperatorController.markDemo")` 아래에 있다(ChannelIntegrationTest.java:358). 하네스는 메서드 `@DisplayName`만 읽으므로 대조에는 영향이 없다.

### 실행한 명령

1. `cat .claude/harness.json && gh issue view 14 && git log main..HEAD && git diff --stat main...HEAD` → 설정 있음(`scenariosDir=docs/result/scenarios`), AC 6개, 커밋 5개, 파일 22개 변경
2. `cat -n docs/result/scenarios/issue-14.md` → 시그니처, 시나리오 15개, Red/Green 기록, 수동 확인 표 확인
3. `git diff main...HEAD -- backend/src/main frontend/src/api ChannelPage.tsx .gitignore .env.example README.md compose.yml` → 구현 전체를 읽음
4. `cat -n demo/setup.mjs demo/simulator.sh; ls -la demo` → 스크립트 두 개, `streams.txt`(18:10), `video.mp4` 있음
5. `git diff main...HEAD -- backend/src/test frontend/src/pages/ChannelPage.test.tsx` → 테스트 본문을 읽음
6. `cat -n ChannelMapper.xml | head -40; grep SecurityConfig` → `c.demo` 조회 확인, SecurityConfig 위치 확인
7. `grep operator SecurityConfig.java; grep spec-fixed.md; grep -A12 C3 prd/channel.md` → :56 `hasRole("OPERATOR")`, 사양 :191 "직접 녹화한 것이나 … CC BY"
8. `grep record … Response` / ErrorCode → 스크립트가 쓰는 필드·코드가 존재함
9. `./gradlew test --tests ChannelServiceTest --tests ChannelOperatorControllerTest --tests ChannelIntegrationTest --tests ChannelControllerTest` → BUILD SUCCESSFUL
10. `npx vitest run ChannelPage.test.tsx` → 10건 통과
11. `grep testsuite/testcase build/test-results/...` → markDemo 관련 스위트 모두 failures=0 errors=0, 이번 이슈 테스트 10건 이름 확인
12. `curl /api/channels/{62,63,64}` → 셋 다 `live:true, demo:true`
13. `docker compose --profile demo ps; docker inspect RestartCount; wc -l, awk 'length' streams.txt; git check-ignore -v; git status --short` → demo Up, 재시작 0, 3줄, 키 길이 43, ignore 매치, 작업 트리 깨끗
14. `curl /hls/live/{id}/index.m3u8` → 302
15. `docker compose logs demo`(키 가림) / `logs mediamtx | grep terminated|publishing` → 09:10:18 세 연결 종료, 09:10:22 세 경로 다시 송출
16. `curl -L /hls/live/{id}/index.m3u8` → 200, `#EXTM3U`
17. 로그에서 auth·401·denied 개수, 현재 키 3개 개수(`grep -cF -f`, 키는 스크래치 임시 파일에 두었다가 바로 삭제), `key=` 개수 → 0 / 0 / 0
18. `grep -n` 으로 근거 줄 번호 수집 → 위 표의 파일:줄

상태를 바꾸는 명령(스크립트 재실행, 컨테이너 조작, DB 쓰기)은 하지 않았고, 키 값은 출력하지 않았다.

### 관련 파일
- C:\live-streaming-project\docs\result\scenarios\issue-14.md
- C:\live-streaming-project\demo\setup.mjs
- C:\live-streaming-project\demo\simulator.sh
- C:\live-streaming-project\README.md
- C:\live-streaming-project\docs\result\spec-fixed.md
- C:\live-streaming-project\backend\src\main\java\com\livestream\channel\ChannelService.java
- C:\live-streaming-project\backend\src\main\java\com\livestream\channel\ChannelOperatorController.java
- C:\live-streaming-project\backend\src\main\java\com\livestream\common\security\SecurityConfig.java
- C:\live-streaming-project\backend\src\test\java\com\livestream\channel\ChannelIntegrationTest.java
- C:\live-streaming-project\backend\src\test\java\com\livestream\channel\ChannelServiceTest.java
- C:\live-streaming-project\backend\src\test\java\com\livestream\channel\ChannelOperatorControllerTest.java
- C:\live-streaming-project\frontend\src\pages\ChannelPage.tsx
- C:\live-streaming-project\frontend\src\pages\ChannelPage.test.tsx

### 갭 처리 (구현자, 2026-11-23)

검증자의 표는 고치지 않았다. 사용자가 두 제안 모두 선호 없음으로 답해 추천안대로 처리했다.

- **AC 6 ⚠️ → 문장을 고쳤다.** 확정 사양 10절(`spec-fixed.md`), `issues.md`, GitHub 이슈 #14 AC 6을 "직접 만들었거나 녹화한 것, 또는 CC BY"로 바꿨다. 영상은 사용자 결정(시그니처 단계)대로 ffmpeg 생성 영상이고, README 출처 문구는 그대로다.
- **약한 단언 → 시나리오 1개 추가.** `[정상] ChannelService.markDemo — should save the demo mark to the channel when an operator marks it`(`verify(channelMapper).markDemo`). 구현이 이미 있어 Red는 변이로 확인했다: `channelMapper.markDemo(channelId)` 줄을 지우면 실패, 되돌리면 통과. `check-scenarios.mjs` 시나리오 16 = 테스트 16.
- 컨트롤러 테스트의 약한 단언(목 반환값)과 문서 표기(`ChannelIntegration` 라벨)는 그대로 둔다 — 검증자도 통합 테스트가 맡는다고 봤고, 대조에는 영향이 없다.

---

## 리팩터

> `/harness:tdd-refactor 14` (2026-11-23). 기준선: 백엔드 390건·프론트 31건 전체 통과.

대상(이번 이슈가 바꾼 `backend/src/main`·`frontend/src` 9개 파일)을 점검했고 고친 것은 없다.

- 중복: `ChannelService.markDemo`는 `update`·`updateTitle`과 같은 순서(찾기 → 저장 → 객체 반영 → 응답)다. 세 번째지만 바꾸는 필드가 셋 다 달라 공통화하면 인자만 는다.
- 네이밍·단일 책임·컨벤션: 기존 운영자 컨트롤러·매퍼 XML·record DTO와 같은 모양.
- 매직 넘버: SQL의 `demo = 1`은 V7의 CHECK(0/1)와 주석으로 묶여 있다.
- 손대지 않은 것: `demo/setup.mjs`·`demo/simulator.sh` — `srcGlobs` 밖이고 단위 테스트가 없다(로컬 compose 확인이 근거).

---

## 보안 점검

> `/harness:security-review 14` (2026-11-23).

**수집:** `compileJava compileTestJava` 통과, `npx tsc -b` 통과, `npm audit` 취약점 0개. 비밀값 패턴·알려진 접두사 검색(`backend/src/main`·`frontend/src`·`demo`) 0건. `.env`는 커밋 대상·이 브랜치 커밋 어디에도 없다(`.env.example`만, 자리표시자 값). 예외 대장(`securityExceptions`)은 설정에 없다.

| 분류 | 위치 | 무엇 | 판단 | 처리 |
| --- | --- | --- | --- | --- |
| 🟡 | `.env.example` `DEMO_PASSWORD=change-me1` | 그대로 복사해 쓰면 데모 스트리머 3개의 비밀번호가 누구나 아는 값이 된다. 로그인하면 데모 채널 이름·제목을 바꾸거나 키를 재발급(데모 송출 중단)할 수 있다 | `OPERATOR_PASSWORD`·DB 비밀번호와 같은 자리표시자 관례이고, 로컬 개발용이다. 서버에서는 `.env`를 따로 만든다. #26의 🟡(`MEDIAMTX_API_PASSWORD=change-me`)와 같은 종류 — 배포 단계에서 함께 다룬다(예: 알려진 자리표시자 값이면 스크립트·앱이 거부) | 보고만 |
| ⚪ | `demo/setup.mjs` `console.log`·`console.error` | 출력은 데모 계정 이메일(`demo{i}@demo.local`, 실제 사람 아님)·채널 번호·실패 단계·HTTP 코드뿐. 응답 본문은 오류 `code`만 읽는다(키가 든 본문은 출력하지 않는다) | 수동 확인에서 스크립트 출력에 키·비밀번호 0건 | 그대로 |
| ⚪ | `demo/simulator.sh` ffmpeg 인자 | 송출 주소(`?key=…`)가 ffmpeg 프로세스 인자에 있다. 출력은 `sed`로 `key=****` 가림 | 인자를 볼 수 있는 사람은 그 호스트의 Docker 권한이 있는 사람이고, 그러면 `demo/streams.txt`도 읽는다. 로그에는 남지 않는다(수동 확인 0건) | 그대로 |
| ⚪ | `demo/streams.txt` | 데모 채널 3개의 키 평문 | 확정 사양대로 서버 밖(시뮬레이터)이 키를 보관해야 한다(PRD C1·C3). git 제외, 생성 권한 0600(리눅스 서버에서 의미), 데모 채널 키뿐 | 그대로 |
| ⚪ | `demo/setup.mjs` `DEMO_BASE_URL` | 운영자·데모 비밀번호를 로그인 요청에 싣는다 | 기본값은 `http://localhost:8080`(같은 호스트). 서버에서는 서버 안에서 돌리거나 HTTPS 주소를 쓴다 — 배포 단계 | 그대로 |
| ⚪ | `PUT /api/operator/channels/{id}/demo` | 권한·위조 요청 | `/api/operator/**` `hasRole("OPERATOR")`(통합 테스트로 403·401 고정), 상태 변경이라 `OriginValidationFilter`가 Origin을 본다. 경로 변수는 `long`이라 숫자가 아니면 400 | 그대로 |
| ⚪ | 응답 | 새 필드 `demo`(boolean)만. 오류 응답은 기존 `ErrorResponse`(code·message) | 내부 정보 없음 | 그대로 |

**🔴 없음.** 고친 것 없음. 남은 것은 🟡 1개(배포 단계에서 다룸)와 ⚪뿐이다.
