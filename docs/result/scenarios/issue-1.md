# 이슈 #1 — 이메일로 가입한다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/1
> PRD: `docs/result/prd/member.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

---

## 시그니처

### 관련 ADR

- **계정 M1** — 인증은 Spring Security(Spring의 인증·권한 프레임워크)로 한다. 이 이슈에는 로그인이 없으므로 가입 경로와 헬스체크만 열어 둔다.
- **위조 요청 방어(기획서 결정)** — 상태를 바꾸는 요청(POST·PUT·PATCH·DELETE)은 `Origin`(없으면 `Referer`)이 허용 목록에 있을 때만 받는다. CSRF 토큰은 쓰지 않는다.
- **계정 PRD 작은 결정** — 비밀번호는 BCrypt(일부러 느리게 만든 비밀번호 해시), 이메일은 소문자로 맞춰 저장하고 UNIQUE, 닉네임 UNIQUE, 역할은 회원 한 명에 하나.

### 타입

```java
// com.livestream.member
public record SignUpRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
                message = "비밀번호는 8~64자이고 영문과 숫자를 모두 포함해야 합니다") String password,
        @NotNull @Pattern(regexp = "^[가-힣A-Za-z0-9]{2,12}$",
                message = "닉네임은 2~12자의 한글·영문·숫자만 쓸 수 있습니다") String nickname) {}

public record MemberResponse(long id, String email, String nickname, Role role) {}

public enum Role { MEMBER, STREAMER, OPERATOR }
```

```java
// com.livestream.member
@RestController @RequestMapping("/api/members")
class MemberController {
    @PostMapping   // 201 Created
    ResponseEntity<MemberResponse> signUp(@Valid @RequestBody SignUpRequest request);
}

@Service
public class MemberService {
    // 이메일을 소문자로 맞추고 저장한다. 중복이면 BusinessException
    public MemberResponse signUp(SignUpRequest request);
}

@Mapper  // SQL은 resources/mapper/member/MemberMapper.xml
public interface MemberMapper {
    void insert(Member member);                 // 생성된 id를 member에 채운다
    boolean existsByEmail(String email);        // 소문자 이메일로
    boolean existsByNickname(String nickname);
    Optional<Member> findByEmail(String email); // 저장된 해시 확인용. #2 로그인도 쓴다
}
```

```java
// com.livestream.common.error — 공통 에러 형식. 이후 모든 이슈가 따른다
public enum ErrorCode {              // HTTP 상태와 기본 메시지를 함께 든다
    INVALID_INPUT(400),              // 메시지는 처음 걸린 필드의 메시지
    FORBIDDEN_ORIGIN(403),
    MEMBER_EMAIL_DUPLICATED(409),    // "이미 가입된 이메일입니다"
    MEMBER_NICKNAME_DUPLICATED(409); // "이미 사용 중인 닉네임입니다"
}
public class BusinessException extends RuntimeException { ErrorCode code; }
public record ErrorResponse(String code, String message) {}   // {"code": "...", "message": "..."}
@RestControllerAdvice class GlobalExceptionHandler { ... }

// com.livestream.common.security
class SecurityConfig { SecurityFilterChain filterChain(HttpSecurity http); }  // POST /api/members, 헬스체크만 허용
class OriginValidationFilter extends OncePerRequestFilter {
    OriginValidationFilter(List<String> allowedOrigins);  // app.allowed-origins (로컬: http://localhost:8080, http://localhost:5173)
}
```

**테이블** — `V1__create_member.sql`: `member`(`id` 자동 증가, `email` UNIQUE, `password_hash`, `nickname VARCHAR2(12 CHAR)` UNIQUE, `role` CHECK, `created_at`)

### 에러 케이스

| 상황 | 에러 코드 | HTTP |
| --- | --- | --- |
| 이메일 형식·길이, 비밀번호 규칙, 닉네임 규칙 위반 | `INVALID_INPUT` | 400 |
| 같은 이메일(대소문자 무시)이 이미 있음 — 동시 가입으로 DB 제약에 걸린 경우 포함 | `MEMBER_EMAIL_DUPLICATED` | 409 |
| 같은 닉네임이 이미 있음 — 동시 가입으로 DB 제약에 걸린 경우 포함 | `MEMBER_NICKNAME_DUPLICATED` | 409 |
| 상태 변경 요청인데 `Origin`·`Referer`가 허용 목록에 없거나 둘 다 없음 | `FORBIDDEN_ORIGIN` | 403 |

### 화면

- `src/api/members.ts` — `signUp(req: SignUpRequest): Promise<MemberResponse>`
- `src/api/client.ts` — `errorMessage(e: unknown): string` 추가(에러 본문의 `message`, 없으면 "잠시 후 다시 시도해 주세요")
- `/signup` 가입 화면(props 없는 페이지). 성공하면 `/login`으로 이동. 머리글에 "가입" 링크
- `/login` — 이동할 곳만 있는 빈 화면(로그인은 #2)

### 판단이 갈린 곳

1. 주소는 `POST /api/members`(자원 기준). 로그인은 #2에서 `/api/auth/login`.
2. 입력 규칙은 요청 DTO(계층 사이로 데이터를 옮길 때 쓰는 형태 정의)의 Bean Validation(어노테이션으로 입력 규칙을 거는 Java 표준)으로 검사하고, 서비스는 중복만 판단한다.
3. 중복은 미리 조회해서 막고, 동시에 들어온 것은 DB UNIQUE 제약이 막는다. 걸린 제약 이름으로 이메일·닉네임을 구분한다.
4. 이메일 최대 254자 — 확정 사양에 없지만 너무 긴 값이 DB에서 500으로 터지지 않게 넣었다(이메일 주소 표준 최대 길이).
5. 닉네임의 한글은 완성형(가~힣)만. 자음·모음만 있는 글자("ㄱ")는 거부한다.

### 이 이슈에서 만들지 않는 것

- 로그인·세션 (#2)
- 가입 화면의 입력 즉시 검사 — 서버가 준 메시지만 보여 준다
- 이메일 인증 (확정 사양에서 뺐다)

---

## 테스트 시나리오

### 정상

- [x] [정상] `MemberService.signUp` — should create member with role MEMBER when email and nickname are new
- [x] [정상] `MemberService.signUp` — should save lower-cased email when email contains upper case letters
- [x] [정상] `POST /api/members` — should return 201 with id, email, nickname and role when request is valid
- [x] [정상] `OriginValidationFilter` — should pass when origin is in allowed list
- [x] [정상] `OriginValidationFilter` — should pass when origin is missing and referer is in allowed list
- [x] [정상] `OriginValidationFilter` — should pass when method is GET without origin and referer
- [x] [정상] `MemberSignUpIntegration` — should store bcrypt hash and not raw password when member signs up

### 경계

- [x] [경계] `POST /api/members` — should return 201 when password is 8 characters with letters and digits
- [x] [경계] `POST /api/members` — should return 201 when password is 64 characters with letters and digits
- [x] [경계] `POST /api/members` — should return 400 when password is 7 characters
- [x] [경계] `POST /api/members` — should return 400 when password is 65 characters
- [x] [경계] `POST /api/members` — should return 201 when nickname is 2 characters
- [x] [경계] `POST /api/members` — should return 201 when nickname is 12 characters
- [x] [경계] `POST /api/members` — should return 400 when nickname is 1 character
- [x] [경계] `POST /api/members` — should return 400 when nickname is 13 characters
- [x] [경계] `POST /api/members` — should return 400 when email is longer than 254 characters
- [x] [경계] `MemberSignUpIntegration` — should create one member and reject the other with MEMBER_NICKNAME_DUPLICATED when two sign ups with same nickname arrive at once

### 예외

- [x] [예외] `POST /api/members` — should return 400 when password has letters only
- [x] [예외] `POST /api/members` — should return 400 when password has digits only
- [x] [예외] `POST /api/members` — should return 400 when nickname contains characters other than korean, english letters and digits
- [x] [예외] `POST /api/members` — should return 400 when nickname contains a standalone korean consonant
- [x] [예외] `POST /api/members` — should return 400 when email format is invalid
- [x] [예외] `POST /api/members` — should return error body with code INVALID_INPUT and field message when validation fails
- [x] [예외] `POST /api/members` — should return 409 with code MEMBER_EMAIL_DUPLICATED when email is taken
- [x] [예외] `POST /api/members` — should return 409 with code MEMBER_NICKNAME_DUPLICATED when nickname is taken
- [x] [예외] `MemberService.signUp` — should throw MEMBER_EMAIL_DUPLICATED when lower-cased email already exists
- [x] [예외] `MemberService.signUp` — should throw MEMBER_NICKNAME_DUPLICATED when nickname already exists
- [x] [예외] `MemberService.signUp` — should throw MEMBER_EMAIL_DUPLICATED when database rejects duplicate email
- [x] [예외] `MemberService.signUp` — should throw MEMBER_NICKNAME_DUPLICATED when database rejects duplicate nickname
- [x] [예외] `OriginValidationFilter` — should return 403 with code FORBIDDEN_ORIGIN when origin is not allowed
- [x] [예외] `OriginValidationFilter` — should return 403 when post request has neither origin nor referer
- [x] [예외] `OriginValidationFilter` — should return 403 when referer is not in allowed list
- [x] [예외] `MemberSignUpIntegration` — should return 403 when sign up request comes from another origin

### 수동 확인 (테스트 도구가 없는 화면 — 시나리오 수에 넣지 않는다)

프론트 테스트 도구(Vitest)는 #12에서 넣는다. 아래는 Green 단계에서 브라우저로 확인하고 결과를 이 문서와 PR에 남긴다.

- 가입에 성공하면 `/login`으로 이동한다
- 이미 가입된 이메일로 가입하면 서버 메시지("이미 가입된 이메일입니다")가 화면에 보인다

**확인 결과 (2026-11-20, Green):** 로컬에서 Oracle·Redis 컨테이너 + `bootRun` + Vite 개발 서버(출처 `http://localhost:5173`)로 브라우저에서 확인했다.

- 가입 성공: `manual1@example.com` / `수동확인1` 제출 → `POST /api/members` 201 → `/login`으로 이동 ✅
- 실패 메시지: `MANUAL1@example.com`(대문자, 같은 이메일) / `수동확인2` 제출 → 화면에 "이미 가입된 이메일입니다" 표시, `/signup`에 머묾 ✅

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 새 이메일·`abcd1234`·`시청자1`로 가입 → 201, 역할 `MEMBER`, 비밀번호는 BCrypt 해시로만 저장 | `[정상] MemberService.signUp — should create member with role MEMBER…`<br>`[정상] POST /api/members — should return 201 with id, email, nickname and role…`<br>`[정상] MemberSignUpIntegration — should store bcrypt hash and not raw password…` |
| 2. `A@b.com` 가입 후 `a@b.com` → 409 | `[정상] MemberService.signUp — should save lower-cased email…`<br>`[예외] MemberService.signUp — should throw MEMBER_EMAIL_DUPLICATED when lower-cased email already exists`<br>`[예외] POST /api/members — should return 409 with code MEMBER_EMAIL_DUPLICATED…` |
| 3. 비밀번호 7자·65자·영문만·숫자만 → 400, 8자·64자 영문+숫자 → 가입 | 경계 4개(8·64·7·65자) + `[예외] … letters only` + `[예외] … digits only` |
| 4. 닉네임 1자·13자·허용 외 문자 → 400, 2자·12자 → 가입 | 경계 4개(2·12·1·13자) + `[예외] … other than korean, english letters and digits` |
| 5. 같은 닉네임 → 409 | `[예외] MemberService.signUp — should throw MEMBER_NICKNAME_DUPLICATED when nickname already exists`<br>`[예외] POST /api/members — should return 409 with code MEMBER_NICKNAME_DUPLICATED…` |
| 6. 같은 닉네임 동시 가입 2개 → 하나만 생기고 하나는 409 | `[경계] MemberSignUpIntegration — should create one member and reject the other…`<br>`[예외] MemberService.signUp — should throw MEMBER_NICKNAME_DUPLICATED when database rejects duplicate nickname` |
| 7. 다른 출처 → 403, `Origin`·`Referer` 둘 다 없음 → 403 | `[예외] OriginValidationFilter` 3개 + `[예외] MemberSignUpIntegration — should return 403 when sign up request comes from another origin` |
| 8. 가입 화면: 성공하면 로그인 화면으로, 실패하면 서버 메시지 | **수동 확인** 2개 (테스트 도구 없음) |

**AC에 없는데 추가한 시나리오**

| 시나리오 | 이유 |
| --- | --- |
| 이메일 형식 오류 → 400 | 확정 사양 2절("형식 검사")에 있다. AC가 빠뜨렸다 |
| 이메일 254자 초과 → 400 | 시그니처 판단 4 (승인됨) |
| 닉네임에 자음만 있는 글자 → 400 | 시그니처 판단 5 (승인됨) |
| 400 응답 본문의 `code`·`message` | 공통 에러 형식을 이 이슈에서 정한다. 화면이 이 메시지를 보여 준다(AC 8) |
| DB가 이메일 중복을 거부하면 `MEMBER_EMAIL_DUPLICATED` | 동시 가입의 이메일 쪽. AC 6은 닉네임만 말하지만 같은 장치다 |
| GET 요청은 출처 검사 없이 통과 | 출처 검사가 조회까지 막으면 안 된다(상태 변경 요청만 검사) |
| `Origin` 없고 허용된 `Referer`면 통과 / 허용 안 된 `Referer`면 403 | 기획서 결정("없으면 `Referer`") |

**커버리지:** AC 8개 / 시나리오 33개 / 미커버 0개 (AC 8은 수동 확인)
