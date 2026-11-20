# 이슈 #7 — 운영자가 신청을 승인하면 채널이 생긴다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/7
> PRD: `docs/result/prd/channel.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

> 게이트 처리: 사용자가 자리를 비운 동안 진행했다. 사전 승인(밤새 #7~#9 진행 지시)에 따라 시그니처·시나리오를 구현자가 정하고 승인된 것으로 진행했다.

---

## 시그니처

### 관련 ADR

- **채널 PRD 작은 결정(신청 동시 처리)** — 승인·거절은 "대기일 때만 바꾼다"는 조건부 UPDATE(`WHERE status = 'PENDING'`) 한 문장으로 한다. 바뀐 행이 0이면 이미 처리된 신청이다 → 409. 동시에 승인 2개가 와도 한쪽만 1행을 바꾼다.
- **채널 PRD 작은 결정(승인 처리)** — 신청 승인 → 역할 `STREAMER` → 채널 생성을 **한 트랜잭션**으로. 스트림 키는 만들지 않는다(#9).
- **채널 PRD 작은 결정(승인 후 로그인 상태)** — 승인 트랜잭션이 **끝나면** 그 회원의 세션을 모두 지운다(계정 M2의 색인 저장소, `findByPrincipalName(String.valueOf(memberId))`). 커밋 전에 지우면, 지운 뒤 커밋 전 사이에 다시 로그인한 세션에 `MEMBER`가 남는다.
- **채널 PRD 하지 않을 것(운영자 화면)** — 화면 없는 API만 만든다.
- **확정 사양 3절** — 스트리머 한 명당 채널 하나, 채널 이름은 처음에 닉네임, 소개 0~200자.

### DB

`backend/src/main/resources/db/migration/V3__create_channel.sql`

```sql
CREATE TABLE channel (
    id          NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id   NUMBER             NOT NULL,
    name        VARCHAR2(20 CHAR)  NOT NULL,
    description VARCHAR2(200 CHAR),           -- Oracle은 빈 문자열을 NULL로 저장한다. 빈 소개 = NULL
    created_at  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT fk_channel_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT uk_channel_member UNIQUE (member_id)   -- 스트리머 한 명당 채널 하나
);
```

스트림 키 컬럼은 #9, 방송 제목은 #13이 새 번호로 더한다.

### 타입

```java
// com.livestream.channel
public class Channel {
    Long id; long memberId; String name; String description;
    public Channel(long memberId, String name, String description);
}

// 대기 목록 한 줄. 신청 시각은 streamer_application.created_at
public record PendingApplicationResponse(long id, String nickname, String email, LocalDateTime appliedAt) {}

// SQL은 resources/mapper/channel/ChannelMapper.xml
@Mapper
public interface ChannelMapper {
    void insert(Channel channel);   // 생성된 id를 채운다. 같은 회원의 두 번째 채널은 uk_channel_member 위반
}

@Mapper
public interface StreamerApplicationMapper {
    // 추가
    Optional<StreamerApplication> findById(long id);
    // 대기일 때만 status로 바꾼다. 바뀐 행 수(0 또는 1)를 돌려준다
    int updateStatusIfPending(@Param("id") long id, @Param("status") ApplicationStatus status);
    // 대기 신청과 신청 회원의 닉네임·이메일. 신청 시각이 오래된 순(같으면 id 순)
    List<PendingApplicationResponse> findPending();
}

// 운영자의 대기 목록·승인·거절
@Service
public class StreamerApplicationReviewService {
    public StreamerApplicationReviewService(StreamerApplicationMapper applicationMapper, MemberMapper memberMapper,
                                            ChannelMapper channelMapper, TransactionTemplate transactionTemplate,
                                            FindByIndexNameSessionRepository<? extends Session> sessionRepository);

    public List<PendingApplicationResponse> findPending();

    // 트랜잭션 안: 신청이 없으면 CHANNEL_APPLICATION_NOT_FOUND → 대기→승인 조건부 UPDATE가 0행이면
    //   CHANNEL_APPLICATION_ALREADY_DECIDED → 역할 STREAMER → 채널(이름=닉네임, 소개 빈 값) 생성.
    // 커밋 뒤: 신청 회원의 세션을 모두 지운다. 트랜잭션이 실패하면 지우지 않는다
    public StreamerApplicationResponse approve(long applicationId);   // {"status":"APPROVED"}

    // 신청이 없으면 CHANNEL_APPLICATION_NOT_FOUND, 대기→거절 조건부 UPDATE가 0행이면 CHANNEL_APPLICATION_ALREADY_DECIDED.
    // 역할·세션은 건드리지 않는다
    public StreamerApplicationResponse reject(long applicationId);    // {"status":"REJECTED"}
}

@RestController
@RequestMapping("/api/operator/streamer-applications")
class StreamerApplicationReviewController {
    @GetMapping                       // 200 + [{"id","nickname","email","appliedAt"}]
    List<PendingApplicationResponse> pending();
    @PostMapping("/{id}/approve")     // 200 + {"status":"APPROVED"}
    StreamerApplicationResponse approve(@PathVariable long id);
    @PostMapping("/{id}/reject")      // 200 + {"status":"REJECTED"}
    StreamerApplicationResponse reject(@PathVariable long id);
}
```

```java
// com.livestream.member
@Mapper
public interface MemberMapper {
    void updateRole(@Param("id") long id, @Param("role") Role role);   // 추가
}
```

```java
// com.livestream.common.error
public enum ErrorCode {
    // 추가
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다"),
    CHANNEL_APPLICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "스트리머 신청을 찾을 수 없습니다"),
    CHANNEL_APPLICATION_ALREADY_DECIDED(HttpStatus.CONFLICT, "이미 처리된 스트리머 신청입니다");
}
```

`SecurityConfig` — `/api/operator/**`는 `hasRole("OPERATOR")`(로그인할 때 `ROLE_{역할}` 권한을 싣는다, #2). 권한이 모자라면 `accessDeniedHandler`가 `FORBIDDEN` 본문(`ErrorResponseWriter`)을 쓴다. 401 진입점과 같은 모양이다.

### 에러 케이스

| 상황 | 에러 코드 | HTTP |
| --- | --- | --- |
| 신청 id가 없음 | `CHANNEL_APPLICATION_NOT_FOUND` | 404 |
| 이미 승인·거절된 신청 (동시 승인에서 진 쪽 포함) | `CHANNEL_APPLICATION_ALREADY_DECIDED` | 409 |
| 운영자가 아닌 회원 | `FORBIDDEN` | 403 |
| 로그인하지 않음 | `UNAUTHENTICATED` (기존) | 401 |
| 승인 중 채널 생성 실패 | (그 예외가 그대로 나간다, 500) — 신청·역할은 되돌아간다 | 500 |

### 판단이 갈린 곳

1. **트랜잭션은 `TransactionTemplate`으로 묶는다.** `@Transactional`이면 세션 삭제를 커밋 뒤로 미루려고 트랜잭션 동기화(`TransactionSynchronizationManager`)를 써야 하는데, 이건 Spring 없이 만든 서비스 단위 테스트에서 돌지 않는다. 템플릿은 단위 테스트에 가짜 트랜잭션 매니저를 넣어 그대로 쓸 수 있다.
2. **역할 변경은 `MemberMapper.updateRole`로 한다.** channel 서비스가 member 매퍼를 직접 쓴다. `MemberService`에 메서드를 두면 그 생성자 의존성 4개를 리뷰 서비스 테스트가 다 알아야 하고, 트랜잭션은 어차피 리뷰 서비스가 연다. 닉네임도 `MemberMapper.findById`로 읽는다.
3. **승인·거절 응답은 #6의 `StreamerApplicationResponse`를 다시 쓴다.** 운영자 스크립트(#14)는 상태만 확인한다.
4. **거절에는 트랜잭션을 열지 않는다.** 바꾸는 문장이 조건부 UPDATE 하나뿐이다.
5. **처리 시각 컬럼은 만들지 않는다.** #6 문서에 "#7에서 추가"라고 적었지만 이 이슈의 AC·화면 어디에서도 읽지 않는다.
6. **빈 소개는 NULL로 저장한다.** Oracle에서 `''`는 NULL이다. 읽는 쪽(#8)이 NULL을 빈 문자열로 바꾼다.
7. **대기 목록에 페이지 나누기는 없다.** 운영자 한 명이 아는 사람만 승인하는 규모다(PRD 승인제 이유).
8. 매퍼 SQL은 #6과 같이 `@SpringBootTest` 통합 테스트로 확인한다.

### 이 이슈에서 만들지 않는 것

- 채널 조회·수정 — #8
- 스트림 키 — #9
- 운영자 화면 — 기획서 B(PRD 하지 않을 것)

---

## 테스트 시나리오

### 정상

- [x] [정상] `StreamerApplicationReviewService.approve` — should mark the application approved, change the role to STREAMER and create a channel named after the nickname with an empty description when the application is pending
- [x] [정상] `StreamerApplicationReviewService.approve` — should delete every session of the applicant after the approval is committed
- [x] [정상] `StreamerApplicationReviewService.reject` — should mark the application rejected without changing the role or creating a channel when the application is pending
- [x] [정상] `GET /api/operator/streamer-applications` — should return 200 with id, nickname, email and applied time of each pending application
- [x] [정상] `POST /api/operator/streamer-applications/{id}/approve` — should return 200 with status APPROVED when service approves
- [x] [정상] `POST /api/operator/streamer-applications/{id}/reject` — should return 200 with status REJECTED when service rejects
- [x] [정상] `StreamerApplicationReviewIntegration` — should list pending applications oldest first with nickname, email and applied time when operator requests the pending list
- [x] [정상] `StreamerApplicationReviewIntegration` — should mark the application approved, make the member STREAMER and create one channel named after the nickname with an empty description when operator approves
- [x] [정상] `StreamerApplicationReviewIntegration` — should return 401 for the applicant's existing cookie and STREAMER after logging in again when the application is approved
- [x] [정상] `StreamerApplicationReviewIntegration` — should mark the application rejected and keep the member role MEMBER when operator rejects

### 경계

- [x] [경계] `StreamerApplicationReviewIntegration` — should leave approved and rejected applications out of the pending list
- [x] [경계] `StreamerApplicationReviewIntegration` — should create one channel and reject the other approval when two approvals of the same application arrive at once
- [x] [경계] `StreamerApplicationReviewIntegration` — should reject a second channel for the same member

### 예외

- [x] [예외] `StreamerApplicationReviewService.approve` — should throw CHANNEL_APPLICATION_NOT_FOUND when the application does not exist
- [x] [예외] `StreamerApplicationReviewService.approve` — should throw CHANNEL_APPLICATION_ALREADY_DECIDED without changing the role, creating a channel or deleting sessions when the application is no longer pending
- [x] [예외] `StreamerApplicationReviewService.approve` — should not delete sessions when channel creation fails
- [x] [예외] `StreamerApplicationReviewService.reject` — should throw CHANNEL_APPLICATION_NOT_FOUND when the application does not exist
- [x] [예외] `StreamerApplicationReviewService.reject` — should throw CHANNEL_APPLICATION_ALREADY_DECIDED when the application is no longer pending
- [x] [예외] `POST /api/operator/streamer-applications/{id}/approve` — should return 409 with code CHANNEL_APPLICATION_ALREADY_DECIDED and its message when service reports the application is already decided
- [x] [예외] `POST /api/operator/streamer-applications/{id}/approve` — should return 404 with code CHANNEL_APPLICATION_NOT_FOUND and its message when service reports the application does not exist
- [x] [예외] `StreamerApplicationReviewIntegration` — should return 409 with code CHANNEL_APPLICATION_ALREADY_DECIDED when operator approves an application that is already approved
- [x] [예외] `StreamerApplicationReviewIntegration` — should return 409 with code CHANNEL_APPLICATION_ALREADY_DECIDED when operator rejects an application that is already rejected
- [x] [예외] `StreamerApplicationReviewIntegration` — should throw CHANNEL_APPLICATION_ALREADY_DECIDED and keep the application rejected with no channel when a rejected application is approved
- [x] [예외] `StreamerApplicationReviewIntegration` — should throw CHANNEL_APPLICATION_ALREADY_DECIDED and keep the application approved when an approved application is rejected
- [x] [예외] `StreamerApplicationReviewIntegration` — should keep the application pending, the member role MEMBER and no channel when channel creation fails during approval
- [x] [예외] `StreamerApplicationReviewIntegration` — should return 403 with code FORBIDDEN when a member who is not an operator requests the pending list
- [x] [예외] `StreamerApplicationReviewIntegration` — should return 403 with code FORBIDDEN and keep the application pending when a member who is not an operator approves
- [x] [예외] `StreamerApplicationReviewIntegration` — should return 403 with code FORBIDDEN and keep the application pending when a member who is not an operator rejects

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 대기 신청 3개 → 신청 시각이 오래된 순으로 닉네임·이메일·신청 시각 | `[정상] StreamerApplicationReviewIntegration — should list pending applications oldest first with nickname, email and applied time when operator requests the pending list`<br>`[정상] GET /api/operator/streamer-applications — should return 200 with id, nickname, email and applied time of each pending application`<br>`[경계] StreamerApplicationReviewIntegration — should leave approved and rejected applications out of the pending list` |
| 2. 승인 → 신청 `승인`, 역할 `STREAMER`, 이름=닉네임·소개 빈 채널 하나 | `[정상] StreamerApplicationReviewService.approve — should mark the application approved, change the role to STREAMER and create a channel named after the nickname with an empty description when the application is pending`<br>`[정상] POST /api/operator/streamer-applications/{id}/approve — should return 200 with status APPROVED when service approves`<br>`[정상] StreamerApplicationReviewIntegration — should mark the application approved, make the member STREAMER and create one channel named after the nickname with an empty description when operator approves` |
| 3. 로그인해 있던 신청 회원 → 승인 뒤 기존 쿠키 401, 다시 로그인하면 `STREAMER` | `[정상] StreamerApplicationReviewService.approve — should delete every session of the applicant after the approval is committed`<br>`[정상] StreamerApplicationReviewIntegration — should return 401 for the applicant's existing cookie and STREAMER after logging in again when the application is approved` |
| 4. 거절 → 신청 `거절`, 역할 `MEMBER` 그대로 | `[정상] StreamerApplicationReviewService.reject — should mark the application rejected without changing the role or creating a channel when the application is pending`<br>`[정상] POST /api/operator/streamer-applications/{id}/reject — should return 200 with status REJECTED when service rejects`<br>`[정상] StreamerApplicationReviewIntegration — should mark the application rejected and keep the member role MEMBER when operator rejects` |
| 5. 이미 승인·거절된 신청을 다시 승인·거절 → 409 | `[예외] StreamerApplicationReviewService.approve — should throw CHANNEL_APPLICATION_ALREADY_DECIDED without changing the role, creating a channel or deleting sessions when the application is no longer pending`<br>`[예외] StreamerApplicationReviewService.reject — should throw CHANNEL_APPLICATION_ALREADY_DECIDED when the application is no longer pending`<br>`[예외] POST /api/operator/streamer-applications/{id}/approve — should return 409 with code CHANNEL_APPLICATION_ALREADY_DECIDED and its message when service reports the application is already decided`<br>`[예외] StreamerApplicationReviewIntegration — should return 409 with code CHANNEL_APPLICATION_ALREADY_DECIDED when operator approves an application that is already approved`<br>`[예외] StreamerApplicationReviewIntegration — should return 409 with code CHANNEL_APPLICATION_ALREADY_DECIDED when operator rejects an application that is already rejected`<br>`[예외] StreamerApplicationReviewIntegration — should throw CHANNEL_APPLICATION_ALREADY_DECIDED and keep the application rejected with no channel when a rejected application is approved`<br>`[예외] StreamerApplicationReviewIntegration — should throw CHANNEL_APPLICATION_ALREADY_DECIDED and keep the application approved when an approved application is rejected` |
| 6. 승인 요청 2개가 동시에 → 채널 하나 | `[경계] StreamerApplicationReviewIntegration — should create one channel and reject the other approval when two approvals of the same application arrive at once` |
| 7. 승인 중 채널 생성 실패 → 신청 `대기`, 역할 `MEMBER` 그대로 | `[예외] StreamerApplicationReviewIntegration — should keep the application pending, the member role MEMBER and no channel when channel creation fails during approval`<br>`[예외] StreamerApplicationReviewService.approve — should not delete sessions when channel creation fails` |
| 8. 운영자가 아닌 회원이 대기 목록·승인·거절 → 403 | `[예외] StreamerApplicationReviewIntegration — should return 403 with code FORBIDDEN when a member who is not an operator requests the pending list`<br>`[예외] StreamerApplicationReviewIntegration — should return 403 with code FORBIDDEN and keep the application pending when a member who is not an operator approves`<br>`[예외] StreamerApplicationReviewIntegration — should return 403 with code FORBIDDEN and keep the application pending when a member who is not an operator rejects` |

**AC에 없는데 넣은 시나리오**

| 시나리오 | 이유 |
| --- | --- |
| `[예외] StreamerApplicationReviewService.approve — should throw CHANNEL_APPLICATION_NOT_FOUND when the application does not exist`<br>`[예외] StreamerApplicationReviewService.reject — should throw CHANNEL_APPLICATION_NOT_FOUND when the application does not exist`<br>`[예외] POST /api/operator/streamer-applications/{id}/approve — should return 404 with code CHANNEL_APPLICATION_NOT_FOUND and its message when service reports the application does not exist` | id를 경로로 받으므로 없는 id가 반드시 들어온다. 없으면 조건부 UPDATE가 0행이라 "이미 처리됨"(409)으로 잘못 답한다 |
| `[경계] StreamerApplicationReviewIntegration — should reject a second channel for the same member` | 확정 사양 "스트리머 한 명당 채널 하나"를 DB 제약으로 박는다. #8·#9가 회원 id로 채널을 하나 찾는 것이 이 제약에 기댄다 |

**커버리지:** AC 8개 / 시나리오 28개 / 미커버 0개

---

## AC 검증

> `harness:ac-verifier` (2026-11-21, HEAD 9503b60). 변이 최대 3번·20분·스크래치 복사본·클래스 단위 테스트로 제한해 띄웠다. 아래는 검증자 출력 원문이다(제목 수준만 한 단계 낮췄다). 구현자는 맨 아래 `### 구현자 이견`에만 쓴다.

### 이슈 #7 AC 검증 결과 (feat/channel/issue-7, HEAD 9503b60)

AC 8개는 모두 충족(8/8)이고, 가짜 테스트는 없습니다. 이번 이슈의 테스트 28개는 저장소에서 다시 돌려 전부 통과했습니다. 그다음 스크래치 복사본에서 구현을 일부러 망가뜨리는 변이(mutation)를 3번 넣어 봤고, 세 번 모두 해당 AC 테스트가 실패해서 잡아냈습니다. 저장소 작업 트리는 건드리지 않았습니다(`git status --short` 출력 없음).

경로 약어: Svc = `C:\live-streaming-project\backend\src\main\java\com\livestream\channel\StreamerApplicationReviewService.java`, IT = `C:\live-streaming-project\backend\src\test\java\com\livestream\channel\StreamerApplicationReviewIntegrationTest.java`, UT = `...\StreamerApplicationReviewServiceTest.java`, CT = `...\StreamerApplicationReviewControllerTest.java` (모두 같은 테스트 패키지 디렉터리).

#### AC별 판정

| AC | 판정 | 근거 테스트 (@DisplayName, 파일:줄) | 구현 (파일:줄) | 판단 이유 |
|---|---|---|---|---|
| 1. 대기 3개 → 신청 시각이 오래된 순, 닉네임·이메일·신청 시각 | ✅ | "should list pending applications oldest first with nickname, email and applied time when operator requests the pending list" (IT:174)<br>"should return 200 with id, nickname, email and applied time of each pending application" (CT:246)<br>"should leave approved and rejected applications out of the pending list" (IT:206) | `StreamerApplicationMapper.xml` findPending `ORDER BY a.created_at, a.id`, `WHERE a.status='PENDING'`; Svc:34-36; Controller `pending()` | 신청 3개를 넣을 때 id 순서와 신청 시각 순서를 일부러 어긋나게 했습니다(IT:184-186, 10:03/10:01/10:02). 단언은 시각 순서(second, third, first)이고 닉네임·이메일·시각 세 필드를 `containsExactly`로 확인합니다(IT:199-202). 그래서 id 순으로 정렬하면 실패합니다. 대기 신청만 나오는지도 IT:206에서 따로 봅니다. |
| 2. 승인 → 신청 APPROVED, 역할 STREAMER, 이름=닉네임·소개 빈 채널 하나 | ✅ | "should mark the application approved, make the member STREAMER and create one channel named after the nickname with an empty description when operator approves" (IT:218)<br>UT:89 (같은 문장의 서비스 버전)<br>CT:265 | Svc:41-48 (조건부 UPDATE → `updateRole(STREAMER)` → `channelMapper.insert(new Channel(id, nickname, ""))`); `ChannelMapper.xml`; `V3__create_channel.sql` | 진짜 Oracle에서 상태·역할·채널 이름을 확인합니다(IT:229-230). `queryForMap`은 행이 0개거나 2개 이상이면 예외를 던지므로 "채널 하나"도 함께 검증됩니다. 소개는 Oracle이 `''`를 NULL로 저장하므로 NULL로 단언합니다(IT:232). 이는 시나리오 문서 "판단이 갈린 곳" 6번과 일치합니다. |
| 3. 로그인해 있던 신청자 → 승인 뒤 기존 쿠키로 내 정보 401, 다시 로그인하면 STREAMER | ✅ | "should return 401 for the applicant's existing cookie and STREAMER after logging in again when the application is approved" (IT:236)<br>"should delete every session of the applicant after the approval is committed" (UT:108) | Svc:49-50 (커밋 뒤 `findByPrincipalName(String.valueOf(memberId))`로 찾은 세션을 `deleteById`) | 실제 로그인 쿠키로 `/api/members/me`를 불러 401을 보고, 다시 로그인한 응답의 `$.role`이 STREAMER인지 봅니다. 변이 1(세션 삭제 두 줄 주석 처리)에서 이 IT와 UT:108이 모두 실패했습니다. 따라서 승인 전에는 쿠키가 실제로 유효했고, 401은 세션 삭제 때문에 나온다는 것이 입증됩니다. UT:108은 InOrder로 커밋 다음에 삭제가 오는지도 확인합니다. |
| 4. 거절 → 신청 REJECTED, 역할 MEMBER 그대로 | ✅ | "should mark the application rejected and keep the member role MEMBER when operator rejects" (IT:251)<br>UT:170, CT:305 | Svc:55-59 | HTTP 200 + `$.status` REJECTED를 보고, DB의 상태와 역할을 `containsExactly("REJECTED","MEMBER")`로 확인합니다(IT:260). 역할 변경·채널 생성이 일어나지 않는 것은 UT:179-180에서 `never()`로 봅니다. |
| 5. 이미 승인·거절된 신청을 다시 승인·거절 → 409 | ✅ | IT:305 (승인된 것 승인 → HTTP 409)<br>IT:315 (거절된 것 거절 → HTTP 409)<br>IT:325 (거절된 것 승인 → ALREADY_DECIDED, 상태·역할·채널 0 유지)<br>IT:339 (승인된 것 거절 → ALREADY_DECIDED, APPROVED 유지)<br>CT:276, UT:135, UT:195 | `StreamerApplicationMapper.xml` `WHERE id = #{id} AND status = 'PENDING'`; Svc:67-71; `ErrorCode.CHANNEL_APPLICATION_ALREADY_DECIDED(CONFLICT)` | 네 가지 조합(승인→승인, 거절→거절, 거절→승인, 승인→거절)이 모두 있습니다. 앞의 둘은 HTTP 409와 code로, 뒤의 둘은 서비스 예외 코드로 확인합니다. 코드가 409로 바뀌는 것은 CT:276과 IT:305·315가 확인합니다. |
| 6. 승인 요청 2개가 동시에 → 채널 하나 | ✅ | "should create one channel and reject the other approval when two approvals of the same application arrive at once" (IT:264) | 조건부 UPDATE(xml), `uk_channel_member` UNIQUE(V3) | 두 스레드를 CountDownLatch로 동시에 출발시킵니다. 채널 수가 1이고, 결과가 `{"OK","CHANNEL_APPLICATION_ALREADY_DECIDED"}`인지 봅니다(IT:290-291). 변이 3에서는 조건부 UPDATE를 "읽은 뒤 Java에서 상태 확인 → 무조건 UPDATE"로 바꿨는데, 이 테스트가 실패했습니다(ExecutionException ← DuplicateKeyException ORA-00001 UK_CHANNEL_MEMBER). 즉 실제 경합이 일어났고, 이 테스트가 조건부 UPDATE에 기대고 있다는 것이 확인됩니다. |
| 7. 승인 중 채널 생성 실패 → 신청 PENDING, 역할 MEMBER 그대로 | ✅ | "should keep the application pending, the member role MEMBER and no channel when channel creation fails during approval" (IT:351)<br>"should not delete sessions when channel creation fails" (UT:151) | Svc:41-48 (`TransactionTemplate.execute`로 묶음) | 진짜 Spring의 TransactionTemplate과 진짜 매퍼를 쓰고, ChannelMapper만 예외를 던지는 가짜로 바꿨습니다. DB에서 PENDING/MEMBER/채널 0개를 확인합니다. 변이 2(트랜잭션을 빼고 람다를 직접 호출)에서 이 IT가 실패했습니다. |
| 8. 운영자가 아닌 회원이 대기 목록·승인·거절 요청 → 403 | ✅ | IT:371 (대기 목록 403 + code FORBIDDEN)<br>IT:382 (승인 403 + 신청은 PENDING 유지)<br>IT:395 (거절 403 + PENDING 유지) | `SecurityConfig` `.requestMatchers("/api/operator/**").hasRole("OPERATOR")` + `accessDeniedHandler` → `ErrorCode.FORBIDDEN`; `AuthController.java:43` (`ROLE_`+역할) | 실제 로그인 쿠키로 보안 필터를 모두 거칩니다. 상태 코드, 에러 코드, 부작용이 없는지(신청 PENDING 유지)까지 확인합니다. 테스트한 것은 MEMBER 역할뿐입니다. STREAMER 역할은 테스트하지 않았지만, 허용 목록 방식(`hasRole("OPERATOR")`)이라 구조상 403이 납니다. 아래 "확인하지 않은 것"에 적었습니다. |

**요약: AC 8개 중 ✅ 8 / ⚠️ 0 / ❌ 0**

#### 갭을 메울 시나리오 제안

⚠️·❌는 없습니다. 아래는 필수가 아니라 보강용 선택 사항입니다.
- [ ] [예외] `StreamerApplicationReviewIntegration` — should return 403 with code FORBIDDEN when a streamer who is not an operator requests the pending list
  (AC8의 "운영자가 아닌 회원"에는 STREAMER도 들어갑니다. 지금은 MEMBER로만 확인합니다.)

#### 가짜 테스트 여부

없습니다.
- 예외 단언은 모두 구체적입니다. `isInstanceOf(BusinessException.class).extracting("code").isEqualTo(ErrorCode.X)`이거나, 던진 예외 인스턴스와 같은지(`isSameAs(failure)`) 봅니다. `Exception.class`나 인자 없는 단언은 없습니다.
- 단언이 없는 테스트, 결과를 기다리지 않는 비동기 호출은 없습니다. 동시성 테스트는 `Future.get()`으로 두 결과를 모두 받습니다.
- 컨트롤러 테스트(CT)는 서비스를 목(mock)으로 두고 그 반환값을 JSON으로 확인합니다. 이건 HTTP 매핑과 직렬화를 검사하는 것이고, AC의 실제 동작은 통합 테스트(IT)가 진짜 경로로 따로 확인하므로 가짜로 보지 않았습니다.

#### 확인하지 않은 것

- 동시성 테스트(AC6)는 경합이 매번 일어난다는 보장이 없는 비결정적 테스트입니다. 변이 3에서 경합을 잡은 건 한 번 돌린 결과입니다. 반복해서 돌려 보지 않았습니다. 다만 원래 구현은 어떤 순서로 실행돼도 결과가 같습니다(조건부 UPDATE에 UNIQUE 제약까지 있음).
- AC8의 STREAMER 역할 403은 실행해서 확인하지 않았습니다(구조로만 추론).
- 로그인하지 않은 요청의 401은 AC 밖이라 따로 보지 않았습니다.
- 전체 테스트 스위트는 지시대로 돌리지 않았습니다(구현자가 187개 통과를 보고함).
- 변이 3번 외의 변이(예: ORDER BY를 id로 바꾸기)는 실행하지 않았습니다. AC1 테스트가 id 순서와 시각 순서를 어긋나게 넣었다는 것은 코드를 읽어서 판단했습니다.

#### 실행한 명령·읽은 것

1. `cat .claude/harness.json && gh issue view 7 && git diff origin/main...HEAD --stat` → 설정 확인, AC 8개 확인, 변경 파일 20개
2. `cat -n docs/result/scenarios/issue-7.md` → 시나리오 28개, AC 대조표
3. `cat -n` Svc, Controller, ChannelMapper, StreamerApplicationMapper, Channel, PendingApplicationResponse, `mapper/channel/*.xml`, V3 SQL과 common·member 쪽 diff → 구현 확인
4. `cat -n` UT, CT → 서비스 테스트 8개, 컨트롤러 테스트 5개
5. `cat -n` IT → 통합 테스트 15개
6. 저장소에서 `./gradlew test --tests "StreamerApplicationReview*"`(백그라운드, JAVA_HOME 지정) → BUILD SUCCESSFUL, EXIT=0
7. Grep `ROLE_|PRINCIPAL_NAME|...`, `cat -n member/AuthController.java`, `LoginMember.getName` → 권한 `ROLE_`+역할, 세션 색인 키가 회원 id인지 확인
8. Grep으로 `docs/result/prd/channel.md`의 승인·동시·세션 결정 확인 → 구현이 PRD 104~106행과 일치
9. `git archive HEAD backend | tar -x` → 스크래치 `C:\Users\USER\AppData\Local\Temp\claude\1cbb40fc7d76b7b0\ad17e800-4c27-40f4-ae12-ad63eb406d97\scratchpad\verify7\backend`에 복사
10. 테스트 결과 XML 요약 → approve 컨트롤러 3, GET 1, reject 컨트롤러 1, 통합 15, 서비스 approve 5, 서비스 reject 3. 합계 28개, 실패 0
11. 변이 1 (Svc:49-50 세션 삭제 주석 처리) → 28개 중 2개 실패: IT:236(AC3), UT:108
12. 변이 2 (TransactionTemplate 제거) → 28개 중 2개 실패: IT:351(AC7), UT:108
13. 변이 3 (xml에서 `AND status='PENDING'` 제거 + Java 쪽 상태 확인, IntegrationTest만 실행) → 15개 중 1개 실패: IT:264(AC6), 원인 DuplicateKeyException ORA-00001 UK_CHANNEL_MEMBER
14. `git status --short` (저장소) → 출력 없음. 작업 트리 깨끗함

### 구현자 이견

- **선택 제안(STREAMER 403)은 넣지 않았다.** 검증자도 ⚠️·❌가 아닌 보강용이라고 적었다. 규칙이 허용 목록(`hasRole("OPERATOR")`)이라 MEMBER가 막히면 STREAMER도 같은 줄에서 막힌다. 사전 승인 범위는 ⚠️·❌를 메우는 것이라 1차 검증으로 끝냈다(2차 검증 없음).
- **수동 확인:** 화면이 없는 API 이슈다(PRD "운영자 화면 하지 않음"). HTTP 경로(보안 필터·세션 필터 포함)는 통합 테스트 15개가 진짜 Oracle·Redis로 거친다. 브라우저 확인은 하지 않았다.

---

## 리팩터

고칠 대상 없음(2026-11-21). 이번 이슈가 바꾼 `backend/src/main` 파일 12개를 다섯 기준으로 봤다. 리뷰 서비스는 66줄에 공개 메서드 3개, 반복되는 두 단계(신청 찾기·조건부 결정)는 이미 `findApplication`·`decide`로 나뉘어 있다. 컨트롤러는 위임 한 줄씩, 매퍼·SQL·마이그레이션은 #6과 같은 모양이다. `new StreamerApplicationResponse(...)` 반복은 서비스 두 곳이라 세 번째 전에는 묶지 않는다.

---

## 보안 점검

> `/harness:security-review 7` (2026-11-21, HEAD 9503b60). 대장(`securityExceptions`)은 설정에 없다.

**수집 결과:** 타입 검사(`compileJava compileTestJava`, `tsc -b`) 오류 없음 · `npm audit` 취약점 0 · 비밀값 패턴·알려진 키 접두사 없음 · `.env` 커밋·스테이지 없음 · channel 패키지 로그 출력 없음 · 프론트 변경 없음

| 분류 | 파일·줄 | 내용 | 판단 |
| --- | --- | --- | --- |
| 🔴 | — | 없음 | |
| 🟡 | — | 없음 | |
| ⚪ | `StreamerApplicationReviewService.approve` (세션 삭제) | 커밋 뒤 Redis 세션 삭제가 실패하면 승인은 남고 운영자는 500을 받는다. 그 회원의 낡은 세션에는 `MEMBER`가 남는다 | DB와 Redis는 한 트랜잭션으로 묶을 수 없다(#4 비밀번호 변경과 같은 판단). 낡은 세션은 권한이 더 낮은 쪽(`MEMBER`)이라 권한 상승이 아니다. 다시 로그인하면 `STREAMER`다 |
| ⚪ | `/api/operator/**` | 권한 검사 | 경로 전체를 `hasRole("OPERATOR")`로 막는 허용 목록 방식이다. 로그인 필요(401)는 기존 진입점, 출처 검증(403 `FORBIDDEN_ORIGIN`)은 기존 필터가 그대로 건다. MEMBER 403은 통합 테스트 3개로 확인 |
| ⚪ | `GET /api/operator/streamer-applications` | 응답에 신청자 이메일이 있다 | 운영자만 받는다(AC 1이 요구). 로그에는 남기지 않는다 |
| ⚪ | `/{id}/approve`·`/{id}/reject` 경로 변수 | 숫자가 아닌 id | Spring이 400으로 막는다. 본문은 Spring Boot 기본값이라 스택·SQL이 나가지 않는다. DB로 가는 값은 `long`뿐이고 SQL은 바인드 변수다 |
| ⚪ | `ChannelMapper.insert` | 같은 회원의 두 번째 채널은 `uk_channel_member` 위반 → 500 | 승인은 조건부 UPDATE가 한 번만 통과시키고, 승인된 회원은 `STREAMER`라 다시 신청할 수 없어 정상 경로에서 생기지 않는다. 제약은 마지막 방어선이다 |

즉시 수정할 항목이 없어 코드를 고치지 않았다. 재스캔 대상 변경도 없다.
