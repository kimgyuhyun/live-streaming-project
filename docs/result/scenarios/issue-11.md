# 이슈 #11 — 송출이 시작·종료되면 채널이 방송 중·오프라인이 된다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/11
> PRD: `docs/result/prd/channel.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

> 게이트 처리: 사용자가 자리를 비운 동안 진행했다. 사전 승인(#10 → #11 진행 지시)에 따라 시그니처·시나리오를 구현자가 정하고 승인된 것으로 진행했다.

---

## 시그니처

### 관련 ADR

- **채널 C2(훅 + 1분 대조)** — MediaMTX가 `live/{채널ID}` 경로가 준비되면(`runOnAvailable`) / 끊기면(`runOnUnavailable`) `wget`으로 Spring `/internal/`에 알린다. 실패해도 다시 보내지 않는다. Spring은 1분마다 MediaMTX API `/v3/paths/list`로 지금 송출 중인 경로를 가져와 DB의 진행 중 방송과 맞춘다. 알림과 대조는 **같은 처리 함수**를 쓰고 멱등이다. "채널당 진행 중인 방송은 하나"를 DB 제약으로 건다. 훅에 셸과 `wget`이 필요해 MediaMTX 이미지를 `bluenviron/mediamtx:1.21.4-ffmpeg`로 바꾼다.
- **채널 PRD 작은 결정(패키지)** — 방송 시작·종료, MediaMTX 호출은 `com.livestream.broadcast`.
- **채널 PRD 작은 결정(키 확인 엔드포인트 위치)** — 훅 엔드포인트도 `/internal/` 아래(#10에서 `permitAll`·출처 검사 제외·nginx 404가 이미 걸려 있다). MediaMTX는 내부 이름 `api`로 부른다.
- **#10 보안 점검** — `live/007`은 `live/7`과 다른 MediaMTX 경로다. 키 확인과 같은 규칙(`live/` + 0으로 시작하지 않는 1~18자리)으로만 채널 id를 읽는다.

### 1.21 확인 사항 (2026-11-21, 이 PC의 이미지로 확인)

- `runOnAvailable`·`runOnUnavailable`의 환경변수: `MTX_PATH`(경로 이름), `MTX_QUERY`(송출자가 보낸 query — **키가 들어 있다**, 훅에 싣지 않는다), `MTX_SOURCE_TYPE`, `MTX_SOURCE_ID`. `runOnAvailable`은 스트림이 사라지면 SIGINT로 끝나지만 `wget`은 그 전에 끝난다.
- `-ffmpeg` 이미지의 `wget`은 BusyBox판이고 `--post-data`, `-T`(읽기 시간 제한)를 지원한다.
- `/v3/paths/list` 응답(송출 중 실측): `{"itemCount":1,"pageCount":1,"items":[{"name":"live/21","confName":"all_others","ready":true,...,"available":true,"availableTime":"…","online":true,...,"source":{"type":"rtmpConn","id":"…"},...}]}`. `query`는 없다(키가 응답에 없다). 1.21에서는 `ready`의 새 이름이 `available`이다.

### DB

`backend/src/main/resources/db/migration/V5__create_broadcast.sql`

```sql
-- 방송 한 번 = 송출이 시작돼서 끝날 때까지(확정 사양 5절). ended_at이 NULL이면 진행 중
CREATE TABLE broadcast (
    id         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel_id NUMBER    NOT NULL,
    started_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    ended_at   TIMESTAMP,
    CONSTRAINT fk_broadcast_channel FOREIGN KEY (channel_id) REFERENCES channel (id)
);

-- 채널당 진행 중 방송은 하나(PRD channel C2). Oracle에는 부분 유니크 인덱스가 없어 함수 기반 유니크 인덱스로 건다.
-- 끝난 방송은 식이 NULL이라 색인에 들어가지 않는다(V2의 uk_streamer_application_pending과 같은 방식)
CREATE UNIQUE INDEX uk_broadcast_live_channel
    ON broadcast (CASE WHEN ended_at IS NULL THEN channel_id END);
```

### 타입

```java
// ── com.livestream.broadcast ───────────────────────────

// live/{채널ID} 경로 규칙 한 곳. 키 확인(#10)과 방송 상태(#11)가 같이 쓴다.
// 0으로 시작하지 않는 1~18자리만 채널 id로 읽는다(#10 보안 점검). 아니면 빈 값
final class LivePath {
    static OptionalLong channelIdOf(String path);
}
// PublishAuthService는 자기 정규식 대신 LivePath를 쓴다(동작 같음, #10 테스트가 지킨다)

@Mapper
public interface BroadcastMapper {   // SQL은 resources/mapper/broadcast/BroadcastMapper.xml
    // 진행 중 방송을 하나 만든다. 이미 진행 중이면 uk_broadcast_live_channel 위반(DuplicateKeyException)
    void insertLive(long channelId);
    // 그 채널의 진행 중 방송에 종료 시각을 찍는다. 없으면 0행
    void endLive(long channelId);
    List<Long> findLiveChannelIds();
}

@Service
public class BroadcastService {
    BroadcastService(BroadcastMapper broadcastMapper, MediaMtxClient mediaMtxClient);

    // 훅. live/{채널ID}가 아니면 아무것도 하지 않는다. 이미 진행 중이면 그대로 둔다(멱등)
    public void onAvailable(String path);
    public void onUnavailable(String path);

    // 1분 대조. MediaMTX의 available한 live/{채널ID}와 DB의 진행 중 방송을 비교해
    // 있는데 기록 없음 → 시작, 기록 있는데 없음 → 종료. 훅과 같은 처리 함수(start·end)를 쓴다.
    // MediaMTX API 오류는 그대로 던진다(아무것도 바꾸지 않는다)
    public void reconcile();
}

class MediaMtxClient {   // #10 클래스에 추가
    // GET /v3/paths/list → available == true 인 항목의 name
    List<String> findAvailablePaths();
}

// 1분마다 reconcile(). 실패는 경고 로그(예외 클래스 이름만)로 남기고 다음 회차를 기다린다.
// app.scheduling.enabled=false면 만들지 않는다(테스트 설정). 서버 2대가 각자 돌려도 DB 제약이 방송을 하나로 지킨다
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class BroadcastReconciler {
    BroadcastReconciler(BroadcastService broadcastService);
    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.MINUTES)
    void run();
}

@RestController
class BroadcastHookController {
    @PostMapping("/internal/mediamtx/available")    // ?path=live/7 → 204
    ResponseEntity<Void> available(@RequestParam String path);
    @PostMapping("/internal/mediamtx/unavailable")  // ?path=live/7 → 204
    ResponseEntity<Void> unavailable(@RequestParam String path);
}

// ── com.livestream.channel ─────────────────────────────

public class Channel {
    // 추가. 진행 중 방송이 있으면 true(ChannelMapper의 findById·findByMemberId가 함께 읽는다)
    boolean live;
}
// ChannelService.toResponse가 늘 false 대신 channel.isLive()를 쓴다. ChannelResponse 모양은 그대로
```

`ChannelMapper.xml`의 두 조회에 `CASE WHEN EXISTS (SELECT 1 FROM broadcast b WHERE (CASE WHEN b.ended_at IS NULL THEN b.channel_id END) = c.id) THEN 1 ELSE 0 END AS live`를 더한다. 조건을 유니크 인덱스와 같은 식으로 써서 그 인덱스로 찾는다.

### 설정·인프라

| 파일 | 바꾸는 것 |
| --- | --- |
| `compose.yml` | mediamtx 이미지 `bluenviron/mediamtx:1.21.4-ffmpeg` |
| `infra/mediamtx/mediamtx.yml` | `pathDefaults`에 `runOnAvailable`·`runOnUnavailable`: `wget -q -O /dev/null -T 5 --post-data "" "http://api:8080/internal/mediamtx/available?path=$MTX_PATH"`(종료는 `unavailable`). `MTX_QUERY`는 싣지 않는다 |
| `src/test/resources/config/application.yml` | `app.scheduling.enabled: false` — 테스트 중에 1분 대조가 끼어들지 않게. 대조 테스트는 `reconcile()`을 직접 부른다 |
| 주석 | `ChannelResponse`·`ChannelService`·`frontend/src/api/channels.ts`의 "live는 #11 전까지 늘 false" 문구 |

### 에러 케이스

| 상황 | 응답 |
| --- | --- |
| 훅의 `path`가 `live/{채널ID}`가 아님(`720/7` 등) | 204, 아무것도 바꾸지 않음 |
| 같은 시작 알림 두 번·대조와 알림이 겹침·서버 두 대 동시 대조 | 진행 중 방송 하나. 두 번째 INSERT의 유니크 위반은 "이미 진행 중"으로 보고 삼킨다 |
| 종료 알림인데 진행 중 방송 없음 | 204, 0행 갱신 |
| 대조 중 MediaMTX API 실패 | `reconcile()`은 예외를 던지고 아무것도 바꾸지 않는다. 스케줄러는 경고 로그만 남긴다 |
| `path` 파라미터 없음 | 400(스프링 기본) — MediaMTX는 늘 붙인다 |

### 판단이 갈린 곳

1. **경로 규칙을 `LivePath` 하나로 모은다.** 두 곳뿐이지만, 키 확인과 방송 상태가 경로를 다르게 읽으면 #10의 `live/007` 같은 구멍이 다시 생긴다(보안 규칙이라 세 번째를 기다리지 않는다).
2. **시작은 "먼저 조회하고 없으면 INSERT"가 아니라 "INSERT하고 유니크 위반이면 이미 진행 중"이다.** 조회-후-삽입은 서버 2대가 동시에 지나가면 둘 다 넣는다. 제약이 최종 판정이다(PRD C2).
3. **대조는 `reconcile()`을 직접 부르는 테스트로 본다.** 1분을 기다리지 않는다. 스케줄러는 `app.scheduling.enabled`로 테스트에서 끈다(모든 테스트에 같은 값이라 컨텍스트 캐시가 갈리지 않는다). 스케줄러 자체는 "실패가 새지 않는다"만 단위 테스트로 본다.
4. **동시성(AC 7)은 시작 처리 함수를 두 스레드가 동시에 부르는 통합 테스트로 본다.** 대조와 훅이 공유하는 그 함수가 DB 제약에 기대는지가 요점이다. `reconcile()` 두 개를 동시에 돌리면 "DB 조회 → INSERT" 사이의 타이밍에 따라 겹치지 않을 수도 있어 재현이 흔들린다. defect-repro 절차대로 **제약 없이 먼저 돌려 중복 행 수를 기록**하고, 제약을 넣은 뒤 다시 돌린다.
5. **외래 키 인덱스를 따로 두지 않는다.** 이 저장소의 V2(`streamer_application.member_id`)와 같다. 채널은 지우지 않아 TM 락이 걸릴 일이 없고, 진행 중 방송 조회는 유니크 인덱스의 식으로 찾는다. 이 인덱스는 성능 인덱스가 아니라 제약이라 index-measurement의 전후 측정 대상이 아니다(그 스킬도 유니크 인덱스는 제약용으로 따로 본다).
6. **채널 페이지가 보는 중에 스스로 바뀌는 것은 #12다**(#12 AC 3 "시청 중 방송이 끝나면"). 이 이슈는 서버 상태를 바꾸고, 채널 페이지는 열 때(다시 불러올 때) 그 상태를 보여 준다. AC 8은 송출 시작·종료 뒤 몇 초 안에 채널 API와 채널 페이지(다시 열기)가 바뀌는지로 확인한다.
7. **훅 엔드포인트는 `path`를 query로 받는다.** BusyBox `wget`으로 JSON 본문을 만들려면 셸 따옴표가 복잡해진다. `MTX_PATH`는 `live/21` 꼴이라 URL에 그대로 넣어도 된다.
8. **경로 목록은 첫 페이지(기본 100개)만 본다.** 1인 플랫폼 + 데모 3~5개라 넘지 않는다.

### 이 이슈에서 만들지 않는 것

- 채널 페이지 자동 갱신·플레이어·"방송이 종료되었습니다" — #12
- 스튜디오 연결 상태 — #13
- 방송 기록 조회 API — PRD 하지 않을 것(지난 방송 목록)

---

## 테스트 시나리오

### 정상

- [x] [정상] `BroadcastService.onAvailable` — should start a live broadcast for the channel when live and the channel id becomes available
- [x] [정상] `BroadcastService.onUnavailable` — should end the live broadcast of the channel when live and the channel id becomes unavailable
- [x] [정상] `BroadcastService.reconcile` — should start broadcasts for channels available in MediaMTX but not live in the database
- [x] [정상] `BroadcastService.reconcile` — should end broadcasts live in the database but not available in MediaMTX
- [x] [정상] `MediaMtxClient.findAvailablePaths` — should return the names of the available paths
- [x] [정상] `BroadcastReconciler.run` — should reconcile once per run
- [x] [정상] `ChannelService.find` — should return live true when the channel has a live broadcast
- [x] [정상] `BroadcastIntegration` — should create a live broadcast with a start time and show the channel as live when the available notice for live and the channel id arrives
- [x] [정상] `BroadcastIntegration` — should stamp the end time and show the channel as offline when the unavailable notice arrives
- [x] [정상] `BroadcastIntegration` — should start the broadcast when reconcile finds the channel available in MediaMTX without a live broadcast
- [x] [정상] `BroadcastIntegration` — should end the broadcast when reconcile finds it live in the database but not in MediaMTX

### 경계

- [x] [경계] `BroadcastService.onAvailable` — should keep the existing live broadcast when the channel is already live
- [x] [경계] `BroadcastService.reconcile` — should leave channels that are both available and live untouched
- [x] [경계] `BroadcastService.reconcile` — should ignore available paths that are not live followed by a channel id
- [x] [경계] `MediaMtxClient.findAvailablePaths` — should leave out paths that are not available
- [x] [경계] `BroadcastIntegration` — should keep one live broadcast when the same available notice arrives twice
- [x] [경계] `BroadcastIntegration` — should keep one live broadcast when two servers start the same channel at the same time
- [ ] [경계] `BroadcastIntegration` — should keep the end time of an earlier broadcast when a later broadcast of the same channel ends
- [ ] [경계] `BroadcastIntegration` — should let no start call throw when two servers start the same channel at the same time

### 예외

- [x] [예외] `BroadcastService.onAvailable` — should change nothing when the path is not live followed by a channel id
- [x] [예외] `BroadcastService.onUnavailable` — should change nothing when the path is not live followed by a channel id
- [x] [예외] `BroadcastService.reconcile` — should change nothing when the MediaMTX API fails
- [x] [예외] `MediaMtxClient.findAvailablePaths` — should throw when the MediaMTX API responds with an error
- [x] [예외] `BroadcastReconciler.run` — should not let a failed reconcile escape the scheduler
- [x] [예외] `BroadcastIntegration` — should not start a broadcast when the available notice is for 720 and the channel id
- [x] [예외] `BroadcastIntegration` — should not end the live broadcast when the unavailable notice is for 720 and the channel id

### 수동 확인 (로컬 compose + ffmpeg — 시나리오 수에 넣지 않는다)

AC 8. Green 뒤, AC 검증 전에 `docker compose up --build -d`로 전체를 띄워(mediamtx 이미지 `-ffmpeg`) 확인하고 결과를 아래 "확인 결과"와 PR에 남긴다. 키·운영자 계정 값은 적지 않는다.

- ffmpeg로 송출 시작 → 몇 초 안에 `GET /api/channels/{id}`의 `live`가 true, 채널 페이지(다시 열기)에 `방송 중`
- ffmpeg 종료 → 몇 초 안에 `live` false, 채널 페이지에 `오프라인`
- MediaMTX 로그에 훅 명령 오류가 없고, api 로그에 훅 요청이 남는다. `MTX_QUERY`(키)가 어디에도 실리지 않는다
- 덧붙여(대조): 훅을 거치지 않고 DB에만 진행 중 방송을 남긴 뒤 1분 안에 대조가 종료시키는지
- (AC 검증 갭 처리, 수동) `AC 8 PR 기록` — should copy the issue-11.md "확인 결과" table (start, end, hook error 0 lines, key= 0 lines) into the PR body when the PR is created
- (AC 검증 갭 처리, 수동) `AC 8 시작 지연` — should record the time from ffmpeg start until GET /api/channels/{id} first returns live true when polling begins before ffmpeg starts

**확인 결과 (2026-11-21, Green 뒤, HEAD ddb1e30):** `docker compose up --build -d`로 전체를 띄웠다(mediamtx `bluenviron/mediamtx:1.21.4-ffmpeg`, 앱이 뜨면서 V5가 로컬 개발 DB에 적용됨). #10 수동 확인 때 만든 채널 21과 그 키(스크래치 파일에만 있음)로 `ffmpeg -re -f lavfi -i testsrc=size=640x360:rate=30 -f lavfi -i sine -c:v libx264 -g 60 -c:a aac -t 30 -f flv "rtmp://localhost:1935/live/21?key={키}"`를 30초 송출했다. 채널 API는 250ms마다 물었다.

| 한 것 | 관찰 |
| --- | --- |
| 송출 전 | `GET /api/channels/21` → `"live":false` |
| ffmpeg 시작(02:32:15) | MediaMTX `02:32:19 [path live/21] runOnAvailable command started` → `stream is available and online` → `is publishing to path 'live/21'`. DB `broadcast` 1번 행 `started_at 02:32:19.697`. 채널 API `live=true`(첫 조회에서 이미 true), 브라우저 패널 `/channels/21` → **`방송 중`** ✅ |
| ffmpeg 종료(02:32:45.752) | MediaMTX `02:32:45 runOnAvailable command stopped` → `runOnUnavailable command launched` → `closed: stream unpublished`. DB `ended_at 02:32:45.418`. 채널 API `live=false`를 02:32:45.795에 확인(종료 뒤 0.1초 안), `/channels/21` 다시 열기 → **`오프라인`** ✅ |
| 훅 오류·키 노출 | mediamtx 로그에 `wget`·`error` 0줄. mediamtx·api-1·api-2 로그에 `key=` 0줄, `MTX_QUERY` 0줄 ✅ |
| 덧붙여(1분 대조) | 송출 없이 로컬 DB에 진행 중 방송을 넣음(02:33:18, 채널 API `live=true`) → 35.7초 뒤 `live=false`, DB 2번 행 `ended_at 02:33:55`. api 로그에 `대조 실패` 0줄 ✅ |
| 덧붙여(인덱스) | `user_indexes`에 `UK_BROADCAST_LIVE_CHANNEL UNIQUE` |

**Red에서 통과했던 테스트의 변이 확인 (Green):** `[예외] BroadcastService.reconcile — should change nothing when the MediaMTX API fails`가 구현 없이 통과했다(stub의 예외를 테스트가 받아 두고 갱신 호출이 없는지만 보므로). Green 커밋 뒤 `reconcile()`에서 `findAvailablePaths()` 실패를 빈 목록으로 바꾸는 변이를 넣자 `BroadcastServiceTest` 20개 중 이 테스트 1개만 실패했다. `git checkout --`으로 되돌렸다.

**DB 제약 전후 (defect-repro):** V5를 유니크 인덱스 없이 먼저 만들어 돌린 뒤 인덱스를 더했다.

| 항목 | 인덱스 없음 | `uk_broadcast_live_channel` 있음 |
| --- | --- | --- |
| 같은 시작 알림 두 번(통합) | 진행 중 방송 2개 | 1개 |
| 8개 스레드가 같은 채널을 동시에 시작 처리(통합) | 진행 중 방송 8개 | 1개 |
| 전체 테스트 | 2개 실패 | 313개 통과 |

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 오프라인 채널 7에 `live/7` 시작 알림 → 시작 시각이 있는 진행 중 방송, 채널 페이지 `방송 중` | `[정상] BroadcastService.onAvailable — should start a live broadcast for the channel when live and the channel id becomes available`<br>`[정상] ChannelService.find — should return live true when the channel has a live broadcast`<br>`[정상] BroadcastIntegration — should create a live broadcast with a start time and show the channel as live when the available notice for live and the channel id arrives` |
| 2. 같은 시작 알림이 두 번 → 진행 중 방송 하나 | `[경계] BroadcastService.onAvailable — should keep the existing live broadcast when the channel is already live`<br>`[경계] BroadcastIntegration — should keep one live broadcast when the same available notice arrives twice` |
| 3. 진행 중 방송에 `live/7` 종료 알림 → 종료 시각, 채널 `오프라인` | `[정상] BroadcastService.onUnavailable — should end the live broadcast of the channel when live and the channel id becomes unavailable`<br>`[정상] BroadcastIntegration — should stamp the end time and show the channel as offline when the unavailable notice arrives`<br>`[경계] BroadcastIntegration — should keep the end time of an earlier broadcast when a later broadcast of the same channel ends` (갭 처리) |
| 4. `live/`가 아닌 경로(`720/7` 등)의 알림 → 아무것도 바뀌지 않음 | `[예외] BroadcastService.onAvailable — should change nothing when the path is not live followed by a channel id`<br>`[예외] BroadcastService.onUnavailable — should change nothing when the path is not live followed by a channel id`<br>`[예외] BroadcastIntegration — should not start a broadcast when the available notice is for 720 and the channel id`<br>`[예외] BroadcastIntegration — should not end the live broadcast when the unavailable notice is for 720 and the channel id` |
| 5. MediaMTX에는 송출 중, DB에 진행 중 방송 없음 → 대조하면 시작 | `[정상] BroadcastService.reconcile — should start broadcasts for channels available in MediaMTX but not live in the database`<br>`[경계] BroadcastService.reconcile — should leave channels that are both available and live untouched`<br>`[경계] BroadcastService.reconcile — should ignore available paths that are not live followed by a channel id`<br>`[정상] MediaMtxClient.findAvailablePaths — should return the names of the available paths`<br>`[경계] MediaMtxClient.findAvailablePaths — should leave out paths that are not available`<br>`[정상] BroadcastIntegration — should start the broadcast when reconcile finds the channel available in MediaMTX without a live broadcast` |
| 6. DB에는 진행 중, MediaMTX에 송출 없음 → 대조하면 종료 | `[정상] BroadcastService.reconcile — should end broadcasts live in the database but not available in MediaMTX`<br>`[정상] BroadcastIntegration — should end the broadcast when reconcile finds it live in the database but not in MediaMTX` |
| 7. 대조가 두 서버에서 동시에 같은 채널을 시작 처리 → 진행 중 방송 하나(DB 제약) | `[경계] BroadcastIntegration — should keep one live broadcast when two servers start the same channel at the same time`<br>`[경계] BroadcastIntegration — should let no start call throw when two servers start the same channel at the same time` (갭 처리) |
| 8. 로컬 compose: ffmpeg 송출 시작 → 끊기 → 채널 페이지 `방송 중` → `오프라인` 몇 초 안 | 수동 확인 + 갭 처리 수동 2개(`AC 8 PR 기록`, `AC 8 시작 지연`) |

**AC에 없는데 넣은 시나리오**

| 시나리오 | 이유 |
| --- | --- |
| `[예외] BroadcastService.reconcile — should change nothing when the MediaMTX API fails`<br>`[예외] MediaMtxClient.findAvailablePaths — should throw when the MediaMTX API responds with an error` | MediaMTX가 응답하지 않을 때 "목록이 비었다"로 읽으면 진행 중 방송을 모두 끝내 버린다 |
| `[정상] BroadcastReconciler.run — should reconcile once per run`<br>`[예외] BroadcastReconciler.run — should not let a failed reconcile escape the scheduler` | C2의 "1분마다 대조"를 실제로 거는 곳. 실패가 새도 다음 회차가 돌아야 한다 |

**커버리지:** AC 8개 / 시나리오 26개(AC 검증 갭 처리 2개 포함) / 미커버 0개 (AC 8은 수동 확인)

---

## AC 검증

> `harness:ac-verifier` (2026-11-21, HEAD 12e01d9). 변이 최대 3번·20분·스크래치 복사본·클래스 단위 테스트로 제한해 띄웠다. 아래는 검증자 출력 원문이다(제목 수준만 한 단계 낮췄다). 구현자는 맨 아래 `### 구현자 이견`에만 쓴다.

### 이슈 #11 AC 검증 결과 (HEAD 12e01d9, feat/channel/issue-11)

**요약: AC 8개 중 ✅ 7개, ⚠️ 1개(AC 8), ❌ 0개.** 이번 이슈의 테스트 클래스 5개(45개 테스트)는 HEAD 복사본에서 모두 통과했습니다. 변이 3개 중 2개는 테스트가 잡았고, 1개는 아무 테스트도 실패시키지 못했습니다.

저장소 작업 트리 상태: 마지막에 `git -C C:/live-streaming-project status --short`를 돌렸고 **출력이 비어 있습니다**(종료 코드 0). 파일 수정, checkout, stash, 커밋, docker compose 조작은 하지 않았습니다.

아래 경로는 모두 `C:/live-streaming-project/` 기준입니다. 테스트 파일은 `backend/src/test/java/com/livestream/` 아래, 구현 파일은 `backend/src/main/` 아래에 있습니다.

#### AC별 판정

| AC | 판정 | 근거 테스트 (@DisplayName, 파일:줄) | 구현 (파일:줄) | 판단 이유 |
|---|---|---|---|---|
| 1. `live/7` 시작 알림 → 시작 시각 있는 진행 중 방송, 채널 `방송 중` | ✅ | "should create a live broadcast with a start time and show the channel as live when the available notice for live and the channel id arrives" (broadcast/BroadcastIntegrationTest.java:120)<br>"should start a live broadcast for the channel when live and the channel id becomes available" (broadcast/BroadcastServiceTest.java:43)<br>"should return live true when the channel has a live broadcast" (channel/ChannelServiceTest.java:56) | BroadcastHookController.java:19, BroadcastService.java:22, 52-54, BroadcastMapper.xml:7, ChannelMapper.xml:15-16, ChannelService.java:47, frontend/src/pages/ChannelPage.tsx:24 | 통합 테스트가 실제 HTTP 훅(보안 필터 포함)으로 204를 받고, DB에서 STARTED_AT은 있고 ENDED_AT은 없는지 본 뒤 공개 API `$.live=true`까지 확인합니다. 화면 문구는 ChannelPage.tsx:24가 `live`를 `방송 중`/`오프라인`으로 바꿔 보여 줍니다. 약한 점: STARTED_AT이 있다는 단언은 V5의 `DEFAULT SYSTIMESTAMP NOT NULL` 때문에 원래 항상 참이고, "오프라인이었다"는 전제도 단언하지 않습니다. |
| 2. 같은 시작 알림 두 번 → 진행 중 방송 하나 | ✅ | "should keep one live broadcast when the same available notice arrives twice" (BroadcastIntegrationTest.java:134)<br>"should keep the existing live broadcast when the channel is already live" (BroadcastServiceTest.java:52) | BroadcastService.java:52-56, V5__create_broadcast.sql:13-14 | 두 번째 훅도 204인지 봅니다. 그래서 ORA-00001이 실제로 DuplicateKeyException으로 바뀌어 삼켜지는 것까지 확인됩니다. 진행 중 방송 수는 1로 단언합니다. 변이 1(유니크 인덱스 제거)에서 "expected 1 but was 2"로 실패했습니다. |
| 3. 진행 중에 `live/7` 종료 알림 → 종료 시각, 채널 `오프라인` | ✅ (참고 사항 있음) | "should stamp the end time and show the channel as offline when the unavailable notice arrives" (BroadcastIntegrationTest.java:145)<br>"should end the live broadcast of the channel when live and the channel id becomes unavailable" (BroadcastServiceTest.java:75) | BroadcastHookController.java:25, BroadcastService.java:27, 60-61, BroadcastMapper.xml:11-12, ChannelMapper.xml:16 | ENDED_AT이 있는 행이 1개이고 API가 `live=false`인지 봅니다. 변이 2(채널 live 판정에서 `ended_at` 조건 제거)에서 `$.live expected false but was true`로 실패했습니다. 다만 변이 3(endLive에서 `AND ended_at IS NULL` 제거, 즉 이미 끝난 방송의 종료 시각까지 다시 찍음)은 **아무 테스트도 실패시키지 못했습니다**. 지금 구현은 BroadcastMapper.xml:12에 조건이 있어 맞게 동작하지만, 이 조건이 회귀하면 막아 줄 테스트가 없습니다. |
| 4. `live/`가 아닌 경로(`720/7` 등) 알림 → 아무것도 안 바뀜 | ✅ | "should change nothing when the path is not live followed by a channel id" (BroadcastServiceTest.java:62, 85. 입력은 null, `720/7`, `live/abc`, `live/007`, `live/7/x`, 빈 문자열)<br>"should not start a broadcast when the available notice is for 720 and the channel id" (BroadcastIntegrationTest.java:159)<br>"should not end the live broadcast when the unavailable notice is for 720 and the channel id" (BroadcastIntegrationTest.java:170) | LivePath.java:14, 20, BroadcastService.java:23, 28 | 단위 테스트는 `verifyNoInteractions`로 매퍼 호출이 전혀 없는지 봅니다. 통합 테스트는 시작 쪽은 그 채널의 broadcast 행이 0개인지, 종료 쪽은 진행 중 방송이 1개로 남는지 단언합니다. `live/007` 같은 경계값도 들어 있습니다. |
| 5. MediaMTX에는 송출 중인데 DB에 진행 중 방송 없음 → 대조하면 시작 | ✅ | "should start broadcasts for channels available in MediaMTX but not live in the database" (BroadcastServiceTest.java:98)<br>"should leave channels that are both available and live untouched" (:120)<br>"should ignore available paths that are not live followed by a channel id" (:132)<br>"should return the names of the available paths" (MediaMtxClientTest.java:61)<br>"should leave out paths that are not available" (:71)<br>"should start the broadcast when reconcile finds the channel available in MediaMTX without a live broadcast" (BroadcastIntegrationTest.java:181) | BroadcastService.java:33-43, MediaMtxClient.java:35-37 | 통합 테스트는 :19997에 실제 HttpServer로 가짜 `/v3/paths/list`(1.21 응답 모양)를 띄우고 `reconcile()`을 불러 진행 중 방송이 1개인지 봅니다. 목(mock)만 확인하는 테스트가 아니라 실제 경로를 탑니다. |
| 6. DB에는 진행 중인데 MediaMTX에 송출 없음 → 대조하면 종료 | ✅ | "should end broadcasts live in the database but not available in MediaMTX" (BroadcastServiceTest.java:109)<br>"should end the broadcast when reconcile finds it live in the database but not in MediaMTX" (BroadcastIntegrationTest.java:191) | BroadcastService.java:44-48, 60-61 | 통합 테스트는 훅으로 방송을 시작해 두고, MediaMTX 목록에는 `720/{id}`만 준 뒤 진행 중 방송이 0개인지 봅니다. 관련 안전장치로 MediaMTX API가 실패하면 아무것도 바꾸지 않는지(BroadcastServiceTest.java:144)도 확인합니다. |
| 7. 두 서버가 동시에 같은 채널을 시작 처리 → 진행 중 방송 하나(DB 제약) | ✅ (단언 약함) | "should keep one live broadcast when two servers start the same channel at the same time" (BroadcastIntegrationTest.java:204) | V5__create_broadcast.sql:13-14, BroadcastService.java:52-56. `reconcile()`도 같은 `start()`를 씁니다(:40-41) | 스레드 8개가 래치로 한꺼번에 `onAvailable`을 부르면, 커넥션 풀에서 서로 다른 DB 세션을 쓰므로 Oracle 입장에서는 서버 2대와 같습니다. 변이 1에서 "expected 1 but was 8"로 실패했으므로 DB 제약에 기대는 것이 입증됩니다. 약한 점은 두 가지입니다. (a) `reconcile()`이 아니라 훅 진입점으로 부릅니다. 둘이 공유하는 `start()`를 코드로 읽어 확인했습니다. (b) BroadcastIntegrationTest.java:218의 `catch (Exception e)`가 스레드 안의 모든 예외를 삼킵니다. 그래서 진 쪽에서 예외가 새어 나가도(동시 대조 중 500이나 대조 중단) 이 테스트는 통과합니다. AC의 Then인 "하나"는 정확히 단언합니다. |
| 8. 로컬 compose + ffmpeg로 송출을 시작했다 끊으면 몇 초 안에 `방송 중` → `오프라인` (PR에 기록) | ⚠️ | 자동 테스트는 없습니다(시나리오 문서에서 수동 확인으로 정했습니다). 근거는 docs/result/scenarios/issue-11.md:199-219의 "확인 결과"입니다 | compose.yml:74(`-ffmpeg` 이미지), compose.yml:22-25(별칭 `api`), infra/mediamtx/mediamtx.yml:41-42(훅. `MTX_QUERY`는 싣지 않음), ChannelPage.tsx:24 | 설정은 읽어서 확인했습니다. 기록 시점 HEAD ddb1e30과 지금 HEAD 사이에는 문서만 바뀌었습니다(`git diff --stat`으로 issue-11.md만 +21 확인). 기록상 시작은 ffmpeg를 띄우고 약 4초 뒤 runOnAvailable이 돌아 `방송 중`이 되었고, 종료는 API가 약 0.1초 안에 `live=false`가 되었습니다. ⚠️인 이유는 세 가지입니다. (1) compose를 띄우지 말라는 제약 때문에 제가 다시 재현하지 못했습니다. (2) AC가 요구하는 "PR에 남긴다"는 아직 하지 않았습니다(`gh pr list --head feat/channel/issue-11 --state all`의 출력이 비어 있음). (3) 기록에 "첫 조회에서 이미 true"라고 되어 있어, 송출 시작부터 API가 바뀌기까지의 시간은 API 쪽에서 재지 않았습니다(MediaMTX 로그 기준 약 4초뿐). 또 "채널 페이지가 바뀐다"를 "다시 열면 바뀐다"로 해석했습니다(시나리오 문서 판단 6. 자동 갱신은 #12). 이 해석을 받아들일지는 사람이 정해야 합니다. |

#### 변이 결과 (3번, 모두 스크래치 복사본에서 `git archive HEAD | tar -x`로 만든 뒤 sed로 바꿈)

| # | 변이 | 실행 | 결과 |
|---|---|---|---|
| 1 | V5에서 `CREATE UNIQUE INDEX uk_broadcast_live_channel …` 삭제 | `--tests "BroadcastIntegrationTest"` | **잡음.** 8개 중 2개 실패: "same available notice arrives twice"는 expected 1 but was 2, "two servers start the same channel at the same time"은 expected 1 but was 8. 시나리오 문서의 defect-repro 수치(2개, 8개)와 일치합니다 |
| 2 | ChannelMapper.xml:16을 `WHERE b.channel_id = c.id`로 바꿈(진행 중 여부 무시) | 같음 | **잡음.** 8개 중 1개 실패: "should stamp the end time and show the channel as offline…"에서 `$.live` expected false but was true |
| 3 | BroadcastMapper.xml:12에서 `AND ended_at IS NULL` 제거 | 같음 | **살아남음.** 8개 모두 통과(BUILD SUCCESSFUL in 1m 8s). 지난 방송의 종료 시각을 덮어써도 잡는 테스트가 없습니다 |

스크래치 복사본 위치: `C:/Users/USER/AppData/Local/Temp/claude/1cbb40fc7d76b7b0/d434e676-b23e-4cde-8b32-82990af677dd/scratchpad/m1`(원본 그대로), `.../m2`, `.../m3`, `.../m4`(변이 1~3). 로그는 같은 폴더의 `m2.log`, `m3.log`, `m4.log`입니다.

#### 갭을 메울 시나리오 제안

AC 8 (⚠️):
- [ ] [수동] `AC 8 PR 기록` — should copy the issue-11.md "확인 결과" table (start, end, hook error 0 lines, key= 0 lines) into the PR body when the PR is created
- [ ] [수동] `AC 8 시작 지연` — should record the time from ffmpeg start until GET /api/channels/{id} first returns live true when polling begins before ffmpeg starts

AC 3 (✅지만 변이 3이 살아남음, 회귀 보호용):
- [ ] [경계] `BroadcastIntegration` — should keep the end time of an earlier broadcast when a later broadcast of the same channel ends

AC 7 (✅지만 단언이 약함):
- [ ] [경계] `BroadcastIntegration` — should let no start call throw when two servers start the same channel at the same time

#### 가짜 테스트 또는 약한 단언

- broadcast/BroadcastIntegrationTest.java:218: `catch (Exception e) { }`가 동시 시작 중의 모든 예외를 삼킵니다. 조이는 방법: 예외를 `AtomicInteger`나 `ConcurrentLinkedQueue`에 모아 두고, 비어 있는지와 진행 중 방송 수 1을 함께 단언합니다(또는 위 제안처럼 별도 테스트로 나눕니다).
- broadcast/BroadcastServiceTest.java:144 ("should change nothing when the MediaMTX API fails"): `catchThrowable`의 반환값을 버립니다. 그래서 "예외를 던진다"는 부분은 검증하지 않고 `endLive`가 불리지 않았는지만 봅니다. 시나리오 문서에 따르면 Green 뒤 변이로 이 테스트가 잡는 것은 확인했습니다. 조이는 방법: `assertThatThrownBy(service::reconcile).isInstanceOf(HttpServerErrorException.class)`를 함께 둡니다.
- BroadcastIntegrationTest.java:128의 `STARTED_AT isNotNull`은 컬럼이 `DEFAULT SYSTIMESTAMP NOT NULL`이라 원래 항상 참입니다. 정보가 거의 없지만 해롭지는 않습니다.
- 인자 없는 `rejects.toThrow()`류, `await` 누락, 단언 없는 테스트, 목이 돌려준 값을 그대로 단언하는 테스트는 찾지 못했습니다. 예외 단언은 모두 `HttpServerErrorException`처럼 구체 타입입니다.

#### 실행한 명령 목록

1. `cat .claude/harness.json; gh issue view 11; git diff --stat origin/main...HEAD` → 설정 확인(scenariosDir=docs/result/scenarios), AC 8개, 변경 파일 23개
2. `cat docs/result/scenarios/issue-11.md` → 시그니처, 시나리오 24개, AC 대조표, 수동 확인 기록
3. `git diff origin/main...HEAD -- backend/src/main compose.yml infra frontend backend/src/test/resources` → 구현 diff 확인
4. `cat -n` BroadcastIntegrationTest, BroadcastServiceTest, MediaMtxClientTest, BroadcastReconcilerTest, ChannelServiceTest의 diff와 grep → 테스트 본문과 줄 번호
5. `sed -n 40,80p compose.yml; grep live frontend/src; grep C2 docs/result/prd/channel.md` → 이미지 `-ffmpeg`, ChannelPage.tsx:24의 문구, PRD C2 결정
6. `grep aliases compose.yml; grep runOn docs/spikes/infra-validation.md` → 별칭 `api`(compose.yml:22-25), 스파이크에 runOnAvailable 이름 확인
7. `git archive HEAD | tar -x -C scratchpad/m1`, `docker ps` → 복사본 생성. oracle과 redis 컨테이너가 이미 떠 있음(조작하지 않음)
8. m1(원본)에서 `JAVA_HOME=… ./gradlew test --tests BroadcastIntegrationTest --tests BroadcastServiceTest --tests MediaMtxClientTest --tests BroadcastReconcilerTest --tests ChannelServiceTest` → BUILD SUCCESSFUL in 50s. 결과 XML 기준 통합 8, Reconciler 2, Service 8+7+5, Client 3+3, ChannelService 4+2+3, 실패 0
9. `git archive` 3번(m2~m4)과 python 치환 시도 → python이 Windows 스토어 스텁이라 아무것도 바뀌지 않음(diff 출력 없음)
10. sed로 변이 3개 적용 후 `diff -r` → 각각 한 줄만 바뀐 것을 확인
11. m2, m3, m4 순서대로 `./gradlew test --tests "BroadcastIntegrationTest"` → m2는 2개 실패, m3는 1개 실패, m4는 8개 통과(m4는 백그라운드로 넘어감. 작업 종료 코드 1은 실패 메시지 grep이 0건이어서 생긴 것)
12. `sed -n 1,30p frontend/src/pages/ChannelPage.tsx; git status --short` → 화면 문구 확인, 상태 비어 있음
13. `tail m4.log`와 m4 결과 XML의 grep → m4는 BUILD SUCCESSFUL in 1m 8s, failures 0
14. grep으로 구현 줄 번호 수집, `gh pr list --head feat/channel/issue-11 --state all` → 줄 번호 확보, PR 출력 없음(아직 없음)
15. `grep -n` compose.yml, issue-11.md와 `git status --short` → compose.yml:74, 기록 199-219줄, 상태 비어 있음
16. `git diff --stat ddb1e30 12e01d9` → issue-11.md만 +21(수동 확인 뒤에는 문서만 바뀜)
17. 마지막 `git status --short`(13번, 15번에서 실행) → 출력 비어 있음, 종료 코드 0

### 구현자 이견

- **AC 8 "채널 페이지가 바뀐다"의 해석:** 제품 결정이 새로 필요한 갭으로 보지 않는다. 이슈 분해가 이미 정해 두었다 — "보는 중에 끝나면 종료 문구"는 #12 AC 3이고, #11 본문은 "채널 페이지 상태가 실제로 바뀐다"(서버 상태)다. 그래서 다시 열기로 확인한 해석을 유지하고, 사람이 다르게 보면 #12에서 다룬다.
- 약한 단언 2개(`catchThrowable` 반환값, `STARTED_AT isNotNull`)는 검증자가 해롭지 않다고 했고 변이로 지키는 것이 확인돼 손대지 않았다.

### 갭 처리

사전 승인에 따라 메움. 검증자가 제안한 시나리오 4개를 문구 그대로 옮겼다.
- `[경계] BroadcastIntegration` 2개 → `## 테스트 시나리오` 경계 절과 AC 대조표 3·7행. Red → Green(아래).
- `[수동]` 2개 → `### 수동 확인` 목록에 문구 그대로. 체크박스(`- [ ] [수동]`)로 두면 `check-scenarios.mjs`가 테스트 이름으로 세므로 일반 목록으로 옮겼다. AC 대조표 8행에도 적었다.

#### 갭 처리 결과

- `[경계] BroadcastIntegration — should keep the end time of an earlier broadcast when a later broadcast of the same channel ends`, `[경계] BroadcastIntegration — should let no start call throw when two servers start the same channel at the same time`: 구현이 이미 맞아 바로 통과했다(커밋 b39cad0). 저장소에 변이를 넣어 실패를 확인하고 `git checkout --`으로 되돌렸다.
  - `BroadcastMapper.xml` endLive에서 `AND ended_at IS NULL` 제거 → `BroadcastIntegrationTest` 10개 중 "earlier broadcast" 1개만 실패.
  - `BroadcastService.start`의 `DuplicateKeyException` 받기 제거 → 10개 중 "let no start call throw"와 "same available notice arrives twice" 2개 실패.
- `AC 8 시작 지연` (2026-11-21, 로컬 compose, HEAD b39cad0과 같은 구현): 채널 API 폴링(250ms)을 먼저 띄우고 1초 뒤 ffmpeg로 15초 송출했다.

| 시각(UTC) | 일 |
| --- | --- |
| 02:47:19.112 | ffmpeg 시작 |
| 02:47:23 | MediaMTX `runOnAvailable command started`, `is publishing to path 'live/21'` |
| 02:47:23.406 | `GET /api/channels/21` 처음 `live=true` — **ffmpeg 시작부터 4.29초**. 대부분은 RTMP 연결·키 확인·첫 키프레임까지의 시간이고, 훅은 MediaMTX가 송출을 받은 같은 초에 반영됐다 |
| 02:47:33 | MediaMTX `runOnUnavailable command launched`, `closed: stream unpublished` |
| 02:47:34.566 | `live=false` 확인(ffmpeg 종료를 기다린 뒤 첫 조회) |

- `AC 8 PR 기록`: PR 본문에 위 "확인 결과" 표와 이 시작 지연 표를 옮긴다(create-pr 단계).
