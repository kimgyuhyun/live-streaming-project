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

### Refactor 기록

- 기준선: Green 끝의 전체 통과(프론트 162개, 백엔드 516개 — `NginxChannelCacheTest` 시간 흔들림 1개는 단독 재실행 통과). AC 검증 때 이슈 테스트 네 파일도 다시 통과했다.
- 대상: `git diff main...HEAD`의 소스 15개(테스트 제외). 다섯 기준으로 보고 **고칠 것이 없다**고 판단해 코드를 바꾸지 않았다(refactor 커밋 없음).
  - "진행 중" 조건(`CASE WHEN ended_at IS NULL THEN channel_id END = c.id`) — `ChannelMapper.xml`의 `live` 식과 `live_broadcast_id` 하위 질의 두 곳. `live`를 `live_broadcast_id IS NOT NULL`로 바꾸면 #38이 `FollowMapper.xml`에서 include하는 `<sql id="live">`까지 흔든다. 중복은 세 번째부터 뽑는다.
  - 매직 넘버 — 4초·2분은 `ViewerSnapshot.LOCK_TTL`·`SNAPSHOT_TTL` 상수, Redis 키 이름은 `ViewerSnapshot` 한 곳, 5초 주기는 `@Scheduled` 한 곳(이슈 값).
  - `ViewerCounter.aggregate` — 잠금 → 세션 읽기(실패면 끝) → 채널별 셈 → 방송별 판 → 쓰기·로그. 한 회차를 순서대로 적은 한 덩어리라 쪼개면 오히려 흐름이 흩어진다.
  - `ChannelService.toResponse`가 `static`에서 인스턴스 메서드로 바뀐 것은 판 읽기(`viewerCountReader`) 때문이다. 다른 메서드는 그대로다.
- 손대지 않은 것: `ViewerCountScheduler` — 테스트 설정이 주기 작업을 끄므로(`app.scheduling.enabled=false`) 테스트가 없다. 내용이 `aggregate()` 호출 한 줄이고, 동작은 수동 확인(로컬 도커 로그의 5초 간격)으로 봤다.

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

---

## AC 검증

> `harness:ac-verifier` 출력 원문(2026-11-28). 구현자는 고치지 않는다.

## 이슈 #39 AC 검증 (브랜치 feat/viewer/issue-39, main과 비교)

10개 AC가 모두 충족됐습니다. 이번에 직접 돌린 테스트는 모두 통과했습니다.
- 백엔드: 오늘 07:31에 이슈 테스트 네 파일을 돌렸습니다(BUILD SUCCESSFUL). 결과 XML에서 이번 이슈 nested 클래스의 failures·errors가 모두 0입니다. 개수는 `ViewerCounter.aggregate` 5, `MediaMtxClient.findHlsSessions` 4, `ChannelService.find` 11(새 테스트 2개 포함), `ViewerCountIntegration` 14입니다.
- 프론트: `ChannelPage.test.tsx`·`broadcastView.test.ts` 두 파일에서 43개가 통과했습니다.
- `check-scenarios.mjs 39`: 시나리오 30, 더한 테스트 30, 불일치 0입니다.

### AC 1 — 방송 중 A에 세션 3, B에 1, 방송 중이 아닌 C에 2 → 판에 A=3, B=1이고 C는 없다
✅ 충족
- 테스트:
  - 통합: `should store A=3 and B=1 by broadcast id without C in the current snapshot when live A, live B and offline C have 3, 1 and 2 sessions` (C:\live-streaming-project\backend\src\test\java\com\livestream\broadcast\ViewerCountIntegrationTest.java:189-207)
  - 단위: `should write A=3 and B=1 without C when …` (C:\live-streaming-project\backend\src\test\java\com\livestream\broadcast\ViewerCounterTest.java:55-69)
  - 보조: `should leave out sessions whose path is not live and a channel id` (ViewerCounterTest.java:82-93)
- 구현:
  - `ViewerCounter.aggregate`가 세션을 `LivePath.channelIdOf`로 채널별로 셉니다(C:\live-streaming-project\backend\src\main\java\com\livestream\broadcast\ViewerCounter.java:45).
  - 판에는 `findLiveBroadcasts`(진행 중 방송)마다 한 칸씩만 넣습니다(ViewerCounter.java:49-52).
  - SQL은 `SELECT id, channel_id FROM broadcast WHERE ended_at IS NULL`입니다(C:\live-streaming-project\backend\src\main\resources\mapper\broadcast\BroadcastMapper.xml:16-17).
- 근거:
  - 통합 테스트는 진짜 Oracle·Redis와 가짜 MediaMTX(:19997)로 돌립니다.
  - C는 방송했다가 끝낸 채널입니다. 판을 `containsEntry(A방송id,"3")`·`containsEntry(B방송id,"1")`·`doesNotContainKey(C의 끝난 방송id)`로 단언합니다.
  - 단위 테스트는 판 전체를 `isEqualTo(Map.of(11L,3L,12L,1L))`로 봅니다. 그래서 C가 끼면 실패합니다.

### AC 2 — 방송 중이고 세션이 없는 D → 판에 D=0
✅ 충족
- 테스트:
  - 통합: `should store 0 for live channel D with no session in the current snapshot` (ViewerCountIntegrationTest.java:251-261)
  - 단위: `should write 0 for live channel D when D has no session` (ViewerCounterTest.java:71-80)
- 구현: `sessionsByChannel.getOrDefault(broadcast.channelId(), 0L)` (ViewerCounter.java:50). 값이 있으면 `HSET`으로 씁니다(C:\live-streaming-project\backend\src\main\java\com\livestream\broadcast\ViewerSnapshot.java:38-47).
- 근거:
  - 통합 테스트에서 가짜 MediaMTX는 `pageCount 0`·빈 목록을 줍니다. 판을 `containsEntry(D방송id, "0")`로 단언합니다.
  - 값이 없으면 키 자체가 없으므로 "0을 썼다"와 "안 썼다"를 구분합니다.

### AC 3 — 세션 목록이 여러 페이지 → 모든 페이지의 세션을 센다
✅ 충족
- 테스트:
  - 단위 3페이지: `should read every page when the session list has 3 pages` (C:\live-streaming-project\backend\src\test\java\com\livestream\broadcast\MediaMtxClientTest.java:209-221). `page=0,1,2` 요청을 하나씩 기대하고, 세 페이지 세션을 순서대로 `containsExactly`로 단언합니다.
  - 단위 0페이지: `should return no sessions after a single request when the list reports 0 pages` (:223-231). 두 번째 요청이 오면 MockRestServiceServer가 실패시킵니다.
  - 통합: `should count the sessions from both pages when MediaMTX returns the session list in 2 pages` (ViewerCountIntegrationTest.java:263-273). 2+1 세션이 채널 조회에서 3으로 나오는지 봅니다.
- 구현: `do { GET ?page={page} … page++ } while (page < current.pageCount())` (C:\live-streaming-project\backend\src\main\java\com\livestream\broadcast\MediaMtxClient.java:47-58)
- 경계: 0페이지(요청 1회), 2페이지, 3페이지를 모두 봅니다. 마지막 페이지 다음을 더 묻지 않는 것은 3페이지 테스트가 기대하지 않은 요청을 거부하는 것으로 확인됩니다.

### AC 4 — 집계 뒤 A 조회 → `viewerCount` 3, 방송 중이 아닌 채널은 0
✅ 충족
- 테스트:
  - 통합: `should return viewerCount 3 from the channel lookup of A after aggregating 3 sessions on A` (ViewerCountIntegrationTest.java:209-219). MockMvc로 `GET /api/channels/{id}`를 부르고 JSON의 `viewerCount`를 읽습니다.
  - 통합: `should return viewerCount 0 from the channel lookup of a channel that is not live even when its path has sessions` (:221-230). 경로에 세션 2개가 있어도 0입니다.
  - 단위: `should return the viewer count of the live broadcast when the channel is live` (C:\live-streaming-project\backend\src\test\java\com\livestream\channel\ChannelServiceTest.java:126). 단위: `should return viewerCount 0 without reading viewer counts when the channel is not live` (:138). 판을 읽지 않았는지 `verify(never())`로 봅니다.
- 구현:
  - `ChannelService.viewerCountOf`: `liveBroadcastId == null ? 0 : viewerCountReader.viewerCount(...)` (C:\live-streaming-project\backend\src\main\java\com\livestream\channel\ChannelService.java:73-75)
  - 조회 SQL의 `live_broadcast_id` 하위 질의 (C:\live-streaming-project\backend\src\main\resources\mapper\channel\ChannelMapper.xml:33)
  - `ViewerSnapshot.viewerCount`: `current` → 해시 (ViewerSnapshot.java:51)
- 근거: 단위 테스트의 목 값(3)뿐 아니라 통합 테스트에서 집계 → Redis 판 → SQL → 응답 JSON까지 실제 경로로 3이 나옵니다.

### AC 5 — 집계 객체 두 개(서버 2대)가 같은 회차에 동시에 돌면 세션 목록은 한 번만 읽힌다
✅ 충족
- 테스트:
  - `should read the MediaMTX session list once when two counters aggregate at the same time` (ViewerCountIntegrationTest.java:275-303). 같은 Redis를 보는 `ViewerCounter` 두 개를 `CountDownLatch`로 동시에 출발시킵니다. 가짜 MediaMTX의 요청 수를 `isEqualTo(1)`로 단언합니다.
  - 보조: `should not read the session list again when a second counter runs before the lock expires` (:305-317)
  - 보조(단위): `should not read the session list when another server holds the round lock` (ViewerCounterTest.java:95-103)
- 구현:
  - `tryLock()` = `setIfAbsent(LOCK_KEY, "1", 4초)` (ViewerSnapshot.java:32-33)
  - 못 잡으면 바로 return합니다(ViewerCounter.java:32-34). MediaMTX 호출은 그 뒤에 있습니다.
- 근거:
  - 잠금이 Redis `SET NX` 한 번이라 원자적입니다.
  - 테스트 설정이 `app.scheduling.enabled: false`입니다(C:\live-streaming-project\backend\src\test\resources\config\application.yml:10-11). 그래서 스케줄러가 요청 수를 오염시키지 않습니다.

### AC 6 — 잠금을 잡은 쪽이 멈춰 잠금이 만료되면, 다음 회차에 다른 쪽이 집계한다
✅ 충족
- 테스트:
  - `should let the other counter aggregate in the next round after the lock of the stopped counter expires` (ViewerCountIntegrationTest.java:329-344). 잠금을 지우지 않고 실제 만료를 기다립니다(`await` 최대 6초). 그 뒤 다른 집계 객체가 돌면 요청 수가 2입니다.
  - `should hold the round lock for at most 4 seconds` (:319-327). 잠금 TTL이 1~4000ms인지 봅니다.
- 구현: 잠금을 풀지 않고 `LOCK_TTL = 4초` 만료로만 풀립니다(ViewerSnapshot.java:20, :33).
- 근거:
  - 잠금에 만료가 없으면 6초 `await`가 시간 초과로 실패합니다.
  - 만료가 4초보다 길면 TTL 단언이 실패합니다.
  - 약한 점: :329-344 하나만 보면, 잠금이 아예 없어도 요청 수가 2가 되어 통과합니다. 잠금이 동작한다는 것은 AC 5의 두 테스트가 따로 보장합니다. 그래서 묶어서 충족으로 봅니다.

### AC 7 — MediaMTX 호출이 실패하면 예외가 밖으로 나가지 않고 이전 판의 값이 그대로 보인다
✅ 충족
- 테스트:
  - 통합: `should keep viewerCount of the previous snapshot when MediaMTX fails in the next round` (ViewerCountIntegrationTest.java:382-395). 3으로 판을 쓰고, 잠금을 지워 다음 회차로 넘깁니다. 가짜 MediaMTX가 500을 주게 한 뒤 `aggregate()`를 부르고, 채널 조회가 3인지 봅니다.
  - 단위: `should not throw and not write a snapshot when the MediaMTX call fails` (ViewerCounterTest.java:105-113). `assertThatCode(...).doesNotThrowAnyException()`과 `verify(viewerSnapshot, never()).write(any())`로 봅니다.
  - 보조: `should write a new snapshot in the next round after a round in which MediaMTX failed` (:232-249). `should throw when the MediaMTX API responds with an error` (MediaMtxClientTest.java:233-239). 클라이언트는 `HttpServerErrorException`을 던집니다.
- 구현: `catch (RestClientException e)` → WARN 로그(예외 이름·경과 ms) → `return`. 판을 쓰기 전에 빠집니다(ViewerCounter.java:37-42).
- 근거:
  - 통합 테스트에서 예외가 밖으로 나가면 테스트 메서드가 에러로 끝납니다. 그래서 "예외가 나가지 않음"과 "값 유지"를 실제 경로로 함께 봅니다.
  - 단위 테스트는 연결 실패(`ResourceAccessException`)를 봅니다. 두 경우가 서로 다른 `RestClientException` 하위 타입입니다.
  - 약한 점: 통합 테스트는 실패 회차에 MediaMTX가 실제로 불렸는지(요청 수 2)를 단언하지 않습니다. 다만 `nextRound()`가 잠금을 지우므로 호출은 일어납니다.

### AC 8 — 판이 2분 넘게 새로 만들어지지 않으면 채널 조회의 `viewerCount`는 0이다
✅ 충족
- 테스트:
  - `should return viewerCount 0 for a live channel when the current snapshot has expired` (ViewerCountIntegrationTest.java:358-371). 판을 쓴 뒤 그 판에 1ms 만료를 걸고, 사라진 것을 확인합니다. 그 뒤 방송 중 채널의 조회가 0입니다.
  - `should make the snapshot expire 2 minutes after it is written` (:346-356). 지금 판의 TTL이 110~120초입니다.
  - `should return viewerCount 0 for a live channel when no snapshot has been made` (:373-380)
- 구현:
  - `redis.expire(countKey, SNAPSHOT_TTL)`, `SNAPSHOT_TTL = 2분` (ViewerSnapshot.java:21, :45)
  - `viewers:current`에는 만료가 없습니다. 판이 사라지면 해시 조회가 null이 되어 0을 돌려줍니다(ViewerSnapshot.java:51-60).
- 근거: 2분을 실제로 기다리지 않습니다. 대신 "만료 값이 2분" 단언과 "만료되면 0" 단언을 둘로 나눠 봅니다. TTL을 `current`에 걸거나 아예 안 걸면 :355가 실패합니다(-1 또는 범위 밖).

### AC 9 — 방송 중인 채널 페이지에는 "N명 시청 중"이 보이고, 오프라인이면 보이지 않는다
✅ 충족
- 테스트:
  - 방송 중이면 문구가 보임: `should show 3명 시청 중 when the live channel has 3 viewers` (C:\live-streaming-project\frontend\src\pages\ChannelPage.test.tsx:306-312)
  - 폴링으로 갱신: `should show 5명 시청 중 after the next poll returns 5 viewers` (:314-322)
  - 0명: `should show 0명 시청 중 when the live channel has no viewer` (:324-330)
  - 오프라인: `should stop showing 시청 중 when the broadcast ends while watching` (:332-341). 먼저 보인 뒤 종료 응답이 오면 `queryByText(/시청 중/)`이 null입니다.
  - `viewerCountLabel`: `should return null when the channel is offline` (C:\live-streaming-project\frontend\src\pages\broadcastView.test.ts:101-104). `viewerCountLabel(false, 3)`이라 "수가 있어도 오프라인이면 숨김"을 봅니다.
- 구현:
  - `viewerCountLabel`: `live ? \`${viewerCount}명 시청 중\` : null` (C:\live-streaming-project\frontend\src\pages\broadcastView.ts:34)
  - `ChannelPage.tsx`:41에서 그 값을 구하고, :63에서 null이 아니면 그립니다.
  - 갱신은 기존 `refetchInterval: pollInterval(live)`(5초)을 그대로 씁니다(C:\live-streaming-project\frontend\src\pages\ChannelPage.tsx:24).

### AC 10 — 로컬 도커 실제 시청: 열면 10초 안에 1 늘고, 닫으면 1분 안에 1 준다(수동)
✅ 충족 (시나리오 문서 "## 수동 확인" 기록 근거, 컨테이너 로그로 교차 확인)
- 기록(C:\live-streaming-project\docs\result\scenarios\issue-39.md:256-280):
  - 07:28:13~18에 화면을 열었고, 07:28:17에 `viewerCount` 1이 됐습니다(약 4초).
  - 07:28:48~49에 탭을 닫았고, 07:29:25에 0이 됐습니다(약 36초).
- 교차 확인(제가 직접 본 것):
  - api-1·api-2 로그의 집계 줄을 시각순으로 놓았습니다. `sessions=0`이 07:28:11까지 이어집니다. 첫 `sessions=1`은 07:28:16이고, 마지막 `sessions=1`은 07:29:18입니다. 다시 `sessions=0`이 된 것은 07:29:23입니다. 기록의 시각과 맞습니다.
  - 그 사이 집계는 회차마다 api-1·api-2 중 한쪽만 남겼습니다. 같은 기간 `시청자 수 집계 실패` WARN은 0건입니다(최근 10분 grep).
  - api 컨테이너 시작 시각은 07:27:14 KST입니다(`docker inspect`). 기록의 기준 시각 07:28:00보다 앞섭니다.
  - 지금 `curl http://localhost:8000/api/channels/62`의 응답은 `"live":true,…,"viewerCount":0`입니다. 필드가 실제 응답에 실려 있습니다.

## 요약
AC 10개 중 ✅ 10 / ⚠️ 0 / ❌ 0

## 추가 시나리오 제안 (조이는 용도, 필수 아님)
- [ ] [예외] `ViewerCountIntegration` — should call MediaMTX in the failed round and still keep viewerCount of the previous snapshot
  - AC 7 통합 테스트(:382-395)에 실패 회차의 요청 수(`listRequests == 2`)를 더합니다. "실패를 겪고도 유지"인지 "아예 안 불러서 유지"인지를 구분하는 용도입니다.

## 가짜 테스트 목록
발견하지 못했습니다.
- 예외 단언은 구체적인 타입입니다. `isInstanceOf(HttpServerErrorException.class)`(MediaMtxClientTest.java:238)를 씁니다.
- `assertThatCode(...).doesNotThrowAnyException()`(ViewerCounterTest.java:111)은 "예외가 나가지 않음"이 AC 자체입니다. 그래서 약한 단언이 아닙니다. 판을 쓰지 않았다는 `verify(never())`도 함께 봅니다.
- `ChannelServiceTest`의 3명 테스트(:126)는 목이 준 3을 단언합니다. 다만 검사 대상은 "방송 중이면 진행 중 방송 id로 읽는다"는 연결입니다(id 41L로 물어야 3이 나옵니다). 실제 값 경로는 통합 테스트(:209-219)가 따로 봅니다.
- 프론트의 `expect(await screen.findByText(...)).not.toBeNull()`은 겉보기에 약해 보입니다. 하지만 `findBy*`가 못 찾으면 예외를 던지므로 실제로 검증이 됩니다.
- AC 6 테스트(:329-344)는 혼자서는 잠금이 없어도 통과합니다(위 AC 6 "약한 점"). 가짜 테스트는 아니고, AC 5 테스트와 짝으로 의미가 있습니다.

## 확인하지 않은 것
- 백엔드 전체 스위트와 프론트 전체 스위트는 돌리지 않았습니다. 이슈 테스트 네 파일과 프론트 두 파일만 돌렸습니다.
- AC 10의 1초 간격 `curl` 기록과 브라우저 화면 글자("1명 시청 중")는 제가 다시 재현하지 않았습니다. 집계 로그의 시각으로만 교차 확인했습니다.
- 수동 확인 때 돌던 api 이미지가 지금 HEAD(5821767, 07:30:06 커밋)와 같은 코드인지는 확인하지 않았습니다. 이미지는 커밋 전 작업 트리로 빌드됐습니다(컨테이너 생성 07:27:10).
- 실제 MediaMTX가 세션이 많을 때(기본 페이지 크기를 넘을 때) `pageCount`를 기록대로 주는지는 실물로 확인하지 않았습니다. 페이지 처리는 가짜 서버와 MockRestServiceServer로만 봤습니다.
- 회차마다 남기는 경과 시간 로그는 AC가 아니라 이슈 "정한 것"입니다. 그래서 테스트로 보지 않았습니다(컨테이너 로그에 `elapsedMs=`가 찍히는 것만 봤습니다).

## 실행한 명령 목록
1. `cat .claude/harness.json && gh issue view 39 && git diff main...HEAD --stat` → 설정 확인, AC 10개 확인, 변경 28파일
2. Read `docs/result/scenarios/issue-39.md` → 시그니처·시나리오 30개·AC 대조표·수동 확인 기록 확인
3. `git diff main...HEAD -- backend/src/main frontend/src/pages/ChannelPage.tsx frontend/src/pages/broadcastView.ts frontend/src/api/channels.ts` → 구현 diff 확인
4. `cat -n …/ViewerCountIntegrationTest.java` → 통합 테스트 14개 본문 확인
5. `cat -n …/ViewerCounterTest.java …/MediaMtxClientTest.java; git diff main...HEAD -- …/ChannelServiceTest.java` → 단위 테스트 본문 확인
6. `git diff main...HEAD -- frontend/src/pages/ChannelPage.test.tsx frontend/src/pages/broadcastView.test.ts; grep -rn "scheduling" backend/src/test/resources backend/src/main/resources; grep -n "map-underscore…" …` → 프론트 테스트 5개 확인, 테스트 설정 `scheduling.enabled: false`(config/application.yml:10) 확인, `map-underscore-to-camel-case: true` 확인
7. `cat backend/src/test/resources/config/application.yml | sed -n 1,30p; docker compose ps` → 테스트 설정 확인, api-1·api-2·nginx·mediamtx·demo·oracle·redis 모두 Up
8. `cd backend && JAVA_HOME="$HOME/.jdks/liberica-21.0.7" ./gradlew test --tests "ViewerCounterTest" --tests "MediaMtxClientTest" --tests "ChannelServiceTest" --tests "ViewerCountIntegrationTest"` → BUILD SUCCESSFUL in 48s
9. `cd frontend && npx vitest run src/pages/ChannelPage.test.tsx src/pages/broadcastView.test.ts` → 2파일 43개 통과
10. `grep -o '<testsuite …' TEST-*.xml` (위 네 클래스) → ViewerCounter.aggregate 5, MediaMtxClient.findHlsSessions 4, ChannelService.find 11 외, ViewerCountIntegration 14, 모두 failures 0·errors 0
11. `cat …/LivePath.java; curl -s http://localhost:8000/api/channels/62; docker compose logs --since 1m api-1 api-2 | grep "시청자 수 집계" | tail -6` → 경로 규칙 확인, 응답에 `viewerCount:0` 있음, 집계 로그 5초 간격
12. `docker compose logs --since 1m api-1 api-2 | grep … | sort | tail -12; … | grep -c "WARN.*시청자 수 집계 실패"` → 두 서버가 회차마다 한쪽만 집계, 실패 WARN 0건
13. `docker inspect -f … api-1 api-2 nginx; git log -1 …; docker compose logs api-1 api-2 | grep "sessions=1" | head -5` → api 시작 07:27:14 KST, HEAD 커밋 07:30:06, 첫 sessions=1은 07:28:16
14. `docker compose logs api-1 api-2 | grep "시청자 수 집계" | awk … | sort | awk '22:28:00~22:29:40'` → sessions 0→1 전환 07:28:16, 1→0 전환 07:29:23
15. `ls /c/tdd-harness/scripts; find /c/tdd-harness -name "check-scenarios*"` → 스크립트 위치 확인
16. `node /c/tdd-harness/scripts/check-scenarios.mjs 39` → 시나리오 30, 더한 테스트 30, 불일치 0
17. `awk '/^## AC 검증/{f=1} f' docs/result/scenarios/issue-38.md | head -80` → 출력 형식 확인
18. `grep -n …` (ViewerSnapshot·ViewerCounter·MediaMtxClient·ChannelService·ChannelMapper.xml·BroadcastMapper.xml·ChannelPage.tsx·broadcastView.ts(.test)·ChannelPage.test.tsx·ChannelServiceTest·ViewerCountScheduler) → 근거 줄 번호 수집

### 구현자 이견

- 판정에는 이견이 없다. "확인하지 않은 것"에 대한 사실 보충만 남긴다.
  - 수동 확인 때의 api 이미지: Green의 소스 변경을 모두 끝낸 뒤 빌드했다. 빌드와 커밋(5821767) 사이에 바뀐 것은 이 문서(`issue-39.md`)뿐이다 — 소스는 커밋된 것과 같다.
  - 전체 스위트: Green 기록에 있다(백엔드 516개 중 시간 흔들림 1개 — 단독 재실행 통과, 프론트 162개 통과).
- 추가 시나리오 제안(실패 회차에 MediaMTX를 실제로 불렀는지)은 넣지 않는다. 검증자도 필수가 아니라고 했고, 갭을 테스트 추가로 메우지 않는다는 이번 작업 규칙도 있다. 후속 후보로 남긴다.

---

## 보안 점검

> `/harness:security-review 39` (2026-11-28). 대상: `git diff main...HEAD`.

**수집 결과**

- 타입: `./gradlew compileJava compileTestJava` 통과, `npx tsc -b` 오류 0.
- 의존성: `npm audit` 0건. 이 이슈는 의존성을 바꾸지 않았다(`securityExceptions` 대장은 설정에 없다).
- 비밀값: 더한 줄에서 비밀값 패턴·알려진 접두사 0건. 이 브랜치가 커밋한 `.env` 없음.
- 로그: 더한 로그는 둘 — `시청자 수 집계 sessions={} broadcasts={} elapsedMs={}`(INFO), `시청자 수 집계 실패 cause={예외 클래스 이름} elapsedMs={}`(WARN). 세션 주소·예외 메시지·응답 조각은 없다. `console.*` 없음.

**🔴 즉시 수정 필요 — 없음**

**🟡 권장 수정**

| 파일·줄 | 무엇 | 왜 이 분류 | 어떻게 |
| --- | --- | --- | --- |
| `ViewerSnapshot.write`(`putAll` 다음 `expire`) | 판 해시 쓰기와 만료 걸기가 두 명령이다. 그 사이에 서버가 죽으면 만료 없는 `viewers:{판}:count`가 남는다(그 판은 `current`가 가리키지 않아 보이지는 않는다) | 보안 위험이 아니라 Redis 메모리가 조금씩 새는 견고성 문제다. 서버가 그 몇 ms 사이에 죽을 때만 생긴다 | `MULTI`(또는 Lua 하나)로 `HSET`+`EXPIRE`를 묶는다. #42 측정(Redis 메모리)에서 함께 본다 |
| 집계 — 쿠키 없는 클라이언트 | 요청마다 세션이 새로 생겨(스파이크 4절 AC) 스크립트로 시청자 수를 부풀릴 수 있다 | 이미 정해진 다음 이슈(#40, IP당 5세션)가 막는다. 이 이슈는 이슈 결정대로 `remoteAddr`를 읽기만 한다 | #40 |

**⚪ 무시 가능(판단 근거)**

- **시청자 주소 저장·노출** — `remoteAddr`는 응답 record로 읽기만 하고 쓰지 않는다. Redis·DB·로그 어디에도 들어가지 않는다(이슈 결정). 응답의 `query`(송출 쪽에서는 키가 들어 있는 필드)는 record에 없어 역직렬화하지 않는다.
- **채널 조회에 실린 `viewerCount`** — 누구에게나 같은 공개 값이라 nginx 1초 캐시(`$uri`)로 남의 값이 보이는 문제가 없다. 읽는 쪽은 MediaMTX를 부르지 않아, 채널 조회를 많이 보내도 MediaMTX 부담이 늘지 않는다.
- **Redis 키에 들어가는 값** — 판 번호(`INCR` 결과)와 방송 id(DB `NUMBER`)뿐이다. 사용자 입력이 키에 들어가지 않는다.
- **페이지 루프** — 반복 횟수는 MediaMTX가 준 `pageCount`다. MediaMTX API는 도커 내부망에서만 열리고 Basic 인증(#26)을 거친다. 믿을 수 있는 내부 응답으로 본다. 세션 수에 따른 집계 시간은 #42가 잰다(회차마다 `elapsedMs` 로그).
- **잠금 주인 확인 없음** — 잠금을 풀지 않으므로(만료로만 풀림) 남의 잠금을 지우는 경로가 없다.
- **에러 응답** — 새 엔드포인트가 없다. MediaMTX 실패는 주기 작업 안에서 끝나 응답으로 나가지 않는다.
- **프론트** — 숫자를 JSX 텍스트로만 그린다(`dangerouslySetInnerHTML` 없음).

**처리** — 🔴가 없어 고친 것은 없다. 재스캔은 위 수집 결과가 곧 최종 상태다(코드 변경 없음).
