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

// pages/broadcastView.ts — 화면 상태를 고르는 순수 함수(프론트 컨벤션). 방송 중이면 "{viewerCount}명 시청 중", 아니면 null
export function viewerCountLabel(live: boolean, viewerCount: number): string | null

// pages/ChannelPage.tsx — 제목 아래 메타 줄(채널 이름 옆)에 viewerCountLabel(live, viewerCount)이 null이 아니면 그 문구.
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

- [x] [정상] `MediaMtxClient.findHlsSessions` — should return the path and remote address of each session
- [x] [정상] `MediaMtxClient.findHlsSessions` — should read every page when the session list has 3 pages
- [x] [정상] `ViewerCounter.aggregate` — should write A=3 and B=1 without C when live channels A and B have 3 and 1 sessions and channel C without a live broadcast has 2
- [x] [정상] `ChannelService.find` — should return the viewer count of the live broadcast when the channel is live
- [x] [정상] `ViewerCountIntegration` — should store A=3 and B=1 by broadcast id without C in the current snapshot when live A, live B and offline C have 3, 1 and 2 sessions
- [x] [정상] `ViewerCountIntegration` — should return viewerCount 3 from the channel lookup of A after aggregating 3 sessions on A
- [x] [정상] `ViewerCountIntegration` — should return viewerCount 0 from the channel lookup of a channel that is not live even when its path has sessions
- [x] [정상] `ViewerCountIntegration` — should write a new snapshot in the next round after a round in which MediaMTX failed
- [x] [정상] `ChannelPage` — should show 3명 시청 중 when the live channel has 3 viewers
- [x] [정상] `ChannelPage` — should show 5명 시청 중 after the next poll returns 5 viewers
- [x] [정상] `viewerCountLabel` — should return null when the channel is offline

### 경계

- [x] [경계] `MediaMtxClient.findHlsSessions` — should return no sessions after a single request when the list reports 0 pages
- [x] [경계] `ViewerCounter.aggregate` — should write 0 for live channel D when D has no session
- [x] [경계] `ViewerCounter.aggregate` — should leave out sessions whose path is not live and a channel id
- [x] [경계] `ViewerCounter.aggregate` — should not read the session list when another server holds the round lock
- [x] [경계] `ChannelService.find` — should return viewerCount 0 without reading viewer counts when the channel is not live
- [x] [경계] `ViewerCountIntegration` — should store 0 for live channel D with no session in the current snapshot
- [x] [경계] `ViewerCountIntegration` — should count the sessions from both pages when MediaMTX returns the session list in 2 pages
- [x] [경계] `ViewerCountIntegration` — should read the MediaMTX session list once when two counters aggregate at the same time
- [x] [경계] `ViewerCountIntegration` — should not read the session list again when a second counter runs before the lock expires
- [x] [경계] `ViewerCountIntegration` — should hold the round lock for at most 4 seconds
- [x] [경계] `ViewerCountIntegration` — should let the other counter aggregate in the next round after the lock of the stopped counter expires
- [x] [경계] `ViewerCountIntegration` — should make the snapshot expire 2 minutes after it is written
- [x] [경계] `ViewerCountIntegration` — should return viewerCount 0 for a live channel when the current snapshot has expired
- [x] [경계] `ViewerCountIntegration` — should return viewerCount 0 for a live channel when no snapshot has been made
- [x] [경계] `ChannelPage` — should show 0명 시청 중 when the live channel has no viewer
- [x] [경계] `ChannelPage` — should stop showing 시청 중 when the broadcast ends while watching

### 예외

- [x] [예외] `MediaMtxClient.findHlsSessions` — should throw when the MediaMTX API responds with an error
- [x] [예외] `ViewerCounter.aggregate` — should not throw and not write a snapshot when the MediaMTX call fails
- [x] [예외] `ViewerCountIntegration` — should keep viewerCount of the previous snapshot when MediaMTX fails in the next round

### Red 기록

- 만든 stub: `channel/ViewerCountReader`(인터페이스), `Channel.liveBroadcastId`(필드), `ChannelResponse.viewerCount`, `ChannelService`의 생성자 인자 `ViewerCountReader`와 `viewerCountOf`(본문 `UnsupportedOperationException`), `broadcast/LiveBroadcast`(record), `BroadcastMapper.findLiveBroadcasts`(선언만, SQL 없음), `MediaMtxClient.findHlsSessions`·`HlsSessions`·`HlsSession`, `ViewerSnapshot`(키 상수 + 세 메서드 `UnsupportedOperationException`), `ViewerCounter.aggregate`, 프론트 `ChannelResponse.viewerCount` 타입과 `broadcastView.ts`의 `viewerCountLabel`(`throw`).
- **시나리오 한 줄을 바꿨다** — `[정상] ChannelPage — should not show 시청 중 when the channel is offline`을 `[정상] viewerCountLabel — should return null when the channel is offline`로 옮겼다. 화면에서 "안 보인다"만 보는 테스트는 구현이 없어도 통과한다(아무것도 안 그리니까). 프론트 컨벤션대로 화면 상태를 고르는 규칙을 순수 함수로 뽑아 그 함수의 결과를 본다. 화면 쪽 "안 보임"은 `…stop showing 시청 중 when the broadcast ends…`가 "먼저 보였다가 사라진다"로 본다. 시그니처 절에 `viewerCountLabel`을 더했다.
- **`ChannelService` stub이 기존 테스트를 깨뜨린다(의도)** — 응답을 만드는 `toResponse`가 `viewerCountOf`를 부르고, 그 stub이 던진다. "방송 중이 아니면 0"을 stub에서 미리 0으로 채우면 그 시나리오가 구현 없이 통과한다(#37의 `followerCount` stub이 그랬다). 그래서 채널 응답을 만드는 기존 테스트(`ChannelServiceTest` 13개, 채널 조회를 쓰는 통합 테스트)가 Red 동안 함께 실패한다. Green의 첫 구현에서 모두 돌아온다.
- 기존 테스트 손질(컴파일): `new ChannelResponse(...)`에 `viewerCount` 0(`ChannelControllerTest`·`ChannelOperatorControllerTest`·`ChannelServiceTest`), `new ChannelService(channelMapper)` → 가짜 `ViewerCountReader`를 넘긴다. 프론트 테스트 고정값 넷(`ChannelManagePage`·`StudioBroadcastPage`·`StudioFollowersPage`·`StudioLayout`)에 `viewerCount: 0`, `ChannelPage.test.tsx`의 `channel()`에 `viewerCount` 인자.
- 실행: 단위(`ViewerCounterTest`·`MediaMtxClientTest`·`ChannelServiceTest`)는 한 번에, 통합(`ViewerCountIntegrationTest`)은 컨테이너 기동 때문에 다 쓰고 파일 단위로 한 번, 프론트는 두 파일을 한 번.
- 결과: 시나리오 30개 = 백엔드 25개(클라이언트 4 · 집계 5 · 서비스 2 · 통합 14) + 프론트 5개(`ChannelPage` 4 · `viewerCountLabel` 1). **30개 전부 실패, 구현 없이 통과한 것 0개.** `check-scenarios.mjs` 불일치 0(종료 0).
  - 백엔드 25개 — 모두 stub의 not implemented에 닿았다(단언 `HttpServerErrorException` 기대 1개는 `UnsupportedOperationException`을 받아 실패, 동시 집계 1개는 `ExecutionException`, 판 없음 1개는 채널 조회가 `ServletException`).
  - 프론트 5개 — `viewerCountLabel`은 `not implemented`, 화면 4개는 "N명 시청 중"을 찾지 못해 실패.
- 전체 스위트(Red): 백엔드 516개 중 66개 실패 = 이번 이슈 25개 + `ChannelService` stub 때문에 함께 실패한 기존 41개(`ChannelServiceTest` 13·`ChannelIntegrationTest` 16·`FollowIntegrationTest` 8·`BroadcastIntegrationTest` 2·`StudioTermsIntegrationTest` 2 — 모두 채널 응답을 만드는 경로). 프론트 162개 중 5개 실패(이번 이슈 것만).
- `npx tsc -b`: stub의 안 쓰는 인자 두 개(`TS6133`)만 나온다. Green에서 사라진다.

### Green 기록

- 순서: `ChannelService.viewerCountOf`(방송 중일 때만 판을 읽는다) + `ChannelMapper.xml`의 `live_broadcast_id` → `MediaMtxClient.findHlsSessions`(페이지 루프) → `BroadcastMapper.xml`의 `findLiveBroadcasts` → `ViewerSnapshot`(잠금·판 쓰기·읽기) → `ViewerCounter.aggregate` → `ViewerCountScheduler`(5초) → 프론트 `viewerCountLabel` → `ChannelPage` 메타 줄.
- **Red에서 함께 깨졌던 기존 41개** — 첫 구현(`viewerCountOf`)으로 모두 돌아왔다.
- **구현하며 정한 것(시그니처 밖의 세부)**
  - 진행 중 방송 id는 채널 조회 SQL에 스칼라 하위 질의로 더했다. 조건을 `live` 식과 같은 `CASE WHEN b.ended_at IS NULL THEN b.channel_id END = c.id`로 써 `uk_broadcast_live_channel` 색인을 탄다. `live` 식은 그대로다.
  - 판 쓰기 = `INCR viewers:seq` → `HSET viewers:{판}:count`(방송ID → 수, 문자열) → `EXPIRE 2분` → `SET viewers:current`. 쓸 값이 없으면(진행 중 방송 0개) 해시는 만들지 않고 `current`만 바꾼다.
  - 잠금 = `SET viewers:lock 1 NX EX 4`. 값은 쓰지 않는다(풀지 않으므로 주인을 가릴 일이 없다).
  - 로그: 회차마다 `시청자 수 집계 sessions={} broadcasts={} elapsedMs={}`(INFO), MediaMTX 실패는 `시청자 수 집계 실패 cause={예외 이름} elapsedMs={}`(WARN). 세션 주소·예외 메시지는 남기지 않는다(`BroadcastReconciler`와 같은 이유).
  - 화면: "N명 시청 중"을 제목 아래 메타 줄(채널 이름·데모 표시 옆)에 글자로만 넣었다. 새 스타일·색 없음(`.meta`의 `gap`·글자 크기를 그대로 쓴다).
- 커버리지 명령(`coverage`)이 설정에 없어 측정하지 않았다.
- 실행: 백엔드는 이번 이슈 네 파일(`ChannelServiceTest`·`MediaMtxClientTest`·`ViewerCounterTest`·`ViewerCountIntegrationTest`)을 돌린 뒤 전체를 한 번, 프론트는 전체(26파일 162개 통과)와 `tsc -b`(오류 0)·`lint`(경고 0).
- 백엔드 전체: 516개 중 1개 실패 — `NginxChannelCacheTest — should ask Spring again when the same channel is requested after 1 second`(nginx 컨테이너와 가짜 Spring으로 1초 캐시 만료를 2.1초 기다려 재는 테스트. 이 이슈의 코드를 지나지 않는다). 단독 재실행 통과. #38 기록의 같은 클래스 시간 흔들림과 같은 것으로 본다.

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
| 9. 방송 중이면 "N명 시청 중", 오프라인이면 안 보임 | `[정상] ChannelPage — …3명 시청 중…`·`…5명 시청 중 after the next poll…`<br>`[정상] viewerCountLabel — …null when the channel is offline`<br>`[경계] ChannelPage — …0명 시청 중…`·`…stop showing 시청 중 when the broadcast ends…` |
| 10. 로컬 도커 실제 시청: 열면 10초 안에 +1, 닫으면 1분 안에 −1 | 수동 확인 — 아래 "수동 확인" 절에 시각과 관측값을 기록한다(자동 테스트 없음) |

**커버리지:** AC 10개(자동 9 + 수동 1) / 시나리오 30개 / 미커버 0개

AC에 없는데 추가한 것:
- `…path is not live and a channel id` — 데모 화질 경로 등 `live/{채널ID}` 모양이 아닌 세션이 섞여도 집계가 깨지지 않아야 한다(이슈 "`live/{채널ID}`별"). `LivePath` 규칙을 재사용한다.
- `…new snapshot in the next round after…failed` — 실패 회차 뒤에 집계가 멈추지 않고 이어지는지(이슈 "그 회차는 건너뛰고").
- `…lock for at most 4 seconds`·`…expire 2 minutes…` — 이슈가 정한 만료 값을 숫자로 못 박는다.

---

## 수동 확인

AC 10 — 로컬 도커에서 실제 시청(2026-11-28, KST).

**방법**

- 이 브랜치(Green)로 `docker compose up -d --build api-1 api-2 nginx`. 데모 방송(`demo` 컨테이너)은 그대로 송출 중이다. 진행 중 방송 3개(데모 채널 62 포함).
- 호스트에서 1초마다 `curl http://localhost:8000/api/channels/62`의 `viewerCount`를 시각과 함께 기록했다(nginx 1초 캐시를 거친 값 — 시청자가 보는 값과 같다).
- 시청: 데스크톱 앱의 브라우저 창(실제 Chromium, hls.js 재생)으로 `http://localhost:8000/channels/62`를 열고, 화면 글자를 읽은 뒤 탭을 닫았다. 그 사이 다른 시청자는 없었다(열기 전 `viewerCount` 0, MediaMTX 세션 0).

**관측**

| 시각 | 한 일 / 본 것 |
| --- | --- |
| 07:28:00~07:28:13 | `viewerCount` 0 (기준) |
| 07:28:13~07:28:18 | 방송 화면 열기(창을 여는 호출이 이 사이에 끝났다) |
| 07:28:16 | api-2 집계 로그 `sessions=1 broadcasts=3 elapsedMs=18` |
| 07:28:17 | `viewerCount` 1 — **열고 약 4초 안**(10초 안 ✅) |
| 07:28:40 무렵 | 열어 둔 화면의 글자에 `1명 시청 중`이 보였다 |
| 07:28:48~07:28:49 | 탭 닫기 |
| 07:29:25 | `viewerCount` 0 — **닫고 약 36초 뒤**(1분 안 ✅) |

- 집계는 두 서버 중 그 회차 잠금을 잡은 쪽이 했다(로그가 api-1·api-2에 번갈아 나온다. 같은 5초 안에 두 번 세지 않았다).
- 닫은 뒤 36초는 PRD V1 Consequences의 "세션이 약 30초 남고 + 5초"와 맞는다.
- 이 시간 api 로그에 WARN·ERROR는 시작 때 원래 있던 둘(Flyway 버전 권고, 기본 사용자 설정)뿐이다. 집계 로그에 주소는 없다.
