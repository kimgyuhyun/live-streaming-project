# 이슈 #2 — 로그인·로그아웃하고 내 정보를 본다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/2
> PRD: `docs/result/prd/member.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

---

## 시그니처

### 관련 ADR

- **계정 M1** — 권한 검사·세션 저장은 Spring Security(Spring의 인증·권한 프레임워크)에 맡기고, 로그인은 컨트롤러 → 서비스에서 직접 한다. 순서: 비밀번호 비교 → 세션 ID 교체 → `SecurityContextRepository`(인증 정보를 세션에 저장하는 Spring Security 부품)로 저장. 시도 횟수 세기는 #3에서 비교 앞에 끼운다.
- **계정 M2** — Spring Session(세션을 Redis 같은 외부 저장소에 두는 라이브러리)을 색인 저장소(`repository-type: indexed`)로 쓴다. 세션 색인 이름(principal name)이 회원 id가 되게 해서 #4가 "이 회원의 세션 전부"를 찾을 수 있게 한다.
- **기획서 결정(세션 + 쿠키, 위조 요청 방어)** — 쿠키는 `HttpOnly`·`SameSite=Lax`, 유휴 7일. 로그인·로그아웃도 상태 변경 요청이므로 출처 검증을 받는다.
- **계정 PRD 작은 결정** — 없는 이메일로 로그인하면 가짜 해시로 비밀번호 비교를 한 번 한다(응답 시간으로 이메일 존재가 드러나지 않게).

### 설정

- `build.gradle.kts`: `org.springframework.session:spring-session-data-redis` 추가 (버전은 Spring Boot가 관리)
- `application.yml`: `spring.session.redis.repository-type: indexed`, `spring.session.timeout: 7d`, `server.servlet.session.cookie.http-only: true`, `server.servlet.session.cookie.same-site: lax`

### 타입

```java
// com.livestream.member
public record LoginRequest(
        @NotBlank(message = "이메일을 입력해 주세요") String email,
        @NotBlank(message = "비밀번호를 입력해 주세요")
        @Size(max = 64) @Pattern(regexp = "^[!-~]*$") String password) {}
        // 64자를 넘거나 ASCII 밖 문자가 오면 BCrypt 72바이트 한도에 걸려 500이 나므로 400으로 막는다

// 세션에 싣는 사람 정보. getName() = 회원 id 문자열 → 세션 색인 키
public record LoginMember(long id, Role role) implements AuthenticatedPrincipal, Serializable {}
```

```java
// com.livestream.member
@RestController @RequestMapping("/api/auth")
class AuthController {
    // 200 + MemberResponse. 세션이 있으면 changeSessionId() → ROLE_{role} 권한의 인증 객체 생성
    // → securityContextRepository.saveContext()
    @PostMapping("/login")
    MemberResponse login(@Valid @RequestBody LoginRequest request,
                         HttpServletRequest httpRequest, HttpServletResponse httpResponse);
}

class MemberController {   // 기존에 추가
    @GetMapping("/me")     // 200 + MemberResponse
    MemberResponse me(@AuthenticationPrincipal LoginMember loginMember);
}

public class MemberService {
    // 이메일을 소문자로 바꿔 찾는다. 없는 이메일이면 가짜 해시와 한 번 비교한 뒤 MEMBER_LOGIN_FAILED
    public MemberResponse login(LoginRequest request);
    // DB에서 읽는다 (세션 사본이 아니라 — #4 닉네임 변경이 바로 보이게)
    public MemberResponse findMe(long memberId);
}

public interface MemberMapper {
    Optional<Member> findById(long id);   // 추가
}
```

```java
// com.livestream.common.error — 에러 본문을 만드는 곳을 한 곳으로 모은다 (#1 보안 점검에서 미룸)
public enum ErrorCode {
    // 추가
    UNAUTHENTICATED(401, "로그인이 필요합니다"),
    MEMBER_LOGIN_FAILED(401, "이메일 또는 비밀번호가 맞지 않습니다");
}
public record ErrorResponse(String code, String message) {
    public static ErrorResponse of(ErrorCode code);   // 핸들러·필터·진입점이 모두 이것으로 본문을 만든다
}
public final class ErrorResponseWriter {               // 필터 단계용. Jackson으로 직렬화한다
    public static void write(HttpServletResponse response, ErrorCode code) throws IOException;
}
class GlobalExceptionHandler {
    // 깨진 JSON → 400 INVALID_INPUT, 메시지는 기본 메시지 (#1 보안 점검에서 미룸)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e);
}
```

```java
// com.livestream.common.security
class OriginValidationFilter { ... }   // 403 본문을 손으로 이어 붙이던 문자열 대신 ErrorResponseWriter.write

class SecurityConfig {
    // - POST /api/auth/login 허용
    // - logout: POST /api/auth/logout → 204 (Spring Security 로그아웃 필터, 세션이 없어도 204)
    // - 인증되지 않은 요청 → AuthenticationEntryPoint가 ErrorResponseWriter로 401 UNAUTHENTICATED
    // - requestCache 끔 (401마다 빈 세션이 생기지 않게)
    @Bean SecurityContextRepository securityContextRepository();   // HttpSession 기반. AuthController와 같은 인스턴스
}
```

### 에러 케이스

| 상황 | 에러 코드 | HTTP |
| --- | --- | --- |
| 비밀번호가 틀림, 또는 없는 이메일 — 메시지는 둘 다 "이메일 또는 비밀번호가 맞지 않습니다" | `MEMBER_LOGIN_FAILED` | 401 |
| 로그인 없이 보호된 API 호출 — "로그인이 필요합니다" | `UNAUTHENTICATED` | 401 |
| 로그인 입력이 비었음, 비밀번호가 64자 초과·ASCII 밖 문자 | `INVALID_INPUT` | 400 |
| 요청 본문이 깨진 JSON | `INVALID_INPUT` | 400 |

### 화면

- `src/api/auth.ts` — `login(req: LoginRequest): Promise<MemberResponse>`, `logout(): Promise<void>`
- `src/api/members.ts` — `fetchMe(): Promise<MemberResponse>` 추가
- `/login` 화면(props 없는 페이지) — 성공하면 `['me']` 쿼리를 무효화하고 `/`로 이동, 실패하면 서버 메시지
- `RootLayout` 머리글 — 로그인했으면 닉네임 + 로그아웃 버튼, 아니면 로그인·가입 링크

### 판단이 갈린 곳

1. 로그아웃은 Spring Security 로그아웃 설정으로 한다. 세션이 없어도 204 — 세션이 이미 만료된 화면에서 눌러도 에러가 나지 않는다. 출처 검증은 그대로 받는다.
2. `/me`는 세션 사본이 아니라 DB에서 읽는다. 세션 사본이면 #4에서 닉네임을 바꿔도 머리글에 예전 닉네임이 남는다.
3. requestCache를 끈다. 켜 두면 비로그인 요청이 401을 받을 때마다 Redis에 빈 세션이 생긴다.
4. `ErrorResponseWriter`는 정적 메서드다. 빈으로 만들면 `OriginValidationFilter` 생성자가 바뀌어 기존 테스트까지 고쳐야 한다.
5. 쿠키 `Secure` 플래그는 배포 때 켠다. 로컬은 http다.

### 이 이슈에서 만들지 않는 것

- 로그인 시도 제한, 실제 IP 설정(`forward-headers-strategy`) — #3
- 닉네임·비밀번호 변경, 다른 기기 로그아웃 — #4
- `SignUpRequest`의 영어 기본 메시지(`@Email`·`@NotBlank`·`@Size`) 한글화 — #1 보안 점검 🟡. 이번 요청 범위 밖

---

## 테스트 시나리오

### 정상

- [x] [정상] `MemberService.login` — should return member when password matches
- [x] [정상] `MemberService.login` — should find member by lower-cased email when email contains upper case letters
- [x] [정상] `MemberService.findMe` — should return email, nickname and role when member exists
- [x] [정상] `POST /api/auth/login` — should return 200 with email, nickname and role when service accepts login
- [x] [정상] `GET /api/members/me` — should return 200 with email, nickname and role of logged-in member
- [x] [정상] `MemberLoginIntegration` — should set HttpOnly and SameSite=Lax session cookie when login succeeds
- [x] [정상] `MemberLoginIntegration` — should return email, nickname and role from me with the session cookie after login
- [x] [정상] `MemberLoginIntegration` — should log in with a@B.COM when member signed up with A@b.com
- [x] [정상] `MemberLoginIntegration` — should store session in redis with 7 days max inactive interval when login succeeds
- [x] [정상] `MemberLoginIntegration` — should find the session by member id in session index when login succeeds
- [x] [정상] `MemberLoginIntegration` — should return 401 from me with the same cookie when member logs out

### 경계

- [x] [경계] `MemberLoginIntegration` — should issue a session id different from the one sent before login when login succeeds
- [x] [경계] `MemberLoginIntegration` — should return 204 when logout is requested without a session
- [x] [경계] `POST /api/auth/login` — should return 400 when password is 65 characters
- [x] [경계] `POST /api/auth/login` — should return 400 when password contains non-ascii characters

### 예외

- [x] [예외] `MemberService.login` — should throw MEMBER_LOGIN_FAILED when password does not match
- [x] [예외] `MemberService.login` — should throw MEMBER_LOGIN_FAILED when email does not exist
- [x] [예외] `MemberService.login` — should compare password against a dummy hash once when email does not exist
- [x] [예외] `MemberService.login` — should compare against a bcrypt hash when email does not exist
- [x] [예외] `POST /api/auth/login` — should return 401 with code MEMBER_LOGIN_FAILED and its message when service rejects login
- [x] [예외] `POST /api/auth/login` — should return 400 with code INVALID_INPUT when email is blank
- [x] [예외] `POST /api/members` — should return 400 with code INVALID_INPUT when request body is malformed json
- [x] [예외] `MemberLoginIntegration` — should return the same 401 code and message for wrong password and unknown email
- [x] [예외] `MemberLoginIntegration` — should return 401 with code UNAUTHENTICATED when me is requested without login
- [x] [예외] `MemberLoginIntegration` — should return 403 when login request comes from another origin

### 수동 확인 (테스트 도구가 없는 화면 — 시나리오 수에 넣지 않는다)

프론트 테스트 도구(Vitest)는 #12에서 넣는다. 아래는 Green 단계에서 브라우저로 확인하고 결과를 이 문서와 PR에 남긴다.

- 로그인에 성공하면 `/`로 이동하고 머리글에 닉네임이 보인다
- 새로고침해도 머리글에 닉네임이 남아 있다
- 틀린 비밀번호로 로그인하면 "이메일 또는 비밀번호가 맞지 않습니다"가 화면에 보인다
- 로그아웃하면 머리글이 로그인·가입 링크로 바뀐다

**확인 결과 (2026-11-20, Green):** 로컬에서 Oracle·Redis 컨테이너 + `bootRun` + Vite 개발 서버(출처 `http://localhost:5173`)로 브라우저에서 확인했다. `Login2Check@Example.com`으로 새로 가입한 계정을 소문자 이메일로 로그인했다.

- 틀린 비밀번호: `POST /api/auth/login` 401 → 화면에 "이메일 또는 비밀번호가 맞지 않습니다" 표시, `/login`에 머묾 ✅
- 로그인 성공: 200 → `/`로 이동, 머리글에 닉네임 `수동확인2`와 로그아웃 버튼 ✅
- 새로고침: `GET /api/members/me` 200, 머리글에 닉네임 유지 ✅
- 로그아웃: `POST /api/auth/logout` 204 → `GET /api/members/me` 401 → 머리글이 로그인·가입 링크로 바뀜 ✅

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 맞는 비밀번호로 로그인하면 200, `HttpOnly`·`SameSite=Lax` 세션 쿠키, 그 쿠키로 내 정보 조회 시 이메일·닉네임·역할 | `[정상] MemberService.login — should return member when password matches`<br>`[정상] POST /api/auth/login — should return 200 with email, nickname and role when service accepts login`<br>`[정상] MemberLoginIntegration — should set HttpOnly and SameSite=Lax session cookie when login succeeds`<br>`[정상] MemberLoginIntegration — should return email, nickname and role from me with the session cookie after login`<br>`[정상] MemberService.findMe — should return email, nickname and role when member exists`<br>`[정상] GET /api/members/me — should return 200 with email, nickname and role of logged-in member` |
| 2. 로그인 전 세션 ID와 로그인 후 세션 ID가 다르다 | `[경계] MemberLoginIntegration — should issue a session id different from the one sent before login when login succeeds` |
| 3. 틀린 비밀번호·없는 이메일 둘 다 401, 같은 메시지 | `[예외] MemberService.login — should throw MEMBER_LOGIN_FAILED when password does not match`<br>`[예외] MemberService.login — should throw MEMBER_LOGIN_FAILED when email does not exist`<br>`[예외] POST /api/auth/login — should return 401 with code MEMBER_LOGIN_FAILED and its message when service rejects login`<br>`[예외] MemberLoginIntegration — should return the same 401 code and message for wrong password and unknown email` |
| 4. 없는 이메일이면 비밀번호 비교(가짜 해시)가 한 번 | `[예외] MemberService.login — should compare password against a dummy hash once when email does not exist`<br>`[예외] MemberService.login — should compare against a bcrypt hash when email does not exist` (AC 검증 갭으로 추가) |
| 5. `A@b.com` 가입 → `a@B.COM` 로그인 성공 | `[정상] MemberService.login — should find member by lower-cased email when email contains upper case letters`<br>`[정상] MemberLoginIntegration — should log in with a@B.COM when member signed up with A@b.com` |
| 6. 로그아웃 후 같은 쿠키로 내 정보 조회 시 401 | `[정상] MemberLoginIntegration — should return 401 from me with the same cookie when member logs out` |
| 7. 세션이 Redis에 있고 유휴 만료 7일 | `[정상] MemberLoginIntegration — should store session in redis with 7 days max inactive interval when login succeeds`<br>`[정상] MemberLoginIntegration — should find the session by member id in session index when login succeeds` |
| 8. 비로그인 요청으로 내 정보 조회 시 401 | `[예외] MemberLoginIntegration — should return 401 with code UNAUTHENTICATED when me is requested without login` |

**AC에 없는데 넣은 시나리오**

| 시나리오 | 이유 |
| --- | --- |
| `should find the session by member id in session index` | 이슈 본문의 "색인 저장소"와 M2. #4가 이 색인에 기댄다. AC 7에 묶었다 |
| `should return 204 when logout is requested without a session` | 판단 1(만료된 화면에서 로그아웃)의 동작을 박는다 |
| 로그인 비밀번호 65자·비ASCII → 400 | 검사가 없으면 BCrypt 72바이트 한도로 500이 난다(#1에서 가입 쪽에 실제로 났다) |
| `should return 400 with code INVALID_INPUT when email is blank` | 로그인 입력 검증의 기본 경로 |
| `POST /api/members — should return 400 with code INVALID_INPUT when request body is malformed json` | #1 보안 점검에서 미룬 항목(사용자 요청) |
| `should return 403 when login request comes from another origin` | 로그인도 상태 변경 요청이라 출처 검증을 받는다는 기획서 결정을 로그인 경로에서 확인 |

**커버리지:** AC 8개 / 시나리오 25개 / 미커버 0개 (AC 검증 후 1개 추가)

---

## AC 검증

> `harness:ac-verifier` (2026-11-20, HEAD 11d3097). 아래는 검증자 출력 원문이다. 구현자는 맨 아래 `### 구현자 이견`에만 쓴다.

브랜치 `feat/member/issue-2`(HEAD 11d3097) 기준입니다. 코드와 테스트는 고치지 않았습니다. 변이 검사 2건은 저장소가 아니라 scratchpad에 `git archive`로 꺼낸 사본에서 돌렸습니다.

**요약: AC 8개 중 ✅ 7 / ⚠️ 1 / ❌ 0.** 부족한 곳은 AC 4의 단언입니다. 가짜 해시를 빈 문자열로 바꿔도 테스트가 통과합니다(아래 변이 검사 2에서 실제로 확인).

### 판정표

| AC | 판정 | 근거 테스트 | 구현 | 설명 |
|---|---|---|---|---|
| 1. 맞는 비밀번호로 로그인하면 200, `HttpOnly`·`SameSite=Lax` 쿠키, 그 쿠키로 내 정보 조회 시 이메일·닉네임·역할 | ✅ | MemberLoginIntegrationTest.java:105(쿠키), :118(me), :76(200 단언) / AuthControllerTest.java:55 / MemberControllerTest.java:240 / MemberServiceTest.java:138, :190 | AuthController.java:31-44, MemberController.java:28-31, MemberService.java:47-59, application.yml:18-28 | 진짜 필터와 Redis를 거쳐 `Set-Cookie`의 `SESSION=`에 `HttpOnly`와 `SameSite=Lax`가 함께 붙는지 본다. 같은 쿠키로 `/me`를 부르면 email·nickname·role이 나오는 것도 단언한다. `/me`는 DB에서 읽는다(MemberService.java:57-59) |
| 2. 로그인 전 세션 ID와 로그인 후 세션 ID가 다르다 | ✅ | MemberLoginIntegrationTest.java:178-187 | AuthController.java:35-36 | 저장소에 미리 만든 세션을 Base64 쿠키로 보내고, 응답 세션 ID가 null이 아니고 다른지 본다. 변이 검사 1에서 `changeSessionId()`를 빼면 :186에서 실패했다. 테스트가 헛돌지 않는다 |
| 3. 틀린 비밀번호와 없는 이메일 모두 401, 같은 메시지 | ✅ | MemberServiceTest.java:155, :164 / AuthControllerTest.java:84 / MemberLoginIntegrationTest.java:197-208 | MemberService.java:48-53, ErrorCode.java:11, GlobalExceptionHandler.java:13-17 | 서비스는 두 경우 모두 `MEMBER_LOGIN_FAILED`를 던진다. 컨트롤러 테스트는 그 코드가 401과 정확한 문구로 나가는지 본다. 통합 테스트는 두 경우의 401 본문이 같은지 본다. 셋을 합치면 사슬이 닫힌다. 다만 통합 테스트 혼자서는 문구 자체를 단언하지 않는다(:207은 본문끼리 같은지만 봄) |
| 4. 없는 이메일이면 가짜 해시로 비밀번호 비교가 한 번 | ⚠️ | MemberServiceTest.java:174-182 | MemberService.java:18, :23, :49-50 | 구현은 맞다. `encode(UUID)`로 만든 진짜 BCrypt 해시와 한 번 비교한다. 그런데 단언이 `matches(eq(PASSWORD), anyString())`라서 해시가 무엇이든 통과한다. 변이 검사 2에서 `dummyHash = ""`로 바꿔도 login 5개가 전부 통과했다. 이때 BCrypt는 "Empty encoded password" 경고만 내고 해시 계산 없이 바로 false를 돌려준다. 이 AC가 있는 이유(응답 시간을 같게 하기)를 테스트가 지키지 못한다 |
| 5. `A@b.com`으로 가입하고 `a@B.COM`으로 로그인 | ✅ | MemberServiceTest.java:147 / MemberLoginIntegrationTest.java:132-138 | MemberService.java:48, MemberMapper.xml:18-22 | 통합 테스트는 `CASE123456@b.com`으로 가입하고 `case123456@B.COM`으로 로그인해 200을 받는다. AC와 같은 모양이다. 단위 테스트의 목은 소문자 이메일로 찾을 때만 회원을 돌려준다 |
| 6. 로그아웃 후 같은 쿠키로 내 정보 조회 시 401 | ✅ | MemberLoginIntegrationTest.java:166-175 | SecurityConfig.java:39-42 | AC 그대로 검사한다. 로그아웃 응답 상태(:171)는 단언하지 않는다. 로그아웃 전에 `/me`가 200인지 확인하는 사전 조건도 없다. 쿠키가 유효하다는 것은 :118 테스트가 따로 보장한다 |
| 7. 세션이 Redis에 있고 유휴 만료가 7일 | ✅ | MemberLoginIntegrationTest.java:141-149, :152-163(색인) | application.yml:18-21, build.gradle.kts(`spring-session-data-redis`) | `sessionRepository.findById(id).getMaxInactiveInterval()`이 정확히 `Duration.ofDays(7)`인지 본다. 설정값이라 정확히 같은지 보는 것으로 6일이나 8일 같은 값은 걸러진다. 저장소가 Redis라는 것은 classpath에 세션 저장소가 `spring-session-data-redis`뿐이고 Testcontainers Redis가 떠 있다는 데서 나온 추론이다. 테스트가 Redis 타입이나 키를 직접 단언하지는 않는다 |
| 8. 로그인하지 않고 내 정보 조회 시 401 | ✅ | MemberLoginIntegrationTest.java:211-216 | SecurityConfig.java:43-44, :50 | 401과 `code=UNAUTHENTICATED`를 단언한다. 메시지는 단언하지 않는다 |

### 추가 시나리오 제안 (issue-2.md에 그대로 붙일 수 있는 형태)

AC 4 (⚠️):
- [ ] [예외] `MemberService.login` — should compare against a bcrypt hash when email does not exist
  - 구현 방법: `ArgumentCaptor<String>`로 `matches`의 두 번째 인자를 잡아 `$2a$` 접두사(또는 `BCryptPasswordEncoder` 정규식)를 단언한다. 아니면 :181의 `anyString()`을 `argThat(h -> h.startsWith("$2"))`로 바꾼다.

AC 4의 테스트를 고치는 것과 별개로, 판정을 ✅로 올리지는 않는 선택적 보강:
- [ ] [예외] `MemberLoginIntegration` — should return 401 with message "이메일 또는 비밀번호가 맞지 않습니다" for unknown email (:207의 같은지 비교에 문구 단언을 더한다)
- [ ] [정상] `MemberLoginIntegration` — should return 204 from logout and 200 from me before logout with the same cookie (AC 6의 사전 조건)
- [ ] [정상] `MemberLoginIntegration` — should store session in RedisIndexedSessionRepository when login succeeds (AC 7의 "Redis"를 직접 단언)

### 가짜 테스트와 약한 단언

1. **MemberServiceTest.java:181** — `verify(encoder, times(1)).matches(eq(PASSWORD), anyString())`. 해시 내용을 보지 않는다. 변이 검사로 실제 구멍임을 확인했다. 고치는 방법은 위 제안과 같다.
2. **MemberLoginIntegrationTest.java:171** — 로그아웃 `perform`의 결과를 단언하지 않는다. 지금은 마지막 401 단언이 로그아웃 성공에 기대므로 가짜 테스트는 아니다. `.andExpect(status().isNoContent())`를 붙이면 실패했을 때 원인이 드러난다.
3. **MemberLoginIntegrationTest.java:207** — 두 본문이 같은지만 본다. 둘 다 엉뚱한 같은 본문이어도 통과한다. 서비스 단위 테스트가 보완하지만, 통합 경로에서 코드와 메시지를 직접 단언하면 더 단단하다.
4. `assertThrows(Exception.class…)`나 `isInstanceOf(Exception/RuntimeException.class)`는 없었다(grep 0건). `catchThrowable`은 MemberServiceTest.java:179 한 곳뿐이다. 거기서는 호출 횟수만 보고 예외 종류는 :164 테스트가 따로 본다.

### 범위 밖 관찰 (#1에서 미룬 두 가지)

- **에러 본문을 만드는 곳을 한 곳으로 모으기: 반영됐다.**
  - `ErrorResponse.of`(ErrorResponse.java:6-8)를 GlobalExceptionHandler.java:16, :33이 쓴다.
  - `ErrorResponseWriter.write`(ErrorResponseWriter.java:17-22, 내부에서 `ErrorResponse.of`)를 OriginValidationFilter.java:35와 401 진입점(SecurityConfig.java:43-44)이 쓴다.
  - 예외가 하나 있다. GlobalExceptionHandler.java:27의 `handleInvalid`는 필드별 메시지를 넣어야 해서 `new ErrorResponse(...)`를 직접 만든다. 본문 모양은 같은 record다.
  - `ErrorResponseWriter`는 Spring 빈이 아니라 자체 `new ObjectMapper()`를 쓴다(:12). 본문이 문자열 두 개라 지금은 차이가 없다.
  - 이 경로에 걸린 테스트: OriginValidationFilterTest.java:83(본문에 `FORBIDDEN_ORIGIN`), MemberLoginIntegrationTest.java:215(`UNAUTHENTICATED`), :226(`FORBIDDEN_ORIGIN`). 이 테스트들은 `message`, Content-Type, UTF-8 한글 인코딩을 단언하지 않는다.
- **깨진 JSON의 400을 `INVALID_INPUT`으로 맞추기: 반영됐다.**
  - 구현은 GlobalExceptionHandler.java:31-34, 테스트는 MemberControllerTest.java:225-232(400, `code=INVALID_INPUT`).
  - 테스트는 메시지가 기본 문구("입력값이 올바르지 않습니다")인지, 즉 파싱 오류 내용이 새지 않는지는 단언하지 않는다. 제안: `- [ ] [예외] POST /api/members — should return default INVALID_INPUT message when request body is malformed json`

### 화면 (AC 밖, 읽기만 함)

다음 파일을 읽었고, 시그니처 절과 맞았다.
- frontend/src/api/auth.ts: `login`, `logout`
- frontend/src/api/members.ts: `fetchMe`
- frontend/src/pages/LoginPage.tsx: 성공하면 `['me']` 무효화 후 `/`로 이동, 실패하면 `errorMessage` 표시
- frontend/src/layouts/RootLayout.tsx: 닉네임과 로그아웃 버튼, 아니면 로그인·가입 링크
- frontend/src/router.tsx:14: `/login` 경로

frontend/src/api/client.ts:22-31은 204의 빈 본문을 null로 처리한다. 프론트는 실행하지 않았다. 브라우저 확인 결과는 issue-2.md:175-180의 기록에만 기대고 있다(검증하지 않음).

### 테스트 실행 근거

- **기존 결과 XML:** backend/build/test-results/test에 있는 결과를 더하면 62개이고 실패와 에러는 0이다. 다만 타임스탬프(08:31Z, 즉 17:31 KST)가 커밋 11d3097(17:34 KST)보다 3분 이르다. 이 결과가 HEAD 코드로 돌린 것인지는 확인하지 못했다.
- **변이 검사 1:** HEAD 사본에서 `changeSessionId()` 한 줄만 주석 처리하고 MemberLoginIntegrationTest를 돌렸다. 11개 중 AC 2 테스트 1개만 실패했다(:186 AssertionError). 나머지 10개는 HEAD 코드에 그 한 줄만 뺀 상태에서 통과했다.
- **변이 검사 2:** 사본에서 `dummyHash = ""`로 바꾸고 MemberServiceTest를 돌렸다. 12개 전부 통과했다(login 5개 포함). BUILD SUCCESSFUL.
- 원본 브랜치에서 전체 `./gradlew test`는 다시 돌리지 않았다.

### 구현자 이견

- **테스트 실행 근거의 타임스탬프:** 이견이 아니라 사실 보충이다. 17:31 KST 실행은 `/me` 컨트롤러 테스트를 고친 직후의 전체 실행이다. 그 뒤 커밋(17:34)까지 바뀐 것은 `frontend/src`의 4개 파일과 이 문서뿐이고, `backend/`는 바뀌지 않았다. 다만 검증자가 확인할 수 없었다는 지적은 맞다. 갭을 메운 뒤에는 HEAD에서 전체를 다시 돌려 기록한다.
- 판정에는 이견이 없다. AC 4의 ⚠️에 동의한다.

### 갭 처리 (사용자 결정, 2026-11-20)

- **AC 4 ⚠️ → 갭을 메운다.** 시나리오 `[예외] MemberService.login — should compare against a bcrypt hash when email does not exist`를 추가하고 Red → Green을 다시 돈다.
- 검증자의 선택적 보강 4개(통합 경로의 문구 단언, 로그아웃 204·사전 조건, Redis 저장소 타입 단언, 깨진 JSON 기본 메시지)는 넣지 않는다.

### 2차 검증

> `harness:ac-verifier` (2026-11-20, HEAD 21bd952). 검증자 출력 원문.

AC 4는 이제 ✅입니다. 전체 결과는 **AC 8개 중 ✅ 8 / ⚠️ 0 / ❌ 0**입니다. HEAD에서 전체 테스트를 직접 돌려 63개가 모두 통과했고, `dummyHash = ""` 변이에서는 새 테스트 1개만 실패하는 것을 제가 직접 확인했습니다. 코드·테스트·문서는 고치지 않았고, 테스트는 전부 scratchpad에 `git archive`로 꺼낸 사본에서 돌렸습니다.

#### 바뀐 것 확인
- `git diff --stat 11d3097..HEAD`: 바뀐 파일은 `backend/src/test/java/com/livestream/member/MemberServiceTest.java` 하나, 14줄 추가가 전부입니다. 구현(`MemberService` 포함)은 그대로입니다.
- 작업 트리에서 `docs/result/scenarios/issue-2.md`가 **커밋되지 않은 수정(M)** 상태입니다. 바뀐 내용은 시나리오 1줄, AC 대조표, 커버리지 25개, "## AC 검증"·"### 구현자 이견"·"### 갭 처리" 절입니다. 커밋할지는 그쪽에서 정하시면 됩니다.

#### AC 4 — 없는 이메일로 로그인하면 가짜 해시와 비밀번호 비교가 한 번 일어난다

✅ 충족
- **테스트 1 (횟수):** `should compare password against a dummy hash once when email does not exist` (MemberServiceTest.java:174-182). 비교가 정확히 한 번 일어나는지 봅니다.
- **테스트 2 (새로 추가, 해시 형식):** `should compare against a bcrypt hash when email does not exist` (MemberServiceTest.java:185-196).
  - `ArgumentCaptor`로 `matches`에 넘어간 두 번째 인자를 잡습니다(:193).
  - 그 값이 `\$2[aby]?\$\d{2}\$[./0-9A-Za-z]{53}`와 전체 일치하는지 단언합니다(:195). AssertJ `matches`는 전체 일치로 비교합니다.
  - `verify(encoder)`의 기본값이 `times(1)`이라 이 테스트도 한 번 호출을 함께 확인합니다.
- **구현:** MemberService.java:18, :23(`passwordEncoder.encode(UUID.randomUUID().toString())`), :49-50. 바뀌지 않았습니다.
- **근거 (변이 검사, 직접 실행):**
  - 21bd952 사본에서 :23을 `this.dummyHash = "";`로 바꾸고 `--tests MemberServiceTest`를 돌렸습니다.
  - 결과는 13개 중 새 테스트 1개만 실패했습니다(`MemberServiceTest.java:195`). 실패 메시지는 `Expecting actual: "" to match pattern: "\$2[aby]?\$\d{2}\$[./0-9A-Za-z]{53}"`입니다.
  - 1차에서 지적한 구멍(빈 해시로 비교 비용이 사라져도 통과하던 것)이 막혔습니다.
- **남은 사소한 점 (판정에는 영향 없음):** 정규식의 `\d{2}`는 비용 인자를 고정하지 않습니다. 그래서 비용 04짜리 상수 해시를 넣어도 통과합니다. 지금 구현은 실제 회원 해시와 같은 `PasswordEncoder`로 가짜 해시를 만들기 때문에 비용이 같아서 문제가 되지 않습니다. 사용자가 선택적 보강을 넣지 않기로 했으므로 제안하지 않습니다.

#### 다른 AC (1~3, 5~8)
- 1차 판정 ✅를 유지합니다.
- 그 뒤 바뀐 것은 위 테스트 파일 하나뿐이고, 판정 근거가 된 파일과 줄 번호도 그대로입니다. 줄이 추가된 곳(:183 이후)보다 뒤에 있는 것은 `findMe` 테스트 하나뿐이라, 1차에 적은 MemberServiceTest.java:190이 이제 :204로 밀렸습니다.
- 이번 전체 실행에서 해당 테스트들이 모두 통과했습니다.

#### 실행 근거 (HEAD 기준, 직접 실행)
- 21bd952 사본(수정 없음)에서 전체 `./gradlew test`를 돌렸습니다. **BUILD SUCCESSFUL in 1m 51s**, 17:49 KST 실행입니다.
- 결과 XML을 더하면 1+6+5+1+20+11+1+6+6+6 = **63개**이고, 실패·에러·스킵은 모두 0입니다.
- `MemberServiceTest$Login`은 6개로, 새 테스트가 포함되어 있습니다.
- 구현자가 기록한 "HEAD 63개 통과"와 일치합니다. 이번 결과는 커밋 이후 코드로 제가 직접 돌린 것이라, 1차 때의 "타임스탬프가 커밋보다 이르다"는 문제는 해소됐습니다.

#### 가짜 테스트와 약한 단언
- 1차의 1번 항목(MemberServiceTest.java:181 `anyString()`)은 새 테스트로 보완돼 해소됐습니다. :181은 그대로 남아 있지만 이제 횟수만 맡습니다.
- 1차의 2·3번 항목(통합 테스트 :171, :207)은 사용자 결정대로 넣지 않았고, 판정에는 영향이 없습니다.

---

## 보안 점검

> `/harness:security-review 2` (2026-11-20, HEAD eb7281f). 예외 대장(`securityExceptions`)은 아직 없다.

### 수집

| 항목 | 결과 |
| --- | --- |
| 타입 검사 (`typecheck`) | 오류 0 |
| 의존성 (`audit` = 프론트 `npm audit`) | 취약점 0 |
| 하드코딩된 비밀값·알려진 키 접두사 | 없음 (`backend/src/main`, `frontend/src`) |
| `.env` | 작업 트리·브랜치 커밋 모두 없음 |
| 클라이언트 노출 접두사(`VITE_`) | 프론트에서 환경변수를 쓰지 않음 |
| 로그 호출 | 프론트 `console.log`·백엔드 로거 호출 없음 |
| 비밀번호 비교 | BCrypt만. 없는 이메일도 진짜 BCrypt 해시와 비교 (AC 4, 변이 검사로 확인) |
| 쿠키 옵션 | `HttpOnly`·`SameSite=Lax` (AC 1 통합 테스트). `Secure`는 아래 🟡 |

임시 프로브 테스트(커밋하지 않음)로 테스트가 직접 보지 않는 경로를 돌려 봤다.

| 입력 | 결과 |
| --- | --- |
| 다른 출처(`Origin: http://evil.example`)에서 로그인한 쿠키로 `POST /api/auth/logout` | 403 `FORBIDDEN_ORIGIN`, 이어서 `/me` 200 (세션 유지) |
| `GET /api/auth/logout` | 404, 이어서 `/me` 200 (로그아웃되지 않음) |
| 비로그인 `GET /api/members/me` | 401 `UNAUTHENTICATED`, `Set-Cookie` 없음 (requestCache 끔) |
| 로그인 비밀번호 200자 | 400 `INVALID_INPUT` "비밀번호는 64자 이하입니다" (500 아님) |
| 로그인 본문이 깨진 JSON | 400 `INVALID_INPUT` "입력값이 올바르지 않습니다" (파싱 오류 내용 없음) |
| 로그인 비밀번호 누락 | 400 `INVALID_INPUT` "비밀번호를 입력해 주세요" |
| 틀린 비밀번호로 로그인 | 401 `MEMBER_LOGIN_FAILED`, `Set-Cookie` 없음 |

### 분류

| 분류 | 항목 | 위치 | 판단 | 처리 |
| --- | --- | --- | --- | --- |
| 🟡 | 세션 쿠키에 `Secure` 플래그가 없다 | `application.yml` `server.servlet.session.cookie` | 로컬은 http라 켜면 로그인이 안 된다. 운영(https)에서 꺼진 채면 평문 요청에도 쿠키가 실린다 | 배포 작업 때 운영 설정에서 `secure: true` |
| ⚪ | 로그인 시도 횟수 제한 없음 | 로그인 API | #3에서 만든다 (PRD M3) | — |
| ⚪ | `/me`에서 회원 행이 없으면 500(`NoSuchElementException`) | `MemberService.findMe` | 회원 탈퇴·삭제 기능이 없어 이 경로에 오지 않는다 (PRD "하지 않을 것") | — |
| ⚪ | `ErrorResponseWriter`가 Spring 빈이 아닌 자체 `ObjectMapper`를 쓴다 | `ErrorResponseWriter.java` | 본문이 문자열 두 개라 결과가 같다. 시그니처 판단 4 | — |
| ⚪ | indexed 세션 저장소가 Redis `CONFIG`(키 만료 알림)에 기댄다 | Spring Session | PRD M2 "다시 여는 조건"에 있다. 로컬·자체 운영 Redis에서는 문제없다 | — |
| ⚪ | 세션에 Java 직렬화로 저장되는 `LoginMember` | `LoginMember.java` | record라 `serialVersionUID`를 맞출 필요가 없어, 필드가 바뀌어도 이전 세션이 역직렬화 오류로 깨지지 않는다 | — |
| ⚪ | 백엔드(Gradle) 의존성 스캔 도구 없음 | `harness.json`의 `audit` | #1과 같다. 지금은 npm만 본다 | CI를 붙일 때 넣는다 |

#1에서 넘어온 🟡 중 "깨진 JSON의 400 본문이 비어 있음"은 이 이슈에서 해소했다. "`SignUpRequest`의 영어 기본 메시지"는 이번 범위 밖이라 남아 있다.

### 재스캔

🔴이 없어 코드를 고치지 않았다. `typecheck` 오류 0, `audit` 취약점 0, `test` 63개 전부 통과(리팩토링 직후 실행, 이번 이슈 25 + 기존 38). 남은 🟡·⚪는 위 표의 이유로 의도적으로 남겼다.
