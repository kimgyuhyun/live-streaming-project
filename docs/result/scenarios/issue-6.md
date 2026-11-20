# 이슈 #6 — 회원이 스트리머를 신청하고 상태를 본다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/6
> PRD: `docs/result/prd/channel.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

---

## 시그니처

### 관련 ADR

- **채널 PRD 작은 결정(패키지)** — 신청·승인·채널·키·제목은 `com.livestream.channel`. 이 이슈가 이 패키지의 첫 코드다.
- **채널 PRD 작은 결정(신청 동시 처리)** — 같은 회원의 대기 신청은 하나만, DB 제약으로 막는다. 승인·거절의 조건부 UPDATE는 #7.
- **채널 PRD 하지 않을 것(신청서 내용)** — 사유·심사 메모를 받지 않는다. 신청 요청에는 본문이 없다.

### DB

`backend/src/main/resources/db/migration/V2__create_streamer_application.sql`

```sql
CREATE TABLE streamer_application (
    id         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id  NUMBER       NOT NULL,
    status     VARCHAR2(20) NOT NULL,
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT fk_streamer_application_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT ck_streamer_application_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);
-- 대기 상태인 행만 색인에 올라간다(나머지는 식이 NULL이라 빠진다) → 회원당 대기 신청 하나
CREATE UNIQUE INDEX uk_streamer_application_pending
    ON streamer_application (CASE WHEN status = 'PENDING' THEN member_id END);
```

함수 기반 유니크 인덱스(식의 결과에 거는 유니크 인덱스. Oracle은 식이 NULL인 행을 색인에 넣지 않는다)로 "대기인 행끼리만" 유일하게 한다.

### 타입

```java
// com.livestream.channel
public enum ApplicationStatus { NONE, PENDING, APPROVED, REJECTED }   // NONE은 응답에만 쓴다(DB CHECK에서 제외)

public class StreamerApplication {
    Long id; long memberId; ApplicationStatus status;
    public StreamerApplication(long memberId, ApplicationStatus status);
}

public record StreamerApplicationResponse(ApplicationStatus status) {}

// SQL은 resources/mapper/channel/StreamerApplicationMapper.xml
@Mapper
public interface StreamerApplicationMapper {
    void insert(StreamerApplication application);                          // 생성된 id를 채운다
    boolean existsPendingByMemberId(long memberId);
    Optional<ApplicationStatus> findLatestStatusByMemberId(long memberId);  // id가 가장 큰 행
}

@Service
public class StreamerApplicationService {
    public StreamerApplicationService(StreamerApplicationMapper mapper);
    // 역할이 MEMBER가 아니면 CHANNEL_APPLICATION_NOT_MEMBER. 대기 신청이 있으면 CHANNEL_APPLICATION_PENDING.
    // 동시에 들어와 조회를 함께 통과한 경우는 uk_streamer_application_pending 위반 → CHANNEL_APPLICATION_PENDING
    public StreamerApplicationResponse apply(long memberId, Role role);
    public StreamerApplicationResponse findMine(long memberId);   // 신청이 없으면 NONE
}

@RestController
@RequestMapping("/api/streamer-applications")
class StreamerApplicationController {
    @PostMapping        // 201 + {"status":"PENDING"}
    ResponseEntity<StreamerApplicationResponse> apply(@AuthenticationPrincipal LoginMember loginMember);
    @GetMapping("/me")  // 200 + {"status":"NONE|PENDING|APPROVED|REJECTED"}
    StreamerApplicationResponse mine(@AuthenticationPrincipal LoginMember loginMember);
}
```

```java
// com.livestream.common.error
public enum ErrorCode {
    // 추가
    CHANNEL_APPLICATION_PENDING(HttpStatus.CONFLICT, "이미 대기 중인 스트리머 신청이 있습니다"),
    CHANNEL_APPLICATION_NOT_MEMBER(HttpStatus.CONFLICT, "회원만 스트리머 신청을 할 수 있습니다");
}
```

`SecurityConfig`는 바꾸지 않는다. `anyRequest().authenticated()`(로그인 필요)와 출처 검증이 새 경로에도 그대로 걸린다.

### 에러 케이스

| 상황 | 에러 코드 | HTTP |
| --- | --- | --- |
| 대기 중인 신청이 있음 (동시 요청에서 DB 제약에 걸린 쪽 포함) | `CHANNEL_APPLICATION_PENDING` | 409 |
| 역할이 `STREAMER`·`OPERATOR` | `CHANNEL_APPLICATION_NOT_MEMBER` | 409 |
| 로그인하지 않음 | `UNAUTHENTICATED` (기존) | 401 |

### 화면

- `src/api/streamerApplications.ts` — `type ApplicationStatus = 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED'`, `applyForStreamer(): Promise<StreamerApplicationResponse>`, `fetchMyApplication(): Promise<StreamerApplicationResponse>`
- `/me` 내 정보 화면(props 없는 페이지, 새로 만듦) — 이메일·닉네임·역할과 신청 상태(없음·대기·승인·거절). 역할이 `MEMBER`이고 상태가 없음·거절이면 "스트리머 신청" 버튼. 성공하면 `['myApplication']` 쿼리를 무효화하고, 실패하면 서버 메시지. 로그인하지 않았으면 로그인 링크
- `RootLayout` 머리글 — 닉네임을 `/me` 링크로

### 판단이 갈린 곳

1. 역할은 세션(`LoginMember.role`)의 값을 쓴다. 승인되면 #7이 그 회원의 세션을 지우므로(채널 PRD 작은 결정 "승인 후 로그인 상태") 세션의 역할이 낡지 않는다. channel이 `MemberMapper`에 기대지 않는다.
2. 신청이 없으면 `NONE`을 준다. null이나 404보다 화면 분기가 단순하다.
3. 매퍼 SQL도 `@SpringBootTest` 통합 테스트로 확인한다. `@MybatisTest`는 컨텍스트를 하나 더 띄워 Oracle 컨테이너를 한 번 더 기동한다(CLAUDE.md 테스트 절). #1·#2도 같은 방식이다.
4. `member_id` 일반 인덱스는 두지 않는다. 행이 적고, 인덱스 추가는 측정 절차(index-measurement)를 밟아야 한다.

### 이 이슈에서 만들지 않는 것

- 대기 목록·승인·거절, 처리 시각 컬럼 — #7 (새 마이그레이션으로 추가)
- 내 정보 화면의 닉네임·비밀번호 변경 — #4가 이 화면에 붙인다

---

## 테스트 시나리오

### 정상

- [x] [정상] `StreamerApplicationService.apply` — should save a pending application and return PENDING when member has never applied
- [x] [정상] `StreamerApplicationService.apply` — should save a new pending application when member has no pending application after a rejection
- [x] [정상] `StreamerApplicationService.findMine` — should return the status of the latest application when member has applied
- [x] [정상] `POST /api/streamer-applications` — should return 201 with status PENDING when service accepts application
- [x] [정상] `GET /api/streamer-applications/me` — should return 200 with the application status of the logged-in member
- [x] [정상] `StreamerApplicationIntegration` — should return PENDING from my application after member applies with session cookie

### 경계

- [x] [경계] `StreamerApplicationService.findMine` — should return NONE when member has never applied
- [x] [경계] `StreamerApplicationIntegration` — should keep one pending application when two applications from the same member arrive at once
- [x] [경계] `StreamerApplicationIntegration` — should throw CHANNEL_APPLICATION_PENDING when pending check passes but a pending application is already saved
- [x] [경계] `StreamerApplicationIntegration` — should save a new pending application and report PENDING when member applies again after rejection

### 예외

- [x] [예외] `StreamerApplicationService.apply` — should throw CHANNEL_APPLICATION_PENDING when member has a pending application
- [x] [예외] `StreamerApplicationService.apply` — should throw CHANNEL_APPLICATION_NOT_MEMBER when role is STREAMER
- [x] [예외] `StreamerApplicationService.apply` — should throw CHANNEL_APPLICATION_NOT_MEMBER when role is OPERATOR
- [x] [예외] `POST /api/streamer-applications` — should return 409 with code CHANNEL_APPLICATION_PENDING and its message when service rejects duplicate
- [x] [예외] `POST /api/streamer-applications` — should return 409 with code CHANNEL_APPLICATION_NOT_MEMBER and its message when service rejects non-member
- [x] [예외] `StreamerApplicationIntegration` — should return 401 with code UNAUTHENTICATED when application is requested without login
- [x] [예외] `StreamerApplicationIntegration` — should return 403 when application request comes from another origin

### 수동 확인 (테스트 도구가 없는 화면 — 시나리오 수에 넣지 않는다)

프론트 테스트 도구(Vitest)는 #12에서 넣는다. 아래는 Green 단계에서 브라우저로 확인하고 결과를 이 문서와 PR에 남긴다.

- 머리글의 닉네임을 누르면 `/me`로 가고 이메일·닉네임·역할·신청 상태 "없음"과 "스트리머 신청" 버튼이 보인다
- 버튼을 누르면 상태가 "대기"로 바뀌고 버튼이 사라진다
- 새로고침해도 "대기"가 남아 있다
- 로그인하지 않고 `/me`를 열면 로그인 링크가 보인다

**확인 결과 (2026-11-20, Green):** 공유 개발 DB에 V2를 적용하지 않으려고 Testcontainers DB로 앱을 띄웠다(임시 `TestLiveStreamingApplication` + `bootTestRun`, 확인 후 삭제). Vite 개발 서버(출처 `http://localhost:5173`)에서 `apply6@example.com` / `신청확인6`으로 새로 가입해 확인했다.

- 로그인 전 `/me`: `GET /api/members/me` 401 → "로그인이 필요합니다"와 로그인 링크 ✅
- 로그인 후 머리글 닉네임 `신청확인6`이 `/me` 링크 → 이메일·닉네임·역할 "회원"·신청 상태 "없음"·"스트리머 신청" 버튼 ✅
- 버튼: `POST /api/streamer-applications` 201 → `GET .../me` 200 → 상태 "대기", 버튼 사라짐 ✅
- 새로고침: 상태 "대기" 유지 ✅
- 콘솔 오류는 네트워크 응답(백엔드 기동 전 502, 비로그인 401)뿐, 스크립트 오류 없음

**401·403 테스트 변이 확인 (Green):** 이 두 테스트는 기존 보안 설정이 이미 막고 있어 Red에서 구현 없이 통과했다. `SecurityConfig`에서 이 경로를 `permitAll`로 열고 출처 검증 필터를 빼자 두 테스트만 실패했다(401 → NPE, 403 → 상태 단언 실패). 나머지 4개는 통과. 확인 후 `SecurityConfig`를 되돌렸다.

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 신청한 적 없는 회원이 신청하면 201, 내 신청 상태가 `대기` | `[정상] StreamerApplicationService.apply — should save a pending application and return PENDING when member has never applied`<br>`[정상] POST /api/streamer-applications — should return 201 with status PENDING when service accepts application`<br>`[정상] StreamerApplicationService.findMine — should return the status of the latest application when member has applied`<br>`[정상] GET /api/streamer-applications/me — should return 200 with the application status of the logged-in member`<br>`[정상] StreamerApplicationIntegration — should return PENDING from my application after member applies with session cookie` |
| 2. 대기 중인 신청이 있는 회원이 다시 신청하면 409 | `[예외] StreamerApplicationService.apply — should throw CHANNEL_APPLICATION_PENDING when member has a pending application`<br>`[예외] POST /api/streamer-applications — should return 409 with code CHANNEL_APPLICATION_PENDING and its message when service rejects duplicate` |
| 3. 신청 요청 2개가 동시에 오면 대기 신청은 하나 | `[경계] StreamerApplicationIntegration — should keep one pending application when two applications from the same member arrive at once`<br>`[경계] StreamerApplicationIntegration — should throw CHANNEL_APPLICATION_PENDING when pending check passes but a pending application is already saved` |
| 4. 거절된 회원이 다시 신청하면 새 대기 신청 | `[정상] StreamerApplicationService.apply — should save a new pending application when member has no pending application after a rejection`<br>`[경계] StreamerApplicationIntegration — should save a new pending application and report PENDING when member applies again after rejection` |
| 5. 스트리머나 운영자가 신청하면 409 | `[예외] StreamerApplicationService.apply — should throw CHANNEL_APPLICATION_NOT_MEMBER when role is STREAMER`<br>`[예외] StreamerApplicationService.apply — should throw CHANNEL_APPLICATION_NOT_MEMBER when role is OPERATOR`<br>`[예외] POST /api/streamer-applications — should return 409 with code CHANNEL_APPLICATION_NOT_MEMBER and its message when service rejects non-member` |
| 6. 로그인하지 않은 요청이 신청하면 401 | `[예외] StreamerApplicationIntegration — should return 401 with code UNAUTHENTICATED when application is requested without login` |

**AC에 없는데 넣은 시나리오**

| 시나리오 | 이유 |
| --- | --- |
| `[경계] StreamerApplicationService.findMine — should return NONE when member has never applied` | 이슈 본문의 상태 "없음". 화면이 이 값으로 버튼을 띄운다 |
| `[예외] StreamerApplicationIntegration — should return 403 when application request comes from another origin` | 상태를 바꾸는 요청은 출처 검증을 받는다는 기획서 결정을 새 경로에서 확인. #2 로그인과 같은 이유 |

**커버리지:** AC 6개 / 시나리오 17개 / 미커버 0개
