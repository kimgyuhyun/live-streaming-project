# 이슈 #39 — 채널 페이지에서 동시 시청자 수를 본다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/39
> PRD: `docs/result/prd/viewer.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

---

## 시그니처

### 관련 결정

- **이슈 본문 "이 이슈에서 정한 것"** — 아래 값은 전부 거기서 그대로 옮겼다.
  - 집계: 5초마다 `GET /v3/hlssessions/list`를 마지막 페이지까지 읽어 `live/{채널ID}`별 세션 수를 센다. 진행 중 방송이 없는 경로는 버린다. 세션에서는 `path`·`remoteAddr`만 읽는다. 주소는 저장·로그하지 않는다.
  - 서버 2대: Redis 잠금(`SET NX`, 4초 만료)을 잡은 서버만 그 회차를 센다.
  - 판(스냅샷): 회차마다 `viewers:{판}:count`(방송ID → 수)를 쓰고, 다 쓴 다음 `viewers:current`를 새 판 번호로 바꾼다. 판은 2분 뒤 만료. 진행 중 방송은 0명이어도 넣는다.
  - 채널 조회 응답에 `viewerCount`: 지금 판의 값. 방송 중이 아니거나 판이 없으면 0.
  - MediaMTX 호출이 실패하면 그 회차는 건너뛰고(이전 판 유지) 실패만 남긴다. 회차마다 걸린 시간을 로그로 남긴다.
  - 화면: 방송 중이면 "N명 시청 중". 채널 조회를 이미 폴링(5초)하므로 그 값으로 갱신한다.
  - 테스트 설정은 주기 작업을 끈다(`app.scheduling.enabled=false`, 이미 있다) — 집계 함수를 직접 부른다.
- **viewer PRD V1·V2** — 읽는 쪽(채널 조회)은 MediaMTX를 부르지 않고 판만 읽는다. 패키지는 작은 결정대로 `com.livestream.broadcast`(시청자 수는 진행 중 방송에 묶인다).
- **IP당 5세션 한도(PRD V1)** — #40이 한다. 이 이슈는 `remoteAddr`를 읽어 두기만 하고 세지 않는다.
- **순위 집합 `viewers:{판}:rank`(PRD V2)** — #41이 판에 더한다. 이 이슈는 `count`만 쓴다.
- **기존 패턴**
  - `channel`은 `broadcast`를 모른다 — `PublisherDisconnector`(channel에 인터페이스, broadcast가 구현)와 같은 모양으로 `ViewerCountReader`를 channel에 둔다.
  - 주기 작업은 `BroadcastReconciler`처럼 `@ConditionalOnProperty(app.scheduling.enabled)` 설정 클래스.
  - MediaMTX 응답은 `MediaMtxClient` 안의 record로 필요한 필드만 읽는다. 테스트는 `MockRestServiceServer`(단위)·`:19997` 가짜 `HttpServer`(통합).
  - "진행 중" 식은 `uk_broadcast_live_channel`과 같은 식(`CASE WHEN ended_at IS NULL THEN channel_id END`)으로 찾는다.

### 타입

```java
// ── com.livestream.channel ─────────────────────────────

// 진행 중 방송의 지금 시청자 수를 읽는 쪽. 구현은 broadcast.ViewerSnapshot.
// broadcast가 channel을 부르므로 channel은 broadcast를 모르게 여기에 둔다(PublisherDisconnector와 같은 이유)
public interface ViewerCountReader {
    // 지금 판에서 그 방송의 시청자 수. 판이 없거나(만료 포함) 판에 그 방송이 없으면 0
    long viewerCount(long broadcastId);
}

public class Channel {
    // (기존 필드 그대로)
    // 진행 중 방송의 id. 방송 중이 아니면 null. 조회 SQL이 함께 읽는다
    private Long liveBroadcastId;  // + getter/setter
}

// viewerCount를 끝에 더한다
public record ChannelResponse(long id, String name, String description, boolean live, String title, boolean demo,
                              long followerCount, long viewerCount) {}

public class ChannelService {
    public ChannelService(ChannelMapper channelMapper, ViewerCountReader viewerCountReader);
    // 응답을 만드는 모든 메서드(find·findMine·update·updateTitle·markDemo): 방송 중이면 viewerCountReader로 읽고,
    // 방송 중이 아니면 읽지 않고 0
}
```

```xml
<!-- ChannelMapper.xml columns에 진행 중 방송 id를 더한다(live 식은 그대로).
     (SELECT b.id FROM broadcast b WHERE (CASE WHEN b.ended_at IS NULL THEN b.channel_id END) = c.id) AS live_broadcast_id -->
```

```java
// ── com.livestream.broadcast ───────────────────────────

// 진행 중 방송 한 줄
public record LiveBroadcast(long id, long channelId) {}

public interface BroadcastMapper {
    // (기존 셋 그대로)
    List<LiveBroadcast> findLiveBroadcasts();
}

class MediaMtxClient {
    // (기존 그대로)
    // 시청 세션 전부. ?page=0부터 응답의 pageCount 직전 페이지까지 읽는다(MediaMTX 페이지 번호는 0부터).
    // pageCount가 0이면(세션 없음) 첫 요청 하나로 끝난다. API 오류는 RestClientException 그대로 던진다
    List<HlsSession> findHlsSessions();

    record HlsSessions(int pageCount, List<HlsSession> items) {}
    record HlsSession(String path, String remoteAddr) {}   // 필요한 두 필드만
}

// Redis 키 묶음(잠금·판). 키 이름을 이 클래스 한 곳에 둔다
@Component
class ViewerSnapshot implements ViewerCountReader {
    static final Duration LOCK_TTL = Duration.ofSeconds(4);
    static final Duration SNAPSHOT_TTL = Duration.ofMinutes(2);
    // viewers:lock — SET NX + 4초 만료. 잡았으면 true. 풀지 않는다(만료로 풀려야 같은 회차를 두 번 세지 않는다)
    boolean tryLock();
    // 새 판 번호(viewers:seq INCR) → viewers:{판}:count에 방송ID → 수 → 2분 만료 → viewers:current = 판 번호
    void write(Map<Long, Long> countsByBroadcastId);
    // viewers:current → viewers:{판}:count의 그 방송 값. 없으면 0
    @Override public long viewerCount(long broadcastId);
}

// 한 회차 집계
@Component
class ViewerCounter {
    ViewerCounter(MediaMtxClient mediaMtxClient, BroadcastMapper broadcastMapper, ViewerSnapshot viewerSnapshot);
    // 잠금을 못 잡으면 아무것도 하지 않는다. 잡으면 세션 목록 → 진행 중 방송 → 방송ID별 수(0 포함)를 판으로 쓴다.
    // MediaMTX 호출이 실패하면(RestClientException) 판을 쓰지 않고 실패만 남긴다 — 밖으로 던지지 않는다.
    // 회차마다 걸린 시간을 남긴다(주소는 남기지 않는다)
    void aggregate();
}

// 5초마다 aggregate(). app.scheduling.enabled=false면 띄우지 않는다
@Configuration @EnableScheduling @ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class ViewerCountScheduler {
    @Scheduled(fixedRate = 5, timeUnit = TimeUnit.SECONDS) void run();
}
```

### 프론트

```typescript
// api/channels.ts — ChannelResponse에 더한다
//   viewerCount: number   // 지금 시청자 수(#39). 방송 중이 아니면 0

// pages/ChannelPage.tsx — 제목 아래 메타 줄(채널 이름 옆)에 방송 중일 때만 "{viewerCount}명 시청 중".
//   값은 이미 5초마다 다시 묻는 채널 조회 응답에서 읽는다(새 요청 없음)
// (props 변경 없음)
```

### 에러 케이스

| 상황 | 처리 | HTTP |
| --- | --- | --- |
| MediaMTX 세션 목록 호출 실패 | 그 회차를 건너뛴다. 이전 판이 그대로 보인다. 실패만 로그 | — (주기 작업) |
| 다른 서버가 이번 회차 잠금을 쥐고 있음 | 아무것도 하지 않는다 | — |
| 판이 없음·만료됨 | 채널 조회의 `viewerCount` 0 | 200 |

새 에러 코드는 없다.

### 판단이 갈린 곳

- **판 번호** — 이슈·PRD에 만드는 법이 없다. `viewers:seq`를 `INCR`해 쓴다. 서버 2대의 시계가 어긋나도 번호가 겹치지 않는다. #41은 이 번호를 응답에 실어 "같은 판"으로 다시 묻는다.
- **잠금을 풀지 않는다** — 다 센 뒤 풀면 다른 서버가 같은 5초 안에 또 센다. 4초 만료로만 풀린다(이슈 결정 "4초 만료"의 뜻).
- **`viewers:current`에는 만료를 걸지 않는다** — 판(`count`)이 2분 뒤 사라지면 `current`가 가리키는 판이 없으므로 0이 된다. 이것으로 "2분 넘게 새 판이 없으면 0"을 지킨다.
- **진행 중 방송이 하나도 없는 회차** — Redis는 빈 해시를 저장하지 않는다. 쓸 값이 없어도 새 판 번호로 `current`를 바꾼다(이전 판이 남아 보이지 않게).
- **주기** — `fixedRate` 5초(시작 간격). `fixedDelay`면 집계가 걸린 시간만큼 주기가 늘어난다.
- **잡는 예외** — MediaMTX 실패(`RestClientException`)만 집계 안에서 잡는다(AC). DB·Redis 오류는 스케줄러 기본 처리(로그 후 다음 회차)에 맡긴다.
- **채널 조회 응답의 다른 쓰임** — 스튜디오(`findMine`) 응답에도 같은 값이 실린다. 스튜디오 화면은 이 이슈에서 바꾸지 않는다(PRD 하지 않을 것).
- **표시 숫자** — 같은 화면의 "팔로워 N명"과 같이 숫자를 그대로 쓴다.

### 이 이슈에서 만들지 않는 것

- IP당 5세션 한도·`hlsTrustedProxies`·nginx `X-Forwarded-For`(#40)
- 순위 집합·방송 목록 API·메인 화면 목록(#41)
- 규모별 측정(#42)
- 스튜디오의 "동시 시청자" 칸(PRD 하지 않을 것)
- 매퍼 단독 테스트(`@MybatisTest`) — SQL은 통합 테스트가 진짜 Oracle로 확인한다(#37·#38과 같다)
- E2E(Playwright 설정이 없다)

---

## 테스트 시나리오

### 정상

- [ ] [정상] `MediaMtxClient.findHlsSessions` — should return the path and remote address of each session
- [ ] [정상] `MediaMtxClient.findHlsSessions` — should read every page when the session list has 3 pages
- [ ] [정상] `ViewerCounter.aggregate` — should write A=3 and B=1 without C when live channels A and B have 3 and 1 sessions and channel C without a live broadcast has 2
- [ ] [정상] `ChannelService.find` — should return the viewer count of the live broadcast when the channel is live
- [ ] [정상] `ViewerCountIntegration` — should store A=3 and B=1 by broadcast id without C in the current snapshot when live A, live B and offline C have 3, 1 and 2 sessions
- [ ] [정상] `ViewerCountIntegration` — should return viewerCount 3 from the channel lookup of A after aggregating 3 sessions on A
- [ ] [정상] `ViewerCountIntegration` — should return viewerCount 0 from the channel lookup of a channel that is not live even when its path has sessions
- [ ] [정상] `ViewerCountIntegration` — should write a new snapshot in the next round after a round in which MediaMTX failed
- [ ] [정상] `ChannelPage` — should show 3명 시청 중 when the live channel has 3 viewers
- [ ] [정상] `ChannelPage` — should show 5명 시청 중 after the next poll returns 5 viewers
- [ ] [정상] `ChannelPage` — should not show 시청 중 when the channel is offline

### 경계

- [ ] [경계] `MediaMtxClient.findHlsSessions` — should return no sessions after a single request when the list reports 0 pages
- [ ] [경계] `ViewerCounter.aggregate` — should write 0 for live channel D when D has no session
- [ ] [경계] `ViewerCounter.aggregate` — should leave out sessions whose path is not live and a channel id
- [ ] [경계] `ViewerCounter.aggregate` — should not read the session list when another server holds the round lock
- [ ] [경계] `ChannelService.find` — should return viewerCount 0 without reading viewer counts when the channel is not live
- [ ] [경계] `ViewerCountIntegration` — should store 0 for live channel D with no session in the current snapshot
- [ ] [경계] `ViewerCountIntegration` — should count the sessions from both pages when MediaMTX returns the session list in 2 pages
- [ ] [경계] `ViewerCountIntegration` — should read the MediaMTX session list once when two counters aggregate at the same time
- [ ] [경계] `ViewerCountIntegration` — should not read the session list again when a second counter runs before the lock expires
- [ ] [경계] `ViewerCountIntegration` — should hold the round lock for at most 4 seconds
- [ ] [경계] `ViewerCountIntegration` — should let the other counter aggregate in the next round after the lock of the stopped counter expires
- [ ] [경계] `ViewerCountIntegration` — should make the snapshot expire 2 minutes after it is written
- [ ] [경계] `ViewerCountIntegration` — should return viewerCount 0 for a live channel when the current snapshot has expired
- [ ] [경계] `ViewerCountIntegration` — should return viewerCount 0 for a live channel when no snapshot has been made
- [ ] [경계] `ChannelPage` — should show 0명 시청 중 when the live channel has no viewer
- [ ] [경계] `ChannelPage` — should stop showing 시청 중 when the broadcast ends while watching

### 예외

- [ ] [예외] `MediaMtxClient.findHlsSessions` — should throw when the MediaMTX API responds with an error
- [ ] [예외] `ViewerCounter.aggregate` — should not throw and not write a snapshot when the MediaMTX call fails
- [ ] [예외] `ViewerCountIntegration` — should keep viewerCount of the previous snapshot when MediaMTX fails in the next round

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. A 세션 3·B 1·방송 중이 아닌 C 2 → 판에 A=3, B=1, C 없음 | `[정상] ViewerCounter.aggregate — …A=3 and B=1 without C…`<br>`[정상] ViewerCountIntegration — …store A=3 and B=1 by broadcast id without C…`<br>`[경계] ViewerCounter.aggregate — …path is not live and a channel id`<br>`[정상] MediaMtxClient.findHlsSessions — …path and remote address…` |
| 2. 방송 중이고 세션 없는 D → 판에 D=0 | `[경계] ViewerCounter.aggregate — …write 0 for live channel D…`<br>`[경계] ViewerCountIntegration — …store 0 for live channel D…` |
| 3. 세션 목록이 여러 페이지 → 모든 페이지를 센다 | `[정상] MediaMtxClient.findHlsSessions — …every page…3 pages`<br>`[경계] MediaMtxClient.findHlsSessions — …0 pages`<br>`[경계] ViewerCountIntegration — …both pages…2 pages` |
| 4. 집계 뒤 A 조회 → `viewerCount` 3, 방송 중이 아니면 0 | `[정상] ViewerCountIntegration — …viewerCount 3 from the channel lookup of A…`·`…viewerCount 0…not live…`<br>`[정상] ChannelService.find — …viewer count of the live broadcast…`<br>`[경계] ChannelService.find — …viewerCount 0 without reading…` |
| 5. 집계 객체 두 개가 같은 회차에 동시에 → 세션 목록 한 번만 | `[경계] ViewerCountIntegration — …once when two counters aggregate at the same time`·`…second counter runs before the lock expires`<br>`[경계] ViewerCounter.aggregate — …another server holds the round lock` |
| 6. 잠금을 쥔 쪽이 멈춰 만료 → 다음 회차에 다른 쪽이 집계 | `[경계] ViewerCountIntegration — …other counter aggregate in the next round after the lock…expires`·`…lock for at most 4 seconds` |
| 7. MediaMTX 실패 → 예외가 나가지 않고 이전 판 값이 그대로 | `[예외] ViewerCounter.aggregate — …not throw and not write a snapshot…`<br>`[예외] ViewerCountIntegration — …keep viewerCount of the previous snapshot…`<br>`[정상] ViewerCountIntegration — …new snapshot in the next round after…failed`<br>`[예외] MediaMtxClient.findHlsSessions — …throw…error` |
| 8. 판이 2분 넘게 새로 안 생김 → `viewerCount` 0 | `[경계] ViewerCountIntegration — …current snapshot has expired`·`…expire 2 minutes after it is written`·`…no snapshot has been made` |
| 9. 방송 중이면 "N명 시청 중", 오프라인이면 안 보임 | `[정상] ChannelPage — …3명 시청 중…`·`…5명 시청 중 after the next poll…`·`…not show 시청 중…offline`<br>`[경계] ChannelPage — …0명 시청 중…`·`…stop showing 시청 중 when the broadcast ends…` |
| 10. 로컬 도커 실제 시청: 열면 10초 안에 +1, 닫으면 1분 안에 −1 | 수동 확인 — 아래 "수동 확인" 절에 시각과 관측값을 기록한다(자동 테스트 없음) |

**커버리지:** AC 10개(자동 9 + 수동 1) / 시나리오 30개 / 미커버 0개

AC에 없는데 추가한 것:
- `…path is not live and a channel id` — 데모 화질 경로 등 `live/{채널ID}` 모양이 아닌 세션이 섞여도 집계가 깨지지 않아야 한다(이슈 "`live/{채널ID}`별"). `LivePath` 규칙을 재사용한다.
- `…new snapshot in the next round after…failed` — 실패 회차 뒤에 집계가 멈추지 않고 이어지는지(이슈 "그 회차는 건너뛰고").
- `…lock for at most 4 seconds`·`…expire 2 minutes…` — 이슈가 정한 만료 값을 숫자로 못 박는다.

---

## 수동 확인

(Green 뒤에 로컬 도커에서 기록한다)
