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

- [x] [정상] `FollowService.following` — should return every channel from the mapper with hasMore false when the mapper finds 3 channels
- [x] [정상] `FollowService.following` — should ask the mapper for 21 channels from the given offset
- [x] [정상] `FollowController.following` — should return 200 with items and hasMore of the logged-in member
- [x] [정상] `FollowController.following` — should pass offset 20 to the service when the request has offset=20
- [x] [정상] `FollowIntegration.following` — should list Y, Z, X when the member followed X, Y, Z in that order and only Y is live
- [x] [정상] `FollowIntegration.following` — should return live true and the broadcast title for a live channel
- [x] [정상] `FollowIntegration.following` — should not list a channel that only another member follows
- [x] [정상] `FollowingPage` — should list the channels in the order the server returns
- [x] [정상] `FollowingPage` — should show LIVE and the broadcast title for a live channel
- [x] [정상] `FollowingPage` — should not show LIVE or the title for an offline channel
- [x] [정상] `FollowingPage` — should go to the channel page when a channel is clicked
- [x] [정상] `FollowingPage` — should append the next channels fetched with offset 20 when 더 보기 is clicked
- [x] [정상] `Sidebar` — should open the following page when 팔로잉 is clicked

### 경계

- [x] [경계] `FollowService.following` — should return 20 channels with hasMore true when the mapper finds 21 channels
- [x] [경계] `FollowService.following` — should return hasMore false when the mapper finds exactly 20 channels
- [x] [경계] `FollowService.following` — should return the channel name followed by 의 방송 as the title when the channel has never set a title
- [x] [경계] `FollowController.following` — should use offset 0 when the request has no offset
- [x] [경계] `FollowIntegration.following` — should return 20 channels and hasMore true on the first request when the member follows 21 channels
- [x] [경계] `FollowIntegration.following` — should return 1 channel and hasMore false for offset=20 when the member follows 21 channels
- [x] [경계] `FollowIntegration.following` — should return no items and hasMore false when the member follows no channel
- [x] [경계] `FollowIntegration.following` — should treat a channel whose broadcast has ended as offline
- [x] [경계] `FollowingPage` — should show 팔로우한 채널이 없습니다 when the member follows no channel
- [x] [경계] `FollowingPage` — should not show 더 보기 when hasMore is false

### 예외

- [x] [예외] `FollowService.following` — should throw INVALID_INPUT when the offset is negative
- [x] [예외] `FollowIntegration.following` — should return 401 when a visitor who is not logged in asks for the following list
- [x] [예외] `FollowIntegration.following` — should return 400 with code INVALID_INPUT when offset is negative
- [x] [예외] `FollowingPage` — should go to the login page when the following list returns 401
- [x] [예외] `Sidebar` — should go to the login page when a visitor who is not logged in clicks 팔로잉

### Red 기록

- 만든 stub: `follow/FollowingChannel`·`FollowingResponse`(record), `FollowMapper.findFollowing`(선언만, SQL 없음), `FollowService.following`·`FollowController.following`(본문 `UnsupportedOperationException`), 프론트 `api/follows.ts`의 `FollowingChannel`·`FollowingResponse`·`fetchFollowing`(`throw`).
- 기존 테스트 손질: `FollowingPage.test.tsx`의 예시 데이터 순서 테스트 1개를 이 이슈의 시나리오 8개로 바꿨다(화면이 예시 데이터 대신 응답을 그린다). `sampleAccount.test.ts`의 `sortFollowing` 테스트 2개를 뺐다 — 정렬은 서버가 한다. `sortFollowing`·`SAMPLE_FOLLOWING`은 Green에서 화면이 응답으로 바뀌면 쓰는 곳이 없어 지운다.
- 실행: 단위·컨트롤러는 파일마다(`FollowServiceTest`·`FollowControllerTest`), 통합(`FollowIntegrationTest`)은 컨테이너 기동 때문에 다 쓰고 파일 단위로 한 번, 프론트는 두 파일을 한 번.
- 결과: 시나리오 28개 = 백엔드 18개(서비스 6 · 컨트롤러 3 · 통합 9) + 프론트 10개(`FollowingPage` 8 · `Sidebar` 2). `check-scenarios.mjs` 불일치 0.
  - 백엔드 17개 실패 — 서비스 5개는 stub의 not implemented, 음수 offset 1개는 단언(`BusinessException` 기대, `UnsupportedOperationException`), 컨트롤러 3개·통합 8개는 stub의 not implemented.
  - 프론트 10개 전부 단언 실패(응답의 채널 이름·"더 보기"·로그인 화면을 찾지 못함).
- **구현 없이 통과한 1개** — `FollowIntegration.following — should return 401 when a visitor who is not logged in asks for the following list`. `SecurityConfig`의 `anyRequest().authenticated()`가 이미 막는다. Green에서 변이로 확인한다.

### Green 기록

- 순서: 서비스(음수 offset → 21개 묻기 → 20개 자르기·`hasMore` → 제목 기본값) → 컨트롤러 → SQL(`FollowMapper.xml`의 `findFollowing`) → 프론트(`fetchFollowing` → `FollowingPage` → 라우터 `/following` → 사이드바 연결).
- **진행 중 여부 식 재사용** — `ChannelMapper.xml`의 `columns` 안에 있던 `EXISTS` 식을 `<sql id="live">`로 꺼내 `columns`가 include하고, `FollowMapper.xml`이 `<include refid="com.livestream.channel.ChannelMapper.live"/>`로 같은 식을 쓴다. 식 자체는 한 글자도 바꾸지 않았다(채널 별칭 `c`). 채널 테스트(`Channel*`)가 그대로 통과한다.
- **변이 확인** — `SecurityConfig`에 `.requestMatchers("/api/me/following").permitAll()`을 넣자 `FollowIntegration.following — …401…`이 실패했다(1/26). 되돌렸다.
- **구현하며 정한 것(시그니처 밖의 세부)**
  - 정렬 `ORDER BY live DESC, f.created_at DESC, f.channel_id DESC` + `OFFSET … ROWS FETCH NEXT 21 ROWS ONLY`(Oracle 12c+ 문법, 판단이 갈린 곳의 동률 처리).
  - 화면은 `useInfiniteQuery`. 다음 offset = 지금까지 받은 개수. `retry: false`(401은 다시 물어도 같다). 401이면 `<Navigate to="/login" replace>` — 뒤로 가기로 팔로잉 화면에 돌아와 다시 튕기지 않게 `replace`.
  - 각 채널 칸 전체가 `/channels/{id}` 링크다. 방송 중이면 기존 빨간 테두리·LIVE 표시에 더해 이름 아래 방송 제목(한 줄, 줄임). 스타일은 `AccountPages.module.css`에 `.followTitle`·`.more`·`.moreButton`을 더했다(`ChannelVideos`의 더보기와 같은 모양, 기존 변수만).
  - `/me/following`(내 계정 메뉴)은 그대로 두고 같은 컴포넌트를 쓴다. 이제 두 경로 모두 진짜 목록이다.
  - 예시 데이터 `SAMPLE_FOLLOWING`·`sortFollowing`·`FollowingChannel`(sampleAccount.ts)은 쓰는 곳이 없어져 지웠다.
- 커버리지 명령(`coverage`)이 설정에 없어 측정하지 않았다.
- 실행: 전체 스위트가 몇 분 걸려(Oracle 컨테이너) 시나리오마다 전체를 돌리지 않았다. 백엔드는 계층별로 `FollowServiceTest`·`FollowControllerTest` → `FollowIntegrationTest`·`Channel*`, 프론트는 전체(26파일 157개 통과), 끝에 백엔드 전체를 한 번 돌렸다.
- 백엔드 전체 1회차: 490개 중 1개 실패 — `NginxChannelCacheTest — should return a fresh answer from Spring when the same channel is requested after 1 second`(nginx 1초 캐시 만료를 시간으로 재는 테스트, 이 이슈가 건드리지 않은 경로). 따로 다시 돌리자 통과했다. 시간에 기대는 기존 테스트의 흔들림으로 본다.

### Refactor 기록

- 기준선: Green 끝의 전체 통과(프론트 157개, 백엔드 490개 — `NginxChannelCacheTest` 시간 흔들림 1개는 단독 재실행 통과).
- 대상: `git diff main...HEAD`의 소스 13개(테스트 제외). 다섯 기준으로 보고 **고칠 것이 없다**고 판단해 코드를 바꾸지 않았다(refactor 커밋 없음).
  - 제목 기본값 `"{name}의 방송"` — `ChannelService.find`와 `FollowService.withDefaultTitle` 두 곳. 중복은 세 번째부터 뽑는다.
  - "더 보기" 버튼 CSS — `ChannelVideos.module.css`와 `AccountPages.module.css` 두 곳(색·간격은 모두 변수). 같은 이유로 공통 부품으로 뽑지 않았다. 세 번째 화면이 생기면 `docs/design.md` 5절 공통 부품으로 올린다.
  - 매직 넘버 — 20은 `FollowService.FOLLOWING_PAGE_SIZE` 상수, 프론트는 서버의 `hasMore`와 받은 개수만 써서 20을 모른다.
  - `ChannelMapper.xml`의 `<sql id="live">` 추출은 이미 Green에서 했다(이슈가 정한 "식 재사용").

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

---

## AC 검증

> `harness:ac-verifier` 출력 원문(2026-11-27). 구현자는 고치지 않는다.

## 이슈 #38 AC 검증 (브랜치 feat/viewer/issue-38, main과 비교)

5개 AC가 모두 충족됐습니다. 이번에 직접 돌린 테스트는 모두 통과했습니다.
- 백엔드 follow 패키지: 오늘 15:36에 돌렸고 `#38` 대상 nested 클래스 기준으로 서비스 6, 컨트롤러 3, 통합 9개가 failures 0, errors 0입니다.
- 프론트: `FollowingPage`·`Sidebar` 두 파일에서 10개가 통과했습니다.

### AC 1 — X·Y·Z 순으로 팔로우했고 Y만 방송 중이면 Y, Z, X 순이다
✅ 충족
- 테스트: `should list Y, Z, X when the member followed X, Y, Z in that order and only Y is live` (C:\live-streaming-project\backend\src\test\java\com\livestream\follow\FollowIntegrationTest.java:461-477)
- 구현: `findFollowing`의 `ORDER BY live DESC, f.created_at DESC, f.channel_id DESC` (C:\live-streaming-project\backend\src\main\resources\mapper\follow\FollowMapper.xml). live 식은 `ChannelMapper.xml`의 `<sql id="live">`를 include하며, 식 자체는 diff상 바뀌지 않았습니다.
- 근거: 팔로우 시각을 1·2·3분으로 다르게 넣고 Y에만 진행 중 broadcast를 넣었습니다. 순서를 `containsExactly("Y","Z","X")`로 단언합니다(:476).
  - 이 순서는 "방송 중 먼저 + 오래된 순"(Y,X,Z)과도, "최근 순만"(Z,Y,X)과도 다릅니다. 그래서 두 규칙을 모두 구분해 냅니다.
  - 보조 근거: 끝난 방송은 오프라인으로 처리합니다(:543-557). 남이 팔로우한 채널은 목록에서 빠집니다(:496-507). 프론트는 서버가 준 순서를 그대로 씁니다(C:\live-streaming-project\frontend\src\pages\FollowingPage.test.tsx:43-53).

### AC 2 — 21개를 팔로우하면 첫 요청은 20개와 hasMore true, offset=20은 1개와 hasMore false다
✅ 충족 (아래 제안대로 조이면 더 단단해짐)
- 테스트:
  - `should return 20 channels and hasMore true on the first request…` (FollowIntegrationTest.java:509-519)
  - `should return 1 channel and hasMore false for offset=20…` (:521-531)
  - 서비스 단위 테스트(C:\live-streaming-project\backend\src\test\java\com\livestream\follow\FollowServiceTest.java): 21개는 20개와 true(:231), 정확히 20개는 false(:241), 3개는 false(:211), 매퍼에 21개를 요청함(:221)
  - 컨트롤러: offset 20을 넘김, 값이 없으면 0 (C:\live-streaming-project\backend\src\test\java\com\livestream\follow\FollowControllerTest.java:124, :135)
  - 프론트: "더 보기"를 누르면 offset 20으로 다음 묶음을 이어 붙임(FollowingPage.test.tsx:83-94), hasMore가 false면 "더 보기"를 숨김(:103-109)
- 구현:
  - `FollowService.following`은 `findFollowing(memberId, offset, 21)`로 21개를 물어 20개로 자르고 `rows.size() > 20`으로 hasMore를 정합니다(C:\live-streaming-project\backend\src\main\java\com\livestream\follow\FollowService.java). SQL은 `OFFSET #{offset} ROWS FETCH NEXT #{limit} ROWS ONLY`입니다.
  - 프론트 `getNextPageParam`은 지금까지 받은 개수의 합을 다음 offset으로 씁니다(C:\live-streaming-project\frontend\src\pages\FollowingPage.tsx).
- 경계: 19개 이하(3개), 정확히 20개, 21개를 모두 서비스 단위에서 봅니다. hasMore 판단은 서비스가 하므로 경계를 서비스에서 보는 것이 맞습니다.
- 약한 점: offset=20 테스트는 개수와 hasMore만 단언합니다. 그 1개가 첫 묶음에 없던 채널(가장 먼저 팔로우한 채널)인지는 확인하지 않습니다. 두 묶음이 겹치지 않는다는 점은 단언으로 확인되지 않은 상태입니다. 동률 정렬(`channel_id DESC`)은 구현돼 있습니다.

### AC 3 — 팔로우한 채널이 없으면 "팔로우한 채널이 없습니다"가 보인다
✅ 충족
- 테스트:
  - `should show 팔로우한 채널이 없습니다 when the member follows no channel` (FollowingPage.test.tsx:96-101): `findByText('팔로우한 채널이 없습니다.')`
  - 백엔드: `should return no items and hasMore false…` (FollowIntegrationTest.java:533-541): `{"items": [], "hasMore": false}`를 STRICT로 단언
- 구현: FollowingPage.tsx에서 `isSuccess`이고 목록이 비면 `<p className={styles.empty}>팔로우한 채널이 없습니다.</p>`를 그립니다.

### AC 4 — 비로그인이면 API는 401, 사이드바 "팔로잉"을 누르면 로그인 화면으로 간다
✅ 충족
- 테스트:
  - API 401: `should return 401 when a visitor who is not logged in asks for the following list` (FollowIntegrationTest.java:559-564). 보안 필터까지 실제로 거칩니다.
  - 사이드바: `should go to the login page when a visitor who is not logged in clicks 팔로잉` (C:\live-streaming-project\frontend\src\components\Sidebar.test.tsx:57-64, 파일 안 줄 기준). 진짜 `Sidebar`와 진짜 `FollowingPage`를 쓰고 `fetchFollowing`만 401로 바꿉니다.
  - 화면 단독: FollowingPage.test.tsx:111-116
- 구현:
  - `SecurityConfig`의 `.anyRequest().authenticated()`(:61)에 걸립니다. `/api/me/**`를 허용하는 줄은 없습니다(grep 결과 permitAll은 :50-58의 다른 경로뿐).
  - 사이드바: C:\live-streaming-project\frontend\src\components\Sidebar.tsx:17에 `to: '/following'`이 있고, `to`가 있으면 NavLink로 그립니다(:44-53).
  - 라우터: C:\live-streaming-project\frontend\src\router.tsx:38에 `{ path: 'following', element: <FollowingPage /> }`
  - 401 처리: FollowingPage.tsx에서 `ApiError`이고 status가 401이면 `<Navigate to="/login" replace />`
  - 실제 API 경로에서도 401이 같은 모양으로 오는지 확인했습니다. `api()`는 응답이 ok가 아니면 `ApiError(res.status, body)`를 던집니다(C:\live-streaming-project\frontend\src\api\client.ts:33). 그래서 목으로 넣은 `ApiError(401)`과 실제 동작이 같습니다.
- 참고: 401 테스트는 구현 전부터 통과하던 테스트입니다. 시나리오 문서의 Green 기록에는 permitAll을 넣어 실패하는 것을 봤다는 변이 확인이 있지만, 저는 그 변이를 다시 해 보지 않았습니다.

### AC 5 — 채널을 누르면 그 채널 페이지로 가고, 방송 중인 채널에는 방송 중 표시와 방송 제목이 보인다
✅ 충족
- 테스트:
  - 클릭하면 채널 화면으로 감(FollowingPage.test.tsx:74-81). 링크 주소가 `/channels/{id}`인지는 :43-53에서 href로 단언합니다.
  - 방송 중이면 LIVE와 제목이 보임(:55-64). 오프라인이면 둘 다 없음(:66-72).
  - 백엔드: 방송 중 채널은 `live: true`와 방송 제목을 줌(FollowIntegrationTest.java:479-494). 제목을 정한 적 없으면 `"{이름}의 방송"`(FollowServiceTest.java:251)
- 구현:
  - FollowingPage.tsx: 칸 전체가 `<Link to={`/channels/${channel.channelId}`}>`이고, `channel.live`이면 LIVE 표시와 `<span className={styles.followTitle}>{channel.title}</span>`을 그립니다.
  - router.tsx:51에 `channels/:channelId`가 있습니다.
  - FollowService의 `withDefaultTitle`이 제목 기본값을 채웁니다.

## 요약
AC 5개 중 ✅ 5 / ⚠️ 0 / ❌ 0

## 추가 시나리오 제안 (조이는 용도, 필수 아님)
- [ ] [경계] `FollowIntegration.following` — should return the earliest followed channel for offset=20 when the member follows 21 channels
  - AC 2에서 두 묶음이 겹치지 않는지 확인하는 용도입니다. 지금 :521-531은 개수만 봅니다.

## 가짜 테스트 목록
발견하지 못했습니다.
- 예외 단언은 구체적입니다. `BusinessException`과 `code == INVALID_INPUT`까지 봅니다(FollowServiceTest.java:262-268).
- 프론트의 `expect(await screen.findByText(...)).not.toBeNull()`은 겉보기에 약해 보입니다. 하지만 `findBy*`가 못 찾으면 예외를 던지므로 실제로는 검증이 됩니다.
- 컨트롤러 `returnsFollowing`은 목이 돌려준 값을 단언합니다. 다만 컨트롤러가 맡은 JSON 직렬화 모양을 STRICT로 보는 것이므로 목 검사로만 끝나지는 않습니다.

## 확인하지 않은 것
- 백엔드 전체 스위트와 프론트 전체 스위트는 돌리지 않았습니다. follow 패키지와 프론트 두 파일만 돌렸습니다.
- `ChannelMapper.xml`의 live 식을 `<sql id="live">`로 꺼낸 리팩터링이 채널 테스트(`Channel*`)를 깨지 않는지는 제가 확인하지 않았습니다. `--tests "com.livestream.channel.ChannelMapper*"` 필터는 결과 파일이 하나도 생기지 않았습니다. AC 1의 live 판정은 통합 테스트로 확인했습니다.

## 실행한 명령 목록
1. `cat .claude/harness.json && gh issue view 38 && git diff main...HEAD --stat` → 설정 확인, AC 5개 확인, 변경 파일 20개
2. `cat docs/result/scenarios/issue-38.md` → 시나리오 28개와 AC 대조표 확인
3. `git diff main...HEAD -- backend/src/main frontend/src/api …/Sidebar.tsx …/FollowingPage.tsx …/router.tsx` → 구현 diff 읽음
4. `cat -n …/FollowIntegrationTest.java` → 통합 테스트 9개 본문 확인(:415-573)
5. `git diff main...HEAD -- FollowServiceTest/FollowControllerTest` + `grep -n DisplayName…` → 서비스 6개, 컨트롤러 3개의 본문과 줄 번호 확인
6. `cat -n FollowingPage.test.tsx Sidebar.test.tsx` → 프론트 테스트 10개 본문 확인
7. `cat -n Sidebar.tsx client.ts router.tsx` → 사이드바 `to`, ApiError가 던져지는 조건, `/following`과 `channels/:channelId` 라우트 확인(AccountLayout grep은 경로를 잘못 줘서 exit 2, 판정에 쓰지 않음)
8. `docker compose ps; docker info; grep anyRequest… SecurityConfig.java` → Docker 28.4.0 동작 중, `anyRequest().authenticated()`가 :61에 있음
9. `npx vitest run src/pages/FollowingPage.test.tsx src/components/Sidebar.test.tsx` → 2 files, 10 passed
10. `JAVA_HOME=… ./gradlew test --tests "com.livestream.follow.*" --tests "com.livestream.channel.ChannelMapper*"` → BUILD SUCCESSFUL. 결과 파일은 follow 테스트 것만 생김
11. `grep` 결과 XML (`FollowControllerTest$Following`, `FollowIntegrationTest$Following`, `FollowServiceTest$Following`) → 각각 3, 9, 6개이고 failures 0, errors 0. 타임스탬프가 오늘 06:35-06:36Z라 이번 실행 결과임

### 구현자 이견

- 판정에는 이견이 없다. "확인하지 않은 것"에 대한 사실 보충만 남긴다.
  - `ChannelMapper.xml` 식 추출 뒤 채널 테스트: Green에서 `--tests "FollowIntegrationTest" --tests "Channel*"`로 돌려 통과했고, 백엔드 전체(490개)도 돌렸다(1개 실패는 `NginxChannelCacheTest`의 시간 흔들림, 단독 재실행 통과 — Green 기록 참고). `ChannelMapper*` 필터에 결과가 없던 것은 그 이름의 테스트 클래스가 없어서다(매퍼는 통합 테스트로 확인한다).
- 추가 시나리오 제안(offset=20의 1개가 가장 먼저 팔로우한 채널인지)은 이번에 넣지 않는다. 검증자도 필수가 아니라고 했고, AC 문장은 개수와 `hasMore`만 정한다. 동률 정렬(`channel_id DESC`)과 서비스 단위의 21개 자르기가 겹침을 막는다. 리팩터·보안 단계 뒤에도 남겨 두는 후속 후보로 적는다.

---

## 보안 점검

> `/harness:security-review 38` (2026-11-27). 대상: `git diff main...HEAD`.

**수집 결과**

- 타입: `./gradlew compileJava compileTestJava` 통과, `npx tsc -b` 오류 0.
- 의존성: `npm audit` 0건. 이 이슈는 의존성을 바꾸지 않았다(`securityExceptions` 대장은 설정에 없다).
- 비밀값: 바뀐 소스에서 비밀값 패턴·알려진 접두사 0건. 이 브랜치가 커밋한 `.env` 없음.
- 로그: 더한 줄에 `console.*`·로거 호출 없음.

**🔴 즉시 수정 필요 — 없음**

**🟡 권장 수정**

| 파일·줄 | 무엇 | 왜 이 분류 | 어떻게 |
| --- | --- | --- | --- |
| `FollowController.following`(`@RequestParam int offset`) | `?offset=abc`처럼 숫자가 아닌 값은 `MethodArgumentTypeMismatchException`이 되는데 `GlobalExceptionHandler`가 잡지 않아 프로젝트의 `{code, message}` 모양이 아닌 Spring 기본 에러 응답으로 나간다(실행해 확인하지는 않았다). 기존 `/api/channels/{channelId}`의 `long` 경로 변수도 같다 | 보안 위험은 아니다(스택은 Boot 기본값상 응답에 넣지 않는다). 응답 모양의 일관성 문제이고 기존 경로와 같은 동작이라 이 이슈 범위를 넘는다 | 별도 작업으로 `GlobalExceptionHandler`에 `MethodArgumentTypeMismatchException` → `INVALID_INPUT`(400)을 더한다 |

**⚪ 무시 가능(판단 근거)**

- **IDOR(남의 목록 보기)** — 요청에 회원을 고르는 값이 없다. `FollowController`가 세션의 `LoginMember.id()`만 서비스에 넘긴다(이슈 결정). 통합 테스트 `…not list a channel that only another member follows`가 지킨다.
- **비로그인 접근** — `SecurityConfig`의 `anyRequest().authenticated()`로 401. 변이 확인(permitAll 추가 → 401 테스트 실패)을 Green 기록에 남겼다.
- **SQL 주입** — `findFollowing`의 값은 전부 `#{}` 바인딩(`memberId`·`offset`·`limit`). `${}` 없음. `limit`은 서버 상수(21)다.
- **nginx 캐시로 남의 목록이 보이는 문제** — 캐시는 `location ~ ^/api/channels/[1-9][0-9]*$`만 한다. `/api/me/following`은 `/api/` location으로 가 캐시되지 않는다.
- **큰 offset** — 정렬 대상은 그 회원이 팔로우한 행뿐이라 비용이 회원 한 명의 팔로우 수에 묶인다. `int`를 넘는 값은 형 변환에서 400. nginx `/api/` 속도 제한도 그대로 걸린다.
- **응답에 담긴 값** — 채널 id·이름·방송 중 여부·제목. 모두 공개 채널 조회에 이미 나오는 값이다. 팔로우한 시각은 정렬에만 쓰고 응답에 넣지 않는다.
- **프론트** — 응답 문자열은 JSX 텍스트로만 그린다(`dangerouslySetInnerHTML` 없음). 401이면 `/login`으로 `replace` 이동, 다른 출처로 보내는 경로 없음.

**처리** — 🔴가 없어 고친 것은 없다. 재스캔은 위 수집 결과가 곧 최종 상태다(코드 변경 없음).

## Red 예외 판정

자율 루프는 `red_has_passing_test`(구현 없이 통과한 테스트 1개)로 이 이슈를 SKIP했다. 사람이 다시 확인해 받아들였다.

- 대상: `FollowIntegration.following — should return 401 when a visitor who is not logged in asks for the following list`
- 구현 전에 통과한 이유: `SecurityConfig`의 `anyRequest().authenticated()`가 새 경로 `/api/me/following`도 이미 막는다. 이 AC(비로그인 401)는 구현 전부터 지켜지고 있었고, 이 테스트는 그것이 깨지지 않게 고정한다. `follow` 쪽 기존 401 테스트들(`rejectsAnonymousFollow` 등)과 같은 성격이다.
- 가짜가 아님을 다시 확인(구현자 기록과 별도로 실행):
  1. `SecurityConfig`에 `.requestMatchers("/api/me/following").permitAll()`을 넣고 `./gradlew test --tests "FollowIntegrationTest"` → 26개 중 이 테스트 1개만 실패
  2. 되돌리고 같은 명령 → 통과
- 판정: 테스트는 그대로 둔다. 고칠 코드 없음.
