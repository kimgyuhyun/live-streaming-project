# 이슈 #37 — 채널을 팔로우하고 채널 페이지에서 팔로워 수를 본다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/37
> PRD: `docs/result/prd/viewer.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

---

## 시그니처

### 관련 결정

- **viewer PRD 작은 결정(팔로우 저장·API·내가 팔로우했는지·팔로워 수·비로그인 팔로우·패키지)** — 그리고 이슈 본문 "이 이슈에서 정한 것". 아래 값은 전부 거기서 그대로 옮겼다.
  - 저장: `follow(member_id, channel_id, created_at)`, 기본 키(member_id, channel_id), `channel_id` 색인, 회원·채널 외래 키. `channel.follower_count`(기본 0). 마이그레이션 V9 하나.
  - 팔로워 수: 팔로우가 **실제로 새로 들어갔을 때만** `follower_count = follower_count + 1`, 취소가 **실제로 지웠을 때만** `- 1`, 같은 트랜잭션. 읽고 더해 쓰지 않는다.
  - API: `PUT /api/channels/{id}/follow`(팔로우)·`DELETE` 같은 주소(취소) — 멱등, 204. `GET /api/channels/{id}/follow` → `{following}`. 자기 채널 400(`CANNOT_FOLLOW_OWN_CHANNEL`), 없는 채널 404(`CHANNEL_NOT_FOUND`), 비로그인 401.
  - 채널 조회 응답에 `followerCount`. 내가 팔로우했는지는 넣지 않는다(nginx `$uri` 1초 캐시).
  - 패키지 `com.livestream.follow`.
- **확정 사양 7절 팔로우** — 자기 채널은 팔로우할 수 없다(내 채널 페이지에 버튼 없음). 다시 팔로우해도 한 번, 안 한 채널 취소도 오류 아님. 비로그인이 누르면 로그인 화면.
- **#29 nginx 캐시** — `location ~ ^/api/channels/[1-9][0-9]*$`만 캐시한다. `/follow`는 캐시되지 않는다.
- **동시성** — 숫자 열 방어(영향 행 수에 따른 원자적 ±1)를 넣기 전에 `defect-repro` 절차로 깨지는 것을 먼저 재현해 수치를 남긴다(Green 단계).

### DB

`backend/src/main/resources/db/migration/V9__create_follow.sql`

```sql
-- 팔로우(#37, PRD viewer 작은 결정). 같은 채널을 두 번 팔로우해도 한 줄(기본 키)
CREATE TABLE follow (
    member_id  NUMBER NOT NULL,
    channel_id NUMBER NOT NULL,
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_follow PRIMARY KEY (member_id, channel_id),
    CONSTRAINT fk_follow_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_follow_channel FOREIGN KEY (channel_id) REFERENCES channel (id)
);
CREATE INDEX idx_follow_channel ON follow (channel_id);

-- 팔로워 수. 팔로우가 실제로 들어가거나 지워질 때만 같은 트랜잭션에서 ±1
ALTER TABLE channel ADD (follower_count NUMBER DEFAULT 0 NOT NULL);
```

### 타입

```java
// ── com.livestream.follow ──────────────────────────────

public record FollowStatusResponse(boolean following) {}

// SQL은 resources/mapper/follow/FollowMapper.xml
@Mapper
public interface FollowMapper {
    // 없으면 넣고 1, 이미 있으면 0
    int insertIfAbsent(@Param("memberId") long memberId, @Param("channelId") long channelId);
    // 지운 행 수(1 또는 0)
    int delete(@Param("memberId") long memberId, @Param("channelId") long channelId);
    boolean exists(@Param("memberId") long memberId, @Param("channelId") long channelId);
}

@Service
public class FollowService {
    // 없는 채널 CHANNEL_NOT_FOUND, 자기 채널 CANNOT_FOLLOW_OWN_CHANNEL(아무것도 저장하지 않는다).
    // 새로 들어갔을 때만 팔로워 수 +1
    @Transactional public void follow(long memberId, long channelId);
    // 같은 검사. 실제로 지웠을 때만 팔로워 수 -1
    @Transactional public void unfollow(long memberId, long channelId);
    // 같은 검사. 화면은 자기 채널의 400으로 버튼을 숨긴다
    public boolean isFollowing(long memberId, long channelId);
}

@RestController
class FollowController {
    @PutMapping("/api/channels/{channelId}/follow")    ResponseEntity<Void> follow(channelId, LoginMember);   // 204
    @DeleteMapping("/api/channels/{channelId}/follow") ResponseEntity<Void> unfollow(channelId, LoginMember); // 204
    @GetMapping("/api/channels/{channelId}/follow")    FollowStatusResponse status(channelId, LoginMember);   // 200
}
// SecurityConfig: GET /api/channels/* 의 permitAll은 한 단계 경로만 연다. /follow 셋은 anyRequest().authenticated() → 비로그인 401

// ── com.livestream.channel 변경 ────────────────────────
public record ChannelResponse(long id, String name, String description, boolean live, String title, boolean demo,
                              long followerCount) {}
public class Channel { private long followerCount; /* getter/setter */ }
public interface ChannelMapper {
    // follower_count = follower_count + 1 / - 1 (읽고 더해 쓰지 않는다)
    void increaseFollowerCount(long id);
    void decreaseFollowerCount(long id);
}
// ChannelMapper.xml의 columns에 c.follower_count

// ── com.livestream.common.error.ErrorCode 추가 ─────────
CANNOT_FOLLOW_OWN_CHANNEL(HttpStatus.BAD_REQUEST, "내 채널은 팔로우할 수 없습니다"),
```

### 프론트

```typescript
// api/channels.ts — ChannelResponse에 추가
followerCount: number

// api/follows.ts
export interface FollowStatusResponse { following: boolean }
export function fetchFollowStatus(channelId: number): Promise<FollowStatusResponse> // 비로그인 401, 내 채널 400(ApiError)
export function follow(channelId: number): Promise<void>
export function unfollow(channelId: number): Promise<void>

// pages/FollowButton.tsx — 채널 페이지의 팔로우 버튼
interface FollowButtonProps { channelId: number }
//   팔로우 여부 조회 200 → following이면 "팔로잉", 아니면 "팔로우". 누르면 팔로우/취소하고,
//     성공하면 ['channel', channelId] 캐시의 followerCount를 ±1(채널 조회가 1초 캐시라 다시 묻지 않는다)
//   401 → "팔로우". 누르면 /login
//   400 CANNOT_FOLLOW_OWN_CHANNEL → 버튼을 그리지 않는다(내 채널)
// pages/ChannelPage.tsx — "팔로워 {followerCount}명", 모양만 있던 팔로우 버튼 자리에 <FollowButton>
```

### 에러 케이스

| 상황 | 에러 코드 | HTTP |
| --- | --- | --- |
| 자기 채널을 팔로우·취소·팔로우 여부 조회 | `CANNOT_FOLLOW_OWN_CHANNEL` (추가) | 400 |
| 없는 채널 | `CHANNEL_NOT_FOUND` (기존) | 404 |
| 비로그인 | `UNAUTHENTICATED` (기존, 보안 설정) | 401 |

### 판단이 갈린 곳

- **화면이 "내 채널"을 아는 방법** — 이슈는 "내 채널에서는 버튼이 없다"만 정했다. 채널 조회 응답에 주인 id를 넣거나 팔로우 여부 응답에 필드를 더하면 이슈가 정한 응답 모양이 바뀐다. 대신 자기 채널 규칙(400 `CANNOT_FOLLOW_OWN_CHANNEL`)을 팔로우 여부 조회에도 똑같이 걸고, 화면은 그 400이면 버튼을 그리지 않는다. 자기 채널 판단이 서버 한 곳에 있고, 정해진 응답 모양은 그대로다. 같은 이유로 취소도 자기 채널이면 400이다(세 API의 채널 검사가 같다).
- **비로그인 판단** — 팔로우 여부 조회가 401이면 비로그인으로 본다. 요청이 하나로 끝난다.
- **동시 팔로우 충돌** — 같은 회원의 팔로우가 동시에 두 번 오면 늦은 쪽의 `MERGE`가 기본 키에 걸린다(`DuplicateKeyException`). 이미 팔로우한 것과 같으므로 "새로 들어가지 않음(0)"으로 친다(#35 `StudioTermsService`와 같은 처리).
- **팔로워 수 표시** — `팔로워 {n}명` 그대로(천 단위 쉼표는 정하지 않았다).

### 이 이슈에서 만들지 않는 것

- 팔로잉 화면·`GET /api/me/following`(#38)
- 내 채널(`/me`)의 "팔로워 0명" — 이슈 범위는 채널 페이지다
- 팔로워 목록, 취소 확인 창, 방송 시작 알림(PRD 하지 않을 것)
- 매퍼 단독 테스트(`@MybatisTest`) — SQL은 통합 테스트가 진짜 Oracle로 확인한다
- E2E(Playwright 설정이 없다)

---

## 테스트 시나리오

### 정상

- [x] [정상] `FollowService.follow` — should save the follow and add one to the follower count when the member has not followed the channel
- [x] [정상] `FollowService.unfollow` — should delete the follow and subtract one from the follower count when the member follows the channel
- [x] [정상] `FollowService.isFollowing` — should return true when the member follows the channel
- [x] [정상] `FollowService.isFollowing` — should return false when the member does not follow the channel
- [x] [정상] `ChannelService.find` — should return the follower count of the channel
- [x] [정상] `FollowController` — should return 204 when a member follows a channel
- [x] [정상] `FollowController` — should return 204 when a member unfollows a channel
- [x] [정상] `FollowController` — should return 200 with following true when the member follows the channel
- [x] [정상] `FollowIntegration` — should return 204 and then following true when a member follows another channel
- [x] [정상] `FollowIntegration` — should add one to followerCount of the channel lookup when a member follows the channel
- [x] [정상] `FollowIntegration` — should return 204 and then following false when the member unfollows the channel
- [x] [정상] `FollowIntegration` — should subtract one from followerCount of the channel lookup when the member unfollows the channel
- [x] [정상] `ChannelPage` — should show 팔로워 3명 when the channel has 3 followers
- [x] [정상] `ChannelPage` — should show 팔로잉 when the member already follows the channel
- [x] [정상] `ChannelPage` — should change the button to 팔로잉 when a member clicks 팔로우
- [x] [정상] `ChannelPage` — should show 팔로워 4명 when a member clicks 팔로우 on a channel with 3 followers
- [x] [정상] `ChannelPage` — should change the button back to 팔로우 when the member clicks 팔로잉
- [x] [정상] `ChannelPage` — should show 팔로워 3명 when the member clicks 팔로잉 on a channel with 4 followers
- [x] [정상] `ChannelPage` — should send follow for channel 7 when a member clicks 팔로우
- [x] [정상] `ChannelPage` — should send unfollow for channel 7 when the member clicks 팔로잉
- [x] [정상] `ChannelPage` — should show 팔로우 and 팔로워 3명 again when a member clicks 팔로우 and then 팔로잉 on a channel with 3 followers

### 경계

- [x] [경계] `FollowService.follow` — should not change the follower count when the member already follows the channel
- [x] [경계] `FollowService.follow` — should not change the follower count when saving the follow hits a duplicate key
- [x] [경계] `FollowService.unfollow` — should not change the follower count when the member does not follow the channel
- [x] [경계] `FollowIntegration` — should return followerCount 0 when nobody follows the channel
- [x] [경계] `FollowIntegration` — should return 204 and keep followerCount when the member follows the same channel again
- [x] [경계] `FollowIntegration` — should keep one follow row when the member follows the same channel again
- [x] [경계] `FollowIntegration` — should return 204 and keep followerCount at 0 when a member who does not follow unfollows the channel
- [x] [경계] `FollowIntegration` — should make followerCount equal the 10 follow rows when 20 members follow at the same time and 10 of them unfollow at the same time
- [x] [경계] `FollowIntegration` — should make followerCount equal the follow rows when one member sends follows and unfollows to the same channel at the same time many times
- [x] [경계] `FollowIntegration` — should complete every follow and unfollow without an error when one member sends them to the same channel at the same time

### 예외

- [x] [예외] `FollowService.follow` — should throw CANNOT_FOLLOW_OWN_CHANNEL without saving when the member owns the channel
- [x] [예외] `FollowService.follow` — should throw CHANNEL_NOT_FOUND without saving when the channel does not exist
- [x] [예외] `FollowService.unfollow` — should throw CANNOT_FOLLOW_OWN_CHANNEL without deleting when the member owns the channel
- [x] [예외] `FollowService.isFollowing` — should throw CANNOT_FOLLOW_OWN_CHANNEL when the member asks about their own channel
- [x] [예외] `FollowController` — should return 400 with code CANNOT_FOLLOW_OWN_CHANNEL when service rejects the own channel
- [x] [예외] `FollowController` — should return 404 with code CHANNEL_NOT_FOUND when service reports the channel does not exist
- [x] [예외] `FollowIntegration` — should return 400 with code CANNOT_FOLLOW_OWN_CHANNEL when the streamer follows their own channel
- [x] [예외] `FollowIntegration` — should save no follow row and keep followerCount when the streamer follows their own channel
- [x] [예외] `FollowIntegration` — should return 404 with code CHANNEL_NOT_FOUND when a member follows a channel that does not exist
- [x] [예외] `FollowIntegration` — should return 401 when a visitor who is not logged in follows a channel
- [x] [예외] `FollowIntegration` — should return 401 when a visitor who is not logged in unfollows a channel
- [x] [예외] `FollowIntegration` — should return 401 when a visitor who is not logged in asks whether they follow a channel
- [x] [예외] `ChannelPage` — should not show the follow button when the streamer opens their own channel
- [x] [예외] `ChannelPage` — should go to the login page when a visitor who is not logged in clicks 팔로우
- [x] [예외] `ChannelPage` — should keep 팔로우 and the follower count when the follow request fails

### Red 기록

- 만든 stub: `follow/FollowService`·`FollowController`(본문 `UnsupportedOperationException`), `FollowMapper`·`FollowStatusResponse`, `ChannelMapper.increaseFollowerCount`·`decreaseFollowerCount`(선언만), `ChannelResponse.followerCount`(`ChannelService`는 아직 0을 넣는다), `ErrorCode.CANNOT_FOLLOW_OWN_CHANNEL`, 프론트 `api/follows.ts`(`throw`), `ChannelResponse.followerCount` 타입.
- 마이그레이션 `V9__create_follow.sql`도 Red에 넣었다. 처음엔 빼고 돌렸더니 동시성 테스트 두 개가 `follow` 테이블이 없어(`BadSqlGrammarException`) 단언에 닿지 못했다(나쁜 실패). 스키마는 타입 선언처럼 테스트가 돌기 위한 전제라 넣고 다시 돌렸다.
- 기존 테스트 손질(컴파일): `new ChannelResponse(...)`에 `followerCount` 0을 더했다(`ChannelControllerTest`·`ChannelOperatorControllerTest`·`ChannelServiceTest`). `StreamerApplicationReviewIntegrationTest`의 익명 `ChannelMapper`에 새 메서드 두 개를 위임으로 더했다.
- 실행: 단위·컨트롤러는 파일마다, 통합(`FollowIntegrationTest`)은 컨테이너 기동 때문에 다 쓰고 파일 단위로 한 번.
- 결과: 시나리오 41개 = 백엔드 32개(서비스 10 · 컨트롤러 5 · 통합 16 · `ChannelService.find` 1) + 프론트 9개. 백엔드 32개 중 27개 실패(단언 또는 stub의 not implemented), 프론트 9개 전부 실패(단언).
- **구현 없이 통과한 5개** — Green에서 변이로 확인한다.
  - `FollowIntegration` 401 세 개: `SecurityConfig`의 `anyRequest().authenticated()`가 이미 막는다(`GET /api/channels/*`의 `permitAll`은 한 단계 경로만).
  - `FollowIntegration — should return followerCount 0 when nobody follows the channel`: `ChannelService`가 stub으로 0을 넣는다.
  - `FollowIntegration — …one member sends follows and unfollows…`: stub이라 아무것도 바뀌지 않아 0 = 0. 방어 전 구현(defect-repro)으로 깨지는 것을 Green에서 기록한다.

### Green 기록

**동시성 재현 (defect-repro, 2026-11-26)** — 매퍼·컨트롤러를 먼저 만들고 `FollowService`에 방어 전 구현 두 가지를 차례로 넣어 `FollowIntegrationTest`를 돌렸다. Testcontainers Oracle(`gvenzl/oracle-free:23.26.4-slim-faststart`), 스레드마다 서비스를 불러 각자 커밋(테스트 트랜잭션 없음), 커넥션 풀은 기본(10). 숫자 열 방어 = "영향 행 수가 1일 때만 + DB가 `follower_count ± 1`".

| 구현 | 20명 동시 팔로우 → 10명 동시 취소: `followerCount` / `follow` 행 | 한 회원 동시 팔로우·취소(20건 × 5회, 팔로우 2 : 취소 1): `followerCount` / 행 | 함께 깨진 테스트 |
| --- | --- | --- | --- |
| 방어 전 1 — 영향 행 수는 보지만 값을 읽고 더해 쓴다(`findById` → `setFollowerCount(n ± 1)`, 임시 매퍼 메서드) | **−2 / 10** (1차), **2 / 10** (2차) — 갱신 유실 | 통과(0 / 0, 2회) | — |
| 방어 전 2 — DB가 `± 1`을 하지만 영향 행 수를 보지 않고 늘 `± 1` | 통과(10 / 10) | **30 / 0** | 다시 팔로우 `followerCount` 2(기대 1), 안 한 채널 취소 −1(기대 0) |
| 방어 후 — 영향 행 수가 1일 때만 `follower_count = follower_count ± 1`, 같은 트랜잭션 | **10 / 10** | **같음**(통과) | 없음 |

- 모든 회차에서 스레드 실패는 0(`success=20`·`success=10`). 방어 전 1은 성공 응답을 주면서 숫자만 틀렸다.
- **테스트 보강(커밋 `test: make the one-member follow race catch blind count updates`)** — Red에서 쓴 한 회원 테스트는 팔로우·취소를 반씩 보냈다. 방어 전 2를 넣었더니 +1과 −1이 같은 수라 합이 0이 되어 **통과했다**(행도 0). 이 테스트가 지켜야 할 결함을 잡지 못하므로 비율을 팔로우 2 : 취소 1로 바꿨다(시나리오 문장은 그대로). 바꾼 뒤 방어 전 2에서 30 / 0으로 실패했다.
- 방어 전 1이 한 회원 테스트를 통과한 것은, 같은 회원의 팔로우끼리는 기본 키 잠금으로, 취소끼리는 행 잠금으로 줄을 서서 영향 행 수가 1인 경우가 겹치지 않기 때문이다. 이 테스트는 "영향 행 수" 방어를, 20명 테스트는 "원자적 ±1" 방어를 지킨다.
- 임시 매퍼 메서드(`setFollowerCount`)와 방어 전 코드는 지웠다.

**구현 없이 통과했던 테스트의 변이 확인**

| 변이 | 실패한 테스트 |
| --- | --- |
| `SecurityConfig`에 `/api/channels/*/follow` `permitAll()` | `FollowIntegration` 401 세 개 |
| `FollowButton`이 팔로워 수를 `onSuccess` 대신 `onMutate`에서 고침 | `ChannelPage — should keep 팔로우 and the follower count when the follow request fails` |

- `FollowIntegration — should return followerCount 0 when nobody follows the channel`은 변이로 깨뜨릴 곳이 없다(열 기본값 0·매핑 누락 모두 0). 새 채널이 0에서 시작하는 경계를 문서로 남기는 테스트다.

**구현하며 정한 것 (시그니처 밖의 세부)**

- 동시에 같은 회원의 팔로우가 두 번 오면 늦은 `MERGE`가 기본 키(`pk_follow`)에 걸린다. `FollowService`가 `DuplicateKeyException`을 "새로 들어가지 않음"으로 친다(#35 `StudioTermsService`와 같은 처리). 같은 트랜잭션 안에서 잡으므로 Oracle은 그 문장만 되돌린다.
- 잠금 순서는 팔로우·취소 모두 `follow` 행 → `channel` 행이라 서로 엇갈려 잠그지 않는다.
- 화면: `pages/FollowButton.tsx`가 `ChannelPage.module.css`의 버튼 모양을 그대로 쓴다("팔로우"는 강조 색, "팔로잉"은 기본 버튼). 팔로우 여부 조회는 `retry: false`(401·400은 다시 물어도 같다). 내 채널(400)과 그 밖의 조회 오류는 버튼을 그리지 않는다. 누르는 동안 버튼을 잠근다.
- 기존 테스트 손질(컴파일): `StudioPage.test.tsx`의 채널 응답에 `followerCount: 0`.
- 커버리지 명령(`coverage`)이 설정에 없어 측정하지 않았다.
- 실행: 전체 스위트가 3분 넘게 걸려(Oracle 컨테이너) 시나리오마다 전체를 돌리지 않았다. 백엔드는 계층별(매퍼 SQL·컨트롤러 → 서비스 방어 전 2종 → 방어 후)로 `Follow*`·`ChannelServiceTest`를 돌리고, 끝에 전체 스위트를 한 번 돌렸다. 프론트는 `ChannelPage.test.tsx` → 전체.

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 팔로우 → 204, `following: true`, `followerCount` +1 | `[정상] FollowService.follow — …add one…`<br>`[정상] FollowController — …204 when a member follows…`·`…200 with following true…`<br>`[정상] FollowIntegration — …204 and then following true…`·`…add one to followerCount…`<br>`[경계] FollowIntegration — …followerCount 0 when nobody follows…`<br>`[정상] ChannelService.find — …follower count…` |
| 2. 다시 팔로우 → 204, 수 그대로, 행 하나 | `[경계] FollowService.follow — …already follows…`<br>`[경계] FollowIntegration — …keep followerCount when the member follows the same channel again`·`…keep one follow row…` |
| 3. 취소 → 204, `following: false`, 수 -1 | `[정상] FollowService.unfollow — …subtract one…`<br>`[정상] FollowController — …204 when a member unfollows…`<br>`[정상] FollowIntegration — …following false…`·`…subtract one from followerCount…`<br>`[정상] FollowService.isFollowing — …false…` |
| 4. 안 한 채널 취소 → 204, 수 그대로(0 아래로 안 감) | `[경계] FollowService.unfollow — …does not follow…`<br>`[경계] FollowIntegration — …keep followerCount at 0…` |
| 5. 자기 채널 팔로우 → 400, 저장 없음 | `[예외] FollowService.follow — …CANNOT_FOLLOW_OWN_CHANNEL without saving…`<br>`[예외] FollowController — …400…`<br>`[예외] FollowIntegration — …400 with code CANNOT_FOLLOW_OWN_CHANNEL…`·`…save no follow row and keep followerCount…`<br>`[예외] FollowService.unfollow`·`isFollowing` — 자기 채널(판단이 갈린 곳) |
| 6. 없는 채널 팔로우 → 404 | `[예외] FollowService.follow — …CHANNEL_NOT_FOUND…`<br>`[예외] FollowController — …404…`<br>`[예외] FollowIntegration — …404…` |
| 7. 비로그인 팔로우·취소·여부 → 401 | `[예외] FollowIntegration — …401…` 3개 |
| 8. 20명 동시 팔로우, 10명 동시 취소 → 수 = 행 수(10) | `[경계] FollowIntegration — …20 members follow at the same time and 10 of them unfollow…` (defect-repro로 방어 전 수치 기록) |
| 9. 한 회원 동시 팔로우·취소 반복 → 수 = 행 수 | `[경계] FollowIntegration — …one member sends follows and unfollows…at the same time many times` (defect-repro로 방어 전 수치 기록) |
| 10. "팔로우" 누르면 "팔로잉", 수 +1, 다시 누르면 되돌아감 | `[정상] ChannelPage — …팔로워 3명…`·`…팔로잉 when the member already follows…`·`…change the button to 팔로잉…`·`…팔로워 4명…`·`…change the button back to 팔로우…`·`…팔로워 3명 when the member clicks 팔로잉…`<br>`[예외] ChannelPage — …follow request fails` |
| 11. 내 채널 페이지 → 버튼 없음 | `[예외] ChannelPage — …not show the follow button…`<br>`[예외] FollowService.isFollowing — …own channel` |
| 12. 비로그인이 "팔로우" → 로그인 화면 | `[예외] ChannelPage — …go to the login page…`<br>`[예외] FollowIntegration — …401 when…asks whether they follow…` |

**AC에 없는데 추가한 시나리오**

- `FollowService.unfollow`·`FollowService.isFollowing`의 자기 채널 400 — 화면이 내 채널을 아는 방법(시그니처 "판단이 갈린 곳")
- `ChannelPage — should keep 팔로우 and the follower count when the follow request fails` — 실패했는데 수가 늘어 보이면 화면이 틀린 값을 보인다

**커버리지:** AC 12개 / 시나리오 46개(AC 검증 뒤 5개 추가 — AC 9에 2개, AC 10에 3개) / 미커버 0개

---

## AC 검증

### 이슈 #37 AC 검증 결과 (브랜치 feat/viewer/issue-37, HEAD 3a68bc9) — `harness:ac-verifier` 원문

## 이슈 #37 AC 검증 — 채널을 팔로우하고 채널 페이지에서 팔로워 수를 본다

검증 대상: `C:/live-streaming-issue-37`, 브랜치 `feat/viewer/issue-37` (main 대비 커밋 4개: `cf376a4` docs → `2ee2aab` test → `15ef760` test → `3a68bc9` feat). 시작할 때 worktree는 깨끗했다(`git status --short` 출력 없음). 코드는 고치지 않았다.

12개 AC 모두 구현이 있고 테스트도 통과한다. 두 AC에서 테스트가 놓치는 경우가 있다.
- **AC 9:** 요청이 전부 실패해도 통과한다.
- **AC 10:** 화면이 팔로우를 실제로 취소하는지 확인하지 않는다.

이번 실행 결과(직접 돌림):
- **백엔드** `--tests "Follow*" --tests "ChannelServiceTest"` → BUILD SUCCESSFUL.
  - `FollowIntegration` 16/16, `FollowController` 5/5, `FollowService.follow` 4/4, `.unfollow` 3/3, `.isFollowing` 3/3, `ChannelService.find` 9/9. 실패와 오류는 0이다.
  - 결과 XML 시각은 2026-11-26T11:43Z다(Testcontainers Oracle).
- **프론트** `npx vitest run src/pages/ChannelPage.test.tsx` → 20/20 통과. 이슈 #37 관련 9개 포함.

### AC별 판정 표

| AC | 판정 | 테스트 (파일:줄) | 구현 (파일:줄) | 근거 |
| --- | --- | --- | --- | --- |
| 1. 팔로우 → 204, `following: true`, `followerCount` +1 | ✅ | `should return 204 and then following true when a member follows another channel` (FollowIntegrationTest.java:199)<br>`should add one to followerCount of the channel lookup…` (:212)<br>`should return followerCount 0 when nobody follows the channel` (:251)<br>`FollowService.follow — should save the follow and add one…` (FollowServiceTest.java:59)<br>`ChannelService.find — should return the follower count of the channel` (ChannelServiceTest.java) | `FollowService.follow` (FollowService.java:25-31)<br>`ChannelMapper.xml` `increaseFollowerCount`(`follower_count + 1`), `columns`에 `c.follower_count`<br>`ChannelService.toResponse`의 `getFollowerCount()` | 실제 HTTP PUT 204 다음 GET `/follow`의 `$.following == true`를 단언한다. `GET /api/channels/{id}`의 `followerCount`가 0에서 1이 된다(0 시작은 :251이 별도로 단언). |
| 2. 다시 팔로우 → 204, 수 그대로, 행 하나 | ✅ | `should return 204 and keep followerCount when the member follows the same channel again` (FollowIntegrationTest.java:259)<br>`should keep one follow row…` (:271)<br>`FollowService.follow — should not change the follower count when the member already follows` (FollowServiceTest.java:72) | `FollowMapper.xml` `MERGE … WHEN NOT MATCHED`<br>`FollowService.insertIfAbsent`의 `== 1` 검사 (FollowService.java:56-62) | 두 번째 PUT이 204이고, `followerCount == 1`과 `SELECT COUNT(*) FROM follow == 1`을 진짜 Oracle에서 단언한다. |
| 3. 취소 → 204, `following: false`, 수 -1 | ✅ | `should return 204 and then following false when the member unfollows the channel` (FollowIntegrationTest.java:223)<br>`should subtract one from followerCount…` (:237)<br>`FollowService.unfollow — should delete the follow and subtract one…` (FollowServiceTest.java:112) | `FollowService.unfollow` (FollowService.java:34-40)<br>`decreaseFollowerCount`(`follower_count - 1`) | 두 회원이 팔로우해 2가 된 뒤 한 명이 취소하면 1이 되는 것을 단언한다. 이 경우 0으로 바닥에 붙는 구현은 통과하지 못한다. 취소 후 `$.following == false`도 단언한다. |
| 4. 안 한 채널 취소 → 204, 수 그대로(0 아래로 안 감) | ✅ | `should return 204 and keep followerCount at 0 when a member who does not follow unfollows the channel` (FollowIntegrationTest.java:283)<br>`FollowService.unfollow — should not change the follower count when the member does not follow` (FollowServiceTest.java:125) | `followMapper.delete(...) == 1`일 때만 감소 (FollowService.java:37) | 통합 테스트는 0에서 0이 되는 것만 본다. "그대로"는 단위 테스트의 `never().decreaseFollowerCount`가 일반적으로 보장한다. Green 기록에서 "항상 -1" 변이가 이 통합 테스트를 깨뜨렸다(-1). 선택 제안은 아래에 있다. |
| 5. 자기 채널 팔로우 → 400, 저장 없음 | ✅ | `should return 400 with code CANNOT_FOLLOW_OWN_CHANNEL when the streamer follows their own channel` (FollowIntegrationTest.java:332)<br>`should save no follow row and keep followerCount…` (:344)<br>`FollowService.follow — should throw CANNOT_FOLLOW_OWN_CHANNEL without saving` (FollowServiceTest.java:83) | `checkFollowable` (FollowService.java:47-53)<br>`ErrorCode.CANNOT_FOLLOW_OWN_CHANNEL(BAD_REQUEST)` | 실제 가입, 신청, 승인으로 만든 스트리머가 자기 채널에 PUT하면 400과 `$.code`가 나온다. 이때 follow 행 0, `followerCount` 0을 단언한다. 단위 테스트는 구체 타입 `BusinessException`과 `code`까지 확인한다. |
| 6. 없는 채널 팔로우 → 404 | ✅ | `should return 404 with code CHANNEL_NOT_FOUND when a member follows a channel that does not exist` (FollowIntegrationTest.java:356)<br>`FollowService.follow — should throw CHANNEL_NOT_FOUND without saving` (FollowServiceTest.java:95) | `channelMapper.findById(...).orElseThrow(CHANNEL_NOT_FOUND)` (FollowService.java:48-49) | 로그인 회원이 채널 999999999에 PUT하면 404이고 `$.code == "CHANNEL_NOT_FOUND"`다. |
| 7. 비로그인 팔로우·취소·여부 → 401 | ✅ | `should return 401 when a visitor who is not logged in follows / unfollows / asks whether they follow a channel` (FollowIntegrationTest.java:366, :375, :384) | `SecurityConfig.java:58`의 `GET /api/channels/*` permitAll은 한 단계 경로만 연다. `:61` `anyRequest().authenticated()`, 진입점은 `UNAUTHENTICATED` | 실제 보안 필터를 거친다. PUT·DELETE는 허용 Origin 헤더를 붙여 보내므로 401이 출처 검사가 아니라 인증에서 나온다. Green 기록에 따르면 `permitAll` 변이로 세 개 모두 실패했다. 이 변이는 이번에 다시 돌리지 않았다. |
| 8. 20명 동시 팔로우, 10명 동시 취소 → 수 = 행 수(10) | ✅ | `should make followerCount equal the 10 follow rows when 20 members follow at the same time and 10 of them unfollow at the same time` (FollowIntegrationTest.java:294) | 영향 행 수가 1일 때만 DB가 `follower_count ± 1` (FollowService.java:28-30, :37-39, ChannelMapper.xml) | 래치로 동시에 출발시키고 스레드마다 각자 커밋한다. `[followerCount, 행 수] == [10, 10]`을 단언한다. 이번 실행 출력은 `20 follows {success=20}, 10 unfollows {success=10}`이다. 모두 실패하면 0/0이 되어 단언이 깨지므로 빈 통과도 불가능하다. Green의 defect-repro 기록에서는 "읽고 더해 쓰기" 구현이 -2/10과 2/10으로 실패했다. |
| 9. 한 회원 동시 팔로우·취소 반복 → 수 = 행 수 | ⚠️ 부분 충족 | `should make followerCount equal the follow rows when one member sends follows and unfollows to the same channel at the same time many times` (FollowIntegrationTest.java:310) | 같은 구현 + `DuplicateKeyException`을 "새로 안 들어감"으로 처리 (FollowService.java:56-62) | 구현은 맞다. 테스트는 팔로우 2 : 취소 1, 20건 × 5회로 "항상 ±1" 변이를 잡는다(Green 기록 30/0). 이번 실행은 모든 회차 `success=20`이었다. 다만 단언이 `followerCount == 행 수` 하나뿐이고 성공 수는 출력만 하고 단언하지 않는다(:325, :328). 그래서 요청이 전부 예외로 실패해도 0 == 0으로 통과한다. 동시 MERGE가 기본 키에 걸릴 때의 `DuplicateKeyException` 처리를 확인하는 테스트도 없다. `grep "success\|DuplicateKey"` 결과 :163, :172, :190 집계 코드뿐이다. |
| 10. "팔로우" 누르면 "팔로잉", 수 +1, 다시 누르면 되돌아감 | ⚠️ 부분 충족 | `should change the button to 팔로잉 when a member clicks 팔로우` (ChannelPage.test.tsx:201)<br>`should show 팔로워 4명 when a member clicks 팔로우 on a channel with 3 followers` (:210)<br>`should change the button back to 팔로우 when the member clicks 팔로잉` (:219)<br>`should show 팔로워 3명 when the member clicks 팔로잉 on a channel with 4 followers` (:229)<br>`should keep 팔로우 and the follower count when the follow request fails` (:262) | `FollowButton.tsx:24-33`(onSuccess에서 `['follow']`와 `['channel']` 캐시를 ±1)<br>`ChannelPage.tsx` `팔로워 {followerCount}명` | 화면 문구와 숫자 변화는 단언으로 확인했다. 구현도 `next ? follow : unfollow`로 맞다. 그러나 `unfollowMock`은 선언(:32)과 초기화(:58)만 있고 어디서도 단언하지 않는다. `follow`·`unfollow`가 채널 7로 불렸는지도 확인하지 않는다(`grep toHaveBeenCalledWith` 0건). 그래서 "팔로잉"을 눌러도 `follow`를 다시 보내는 구현이 되돌림 테스트 두 개를 통과한다. 그 경우 화면은 되돌아가도 서버는 팔로우 상태로 남는다. 이건 코드를 읽고 추론한 것이고 변이를 실행해 보지는 않았다. "되돌림"은 처음부터 `following: true`인 상태에서만 보고, 한 화면에서 팔로우 → 팔로잉 → 팔로우로 왕복하는 테스트는 없다. |
| 11. 내 채널 페이지 → 버튼 없음 | ✅ | `should not show the follow button when the streamer opens their own channel` (ChannelPage.test.tsx:239)<br>`FollowService.isFollowing — should throw CANNOT_FOLLOW_OWN_CHANNEL…` (FollowServiceTest.java:171) | `FollowButton.tsx:38`(401이 아닌 오류면 `null`)<br>`isFollowing`의 `checkFollowable` (FollowService.java:43-45) | 서버는 자기 채널의 팔로우 여부 조회에 400을 준다. 화면은 400을 받으면 버튼을 그리지 않는다. 테스트는 조회가 불린 뒤 타이머를 0만큼 넘기고 `팔로우` 버튼이 없음을 단언한다. 이 방식이 대기 상태(`isPending`, 역시 `null`)와 구별되는지는 변이로 직접 확인하지 않았다. 같은 대기 방식을 쓰는 :262가 Green의 onMutate 변이로 깨졌다는 기록을 간접 근거로 삼는다. `팔로잉` 버튼이 없다는 단언은 없다. 구현이 `null`을 반환하므로 판정에는 영향이 없다. |
| 12. 비로그인이 "팔로우" → 로그인 화면 | ✅ | `should go to the login page when a visitor who is not logged in clicks 팔로우` (ChannelPage.test.tsx:252)<br>`FollowIntegration — 401 when … asks whether they follow` (FollowIntegrationTest.java:384) | `FollowButton.tsx:36`(`ApiError` 401이면 loggedOut), `:42` `navigate('/login')` | 팔로우 여부 조회가 401이면 "팔로우" 버튼을 보여 주고, 누르면 `/login` 라우트("로그인 화면")가 렌더되는 것을 단언한다. 서버가 실제로 401을 주는 것은 통합 테스트 :384가 확인한다. |

### 요약

AC 12개 중 ✅ 10 / ⚠️ 2 / ❌ 0

### 갭이 있는 AC에 대한 추가 시나리오 제안

AC 9:
- [ ] [경계] `FollowIntegration` — should complete every follow and unfollow without an error when one member sends them to the same channel at the same time
  - `runAtOnce` 결과의 `success`가 매 회차 20이고 실패 항목이 없음을 단언한다. 그러면 0 == 0으로 비어서 통과하는 일이 없어지고 `DuplicateKeyException` 처리도 지켜진다.
- [ ] [경계] `FollowService.follow` — should not change the follower count when saving the follow hits a duplicate key
  - `insertIfAbsent`가 `DuplicateKeyException`을 던지도록 목을 만들고, 예외가 밖으로 나오지 않으며 `increaseFollowerCount`가 불리지 않음을 단언한다.

AC 10:
- [ ] [정상] `ChannelPage` — should send unfollow for channel 7 when the member clicks 팔로잉
  - `unfollowMock`이 `7`로 한 번 불렸고 `followMock`은 불리지 않았음을 단언한다.
- [ ] [정상] `ChannelPage` — should send follow for channel 7 when a member clicks 팔로우
- [ ] [정상] `ChannelPage` — should show 팔로우 and 팔로워 3명 again when a member clicks 팔로우 and then 팔로잉 on a channel with 3 followers
  - 한 화면에서 왕복한다.

(선택, AC 4 보강 — 판정에는 영향 없음)
- [ ] [경계] `FollowIntegration` — should keep followerCount at 1 when a member who does not follow unfollows a channel with one follower

### 가짜 테스트 목록

다음 네 패턴에 해당하는 테스트는 없다.
- 인자 없는 `toThrow`
- `await` 없는 비동기 호출
- 단언 없는 테스트
- 목이 반환한 값을 그대로 단언

백엔드 예외 단언은 모두 `isInstanceOf(BusinessException.class)`에 `.extracting("code")`까지 확인한다.

`FollowControllerTest.java:250`은 목이 준 `true`를 `$.following`으로 단언한다. 하지만 컨트롤러 직렬화를 보는 `@WebMvcTest`이고, 실제 경로는 `FollowIntegrationTest.java:199`·`:223`이 진짜 DB로 확인하므로 가짜로 보지 않는다.

단언이 약한 곳(위 ⚠️의 원인):
1. `FollowIntegrationTest.java:317-328` — 성공 수를 출력만 하고 단언하지 않아서 전부 실패해도 통과한다. 조이는 법: `assertThat(result).containsExactly(entry("success", 20))` 같은 단언을 회차마다 추가한다.
2. `ChannelPage.test.tsx:219`, `:229` — `follow`와 `unfollow` 목이 둘 다 성공을 돌려줘서 어느 API를 불렀는지 구별하지 못한다. 조이는 법: `expect(unfollowMock).toHaveBeenCalledWith(7)`과 `expect(followMock).not.toHaveBeenCalled()`를 추가한다.

### 실행한 명령 목록

1. `cd /c/live-streaming-issue-37 && cat .claude/harness.json && git status --short && git log --oneline main..feat/viewer/issue-37 && gh issue view 37` → 설정 확인. worktree 깨끗함. 커밋 4개. AC 12개 확인
2. `git diff --stat main...feat/viewer/issue-37 && cat docs/result/scenarios/issue-37.md` → 변경 파일 26개, 시나리오 41개, AC 대조표와 Green(defect-repro) 기록 확인
3. `cat -n` follow 패키지 java 4개 + `V9__create_follow.sql` + `FollowMapper.xml`, `git diff` channel·common·ChannelMapper.xml → 서비스, 매퍼 SQL, 원자적 ±1, 오류 코드 확인
4. `sed -n 1,50p ChannelMapper.xml; grep map-underscore application.yml; grep requestMatchers SecurityConfig` → `c.follower_count`가 조회되고 camelCase 매핑이 켜져 있음, 보안 규칙 :50-61 확인 (grep 하나가 대상 디렉터리 없음으로 exit 2였지만 필요한 출력은 모두 나옴)
5. `cat -n FollowServiceTest.java FollowControllerTest.java` → 단위 10개, 컨트롤러 5개 본문 확인
6. `cat -n FollowIntegrationTest.java` → 통합 16개 본문 확인. 성공 수가 단언되지 않음(:325, :328)
7. `cat -n FollowButton.tsx api/follows.ts`, `git diff` ChannelPage.tsx·channels.ts, `cat -n ChannelPage.test.tsx` → 화면 구현과 테스트 9개 확인
8. `git diff` ChannelServiceTest·StreamerApplicationReviewIntegrationTest, `sed -n 35,65p SecurityConfig.java` → `ChannelService.find` 팔로워 수 테스트, 401 진입점, OriginValidationFilter 위치 확인
9. `JAVA_HOME="$HOME/.jdks/liberica-21.0.7" ./gradlew test --tests "Follow*" --tests "ChannelServiceTest"` → BUILD SUCCESSFUL in 51s
10. `npx vitest run ChannelPage.test.tsx` → 파일 2개, 테스트 22개 통과 (이름 패턴에 2개 파일이 걸림)
11. `grep` `build/test-results/test/TEST-*.xml` (testsuite 요약, `defect-repro` 출력) → FollowIntegration 16, FollowController 5, FollowService 4+3+3, ChannelService.find 9, 실패 0. 동시성 출력은 `success=20`/`success=10`, 한 회원 5회 모두 `success=20`
12. `npx vitest run src/pages/ChannelPage.test.tsx --reporter=verbose` → ChannelPage 20/20 통과, #37 테스트 9개 이름 확인
13. `grep -n "unfollowMock\|followMock).toHaveBeenCalledWith\|toHaveBeenCalledWith(7" ChannelPage.test.tsx; grep -rn "success\|DuplicateKey" follow 테스트; grep -rln follow frontend 테스트` → `unfollowMock`은 :32, :58뿐으로 단언 0건. 성공 수와 `DuplicateKey` 관련 단언 0건(집계 코드 :163, :172, :190뿐)

관련 파일:
- `C:/live-streaming-issue-37/backend/src/main/java/com/livestream/follow/FollowService.java`
- `C:/live-streaming-issue-37/backend/src/test/java/com/livestream/follow/FollowIntegrationTest.java`
- `C:/live-streaming-issue-37/frontend/src/pages/FollowButton.tsx`
- `C:/live-streaming-issue-37/frontend/src/pages/ChannelPage.test.tsx`
- `C:/live-streaming-issue-37/docs/result/scenarios/issue-37.md`

### 구현자 이견

- **AC 9·10의 테스트 갭은 메운다.** 검증자 제안 5개(AC 9에 2개, AC 10에 3개)를 문장 그대로 시나리오에 넣었다. 기존 한 회원 테스트에 성공 수 단언을 덧붙이지 않고 새 테스트로 따로 본다(한 테스트 한 가지).
- **AC 4 선택 제안(팔로워 1명인 채널에서 안 한 회원이 취소)은 넣지 않는다.** 단위 테스트의 `never().decreaseFollowerCount`와 Green 기록의 "늘 −1" 변이(통합 테스트가 −1로 실패)로 이미 지켜진다.
- **AC 11의 "대기 상태와 구별되는지"** — 구현은 대기 중에도, 400에서도 버튼을 그리지 않는다. 둘 다 "버튼 없음"이 맞는 화면이라 구별할 테스트를 더하지 않는다.

### 갭을 메운 결과

추가한 시나리오 5개는 구현이 이미 맞아 처음부터 통과했다. 검증자가 말한 잘못된 구현을 넣어 새 테스트가 잡는지 보고 되돌렸다.

| 변이 | 실패한 테스트 |
| --- | --- |
| `FollowService`가 `DuplicateKeyException`을 잡지 않음(다른 예외로 바꿈) | `FollowService.follow — should not change the follower count when saving the follow hits a duplicate key`, `FollowIntegration — should complete every follow and unfollow without an error when one member sends them to the same channel at the same time` (이때 한 회원 동시 요청 5회차마다 `DuplicateKeyException` 3~9건. 기존 `…followerCount equal the follow rows…`는 검증자 지적대로 통과했다) |
| `FollowButton`이 "팔로잉"을 눌러도 `follow`를 보냄 | `ChannelPage — should send unfollow for channel 7 when the member clicks 팔로잉` |

### 재검증 (갭 보강 뒤, HEAD fa91a8c) — `harness:ac-verifier` 원문

## 이슈 #37 AC 9·10 재검증 (커밋 `fa91a8c` 이후)

AC 9와 AC 10이 이제 충족되어 12개 AC가 모두 ✅다. 커밋 `fa91a8c`는 테스트 4개 파일과 시나리오 문서만 바꿨다. 기능 코드는 `3a68bc9`와 같다. `git diff --stat 3a68bc9 HEAD -- backend/src/main frontend/src`(테스트 파일 제외)의 출력이 비어 있다.

| AC | 판정 | 테스트 (파일:줄) | 구현 (파일:줄) | 근거 |
| --- | --- | --- | --- | --- |
| 9. 한 회원이 같은 채널에 팔로우·취소를 동시에 여러 번 보낸다 → 끝난 뒤 수 = 행 수 | ✅ 충족 | `should make followerCount equal the follow rows when one member sends follows and unfollows to the same channel at the same time many times` (FollowIntegrationTest.java:310)<br>`should complete every follow and unfollow without an error when one member sends them to the same channel at the same time` (FollowIntegrationTest.java:332)<br>`FollowService.follow — should not change the follower count when saving the follow hits a duplicate key` (FollowServiceTest.java:85) | 영향 행 수가 1일 때만 DB가 `follower_count ± 1`을 한다(FollowService.java:25-40, ChannelMapper.xml)<br>`DuplicateKeyException`은 "새로 안 들어감"으로 처리한다(FollowService.java:56-62) | :310은 `followerCount == 행 수`를 단언한다. :332는 같은 비율(팔로우 2 : 취소 1)로 20건을 동시에 보내고 `result`가 정확히 `{success=20}`인지 단언한다(`containsExactly(Map.entry("success", 20))`). 이전 지적은 요청이 전부 실패해도 0 == 0으로 통과한다는 것이었는데, 이 단언으로 막혔다. 단위 테스트(:85)는 insert가 `DuplicateKeyException`을 던질 때 예외가 밖으로 나오지 않고 `increaseFollowerCount`도 불리지 않는지 확인한다. 동시 실행에서 중복이 실제로 날지는 회차마다 다르다. 단위 테스트가 그 경로를 매번 확실하게 지킨다. 문서 "갭을 메운 결과"에는 catch를 없앤 변이에서 :85와 :332가 실패했다고 적혀 있다(회차마다 `DuplicateKeyException` 3~9건). 이 변이는 이번에 다시 돌리지 않았다. 이번 실행에서는 FollowIntegration 17/17, FollowService.follow 5/5가 통과했다. |
| 10. "팔로우"를 누르면 "팔로잉"이 되고 팔로워 수가 1 늘며, 다시 누르면 되돌아간다 | ✅ 충족 | `should change the button to 팔로잉 when a member clicks 팔로우` (ChannelPage.test.tsx:201)<br>`should show 팔로워 4명 when a member clicks 팔로우 on a channel with 3 followers` (:210)<br>`should change the button back to 팔로우 when the member clicks 팔로잉` (:219)<br>`should show 팔로워 3명 when the member clicks 팔로잉 on a channel with 4 followers` (:229)<br>`should send follow for channel 7 when a member clicks 팔로우` (:239)<br>`should send unfollow for channel 7 when the member clicks 팔로잉` (:249)<br>`should show 팔로우 and 팔로워 3명 again when a member clicks 팔로우 and then 팔로잉 on a channel with 3 followers` (:260) | FollowButton.tsx:24-33: `next ? follow : unfollow`를 보내고, 성공하면 `['follow']`와 `['channel']` 캐시를 ±1 한다<br>ChannelPage.tsx `팔로워 {followerCount}명` | :239와 :249는 목 호출을 통째로 비교한다. 팔로우를 누르면 `follow`만 `[[7]]`로 불리고 `unfollow`는 `[]`여야 한다. 팔로잉을 누르면 그 반대다. 이전 지적은 팔로잉을 눌러도 `follow`를 보내는 구현이 통과한다는 것이었는데, 이 단언으로 막혔다. :260은 한 화면 안에서 팔로우를 누르면 팔로잉이 되고, 다시 누르면 팔로우로 돌아오며 팔로워 3명이 그대로 보이는지 확인한다. 1 증가는 :210이 맡는다. 문서 "갭을 메운 결과"에는 팔로잉을 눌러도 `follow`를 보내는 변이에서 :249가 실패했다고 적혀 있다. 이 변이도 이번에 다시 돌리지 않았다. 이번 실행에서는 ChannelPage 23/23이 통과했다. |

### 전체 결과 (AC 12개)

AC 12개 중 ✅ 12 / ⚠️ 0 / ❌ 0

AC 1~8과 11·12는 첫 검증 판정을 그대로 유지한다. 그 뒤로 기능 코드와 해당 테스트가 바뀌지 않았다.

### 실행한 명령

1. `git status --short && git log --oneline -3 && git show --stat fa91a8c && git show fa91a8c -- backend frontend`
   - 작업 트리에 커밋 안 된 변경이 없다. HEAD는 `fa91a8c`이고 테스트 4개 파일만 바뀌었다. 새 테스트 5개의 본문을 확인했다.
2. `git diff fa91a8c~1 fa91a8c -- docs/result/scenarios/issue-37.md`
   - 시나리오 5개 추가, "구현자 이견", "갭을 메운 결과"의 변이 표를 확인했다.
3. `JAVA_HOME="$HOME/.jdks/liberica-21.0.7" ./gradlew test --tests "Follow*"`로 테스트를 돌리고, 결과 XML에서 testsuite 줄을 grep했다.
   - BUILD SUCCESSFUL in 1m 5s.
   - FollowIntegration 17, FollowController 5, FollowService.follow 5, .isFollowing 3, .unfollow 3. 실패와 오류는 0이다(결과 시각 2026-11-26T11:51Z).
   - 새 테스트 2개도 결과 XML에 들어 있다.
4. `npx vitest run src/pages/ChannelPage.test.tsx --reporter=verbose`
   - 23/23 통과. 새 테스트 3개 모두 ✓.
5. `git diff --stat 3a68bc9 HEAD -- backend/src/main frontend/src ':!*.test.tsx' ':!*.test.ts'`
   - 출력이 없다. 기능 코드는 바뀌지 않았다.
6. `grep -n` FollowIntegrationTest·FollowServiceTest·ChannelPage.test.tsx
   - 표에 적은 줄 번호(:310, :332, :85, :201~:260)를 확인했다.

관련 파일:
- `C:/live-streaming-issue-37/backend/src/test/java/com/livestream/follow/FollowIntegrationTest.java`
- `C:/live-streaming-issue-37/backend/src/test/java/com/livestream/follow/FollowServiceTest.java`
- `C:/live-streaming-issue-37/frontend/src/pages/ChannelPage.test.tsx`
- `C:/live-streaming-issue-37/docs/result/scenarios/issue-37.md`

---

## 리팩터

대상: 이번 이슈가 바꾼 소스 16개(`git diff main...HEAD`, 테스트 제외). 기준선은 아래 보안 점검의 전체 스위트(전부 통과).

**고친 것 없음.** 다섯 기준(중복·이름·단일 책임·불필요한 복잡도·컨벤션)으로 봤고, 고칠 근거가 있는 항목이 없었다.

| 본 것 | 판단 |
| --- | --- |
| `api/follows.ts`의 `/channels/${channelId}/follow` 세 번 | 기존 `api/channels.ts`도 경로를 함수마다 그대로 쓴다(컨벤션). 묶으면 이 파일만 모양이 달라진다 |
| `FollowButton`의 `['follow', channelId]` 쿼리 키 두 번 | 두 번. 세 번째(#38 팔로잉 화면 등)가 나올 때 뽑는다 |
| `FollowService`의 채널 검사 | 세 메서드가 `checkFollowable` 하나를 쓴다. 이미 한 곳 |
| `FollowService.insertIfAbsent`(`DuplicateKeyException` → false) | #35 `StudioTermsService`와 같은 모양. 공통화하면 두 도메인이 묶인다(두 번째일 뿐) |
| CSS | 새 CSS 없음. `ChannelPage.module.css`의 `.button`·`.primary`·`.buttonIcon`을 그대로 쓴다 |
| 계층·에러 처리 | 기존 채널 코드와 같은 모양(Controller → Service → MyBatis Mapper, `BusinessException(ErrorCode)`, 레코드 DTO). 패키지 `com.livestream.follow`는 PRD 작은 결정대로 |

---

## 보안 점검

| 점검 | 명령·방법 | 결과 |
| --- | --- | --- |
| 타입 | `gradlew compileJava compileTestJava` + `npx tsc -b` | 오류 0 (백엔드 BUILD SUCCESSFUL, tsc 통과) |
| 의존성 | `npm audit` (`securityExceptions` 대장 없음) | 0 vulnerabilities. 이번 이슈는 의존성을 바꾸지 않았다 |
| 비밀값 | 바뀐 파일에서 `SECRET/TOKEN/PASSWORD/API_KEY…` 대입, 알려진 키 접두사 grep | 0건 |
| `.env` | 작업 트리·브랜치 커밋에서 `.env` | 0건 |
| 로그 | 바뀐 운영 코드의 `console.*`·`log.*`·`System.out` | 0건 (`FollowIntegrationTest`의 `System.out.printf`는 테스트 코드, defect-repro 수치 기록용) |

| 분류 | 항목 | 판단 |
| --- | --- | --- |
| 🔴 즉시 수정 | 없음 | |
| 🟡 권장 | **인기 채널의 `channel` 행 잠금** — `FollowService.follow`/`unfollow`가 `channel.follower_count`를 같은 트랜잭션에서 고쳐, 한 채널에 팔로우가 몰리면 그 행 잠금에서 줄을 선다(20명 동시 테스트는 1.5초 안에 끝났다). PRD가 정한 구조이고 정확성은 지켜진다. 대비책과 측정 계획: 한 채널에 동시 팔로우 100·1,000건을 k6로 보내 p95와 커넥션 풀 대기를 잰다(`load-test` 스킬). 넘치면 카운터를 Redis `INCR` + 주기 반영으로 옮기는 안을 그때 비교한다 | 다음 측정 이슈(#42와 함께)로 넘긴다 |
| 🟡 권장 | **팔로우 토글 반복** — 한 회원이 팔로우·취소를 빠르게 반복하면 요청마다 쓰기 2건(`follow` + `channel`). nginx `/api/` IP당 10r/s(버스트 40, #31)가 이미 막는다. 회원 단위 제한은 없다 | 지금은 IP 제한으로 충분. 남용이 보이면 회원 단위 제한을 다른 이슈로 |
| ⚪ 무시 | **자기 채널 팔로우 여부가 400** — 요청한 사람에게 "이 채널이 내 것"이라는 것만 알려 준다. 남의 정보는 드러나지 않는다 | 의도된 설계(시그니처 "판단이 갈린 곳") |
| ⚪ 무시 | **없는 채널 404** — 채널 존재 여부는 공개 채널 조회(`GET /api/channels/{id}`)로 이미 알 수 있다 | 새로 드러나는 정보 없음 |

추가로 본 것:

- **IDOR** — 세 API 모두 회원 id를 세션(`@AuthenticationPrincipal`)에서만 읽는다. 경로의 값은 채널 id뿐이라 남의 팔로우를 바꾸거나 볼 수 없다.
- **위조 요청(CSRF)** — `PUT`·`DELETE`는 `OriginValidationFilter`(GET·HEAD·OPTIONS·TRACE만 건너뜀)가 출처를 검사한다. 통합 테스트는 허용 출처를 붙여 보내 401이 출처 검사가 아니라 인증에서 나오는 것을 본다.
- **캐시 섞임** — `GET /api/channels/{id}/follow`는 회원마다 다르다. nginx 캐시 location은 `^/api/channels/[1-9][0-9]*$`만 맞아 `/follow`는 캐시되지 않는다(`infra/nginx/nginx.conf:77`). 채널 조회 응답에는 회원별 값을 넣지 않았다.
- **입력 검증** — 본문이 없다. 경로의 `channelId`는 `long`으로 묶여 숫자가 아니면 Spring이 400으로 끝낸다.
- **에러 응답** — 새 오류는 `BusinessException(ErrorCode)`로만 나가 `{code, message}`뿐이다. `DuplicateKeyException`은 서비스가 삼켜 밖으로 나가지 않는다.

재스캔: 고친 것이 없어 위 결과가 최종이다. 전체 스위트 백엔드 472·프론트 68 통과(실패 0).
