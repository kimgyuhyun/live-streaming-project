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

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 오프라인 채널 7에 `live/7` 시작 알림 → 시작 시각이 있는 진행 중 방송, 채널 페이지 `방송 중` | `[정상] BroadcastService.onAvailable — should start a live broadcast for the channel when live and the channel id becomes available`<br>`[정상] ChannelService.find — should return live true when the channel has a live broadcast`<br>`[정상] BroadcastIntegration — should create a live broadcast with a start time and show the channel as live when the available notice for live and the channel id arrives` |
| 2. 같은 시작 알림이 두 번 → 진행 중 방송 하나 | `[경계] BroadcastService.onAvailable — should keep the existing live broadcast when the channel is already live`<br>`[경계] BroadcastIntegration — should keep one live broadcast when the same available notice arrives twice` |
| 3. 진행 중 방송에 `live/7` 종료 알림 → 종료 시각, 채널 `오프라인` | `[정상] BroadcastService.onUnavailable — should end the live broadcast of the channel when live and the channel id becomes unavailable`<br>`[정상] BroadcastIntegration — should stamp the end time and show the channel as offline when the unavailable notice arrives` |
| 4. `live/`가 아닌 경로(`720/7` 등)의 알림 → 아무것도 바뀌지 않음 | `[예외] BroadcastService.onAvailable — should change nothing when the path is not live followed by a channel id`<br>`[예외] BroadcastService.onUnavailable — should change nothing when the path is not live followed by a channel id`<br>`[예외] BroadcastIntegration — should not start a broadcast when the available notice is for 720 and the channel id`<br>`[예외] BroadcastIntegration — should not end the live broadcast when the unavailable notice is for 720 and the channel id` |
| 5. MediaMTX에는 송출 중, DB에 진행 중 방송 없음 → 대조하면 시작 | `[정상] BroadcastService.reconcile — should start broadcasts for channels available in MediaMTX but not live in the database`<br>`[경계] BroadcastService.reconcile — should leave channels that are both available and live untouched`<br>`[경계] BroadcastService.reconcile — should ignore available paths that are not live followed by a channel id`<br>`[정상] MediaMtxClient.findAvailablePaths — should return the names of the available paths`<br>`[경계] MediaMtxClient.findAvailablePaths — should leave out paths that are not available`<br>`[정상] BroadcastIntegration — should start the broadcast when reconcile finds the channel available in MediaMTX without a live broadcast` |
| 6. DB에는 진행 중, MediaMTX에 송출 없음 → 대조하면 종료 | `[정상] BroadcastService.reconcile — should end broadcasts live in the database but not available in MediaMTX`<br>`[정상] BroadcastIntegration — should end the broadcast when reconcile finds it live in the database but not in MediaMTX` |
| 7. 대조가 두 서버에서 동시에 같은 채널을 시작 처리 → 진행 중 방송 하나(DB 제약) | `[경계] BroadcastIntegration — should keep one live broadcast when two servers start the same channel at the same time` |
| 8. 로컬 compose: ffmpeg 송출 시작 → 끊기 → 채널 페이지 `방송 중` → `오프라인` 몇 초 안 | 수동 확인 |

**AC에 없는데 넣은 시나리오**

| 시나리오 | 이유 |
| --- | --- |
| `[예외] BroadcastService.reconcile — should change nothing when the MediaMTX API fails`<br>`[예외] MediaMtxClient.findAvailablePaths — should throw when the MediaMTX API responds with an error` | MediaMTX가 응답하지 않을 때 "목록이 비었다"로 읽으면 진행 중 방송을 모두 끝내 버린다 |
| `[정상] BroadcastReconciler.run — should reconcile once per run`<br>`[예외] BroadcastReconciler.run — should not let a failed reconcile escape the scheduler` | C2의 "1분마다 대조"를 실제로 거는 곳. 실패가 새도 다음 회차가 돌아야 한다 |

**커버리지:** AC 8개 / 시나리오 24개 / 미커버 0개 (AC 8은 수동 확인)
