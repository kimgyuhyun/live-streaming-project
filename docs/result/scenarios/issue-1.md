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
                message = "비밀번호는 8~64자이고 영문과 숫자를 모두 포함해야 합니다")
        @Pattern(regexp = "^[!-~]*$",   // 보안 점검에서 추가: 공백을 뺀 ASCII만 (BCrypt 72바이트 한도)
                message = "비밀번호는 영문·숫자·특수문자만 쓸 수 있습니다") String password,
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
- [x] [정상] `MemberSignUpIntegration` — should return 201 and persist role MEMBER when sign up request comes from allowed origin

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
- [x] [경계] `MemberSignUpIntegration` — should return MEMBER_NICKNAME_DUPLICATED when pre-check passes but database rejects duplicate nickname
- [x] [경계] `MemberSignUpIntegration` — should return MEMBER_EMAIL_DUPLICATED when pre-check passes but database rejects duplicate email

### 예외

- [x] [예외] `POST /api/members` — should return 400 when password has letters only
- [x] [예외] `POST /api/members` — should return 400 when password has digits only
- [x] [예외] `POST /api/members` — should return 400 when nickname contains characters other than korean, english letters and digits
- [x] [예외] `POST /api/members` — should return 400 when nickname contains a standalone korean consonant
- [x] [예외] `POST /api/members` — should return 400 with ascii-only message when password contains non-ascii characters
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
| 1. 새 이메일·`abcd1234`·`시청자1`로 가입 → 201, 역할 `MEMBER`, 비밀번호는 BCrypt 해시로만 저장 | `[정상] MemberService.signUp — should create member with role MEMBER…`<br>`[정상] POST /api/members — should return 201 with id, email, nickname and role…`<br>`[정상] MemberSignUpIntegration — should store bcrypt hash and not raw password…`<br>`[정상] MemberSignUpIntegration — should return 201 and persist role MEMBER when sign up request comes from allowed origin` (AC 검증 1차 후 추가) |
| 2. `A@b.com` 가입 후 `a@b.com` → 409 | `[정상] MemberService.signUp — should save lower-cased email…`<br>`[예외] MemberService.signUp — should throw MEMBER_EMAIL_DUPLICATED when lower-cased email already exists`<br>`[예외] POST /api/members — should return 409 with code MEMBER_EMAIL_DUPLICATED…` |
| 3. 비밀번호 7자·65자·영문만·숫자만 → 400, 8자·64자 영문+숫자 → 가입 | 경계 4개(8·64·7·65자) + `[예외] … letters only` + `[예외] … digits only` |
| 4. 닉네임 1자·13자·허용 외 문자 → 400, 2자·12자 → 가입 | 경계 4개(2·12·1·13자) + `[예외] … other than korean, english letters and digits` |
| 5. 같은 닉네임 → 409 | `[예외] MemberService.signUp — should throw MEMBER_NICKNAME_DUPLICATED when nickname already exists`<br>`[예외] POST /api/members — should return 409 with code MEMBER_NICKNAME_DUPLICATED…` |
| 6. 같은 닉네임 동시 가입 2개 → 하나만 생기고 하나는 409 | `[경계] MemberSignUpIntegration — should create one member and reject the other…`<br>`[예외] MemberService.signUp — should throw MEMBER_NICKNAME_DUPLICATED when database rejects duplicate nickname`<br>`[경계] MemberSignUpIntegration — should return MEMBER_NICKNAME_DUPLICATED when pre-check passes but database rejects duplicate nickname` (AC 검증 1차 후 추가)<br>`[경계] MemberSignUpIntegration — should return MEMBER_EMAIL_DUPLICATED when pre-check passes but database rejects duplicate email` (AC 검증 1차 후 추가) |
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

**커버리지:** AC 8개 / 시나리오 37개 / 미커버 0개 (AC 8은 수동 확인, 아래 AC 검증의 범위 밖 인정 참고)

**보안 점검 후 추가한 시나리오:** `[예외] POST /api/members — should return 400 with ascii-only message when password contains non-ascii characters`. AC 3에는 없지만, 한글 62자 + `a1`(64자)이 규칙을 통과한 뒤 BCrypt 72바이트 한도에 걸려 500이 났다(보안 점검 🔴). 사용자 결정으로 비밀번호를 공백을 뺀 ASCII로 제한했다(확정 사양 2절에도 반영).

---

## AC 검증

> `harness:ac-verifier` 출력 원문 (2026-11-20, 1차). 구현자는 아래 `### 구현자 이견`에만 쓴다.

이슈 #1의 AC 8개 가운데 5개는 충족했고, 3개(AC 1, 6, 8)는 부분 충족입니다. 미충족은 없습니다.

```
AC 8개 중 — ✅ 5 / ⚠️ 3 / ❌ 0
```

구현 코드를 읽어 보면 8개 모두 AC대로 동작합니다. 부분 충족 3개는 구현 결함이 아니라 검증이 약한 경우입니다. 테스트가 목(mock)이나 필터를 끈 경로만 타거나, 자동 테스트가 없습니다.

`./gradlew test --rerun`으로 테스트를 새로 돌렸고 34개가 모두 통과했습니다. 시나리오 33개(필터 6, 컨트롤러 18, 서비스 6, 통합 3)에 컨텍스트 테스트 1개를 더한 수입니다. 시나리오 문서의 33개 문장은 모두 테스트의 `@DisplayName`으로 들어가 있습니다.

### 판정표

| AC | 판정 | 테스트 (파일:줄) | 구현 (파일:줄) | 근거 |
| --- | --- | --- | --- | --- |
| 1. 새 이메일, `abcd1234`, `시청자1`로 가입하면 201, 역할 MEMBER, BCrypt 해시로만 저장 | ⚠️ | `should create member with role MEMBER when email and nickname are new` (MemberServiceTest.java:49)<br>`should return 201 with id, email, nickname and role when request is valid` (MemberControllerTest.java:67)<br>`should store bcrypt hash and not raw password when member signs up` (MemberSignUpIntegrationTest.java:58) | MemberController.java:23 (201)<br>MemberService.java:31 (`passwordEncoder.encode`, `Role.MEMBER`)<br>SecurityConfig.java:29 (permitAll), :37 (BCrypt)<br>V1__create_member.sql:4 | BCrypt 저장은 실제 Oracle에서 `matches()`로 확인합니다. 원문 문자열은 `matches`를 통과하지 못하므로 "해시로만"의 근거가 됩니다. 역할 MEMBER는 insert에 넘긴 인자를 캡처해서 확인합니다. 그런데 **201은 `@AutoConfigureMockMvc(addFilters = false)`(MemberControllerTest.java:26)에서 서비스를 목으로 둔 채로만 확인합니다.** 응답의 `$.id`(:71)와 `$.role`(:74)은 목이 돌려준 값(:44-48)이라 직렬화만 검사합니다. 보안 체인을 켠 채로 허용된 Origin에서 POST하면 201이 나오고 DB에 회원이 생기는지 보는 자동 테스트는 없습니다. DB에 저장된 role을 다시 읽어 보는 테스트도 없습니다. 이 경로는 시나리오 문서 163행의 수동 확인(Vite 5173에서 201)만 있습니다. |
| 2. `A@b.com` 가입 후 `a@b.com`이면 409(이메일 중복) | ✅ | `should save lower-cased email when email contains upper case letters` (MemberServiceTest.java:57)<br>`should throw MEMBER_EMAIL_DUPLICATED when lower-cased email already exists` (:65)<br>`should return 409 with code MEMBER_EMAIL_DUPLICATED when email is taken` (MemberControllerTest.java:190) | MemberService.java:23-25<br>GlobalExceptionHandler.java:12-17<br>ErrorCode.java:10 | 목은 정확히 `existsByEmail("a@b.com")`에만 true를 돌려줍니다(:67). 그래서 서비스가 소문자로 바꾸지 않으면 예외가 나지 않아 테스트가 실패합니다. 진짜 검사입니다. 저장할 때 소문자로 바꾸는지도 별도로 단언하고, 409 변환은 컨트롤러 테스트가 확인합니다. 테스트의 입력 방향은 AC와 반대(기존 소문자, 요청 대문자)입니다. 다만 두 방향 모두 같은 소문자 조회를 타므로 AC 방향도 덮습니다. |
| 3. 비밀번호 7자, 65자, 영문만, 숫자만이면 400. 영문+숫자 8자, 64자면 가입 | ✅ | MemberControllerTest.java:78 (8자, 201), :84 (64자 = 32+32, 201), :90 (7자, 400 + INVALID_INPUT), :98 (65자 = 32+33, 400), :142 (영문만 8자), :150 (숫자만 8자) | SignUpRequest.java:11 `^(?=.*[A-Za-z])(?=.*\d).{8,64}$` | 경계 네 점(7, 8, 64, 65)이 모두 있습니다. 영문만과 숫자만 테스트는 8자짜리라 길이 규칙과 섞이지 않습니다. 400 테스트는 상태와 `code`를 둘 다 단언합니다. 201 쪽은 서비스가 목이지만, 서비스는 길이를 보지 않습니다. 64바이트 ASCII는 BCrypt 72바이트 한도 안이라 실제 경로에서도 가입됩니다. |
| 4. 닉네임 1자, 13자, 허용 외 문자면 400. 2자, 12자면 가입 | ✅ | MemberControllerTest.java:106 (`가나`, 201), :112 (12자, 201), :118 (`가`, 400), :126 (13자, 400), :158 (`시청자!`, 400), :166 (`ㄱ나다`, 400) | SignUpRequest.java:13 `^[가-힣A-Za-z0-9]{2,12}$`<br>V1__create_member.sql:5 `VARCHAR2(12 CHAR)` | 12자와 13자 문자열을 글자 수까지 세어 확인했습니다. 정규식은 허용 문자만 나열하는 방식이고 `matches()`로 검사하므로, 다른 문자는 모두 거부됩니다. 컬럼이 CHAR 단위라 한글 12자도 DB에 들어갑니다. |
| 5. 같은 닉네임이면 409(닉네임 중복) | ✅ | `should throw MEMBER_NICKNAME_DUPLICATED when nickname already exists` (MemberServiceTest.java:76)<br>`should return 409 with code MEMBER_NICKNAME_DUPLICATED when nickname is taken` (MemberControllerTest.java:201) | MemberService.java:27-29<br>ErrorCode.java:11 | 미리 조회해서 막는 경로와, 그 예외를 409로 바꾸는 경로가 각각 테스트됩니다. |
| 6. 같은 닉네임으로 동시에 2건이면 회원 1명 생성, 나머지 409 | ⚠️ | `should create one member and reject the other with MEMBER_NICKNAME_DUPLICATED when two sign ups with same nickname arrive at once` (MemberSignUpIntegrationTest.java:69)<br>`should throw MEMBER_NICKNAME_DUPLICATED when database rejects duplicate nickname` (MemberServiceTest.java:100) | MemberService.java:32-35, 41-49<br>V1__create_member.sql:10 (`uk_member_nickname`) | 실제 Oracle에서 결과가 {OK, MEMBER_NICKNAME_DUPLICATED}인지 확인하므로 AC의 결과 자체는 맞습니다. 하지만 **이 테스트는 실제로 경합이 일어나든 안 일어나든 통과합니다.** 한쪽이 먼저 commit하면 미리 조회하는 단계(:27)에서 걸려 같은 결과가 나옵니다. 그러면 DB UNIQUE 위반을 409로 바꾸는 경로(:34-35)는 실행되지 않습니다. 이 경로를 확실히 실행하는 테스트는 서비스 단위 테스트뿐인데, 거기서 쓰는 예외 메시지 `"ORA-00001: unique constraint (APP.UK_MEMBER_NICKNAME) violated"`(:102)는 손으로 쓴 것입니다. 그래서 실제 Oracle 23 Free와 MyBatis가 만든 `DuplicateKeyException` 메시지에 제약 이름이 들어가는지는 어떤 테스트도 확실히 확인하지 않습니다. 이 가정이 틀리면 :49에서 예외를 그대로 다시 던져 500이 납니다. 그때도 통합 테스트는 매번 실패하지 않고 가끔만 실패합니다. 이번 실행에서 이 경로를 탔는지는 알 수 없습니다. 서비스가 로그를 남기지 않고, 테스트 결과 XML에서 `ORA-00001`, `DuplicateKey`, `UK_MEMBER`를 찾아도 0건이었습니다. "회원은 하나만"도 DB 건수로 확인하지 않고 결과 목록으로 추론합니다. |
| 7. 다른 출처의 Origin이면 403. Origin, Referer가 둘 다 없어도 403 | ✅ | `should return 403 with code FORBIDDEN_ORIGIN when origin is not allowed` (OriginValidationFilterTest.java:75)<br>`should return 403 when post request has neither origin nor referer` (:87)<br>`should return 403 when referer is not in allowed list` (:95)<br>`should return 403 when sign up request comes from another origin` (MemberSignUpIntegrationTest.java:99) | OriginValidationFilter.java:31-41, 44-62<br>SecurityConfig.java:27 | 두 조건 모두 필터 단위 테스트가 있습니다. 통합 테스트는 실제 보안 체인에서 `$.code = FORBIDDEN_ORIGIN`까지 확인하므로, 필터가 체인에 연결돼 있다는 것도 증명됩니다. 둘 다 없는 경우는 필터 단위에서만 보지만 코드 경로는 같습니다. 약점은 거부 테스트들이 체인으로 넘어가지 않았다는 것(`passed()`가 false)을 단언하지 않는다는 점입니다. 판정을 바꿀 정도는 아닙니다. |
| 8. 가입 화면에서 성공하면 로그인 화면으로 가고, 실패하면 서버 메시지를 보여 준다 | ⚠️ | 자동 테스트 없음(프론트 테스트 도구 없음). 수동 확인 기록은 docs/result/scenarios/issue-1.md:161-164 | frontend/src/pages/SignUpPage.tsx:15 (`onSuccess: () => navigate('/login')`), :38 (`errorMessage(mutation.error)`)<br>frontend/src/api/client.ts:33, 38-44<br>frontend/src/router.tsx (`signup`, `login` 경로) | 코드를 읽으면 AC대로 동작합니다. 실패 응답 400, 403, 409는 모두 `{code, message}` JSON이고(GlobalExceptionHandler, 필터 :40), `errorMessage`가 그 `message`를 꺼냅니다. 수동 기록은 가입 성공 후 이동과 409 메시지 표시 두 가지입니다. 하지만 구현자가 남긴 기록이고 저는 재현하지 않았습니다(브라우저 없음). 400 메시지 표시는 수동으로도 보지 않았습니다. 자동 회귀 장치가 없으므로 ✅는 주지 않았습니다. |

### 갭을 메울 시나리오 제안 (시나리오 문서에 그대로 붙일 수 있는 형태)

```
- [ ] [정상] `MemberSignUpIntegration` — should return 201 and persist role MEMBER when sign up request comes from allowed origin
      (AC 1: 보안 체인을 켠 MockMvc로 Origin http://localhost:8080 POST, 201 단언, findByEmail로 role == MEMBER 단언. AC 7의 허용 쪽 연결도 함께 증명)
- [ ] [경계] `MemberSignUpIntegration` — should return MEMBER_NICKNAME_DUPLICATED when pre-check passes but database rejects duplicate nickname
      (AC 6: 실제 MemberMapper에 위임하되 existsByNickname만 false로 돌려주는 래퍼로 MemberService를 만든다. 같은 닉네임 회원을 먼저 넣은 뒤 가입해 실제 Oracle 예외 메시지 경로를 매번 실행한다)
- [ ] [경계] `MemberSignUpIntegration` — should return MEMBER_EMAIL_DUPLICATED when pre-check passes but database rejects duplicate email
      (위와 같은 방식, 이메일 쪽)
- [ ] [정상] `SignUpPage` — should navigate to /login when sign up succeeds            (AC 8: Vitest를 넣는 #12 이후)
- [ ] [예외] `SignUpPage` — should show server message when sign up fails with 409    (AC 8: Vitest를 넣는 #12 이후)
```

선택 사항: 동시성 테스트(MemberSignUpIntegrationTest.java:95)에 같은 닉네임 회원이 DB에 정확히 1건인지 확인하는 단언을 추가할 수 있습니다. 지금 매퍼에는 건수 조회 메서드가 없어서, 테스트에서 JdbcTemplate으로 세야 합니다.

### 가짜 테스트와 약한 단언

인자 없는 예외 단언, await 누락, 단언 없는 테스트 같은 전형적인 가짜 테스트는 없었습니다. 예외 단언은 모두 `BusinessException`과 `ErrorCode`까지 구체적으로 확인합니다. 아래는 약한 단언입니다.

1. **MemberControllerTest.java:71, :74**: `$.id == 1`과 `$.role == "MEMBER"`는 setUp의 목 응답(:44-48)을 그대로 단언합니다. 직렬화 검사로만 보고, 역할 검증은 위의 통합 테스트 제안으로 옮기면 됩니다.
2. **MemberServiceTest.java:90, :102**: 손으로 쓴 `DuplicateKeyException` 메시지에 의존합니다. 위의 "pre-check passes but database rejects" 통합 테스트로 실제 메시지 형식을 고정하면 됩니다.
3. **MemberSignUpIntegrationTest.java:69-96**: 경합 여부와 관계없이 통과합니다(AC 6 근거 참고).
4. **OriginValidationFilterTest.java:86-103**: 거부 테스트 3개가 상태 코드만 보고 체인으로 넘어가지 않았는지는 보지 않습니다. `assertThat(passed()).isFalse()`를 추가하면 됩니다(한 테스트 한 검사 원칙에 따라 별도 테스트로 나눠도 됩니다).

### AC 범위 밖 관찰 (판정에 반영하지 않음, security-review 참고용)

1. 비밀번호 정규식 `.{8,64}`(SignUpRequest.java:11)는 영문과 숫자 외의 문자도 받습니다. 예를 들어 한글 62자에 `a1`을 붙인 64자는 정규식을 통과하지만 UTF-8로 72바이트를 넘습니다. 프로젝트가 쓰는 spring-security-crypto 6.5.11의 BCrypt는 72바이트를 넘는 비밀번호에 IllegalArgumentException을 던지고(BCrypt.class에서 "password cannot be more than 72 bytes" 문자열 확인), 이 예외를 받는 핸들러가 없어 500이 날 것으로 보입니다. 실행해서 확인하지는 않았습니다.
2. 이메일 컬럼은 `VARCHAR2(254)`(바이트 단위)이고 검증은 `@Size(max = 254)`(글자 단위)입니다. 비ASCII 이메일이면 둘이 어긋날 수 있습니다. 실행해서 확인하지는 않았습니다.

### 구현자 이견

- **AC 6 — 실제 Oracle 메시지 형식:** Green 단계에서 커밋하지 않은 임시 테스트로 실제 Oracle 23 Free + MyBatis가 낸 `DuplicateKeyException` 메시지를 찍어 봤다. 닉네임 중복은 `ORA-00001: 고유한 제약 조건(TEST.UK_MEMBER_NICKNAME)이 …`, 이메일 중복은 `…(TEST.UK_MEMBER_EMAIL)…`로 제약 이름이 들어 있었다. 다만 이 확인을 테스트로 남기지 않았으므로 "어떤 테스트도 확실히 확인하지 않는다"는 지적은 맞다. 판정에 동의한다.

### 갭 처리 (사용자 결정, 2026-11-20)

- **AC 1·6 — 갭을 메운다.** 검증자가 제안한 통합 시나리오 3개를 `## 테스트 시나리오`에 그대로 추가하고 Red → Green을 다시 돈다.
- **AC 8 — #12까지 범위 밖으로 인정한다.** 프론트 테스트 도구(Vitest)는 화면 상태 로직이 처음 생기는 #12에서 넣기로 했다(CLAUDE.md, `docs/result/issues.md` 셋업 체크리스트). 이 이슈에서는 브라우저 수동 확인으로 대신하고, 검증자가 제안한 `SignUpPage` 시나리오 2개는 #12에서 Vitest를 넣을 때 추가한다.

### 2차 검증

> `harness:ac-verifier` 출력 원문 (2026-11-20, 2차 — 갭 처리 후).

AC 8개 중 7개가 충족, 1개(AC 8)가 부분 충족이고 미충족은 없습니다.

```
AC 8개 중 — ✅ 7 / ⚠️ 1 / ❌ 0
```

1차에서 ⚠️였던 AC 1과 6은 이번에 추가된 통합 테스트 3개로 갭이 메워져 ✅입니다. AC 8은 #12까지 범위 밖으로 둔다는 사용자 결정이 타당하다고 봅니다. 다만 자동 테스트가 없다는 사실은 그대로라서 판정은 ⚠️로 유지합니다. 이 ⚠️는 이미 알고 받아들인 갭이므로 머지를 막을 사유는 아니라고 판단합니다.

`./gradlew test --rerun` 결과는 BUILD SUCCESSFUL, EXIT=0입니다. 37개 테스트가 실패 0, 에러 0, 스킵 0으로 모두 통과했습니다. 내역은 필터 6, 컨트롤러 18, 서비스 6, 통합 6에 컨텍스트 테스트 1입니다. 시나리오 문서의 문장 36개는 모두 테스트의 `@DisplayName`과 정확히 일치합니다(누락 0).

#### 판정표

파일 위치는 다음 기준입니다.
- 테스트: `backend/src/test/java/com/livestream/…`
- 구현: `backend/src/main/java/com/livestream/…`

| AC | 판정 | 테스트 (파일:줄) | 구현 (파일:줄) | 근거 |
| --- | --- | --- | --- | --- |
| 1. 새 이메일, 비밀번호 `abcd1234`, 닉네임 `시청자1`로 가입하면 201, 역할 MEMBER, 비밀번호는 BCrypt 해시로만 저장 | ✅ | `should return 201 and persist role MEMBER when sign up request comes from allowed origin` (member/MemberSignUpIntegrationTest.java:147, 단언 :156, :158)<br>`should store bcrypt hash and not raw password when member signs up` (같은 파일 :92, 단언 :99)<br>`should create member with role MEMBER when email and nickname are new` (member/MemberServiceTest.java:49)<br>`should return 201 with id, email, nickname and role when request is valid` (member/MemberControllerTest.java:67) | member/MemberController.java:23 (201)<br>member/MemberService.java:31 (`passwordEncoder.encode`, `Role.MEMBER`)<br>common/security/SecurityConfig.java:27, :29, :37<br>V1__create_member.sql:4, :11 | 1차의 갭은 "보안 체인을 켠 실제 경로에서 201이 나오고 DB에 role이 저장되는지"였습니다. 새 테스트가 이것을 직접 확인합니다. `@SpringBootTest`와 `@AutoConfigureMockMvc`를 쓰고 필터를 끄지 않았으며, 허용된 Origin으로 POST해 201을 받은 뒤 DB에서 다시 읽은 role이 MEMBER인지 단언합니다. BCrypt 쪽은 `BCryptPasswordEncoder.matches`가 BCrypt 형식이 아닌 문자열에는 false를 돌려주므로, 원문 그대로 저장했다면 :99가 실패합니다. 통합 테스트는 DB를 같이 써서 닉네임을 `시청자1`로 고정할 수 없습니다. 그 값으로 하는 검증은 컨트롤러 테스트(:31)와 서비스 테스트에 있습니다. 닉네임 값은 AC 4의 규칙으로 따로 검증되므로 결론에 영향이 없습니다. |
| 2. `A@b.com`으로 가입된 상태에서 `a@b.com`으로 가입하면 409(이메일 중복) | ✅ | `should save lower-cased email when email contains upper case letters` (member/MemberServiceTest.java:57, 단언 :61)<br>`should throw MEMBER_EMAIL_DUPLICATED when lower-cased email already exists` (같은 파일 :65, 목 설정 :67)<br>`should return 409 with code MEMBER_EMAIL_DUPLICATED when email is taken` (member/MemberControllerTest.java:190)<br>`should return MEMBER_EMAIL_DUPLICATED when pre-check passes but database rejects duplicate email` (member/MemberSignUpIntegrationTest.java:175) | member/MemberService.java:23-25<br>common/error/GlobalExceptionHandler.java:12-17<br>common/error/ErrorCode.java:10 | 목은 정확히 `existsByEmail("a@b.com")`일 때만 true를 돌려줍니다. 그래서 서비스가 이메일을 소문자로 바꾸지 않으면 예외가 나지 않아 테스트가 실패합니다. 저장할 때 소문자로 바꾸는지는 :61이 따로 단언합니다. AC가 말하는 방향(먼저 대문자로 가입, 나중에 소문자로 요청)은 이 두 단언을 합쳐 덮습니다. 실제 DB에서 대소문자만 다른 이메일로 해 보는 테스트는 없습니다. 하지만 두 방향 모두 같은 소문자 변환 코드(:23)를 지나므로 판정을 낮추지 않았습니다. |
| 3. 비밀번호가 7자·65자·영문만·숫자만이면 400, 영문+숫자 8자·64자면 가입 | ✅ | member/MemberControllerTest.java:78 (8자 → 201)<br>:84 (64자 = 32+32 → 201)<br>:90 (7자 → 400 + INVALID_INPUT)<br>:98 (65자 = 32+33 → 400)<br>:142 (영문만 8자)<br>:150 (숫자만 8자) | member/SignUpRequest.java:11 | 경계 네 점(7, 8, 64, 65)이 모두 있습니다. 영문만·숫자만 테스트는 8자짜리라 길이 규칙과 섞이지 않습니다. 400 테스트는 상태와 `code`를 둘 다 단언합니다. 실제 경로에서 가입되는지는 통합 테스트(:96, :150)가 8자 `abcd1234`로 보여 줍니다. |
| 4. 닉네임이 1자·13자이거나 한글·영문·숫자 외 문자를 포함하면 400, 2자·12자면 가입 | ✅ | member/MemberControllerTest.java:106 (`가나` → 201)<br>:112 (12자 → 201)<br>:118 (`가` → 400)<br>:126 (13자 → 400)<br>:158 (`시청자!` → 400)<br>:166 (`ㄱ나다` → 400) | member/SignUpRequest.java:13 `^[가-힣A-Za-z0-9]{2,12}$`<br>V1__create_member.sql:5 `VARCHAR2(12 CHAR)` | 경계 네 점이 모두 있습니다. 정규식이 허용 문자만 나열하는 방식이라 그 밖의 문자는 모두 거부됩니다. 컬럼이 글자(CHAR) 단위라 한글 12자도 DB에 들어갑니다. |
| 5. 닉네임 `시청자1`인 회원이 있을 때 같은 닉네임으로 가입하면 409(닉네임 중복) | ✅ | `should throw MEMBER_NICKNAME_DUPLICATED when nickname already exists` (member/MemberServiceTest.java:76)<br>`should return 409 with code MEMBER_NICKNAME_DUPLICATED when nickname is taken` (member/MemberControllerTest.java:201) | member/MemberService.java:27-29<br>common/error/ErrorCode.java:11 | 미리 조회해서 막는 경로와, 그 예외를 409로 바꾸는 경로가 각각 테스트됩니다. 409 변환은 실제 `GlobalExceptionHandler`를 쓴 `@WebMvcTest`에서 확인합니다. |
| 6. 같은 닉네임으로 가입 요청 2개가 동시에 오면 회원은 하나만 생기고 다른 하나는 409 | ✅ | `should return MEMBER_NICKNAME_DUPLICATED when pre-check passes but database rejects duplicate nickname` (member/MemberSignUpIntegrationTest.java:162)<br>`should return MEMBER_EMAIL_DUPLICATED when pre-check passes but database rejects duplicate email` (:175)<br>`should create one member and reject the other with MEMBER_NICKNAME_DUPLICATED when two sign ups with same nickname arrive at once` (:103, 단언 :129)<br>`should throw MEMBER_NICKNAME_DUPLICATED when database rejects duplicate nickname` (member/MemberServiceTest.java:100) | member/MemberService.java:32-35, :41-49<br>V1__create_member.sql:9-10 | 1차의 핵심 갭은 실제 Oracle이 낸 `DuplicateKeyException` 메시지에 제약 이름이 들어 있는지 테스트로 고정되지 않았다는 것이었습니다. 이제 :62-85의 래퍼가 미리 조회하는 단계만 "없음"으로 답하고, 저장은 진짜 매퍼로 실제 Oracle에 합니다. 그래서 UNIQUE 위반 경로를 매번 확실히 탑니다(경합 운에 기대지 않음). 이 테스트가 헛돌 수 없는 이유는 세 가지입니다. 저장이 성공하면 `assertThatThrownBy`가 실패합니다. 메시지에서 제약 이름을 못 찾으면 :49가 `DuplicateKeyException`을 다시 던져 `isInstanceOf(BusinessException.class)`가 실패합니다. 이메일과 닉네임을 잘못 구분하면 `code` 단언이 실패합니다. 두 테스트는 이메일만 겹치게, 또는 닉네임만 겹치게 데이터를 만들어 걸리는 제약이 하나뿐이고, 이번 실행에서 둘 다 통과했습니다. "회원은 하나만"은 DB 건수로 세지 않습니다. 대신 uk_member_nickname(V1:10)이 두 번째 행을 구조적으로 막고, 결과 "OK"가 정확히 1건임을 :129가 단언하므로 성립합니다. |
| 7. `Origin`이 우리 도메인이 아니면 403, `Origin`·`Referer`가 둘 다 없어도 403 | ✅ | `should return 403 with code FORBIDDEN_ORIGIN when origin is not allowed` (common/security/OriginValidationFilterTest.java:75)<br>`should return 403 when post request has neither origin nor referer` (:87)<br>`should return 403 when referer is not in allowed list` (:95)<br>`should return 403 when sign up request comes from another origin` (member/MemberSignUpIntegrationTest.java:133) | common/security/OriginValidationFilter.java:31-41, :44-62<br>common/security/SecurityConfig.java:27 | 두 조건 모두 필터 단위 테스트가 있습니다. 통합 테스트는 실제 보안 체인에서 `$.code = FORBIDDEN_ORIGIN`까지 확인하므로 필터가 체인에 연결돼 있다는 것도 증명합니다. 새 AC 1 테스트(:147)는 허용된 Origin이 체인을 통과한다는 반대쪽도 실제 체인에서 증명합니다. 남은 약점은 아래 "약한 단언" 1번에 적었습니다. |
| 8. 가입 화면에서 성공하면 로그인 화면으로 가고, 실패하면 서버가 준 메시지를 보여 준다 | ⚠️ | 자동 테스트 없음(프론트 테스트 도구 없음, 확인 근거는 아래 실행 명령 8번)<br>수동 확인 기록은 docs/result/scenarios/issue-1.md:164-167 | frontend/src/pages/SignUpPage.tsx:15 (`onSuccess: () => navigate('/login')`), :38 (`errorMessage(mutation.error)`)<br>frontend/src/api/client.ts:33, :38-44<br>frontend/src/router.tsx:13-14<br>frontend/src/layouts/RootLayout.tsx:7 | 코드를 읽으면 AC대로 동작합니다. 실패 응답 400, 403, 409는 모두 `{code, message}` JSON입니다(GlobalExceptionHandler.java:15-16, :26-27, OriginValidationFilter.java:40). `errorMessage`가 그 `message`를 꺼냅니다. 그래도 ⚠️로 둔 이유는 세 가지입니다. 자동 회귀 장치가 없습니다. 수동 기록은 구현자가 남긴 것이고 저는 브라우저로 재현하지 않았습니다. 수동 기록은 201 후 이동과 409 메시지 표시만 다루고, 400 메시지 표시는 확인하지 않았습니다(코드상으로는 같은 경로). 범위 밖이라는 결정은 문서 :263, `docs/result/issues.md:12`, `CLAUDE.md:113`과 일치합니다. |

#### 경계 조건 점검

- AC 3: 7/8/64/65자 네 점이 모두 있습니다.
- AC 4: 1/2/12/13자 네 점이 모두 있습니다.
- AC 6의 "동시": 경합 중 미리 조회를 둘 다 통과하는 경우를 래퍼로 매번 재현합니다. 그래서 경계 경로가 운에 기대지 않습니다.

#### 갭이 있는 AC에 붙일 시나리오 제안

AC 8용입니다. 이미 문서 :263에서 #12로 미룬 것과 같으므로 #12 시나리오 문서에 붙이면 됩니다.

```
- [ ] [정상] `SignUpPage` — should navigate to /login when sign up succeeds
- [ ] [예외] `SignUpPage` — should show server message when sign up fails with 409
- [ ] [예외] `SignUpPage` — should show server message when sign up fails with 400
```

세 번째는 1차 제안에 없던 것입니다. 수동 확인에서도 400 메시지 표시를 보지 않았기 때문에 추가했습니다. #12 전까지 수동으로 보완하려면 수동 확인 목록에 "7자 비밀번호로 제출 → '비밀번호는 8~64자이고 영문과 숫자를 모두 포함해야 합니다' 표시"를 하나 넣으면 됩니다.

#### 가짜 테스트와 약한 단언

인자 없는 예외 단언, `isInstanceOf(Exception.class)`나 `RuntimeException.class`, 단언 없는 테스트는 없습니다(실행 명령 6번의 grep 0건). 예외 단언은 모두 `BusinessException`과 `ErrorCode`까지 확인합니다. 남은 약한 단언은 다음과 같고, 판정을 바꾸지는 않습니다.

1. **common/security/OriginValidationFilterTest.java:87-103**: 거부 테스트 3개가 상태 코드만 보고, 요청이 다음 필터로 넘어가지 않았는지는 확인하지 않습니다. 1차에서 지적했고 아직 그대로입니다. 별도 테스트로 `assertThat(passed()).isFalse()`를 추가하면 조일 수 있습니다.
2. **member/MemberControllerTest.java:71, :74**: `$.id == 1`과 `$.role == "MEMBER"`는 setUp의 목 응답(:44-48)을 그대로 단언하므로 직렬화 검사일 뿐입니다. 이제 role은 통합 테스트(member/MemberSignUpIntegrationTest.java:158)가 실제 DB에서 확인하므로 보완됐습니다.
3. **member/MemberServiceTest.java:90, :102**: 손으로 쓴 `ORA-00001` 메시지에 의존합니다. 실제 메시지 형식은 통합 테스트(:162, :175)가 고정했으므로 이제 보조 테스트 역할입니다.
4. **member/MemberSignUpIntegrationTest.java:103-130**: 동시성 테스트 자체는 여전히 경합이 실제로 일어났는지와 무관하게 통과합니다. 다만 경합 경로는 :162, :175가 확실히 실행하므로 AC 6의 갭은 아닙니다.

참고로 통합 테스트는 `unique()`(6자리 난수)로 데이터를 구분합니다. Testcontainers를 재사용하지 않아 실행마다 새 DB를 쓰므로(TestcontainersConfiguration.java:17) 충돌 가능성은 무시할 만합니다.

---

## 보안 점검

> `/harness:security-review 1` (2026-11-20). 예외 대장(`securityExceptions`)은 아직 없다.

### 수집

| 항목 | 결과 |
| --- | --- |
| 타입 검사 (`typecheck`) | 오류 0 (수정 전·후 모두) |
| 의존성 (`audit` = 프론트 `npm audit`) | 취약점 0 |
| 하드코딩된 비밀값·알려진 키 접두사 | 없음 (`backend/src/main`, `frontend/src`) |
| `.env` | 작업 트리·브랜치 커밋 모두 없음, `.gitignore`가 막음 |
| 클라이언트 노출 접두사(`VITE_`) | 프론트에서 환경변수를 쓰지 않음 |
| 로그 호출 | 프론트 `console.log`·백엔드 로거 호출 없음 |
| 5xx 응답 내부 정보 | Spring 기본값(`server.error`의 message·stacktrace 미포함) |
| 비밀번호 저장 | BCrypt만 (AC 1 통합 테스트) |

임시 프로브 테스트(커밋하지 않음)로 AC 검증자의 범위 밖 관찰 두 건과 깨진 JSON을 실제로 돌려 봤다.

| 입력 | 결과 |
| --- | --- |
| 비밀번호 한글 62자 + `a1` (64자, 188바이트) | `IllegalArgumentException: password cannot be more than 72 bytes` → 처리되지 않아 500 |
| 이메일 132자·386바이트(한글 로컬·도메인) | `@Email`이 400으로 막음 (DB 오류 재현 안 됨) |
| 깨진 JSON `{"email":` | 400이지만 본문이 비어 있음 (`{code, message}` 아님) |

### 분류

| 분류 | 항목 | 위치 | 판단 | 처리 |
| --- | --- | --- | --- | --- |
| 🔴 | 비밀번호 72바이트 초과 시 500 | `SignUpRequest.java`, `MemberService.java` | 사용자 입력만으로 처리되지 않는 예외가 난다. 이 이슈가 만든 경로의 입력 검증 부족 | **수정함.** 사용자 결정(A안): 비밀번호를 공백을 뺀 ASCII(`[!-~]`)로 제한. 시나리오 1개 추가(Red `ae158e6` → Green `1ca64e6`), 확정 사양 2절 반영 |
| 🟡 | 깨진 JSON의 400 본문이 비어 있음 | `GlobalExceptionHandler` | 화면에 일반 문구만 뜬다. 보안 문제가 아니라 응답 형식 불일치 | #2에서 에러 처리를 손볼 때 `HttpMessageNotReadableException` → `INVALID_INPUT` |
| 🟡 | `@Email`·`@NotBlank`·`@Size` 메시지가 영어 기본값 | `SignUpRequest.java` | 화면이 서버 메시지를 그대로 보여 준다 | 한글 메시지를 단다 (다음 회원 이슈) |
| 🟡 | 가입 요청 횟수 제한 없음 | 가입 API | 계정 대량 생성이 가능하다. 확정 사양 밖 | 배포 때 nginx `limit_req` |
| 🟡 | 판별하지 못한 중복 오류는 메시지에 입력값(이메일)을 담은 채 로그로 간다 | `MemberService.java` `duplicateCodeOf` | 지금은 제약 2개를 모두 판별해 이 경로에 오지 않는다 | 회원 테이블에 UNIQUE 제약을 더할 때 판별 목록도 늘린다 |
| ⚪ | 가입 409로 계정 존재가 드러남 | 가입 API | AC 2가 요구하는 동작. 로그인은 #2에서 같은 메시지로 막는다 | — |
| ⚪ | 헬스체크 공개, 구성 요소 UP/DOWN 표시 | `SecurityConfig.java`, `application.yml` | `show-details`가 기본값(never)이라 접속 정보는 나가지 않는다 | — |
| ⚪ | 백엔드(Gradle) 의존성 스캔 도구 없음 | `harness.json`의 `audit` | 지금은 npm만 본다 | CI를 붙일 때 Gradle 의존성 검사를 넣는다 |

### 재스캔

수정 뒤 `typecheck` 오류 0, `audit` 취약점 0, `test` 38개 전부 통과(이번 이슈 37 + 기존 1). 남은 🟡·⚪는 위 표의 이유로 의도적으로 남겼다.
