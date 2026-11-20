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
| 4. 없는 이메일이면 비밀번호 비교(가짜 해시)가 한 번 | `[예외] MemberService.login — should compare password against a dummy hash once when email does not exist` |
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

**커버리지:** AC 8개 / 시나리오 24개 / 미커버 0개
