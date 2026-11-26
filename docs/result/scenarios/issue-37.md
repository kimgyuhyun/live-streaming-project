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

### 경계

- [x] [경계] `FollowService.follow` — should not change the follower count when the member already follows the channel
- [x] [경계] `FollowService.unfollow` — should not change the follower count when the member does not follow the channel
- [x] [경계] `FollowIntegration` — should return followerCount 0 when nobody follows the channel
- [x] [경계] `FollowIntegration` — should return 204 and keep followerCount when the member follows the same channel again
- [x] [경계] `FollowIntegration` — should keep one follow row when the member follows the same channel again
- [x] [경계] `FollowIntegration` — should return 204 and keep followerCount at 0 when a member who does not follow unfollows the channel
- [x] [경계] `FollowIntegration` — should make followerCount equal the 10 follow rows when 20 members follow at the same time and 10 of them unfollow at the same time
- [x] [경계] `FollowIntegration` — should make followerCount equal the follow rows when one member sends follows and unfollows to the same channel at the same time many times

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

**커버리지:** AC 12개 / 시나리오 41개 / 미커버 0개
