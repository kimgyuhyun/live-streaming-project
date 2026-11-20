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
- [x] [경계] `StreamerApplicationIntegration` — should report a pending application from the pending check when one is saved

### 예외

- [x] [예외] `StreamerApplicationService.apply` — should throw CHANNEL_APPLICATION_PENDING when member has a pending application
- [x] [예외] `StreamerApplicationService.apply` — should throw CHANNEL_APPLICATION_NOT_MEMBER when role is STREAMER
- [x] [예외] `StreamerApplicationService.apply` — should throw CHANNEL_APPLICATION_NOT_MEMBER when role is OPERATOR
- [x] [예외] `POST /api/streamer-applications` — should return 409 with code CHANNEL_APPLICATION_PENDING and its message when service rejects duplicate
- [x] [예외] `POST /api/streamer-applications` — should return 409 with code CHANNEL_APPLICATION_NOT_MEMBER and its message when service rejects non-member
- [x] [예외] `StreamerApplicationIntegration` — should return 409 with code CHANNEL_APPLICATION_PENDING when member with a saved pending application applies again with session cookie
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
| 2. 대기 중인 신청이 있는 회원이 다시 신청하면 409 | `[예외] StreamerApplicationService.apply — should throw CHANNEL_APPLICATION_PENDING when member has a pending application`<br>`[예외] POST /api/streamer-applications — should return 409 with code CHANNEL_APPLICATION_PENDING and its message when service rejects duplicate`<br>`[예외] StreamerApplicationIntegration — should return 409 with code CHANNEL_APPLICATION_PENDING when member with a saved pending application applies again with session cookie` (AC 검증 갭으로 추가)<br>`[경계] StreamerApplicationIntegration — should report a pending application from the pending check when one is saved` (AC 검증 갭으로 추가) |
| 3. 신청 요청 2개가 동시에 오면 대기 신청은 하나 | `[경계] StreamerApplicationIntegration — should keep one pending application when two applications from the same member arrive at once`<br>`[경계] StreamerApplicationIntegration — should throw CHANNEL_APPLICATION_PENDING when pending check passes but a pending application is already saved` |
| 4. 거절된 회원이 다시 신청하면 새 대기 신청 | `[정상] StreamerApplicationService.apply — should save a new pending application when member has no pending application after a rejection`<br>`[경계] StreamerApplicationIntegration — should save a new pending application and report PENDING when member applies again after rejection` |
| 5. 스트리머나 운영자가 신청하면 409 | `[예외] StreamerApplicationService.apply — should throw CHANNEL_APPLICATION_NOT_MEMBER when role is STREAMER`<br>`[예외] StreamerApplicationService.apply — should throw CHANNEL_APPLICATION_NOT_MEMBER when role is OPERATOR`<br>`[예외] POST /api/streamer-applications — should return 409 with code CHANNEL_APPLICATION_NOT_MEMBER and its message when service rejects non-member` |
| 6. 로그인하지 않은 요청이 신청하면 401 | `[예외] StreamerApplicationIntegration — should return 401 with code UNAUTHENTICATED when application is requested without login` |

**AC에 없는데 넣은 시나리오**

| 시나리오 | 이유 |
| --- | --- |
| `[경계] StreamerApplicationService.findMine — should return NONE when member has never applied` | 이슈 본문의 상태 "없음". 화면이 이 값으로 버튼을 띄운다 |
| `[예외] StreamerApplicationIntegration — should return 403 when application request comes from another origin` | 상태를 바꾸는 요청은 출처 검증을 받는다는 기획서 결정을 새 경로에서 확인. #2 로그인과 같은 이유 |

**커버리지:** AC 6개 / 시나리오 19개 / 미커버 0개 (AC 검증 후 2개 추가)

---

## AC 검증

> `harness:ac-verifier` (2026-11-20, HEAD e5e809b). 아래는 검증자 출력 원문이다. 구현자는 맨 아래 `### 구현자 이견`에만 쓴다.

## 이슈 #6 AC 검증 결과

AC 6개 중 5개는 충족이고, AC 2 하나만 부분 충족입니다. 테스트에 거짓 양성(가짜 테스트)은 없었습니다. 구현은 고치지 않았습니다.

이슈 테스트 17개는 제가 직접 돌렸고 전부 통과했습니다(`./gradlew test --tests "com.livestream.channel.*"`, BUILD SUCCESSFUL, 2분 11초). 결과 XML의 타임스탬프는 오늘(2026-11-20) 실행분입니다. 내역은 컨트롤러 3+1, 통합 6, 서비스 5+2이고 실패·오류·스킵은 0입니다.

### 판정

| AC | 판정 | 근거 테스트 (@DisplayName, 파일:줄) | 구현 (파일:줄) | 판단 이유 |
|---|---|---|---|---|
| 1. 신청한 적 없는 회원 → 201, 내 상태 `대기` | ✅ | `should save a pending application and return PENDING when member has never applied` (StreamerApplicationServiceTest.java:43)<br>`should return 201 with status PENDING when service accepts application` (StreamerApplicationControllerTest.java:49)<br>`should return PENDING from my application after member applies with session cookie` (StreamerApplicationIntegrationTest.java:124) | StreamerApplicationController.java:22-26 (`HttpStatus.CREATED`)<br>StreamerApplicationService.java:21-34<br>StreamerApplicationMapper.xml:5-8, 16-22 | 201은 컨트롤러가 직접 만드는 값이라 WebMvc 테스트(:56)로 확인됩니다. 실제 Oracle에서 신청 후 `/me`가 PENDING인 것은 통합 테스트(:132-133)가 확인합니다. 약점: 통합 테스트의 POST(:130)에 상태 단언이 없습니다. 그래서 전체 경로에서 201이 나온다는 직접 단언은 없습니다(아래 "조일 곳" 1) |
| 2. 대기 신청이 있는 회원이 다시 신청 → 409 | ⚠️ | `should throw CHANNEL_APPLICATION_PENDING when member has a pending application` (ServiceTest:67, `existsPending`를 목으로 true 고정)<br>`should return 409 with code CHANNEL_APPLICATION_PENDING and its message when service rejects duplicate` (ControllerTest:61, 서비스 목)<br>(보조) `should throw CHANNEL_APPLICATION_PENDING when pending check passes but a pending application is already saved` (IntegrationTest:165) | Service.java:25-27, 30-31<br>Mapper.xml:10-14<br>GlobalExceptionHandler.java:13-17<br>ErrorCode.java:14 | 서비스 판정, HTTP 409 변환, DB 제약 경로가 각각 따로 검증됩니다. 그런데 "실제 대기 신청이 저장된 회원이 다시 신청하면 409"를 한 번에 지나가는 테스트는 없습니다. `existsPendingByMemberId`가 실제 Oracle에서 **true를 내는 경우**도 결정적으로 단언하는 테스트가 없습니다. 이 메서드는 서비스 테스트에서 목이고(:45,58,69), 통합 :107에서는 false로 덮어씌워집니다. 동시성 테스트에서는 타이밍에 따라 탈 수도 있지만 결과를 단언하지 않습니다. 이 SQL이 늘 false로 깨지더라도 DB 유니크 인덱스가 같은 409를 내므로 지금 동작은 맞습니다. 다만 AC를 그대로 재현하는 경로는 목을 이어 붙여야만 성립합니다 |
| 3. 동시 신청 2개 → 대기 신청은 하나 | ✅ | `should keep one pending application when two applications from the same member arrive at once` (IntegrationTest:137)<br>`should throw CHANNEL_APPLICATION_PENDING when pending check passes but a pending application is already saved` (IntegrationTest:165) | V2__create_streamer_application.sql:12-13 (`CASE WHEN status='PENDING'` 함수 기반 유니크 인덱스)<br>Service.java:28-32 (`DuplicateKeyException` → PENDING) | 동시성 테스트는 래치로 두 스레드를 동시에 출발시킵니다. `result.get()`(:157)이 BusinessException이 아닌 예외를 그대로 터뜨리고, 실제 DB의 PENDING 행 수가 1인지 단언합니다(:161). 실제로 겹쳐 들어간다는 보장은 없지만, :165가 사전 확인을 건너뛰게 해서 Oracle 제약 위반이 PENDING 코드로 바뀌는 경로를 결정적으로 확인합니다. 인덱스가 없으면 :165가 실패합니다. 두 테스트를 합치면 충족입니다. 참고로 AC는 "요청 2개"(HTTP)지만 테스트는 서비스 수준입니다. 각 스레드의 결과(OK 하나, PENDING 하나)는 단언하지 않습니다 |
| 4. 거절된 회원이 다시 신청 → 새 대기 신청 | ✅ | `should save a new pending application and report PENDING when member applies again after rejection` (IntegrationTest:177)<br>`should save a new pending application when member has no pending application after a rejection` (ServiceTest:56) | Mapper.xml:5-8 (INSERT, UPDATE 아님), 10-14, 16-22 (`ORDER BY id DESC`)<br>V2 sql:12-13 (REJECTED 행은 색인에서 빠짐) | 실제 Oracle에 REJECTED 행을 넣고 신청한 뒤 최신 상태가 PENDING인지 단언합니다(:181-185). 구현은 INSERT라서 "새" 행이 생깁니다. 약점: 서비스 테스트 :59의 `findLatestStatusByMemberId` 스텁은 `apply`가 호출하지 않아 의미가 없습니다. 그 테스트는 실질적으로 :43과 같은 테스트입니다. 통합 테스트도 REJECTED 행이 남아 있는지(행 2개)까지는 보지 않습니다 |
| 5. 스트리머·운영자가 신청 → 409 | ✅ | `should throw CHANNEL_APPLICATION_NOT_MEMBER when role is STREAMER` (ServiceTest:78)<br>`should throw CHANNEL_APPLICATION_NOT_MEMBER when role is OPERATOR` (ServiceTest:87)<br>`should return 409 with code CHANNEL_APPLICATION_NOT_MEMBER and its message when service rejects non-member` (ControllerTest:74) | Service.java:22-24<br>Controller.java:25 (`loginMember.role()` 전달)<br>ErrorCode.java:15<br>AuthController.java:39 (로그인 때 DB의 role을 세션에 실음) | 두 역할 모두 구체적인 코드로 단언합니다. 컨트롤러 테스트의 스텁은 `Role.STREAMER` 인자에만 걸려 있습니다. 컨트롤러가 세션의 역할을 넘기지 않으면 목이 null을 돌려 409 단언이 실패하므로, 역할 전달도 검증됩니다. 실제 STREAMER 세션으로 하는 전체 경로 테스트는 없습니다. 승격 API가 #7이라 이 이슈 범위에서는 타당하다고 봅니다 |
| 6. 로그인하지 않은 요청 → 401 | ✅ | `should return 401 with code UNAUTHENTICATED when application is requested without login` (IntegrationTest:189) | SecurityConfig.java:43-44, 50 (`anyRequest().authenticated()` + 진입점이 UNAUTHENTICATED를 씀) | 실제 보안 필터 체인을 거칩니다. 출처 헤더는 허용 출처로 넣어, 403이 아니라 401이 나는 경로를 탑니다. 상태와 `$.code`를 둘 다 단언합니다. issue-6.md에 변이 확인 기록(permitAll로 열자 실패)이 있지만, 저는 다시 돌려 보지 않았습니다 |

### 갭을 메울 시나리오 제안

AC 2 (⚠️):
- [ ] [예외] `StreamerApplicationIntegration` — should return 409 with code CHANNEL_APPLICATION_PENDING when member with a saved pending application applies again with session cookie
- [ ] [경계] `StreamerApplicationIntegration` — should throw CHANNEL_APPLICATION_PENDING without touching the constraint when a pending application is already saved

두 번째는 실제 매퍼로 `apply`를 부르고 PENDING 행 수가 1로 남는지 확인하는 테스트입니다. `existsPendingByMemberId`의 true 경로를 실제 Oracle에서 확인합니다.

### 가짜 테스트

없습니다. 예외 단언은 모두 `isInstanceOf(BusinessException.class).extracting("code").isEqualTo(...)` 형태로 구체적이고, 단언이 없는 테스트도 없습니다. 아래는 가짜는 아니지만 약한 곳입니다.

1. **IntegrationTest.java:130**: POST 결과에 단언이 없습니다. `.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))`를 붙이면 AC 1의 201이 전체 경로에서 확인됩니다.
2. **ServiceTest.java:59**: `findLatestStatusByMemberId` 스텁은 `apply`가 쓰지 않아 "거절 후" 조건을 표현하지 못합니다. 지우든지, 이 시나리오는 통합 테스트 :177에 맡긴다고 정리하면 됩니다.
3. **IntegrationTest.java:156-158**: 각 스레드 결과를 버립니다. 반환값을 모아 `containsExactlyInAnyOrder("OK", "CHANNEL_APPLICATION_PENDING")`로 단언하면, 진 쪽이 409로 끝났다는 것까지 확인됩니다.
4. **ControllerTest.java:57, 100**: `$.status`는 목이 돌려준 값을 그대로 확인하는 것이라 직렬화 확인 이상의 의미는 없습니다. 상태 코드(201·409)는 실제 컨트롤러·핸들러가 만드는 값이라 유효합니다.

### 확인하지 않은 것

- 전체 테스트 80개는 돌리지 않았습니다. channel 패키지 17개만 돌렸습니다.
- 프론트(`frontend/src/pages/MyPage.tsx` 등)는 읽지도 실행하지도 않았습니다. 화면 동작은 issue-6.md "확인 결과"의 수동 기록뿐입니다. 이슈 본문의 "내 정보 화면에 신청 버튼과 상태"는 AC 체크박스가 아니라 판정에서 뺐습니다.
- 401·403 변이 확인은 문서 기록만 봤고 재현하지 않았습니다.

### 실행·읽은 것

1. `cat .claude/harness.json && git log --oneline -6 && gh issue view 6` → 설정 있음(scenariosDir=docs/result/scenarios), AC 6개 확인
2. `cat docs/result/scenarios/issue-6.md && git diff main...HEAD --stat` → 시나리오 17개와 AC 대조표 확인, 변경 파일 17개
3. `cat -n` 대상: `backend/src/main/java/com/livestream/channel/*.java`, V2 마이그레이션, `StreamerApplicationMapper.xml` → 구현 줄 번호 확인
4. `ls` + `cat -n` 대상: `backend/src/test/java/com/livestream/channel/*.java` → 테스트 파일 3개(Controller·Integration·Service)의 본문과 단언 확인
5. `cat -n ErrorCode.java Role.java LoginMember.java` + `grep` (BusinessException·UNAUTHENTICATED·authenticated()·permitAll) → ErrorCode :14-15 확인, SecurityConfig :43-50 확인
6. `cat -n GlobalExceptionHandler.java`, `grep "LoginMember("`, `grep` (PRD 신청 관련) → 409 변환 :13-17, 세션 역할 출처 AuthController :39, PRD :104 "같은 회원의 대기 신청은 하나만(DB 제약)"
7. `./gradlew test --tests "com.livestream.channel.*"` (JAVA_HOME=liberica-21.0.7) → BUILD SUCCESSFUL
8. 결과 XML 요약 grep → 스위트 7개, testcase 17개, failures·errors·skipped 0
9. Grep `existsPendingByMemberId|isCreated|isConflict` (channel 테스트) → 201·409 단언은 ControllerTest :56,68,81에만 있음, `existsPendingByMemberId`는 목·덮어쓰기에서만 쓰임
10. Grep `streamer-applications|StreamerApplication` (backend/src/test 전체) → 위 3개 파일 말고는 없음

### 관련 파일 (절대 경로)

- C:\live-streaming-project\.claude\worktrees\non-overlapping-manual-approval-issues-5017ff\docs\result\scenarios\issue-6.md
- C:\live-streaming-project\.claude\worktrees\non-overlapping-manual-approval-issues-5017ff\backend\src\main\java\com\livestream\channel\StreamerApplicationService.java
- C:\live-streaming-project\.claude\worktrees\non-overlapping-manual-approval-issues-5017ff\backend\src\main\java\com\livestream\channel\StreamerApplicationController.java
- C:\live-streaming-project\.claude\worktrees\non-overlapping-manual-approval-issues-5017ff\backend\src\main\resources\mapper\channel\StreamerApplicationMapper.xml
- C:\live-streaming-project\.claude\worktrees\non-overlapping-manual-approval-issues-5017ff\backend\src\main\resources\db\migration\V2__create_streamer_application.sql
- C:\live-streaming-project\.claude\worktrees\non-overlapping-manual-approval-issues-5017ff\backend\src\test\java\com\livestream\channel\StreamerApplicationServiceTest.java
- C:\live-streaming-project\.claude\worktrees\non-overlapping-manual-approval-issues-5017ff\backend\src\test\java\com\livestream\channel\StreamerApplicationControllerTest.java
- C:\live-streaming-project\.claude\worktrees\non-overlapping-manual-approval-issues-5017ff\backend\src\test\java\com\livestream\channel\StreamerApplicationIntegrationTest.java

### 구현자 이견

- **AC 2 ⚠️ — 갭을 메운다(사용자 결정).** 시나리오 2개를 추가했다. 검증자의 두 번째 제안("제약을 건드리지 않고 PENDING, 대기 행 1개")은 그대로 옮기지 않았다. `existsPendingByMemberId` SQL이 늘 false로 깨져도 DB 유니크 인덱스가 같은 코드·같은 행 수를 내므로 그 테스트는 통과해 버린다. 대신 대기 신청을 저장한 뒤 확인 쿼리가 true를 내는지 직접 본다(`should report a pending application from the pending check when one is saved`).
- **약한 곳 1·3** — 통합 테스트의 신청 POST에 201 단언을, 동시 신청 테스트에 스레드별 결과(`OK` 하나, `CHANNEL_APPLICATION_PENDING` 하나) 단언을 더한다.
- **약한 곳 2** — 서비스 테스트의 쓰이지 않는 `findLatestStatusByMemberId` 스텁을 뺀다. 이 테스트는 서비스 수준에서 "대기 신청 없음이면 저장"만 보고, "거절 후 재신청"의 실제 의미는 통합 테스트(`should save a new pending application and report PENDING when member applies again after rejection`)가 진짜 DB로 본다.
- **갭 테스트 변이 확인** — 추가한 테스트는 이미 있는 구현을 검사하므로 처음부터 통과한다. 대기 확인 SQL을 `AND 1 = 0`(늘 없음)으로 망가뜨리자 `should report a pending application from the pending check when one is saved`만 실패했다(통합 8개 중 1개). HTTP 409 테스트는 DB 제약이 같은 409를 내서 통과한다 — AC 2의 동작을 보는 테스트이므로 의도대로다. 되돌린 뒤 전체 82개 통과.
- **약한 곳 4** — 그대로 둔다. 컨트롤러 테스트의 `$.status`는 직렬화 확인이고, 같은 값을 통합 테스트가 진짜 경로로 본다.
