# 이슈 #38 — 팔로잉 화면에서 팔로우한 채널을 방송 중 먼저 본다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/38
> PRD: `docs/result/prd/viewer.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

---

## 시그니처

### 관련 결정

- **이슈 본문 "이 이슈에서 정한 것"** — 아래 값은 전부 거기서 그대로 옮겼다.
  - API: `GET /api/me/following?offset=` → `{items: [{channelId, name, live, title}], hasMore}`. 20개씩. 내 것만(다른 회원을 고르는 값이 없다).
  - 순서: 방송 중 먼저, 각 묶음 안에서는 팔로우한 시각(`follow.created_at`) 최근 순. SQL 한 번. 진행 중 여부는 `ChannelMapper.xml`의 `EXISTS` 식 그대로.
  - 화면: `/following`. 사이드바 "팔로잉"(지금 모양만)이 이 화면으로 간다. 비로그인은 로그인 화면으로. "더 보기"로 20개씩 이어 붙인다.
- **viewer PRD 작은 결정 "팔로잉 목록"** — PRD는 `?page=`로 적었지만 이슈가 `?offset=`으로 정했다. 이슈 값을 쓴다(이슈가 더 나중이고 더 구체적이다).
- **확정 사양 7절 팔로우** — 방송 중 먼저, 묶음 안에서는 최근에 팔로우한 순, 한 번에 20개씩 더.
- **확정 사양 4절 / #13** — 방송 제목을 정한 적 없으면 `"{채널 이름}의 방송"`. 채널 조회(`ChannelService.find`)와 같은 규칙을 팔로잉 목록의 `title`에도 쓴다.
- **보안** — `/api/me/following`은 `SecurityConfig`의 `anyRequest().authenticated()`에 걸려 비로그인이면 401이다(새 설정 없음).

### 타입

```java
// ── com.livestream.follow ──────────────────────────────

// 팔로잉 목록 한 줄. title은 방송 제목(정한 적 없으면 "{name}의 방송")
public record FollowingChannel(long channelId, String name, boolean live, String title) {}

public record FollowingResponse(List<FollowingChannel> items, boolean hasMore) {}

public interface FollowMapper {
    // (기존 셋 그대로)
    // 내가 팔로우한 채널을 방송 중 먼저 → 팔로우 시각 최근 순으로 offset부터 limit개. title은 저장값 그대로(NULL 가능)
    List<FollowingChannel> findFollowing(@Param("memberId") long memberId,
                                         @Param("offset") int offset, @Param("limit") int limit);
}

public class FollowService {
    public static final int FOLLOWING_PAGE_SIZE = 20;
    // 21개를 물어 20개를 돌려주고, 21번째가 있으면 hasMore true. offset이 음수면 INVALID_INPUT
    public FollowingResponse following(long memberId, int offset);
}

class FollowController {
    // offset이 없으면 0
    @GetMapping("/api/me/following")
    FollowingResponse following(@RequestParam(defaultValue = "0") int offset, @AuthenticationPrincipal LoginMember loginMember);
}
```

```xml
<!-- ChannelMapper.xml: columns 안의 live 식을 <sql id="live">로 꺼내 columns가 include한다(식은 그대로).
     FollowMapper.xml의 findFollowing이 <include refid="com.livestream.channel.ChannelMapper.live"/>로 같은 식을 쓴다 -->
```

### 프론트

```typescript
// api/follows.ts
export interface FollowingChannel { channelId: number; name: string; live: boolean; title: string }
export interface FollowingResponse { items: FollowingChannel[]; hasMore: boolean }
// 비로그인이면 401(ApiError)
export function fetchFollowing(offset: number): Promise<FollowingResponse>

// pages/FollowingPage.tsx — 예시 데이터(sampleAccount.ts의 SAMPLE_FOLLOWING·sortFollowing)를 응답으로 바꾼다
//   목록은 응답 순서 그대로(서버가 정렬한다). 각 채널은 /channels/{channelId} 링크
//   방송 중이면 LIVE 표시 + 방송 제목, 오프라인이면 이름만
//   hasMore면 "더 보기" → offset = 지금까지 받은 개수로 다음 묶음을 이어 붙인다(useInfiniteQuery)
//   빈 목록 → "팔로우한 채널이 없습니다."
//   401 → /login으로 보낸다(<Navigate replace>)
// (props 없음)

// router.tsx — RootLayout 아래 { path: 'following', element: <FollowingPage /> }
// components/Sidebar.tsx — "팔로잉" 항목에 to: '/following'
```

### 에러 케이스

| 상황 | 에러 코드 | HTTP |
| --- | --- | --- |
| 비로그인 | `UNAUTHENTICATED` (기존, 보안 설정) | 401 |
| `offset`이 음수 | `INVALID_INPUT` (기존) | 400 |

### 판단이 갈린 곳

- **`hasMore` 판단** — 21개를 물어 21번째가 있으면 `true`. `COUNT(*)`를 따로 하지 않아 "SQL 한 번"을 지킨다.
- **같은 시각에 팔로우한 채널** — 묶음·시각이 같으면 `channel_id` 큰 순으로 한 번 더 정렬한다. offset으로 나눠 읽을 때 순서가 매번 같아야 겹치거나 빠지지 않는다.
- **음수 offset** — 이슈에 없다. Oracle은 음수 `OFFSET`을 0으로 보지만, 잘못된 요청을 첫 페이지로 조용히 바꾸지 않고 기존 `INVALID_INPUT`(400)으로 거절한다. 새 에러 코드는 만들지 않는다.
- **오프라인 채널의 `title`** — 응답 모양이 정해져 있어 값은 늘 채운다(채널 조회와 같은 규칙). 화면은 방송 중일 때만 보여 준다.
- **`/me/following`(내 계정 메뉴 "팔로잉 채널")** — 이슈 뒤에 생긴 예시 데이터 화면이다. 이슈가 정한 화면은 `/following`이므로 그 경로를 새로 연다. 내 계정 메뉴의 경로는 그대로 두고 같은 컴포넌트를 쓴다(메뉴를 고치지 않는다 — 두 경로가 같은 진짜 목록을 보인다).
- **문구** — 빈 목록 문구는 기존 화면의 `팔로우한 채널이 없습니다.`(마침표 포함, 다른 내 계정 화면의 빈 문구와 같은 모양)를 그대로 쓴다.

### 이 이슈에서 만들지 않는 것

- 사이드바에 팔로잉 채널 목록(PRD 하지 않을 것)
- 팔로잉 화면 안의 팔로우 취소 버튼(이슈에 없다)
- 매퍼 단독 테스트(`@MybatisTest`) — SQL은 통합 테스트가 진짜 Oracle로 확인한다(#37과 같다)
- E2E(Playwright 설정이 없다)

---

## 테스트 시나리오

### 정상

- [ ] [정상] `FollowService.following` — should return every channel from the mapper with hasMore false when the mapper finds 3 channels
- [ ] [정상] `FollowService.following` — should ask the mapper for 21 channels from the given offset
- [ ] [정상] `FollowController.following` — should return 200 with items and hasMore of the logged-in member
- [ ] [정상] `FollowController.following` — should pass offset 20 to the service when the request has offset=20
- [ ] [정상] `FollowIntegration.following` — should list Y, Z, X when the member followed X, Y, Z in that order and only Y is live
- [ ] [정상] `FollowIntegration.following` — should return live true and the broadcast title for a live channel
- [ ] [정상] `FollowIntegration.following` — should not list a channel that only another member follows
- [ ] [정상] `FollowingPage` — should list the channels in the order the server returns
- [ ] [정상] `FollowingPage` — should show LIVE and the broadcast title for a live channel
- [ ] [정상] `FollowingPage` — should not show LIVE or the title for an offline channel
- [ ] [정상] `FollowingPage` — should go to the channel page when a channel is clicked
- [ ] [정상] `FollowingPage` — should append the next channels fetched with offset 20 when 더 보기 is clicked
- [ ] [정상] `Sidebar` — should open the following page when 팔로잉 is clicked

### 경계

- [ ] [경계] `FollowService.following` — should return 20 channels with hasMore true when the mapper finds 21 channels
- [ ] [경계] `FollowService.following` — should return hasMore false when the mapper finds exactly 20 channels
- [ ] [경계] `FollowService.following` — should return the channel name followed by 의 방송 as the title when the channel has never set a title
- [ ] [경계] `FollowController.following` — should use offset 0 when the request has no offset
- [ ] [경계] `FollowIntegration.following` — should return 20 channels and hasMore true on the first request when the member follows 21 channels
- [ ] [경계] `FollowIntegration.following` — should return 1 channel and hasMore false for offset=20 when the member follows 21 channels
- [ ] [경계] `FollowIntegration.following` — should return no items and hasMore false when the member follows no channel
- [ ] [경계] `FollowIntegration.following` — should treat a channel whose broadcast has ended as offline
- [ ] [경계] `FollowingPage` — should show 팔로우한 채널이 없습니다 when the member follows no channel
- [ ] [경계] `FollowingPage` — should not show 더 보기 when hasMore is false

### 예외

- [ ] [예외] `FollowService.following` — should throw INVALID_INPUT when the offset is negative
- [ ] [예외] `FollowIntegration.following` — should return 401 when a visitor who is not logged in asks for the following list
- [ ] [예외] `FollowIntegration.following` — should return 400 with code INVALID_INPUT when offset is negative
- [ ] [예외] `FollowingPage` — should go to the login page when the following list returns 401
- [ ] [예외] `Sidebar` — should go to the login page when a visitor who is not logged in clicks 팔로잉

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. X·Y·Z 순서로 팔로우, Y만 방송 중 → Y, Z, X | `[정상] FollowIntegration.following — …Y, Z, X…`<br>`[경계] FollowIntegration.following — …broadcast has ended as offline`<br>`[정상] FollowIntegration.following — …only another member follows`<br>`[정상] FollowingPage — …order the server returns` |
| 2. 21개 → 20개 + `hasMore: true`, `offset=20` → 1개 + `hasMore: false` | `[경계] FollowService.following — …21 channels…`·`…exactly 20 channels`<br>`[정상] FollowService.following — …21 channels from the given offset`·`…3 channels`<br>`[정상] FollowController.following — …offset 20…` / `[경계] …offset 0…`<br>`[경계] FollowIntegration.following — …20 channels and hasMore true…`·`…1 channel and hasMore false for offset=20…`<br>`[정상] FollowingPage — …append the next channels…` / `[경계] …not show 더 보기…`<br>`[예외] FollowService.following`·`FollowIntegration.following` — 음수 offset |
| 3. 팔로우한 채널 없음 → "팔로우한 채널이 없습니다" | `[경계] FollowingPage — …팔로우한 채널이 없습니다…`<br>`[경계] FollowIntegration.following — …no items and hasMore false…` |
| 4. 비로그인 → API 401, 사이드바 "팔로잉" → 로그인 화면 | `[예외] FollowIntegration.following — …401…`<br>`[예외] Sidebar — …visitor who is not logged in clicks 팔로잉`<br>`[예외] FollowingPage — …401`<br>`[정상] Sidebar — …open the following page…` |
| 5. 채널을 누르면 채널 페이지, 방송 중이면 방송 중 표시 + 방송 제목 | `[정상] FollowingPage — …channel page when a channel is clicked`·`…LIVE and the broadcast title…`·`…not show LIVE or the title for an offline channel`<br>`[정상] FollowIntegration.following — …live true and the broadcast title…`<br>`[정상] FollowController.following — …200 with items…`<br>`[경계] FollowService.following — …의 방송 as the title…` |

**커버리지:** AC 5개 / 시나리오 28개 / 미커버 0개

AC에 없는데 추가한 것: 음수 offset 거절(`INVALID_INPUT`) 2개 — 입력 검증, 판단이 갈린 곳 참고. 제목 기본값 1개 — 확정 사양 4절 규칙을 이 응답에도 지킨다.
