# 이슈 #3 — 로그인을 연속으로 틀리면 잠시 막힌다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/3
> PRD: `docs/result/prd/member.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

---

## 시그니처

### 관련 ADR

- **계정 M3** — 이메일별(소문자로 맞춘 이메일)·IP별 실패 카운터를 Redis에 둔다. **비밀번호를 비교하기 전에** Lua 스크립트(Redis 안에서 한 번에 실행되는 작은 프로그램) 하나로 두 카운터를 원자적으로 확인하고 올린다. 이메일 카운터는 실패할 때마다 만료를 15분으로 다시 잡고(마지막 실패 기준, 2026-11-20 확정), IP 카운터는 첫 실패에만 만료 15분을 잡는다(고정 구간). 성공하면 이메일 카운터를 지운다.
- **계정 M1** — 센다 → 막혔으면 거부 → 비교 → 성공하면 초기화 순서를 `MemberService.login` 한 메서드에 둔다.
- **계정 PRD 작은 결정(실제 사용자 IP)** — `server.forward-headers-strategy: native`. nginx(`infra/nginx/nginx.conf`)는 `X-Forwarded-For`를 `$remote_addr`로 덮어써 넘기고, Tomcat은 내부 프록시 대역(도커 네트워크 포함)에서 온 이 헤더만 믿는다.

### 타입

```java
// com.livestream.member
@Component
class LoginAttemptLimiter {
    static final int EMAIL_LIMIT = 5;
    static final int IP_LIMIT = 20;
    static final Duration WINDOW = Duration.ofMinutes(15);
    // Redis 키: login:fail:email:{소문자 이메일}, login:fail:ip:{ip}

    LoginAttemptLimiter(StringRedisTemplate redis);

    // 비밀번호 비교 전에 부른다. 이메일 카운터가 5, IP 카운터가 20에 이미 닿았으면 아무것도 세지 않고
    // RetryLaterException(MEMBER_LOGIN_BLOCKED, 남은 초)를 던진다. 둘 다 막혔으면 더 긴 쪽의 남은 초.
    // 아니면 두 카운터를 1씩 올린다
    void acquire(String email, String ip);

    // 로그인 성공 뒤: 이메일 카운터를 지우고, 미리 센 IP 카운터 1을 되돌린다
    void succeeded(String email, String ip);
}

public class MemberService {
    public MemberService(MemberMapper memberMapper, PasswordEncoder passwordEncoder,
                         LoginAttemptLimiter loginAttemptLimiter);   // 생성자에 하나 추가
    public MemberResponse login(LoginRequest request, String clientIp); // ip 파라미터 추가
}

class AuthController {
    // login 안에서 httpRequest.getRemoteAddr()를 서비스에 넘긴다 (시그니처는 그대로)
}
```

```java
// com.livestream.common.error
public enum ErrorCode {
    // 추가. %d는 남은 분. RetryLaterException으로만 던진다
    MEMBER_LOGIN_BLOCKED(HttpStatus.TOO_MANY_REQUESTS, "로그인 시도가 너무 많습니다. %d분 후 다시 시도해 주세요");
}

// 잠시 뒤에 다시 하라는 예외. 남은 시간을 함께 든다
public class RetryLaterException extends BusinessException {
    public RetryLaterException(ErrorCode code, long retryAfterSeconds); // 메시지의 %d = 남은 초를 분으로 올림
    public long getRetryAfterSeconds();
}

class GlobalExceptionHandler {
    // 429 + Retry-After: {초} 헤더 + {"code":"MEMBER_LOGIN_BLOCKED","message":"…N분 후…"}
    @ExceptionHandler(RetryLaterException.class)
    ResponseEntity<ErrorResponse> handleRetryLater(RetryLaterException e);
}
```

`application.yml`: `server.forward-headers-strategy: native`

### 에러 케이스

| 상황 | 에러 코드 | HTTP |
| --- | --- | --- |
| 같은 이메일로 이미 5번 실패했거나, 같은 IP에서 이미 20번 실패함 (비밀번호가 맞아도) | `MEMBER_LOGIN_BLOCKED` — `Retry-After` 헤더에 남은 초, 메시지에 올림한 분 | 429 |

### 화면

바꿀 것이 없다. 로그인 화면은 서버가 준 `message`를 그대로 보여 준다(#2). 서버가 "N분 후" 문구를 만든다.

### 판단이 갈린 곳

1. 남은 시간(초)은 HTTP 표준 `Retry-After` 헤더로 준다. 본문은 지금의 `{code, message}` 모양을 그대로 쓰고, 분 단위 문구는 서버가 올림으로 만든다.
2. 막힌 요청은 아무것도 세지 않는다. 세면 막힌 요청이 이메일 만료를 다시 잡아 "5번째 실패 기준 15분"(AC 3)이 깨지고, 공격자가 두드리는 동안 차단이 끝없이 늘어난다.
3. 로그인에 성공하면 IP 카운터 1을 되돌린다. 비교 전에 세므로, 되돌리지 않으면 성공한 로그인도 IP 카운터에 쌓여 한 IP를 여럿이 쓰는 곳은 성공 20번에도 막힌다. 확정 사양은 "IP에서 20번 **실패**"다.
4. "nginx가 넘긴 실제 IP"(AC 6)는 테스트와 수동 확인으로 나눠 본다. MockMvc는 Tomcat을 거치지 않으므로, 테스트는 "IP가 다르면 따로 센다"와 "컨트롤러가 `getRemoteAddr()`를 넘긴다"까지 본다. `X-Forwarded-For` 반영은 Green에서 `bootRun`에 curl로 확인한다. 실제 포트로 띄우는 테스트는 컨텍스트가 하나 더 떠서 Oracle 컨테이너를 한 번 더 기동하므로 쓰지 않는다(CLAUDE.md).
5. `MemberService` 생성자와 `login` 시그니처가 바뀌므로, 기존 테스트(`MemberServiceTest`·`AuthControllerTest`·`MemberSignUpIntegrationTest`)의 생성·호출 부분을 Red에서 맞춘다. 단언은 바꾸지 않는다.
6. 테스트끼리 같은 Redis를 쓰므로, 새 통합 테스트는 요청마다 다른 IP(`remoteAddr`)와 새 이메일을 쓴다. 같은 IP를 쓰면 다른 테스트의 실패가 IP 카운터에 쌓인다.

### 이 이슈에서 만들지 않는 것

- 사람 확인(캡차), 차단 수동 해제, 가입 요청 횟수 제한(배포 때 nginx `limit_req`)
- nginx 설정 변경 — 이미 `X-Forwarded-For`를 `$remote_addr`로 넘긴다

---

## 테스트 시나리오

### 정상

- [x] [정상] `MemberService.login` — should count the attempt before comparing password
- [x] [정상] `MemberService.login` — should report success to the limiter when password matches
- [x] [정상] `LoginAttemptLimiter` — should reset email counter expiry to 15 minutes on every failure
- [x] [정상] `LoginAttemptLimiter` — should keep ip counter expiry from the first failure
- [x] [정상] `LoginAttemptLimiter` — should count attempts from different ips separately
- [x] [정상] `POST /api/auth/login` — should pass the client ip to the service
- [x] [정상] `MemberLoginAttemptIntegration` — should log in after four failures and block only after five more failures

### 경계

- [x] [경계] `LoginAttemptLimiter` — should allow the fifth attempt when email has four failures
- [x] [경계] `LoginAttemptLimiter` — should block with remaining seconds when email has five failures
- [x] [경계] `LoginAttemptLimiter` — should report remaining time from the fifth failure when blocked
- [x] [경계] `LoginAttemptLimiter` — should not extend the block when a blocked attempt arrives
- [x] [경계] `LoginAttemptLimiter` — should block the 21st attempt from an ip with 20 failures across different emails
- [x] [경계] `LoginAttemptLimiter` — should not count successful logins toward the ip limit
- [x] [경계] `POST /api/auth/login` — should round remaining time up to whole minutes in the message
- [x] [경계] `MemberLoginAttemptIntegration` — should compare password at most five times when 20 wrong-password logins for one email arrive at once

### 예외

- [x] [예외] `MemberService.login` — should not compare password when attempts are blocked
- [x] [예외] `POST /api/auth/login` — should return 429 with Retry-After header when attempts are blocked
- [x] [예외] `MemberLoginAttemptIntegration` — should return 429 with Retry-After when correct password is sent after five failures
- [x] [예외] `MemberLoginAttemptIntegration` — should return code MEMBER_LOGIN_BLOCKED with minutes message when blocked

### 수동 확인 (테스트 도구가 없거나 MockMvc로 볼 수 없는 것 — 시나리오 수에 넣지 않는다)

Green 단계에서 확인하고 결과를 이 문서와 PR에 남긴다.

- 로그인 화면에서 같은 이메일로 5번 틀린 뒤 6번째를 보내면 "로그인 시도가 너무 많습니다. 15분 후 다시 시도해 주세요"가 보인다 (AC 8)
- `bootRun`에 `X-Forwarded-For: A`로 서로 다른 이메일 20번 실패 → 21번째 429, 이어서 `X-Forwarded-For: B`로 보내면 401 (AC 6의 "nginx가 넘긴 실제 IP")

**확인 결과 (2026-11-20, Green):** 로컬에서 Oracle·Redis 컨테이너 + `bootRun` + Vite 개발 서버로 확인했다.

- 화면 문구: `/login`에서 #2 수동 확인 때 가입한 계정으로 틀린 비밀번호 5번 → 모두 401과 "이메일 또는 비밀번호가 맞지 않습니다", 6번째 → 429와 "로그인 시도가 너무 많습니다. 15분 후 다시 시도해 주세요" 표시 ✅
- 실제 IP: `bootRun`(127.0.0.1, Tomcat이 믿는 내부 프록시)에 curl로 `X-Forwarded-For: 203.0.113.50`, 서로 다른 이메일 20번 → 모두 401, 21번째 429(`Retry-After: 897`, `MEMBER_LOGIN_BLOCKED`, "15분 후"). 이어서 `X-Forwarded-For: 203.0.113.51` → 401 ✅. 헤더가 무시됐다면 모두 127.0.0.1로 세어 두 번째 IP도 429였다

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 같은 이메일 5번 실패 후 맞는 비밀번호로 6번째 → 429, 남은 시간(초)이 응답에 | `[경계] LoginAttemptLimiter — should block with remaining seconds when email has five failures`<br>`[예외] MemberService.login — should not compare password when attempts are blocked`<br>`[예외] POST /api/auth/login — should return 429 with Retry-After header when attempts are blocked`<br>`[예외] MemberLoginAttemptIntegration — should return 429 with Retry-After when correct password is sent after five failures` |
| 2. 4번 실패 후 맞는 비밀번호 → 성공, 그 뒤 다시 5번 틀려야 막힘 | `[경계] LoginAttemptLimiter — should allow the fifth attempt when email has four failures`<br>`[정상] MemberService.login — should report success to the limiter when password matches`<br>`[정상] MemberLoginAttemptIntegration — should log in after four failures and block only after five more failures` |
| 3. 5번째 실패 직후 차단 응답의 남은 시간은 900초 이하, 5번째 실패 기준 | `[경계] LoginAttemptLimiter — should report remaining time from the fifth failure when blocked`<br>`[경계] LoginAttemptLimiter — should not extend the block when a blocked attempt arrives` |
| 4. 4번 실패하는 동안 실패마다 이메일 카운터 만료가 15분으로 다시 잡힘 | `[정상] LoginAttemptLimiter — should reset email counter expiry to 15 minutes on every failure` |
| 5. 같은 IP 서로 다른 이메일 20번 실패 → 21번째 429, IP 카운터 만료는 첫 실패 기준 15분 | `[경계] LoginAttemptLimiter — should block the 21st attempt from an ip with 20 failures across different emails`<br>`[정상] LoginAttemptLimiter — should keep ip counter expiry from the first failure` |
| 6. 다른 IP의 요청은 IP 카운터가 따로 셈(nginx가 넘긴 실제 IP 기준) | `[정상] LoginAttemptLimiter — should count attempts from different ips separately`<br>`[정상] POST /api/auth/login — should pass the client ip to the service`<br>수동 확인: `X-Forwarded-For` 반영 |
| 7. 같은 이메일로 틀린 비밀번호 20개 동시 → 비밀번호 비교는 5번까지 | `[경계] MemberLoginAttemptIntegration — should compare password at most five times when 20 wrong-password logins for one email arrive at once` |
| 8. 로그인 화면이 429를 받으면 "로그인 시도가 너무 많습니다. N분 후 다시 시도해 주세요" | `[예외] MemberLoginAttemptIntegration — should return code MEMBER_LOGIN_BLOCKED with minutes message when blocked`<br>`[경계] POST /api/auth/login — should round remaining time up to whole minutes in the message`<br>수동 확인: 화면 표시 |

**AC에 없는데 넣은 시나리오**

| 시나리오 | 이유 |
| --- | --- |
| `[경계] LoginAttemptLimiter — should not count successful logins toward the ip limit` | 판단 3. 확정 사양의 "20번 **실패**"를 지킨다 |
| `[정상] MemberService.login — should count the attempt before comparing password` | PRD M3의 "비교 전에 센다" 순서를 단위 테스트로 박는다. AC 7의 동시성 보장이 이 순서에 기댄다 |

**커버리지:** AC 8개 / 시나리오 19개 / 미커버 0개 (AC 6·8의 일부는 수동 확인)
