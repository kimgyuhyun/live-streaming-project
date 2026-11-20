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
