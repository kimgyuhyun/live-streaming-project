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
- [x] [경계] `LoginAttemptLimiter` — should block when the email block has less than a second left
- [x] [경계] `LoginAttemptLimiter` — should allow the attempt and start a new count after the email block expires
- [x] [경계] `LoginAttemptLimiter` — should report about 15 minutes remaining when an ip is blocked right after its 20th failure
- [x] [경계] `LoginAttemptLimiter` — should not create an ip counter when a success arrives after the ip counter expired
- [x] [경계] `LoginAttemptLimiter` — should not count a blocked attempt toward the ip counter
- [x] [경계] `POST /api/auth/login` — should round remaining time up to whole minutes in the message
- [x] [경계] `POST /api/auth/login` — should return 400 when email is 255 characters
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
| 3. 5번째 실패 직후 차단 응답의 남은 시간은 900초 이하, 5번째 실패 기준 | `[경계] LoginAttemptLimiter — should report remaining time from the fifth failure when blocked`<br>`[경계] LoginAttemptLimiter — should not extend the block when a blocked attempt arrives`<br>`[경계] LoginAttemptLimiter — should block when the email block has less than a second left` (AC 검증 갭)<br>`[경계] LoginAttemptLimiter — should allow the attempt and start a new count after the email block expires` (AC 검증 갭) |
| 4. 4번 실패하는 동안 실패마다 이메일 카운터 만료가 15분으로 다시 잡힘 | `[정상] LoginAttemptLimiter — should reset email counter expiry to 15 minutes on every failure` |
| 5. 같은 IP 서로 다른 이메일 20번 실패 → 21번째 429, IP 카운터 만료는 첫 실패 기준 15분 | `[경계] LoginAttemptLimiter — should block the 21st attempt from an ip with 20 failures across different emails`<br>`[정상] LoginAttemptLimiter — should keep ip counter expiry from the first failure`<br>`[경계] LoginAttemptLimiter — should report about 15 minutes remaining when an ip is blocked right after its 20th failure` (AC 검증 갭) |
| 6. 다른 IP의 요청은 IP 카운터가 따로 셈(nginx가 넘긴 실제 IP 기준) | `[정상] LoginAttemptLimiter — should count attempts from different ips separately`<br>`[정상] POST /api/auth/login — should pass the client ip to the service`<br>수동 확인: `X-Forwarded-For` 반영 |
| 7. 같은 이메일로 틀린 비밀번호 20개 동시 → 비밀번호 비교는 5번까지 | `[경계] MemberLoginAttemptIntegration — should compare password at most five times when 20 wrong-password logins for one email arrive at once` |
| 8. 로그인 화면이 429를 받으면 "로그인 시도가 너무 많습니다. N분 후 다시 시도해 주세요" | `[예외] MemberLoginAttemptIntegration — should return code MEMBER_LOGIN_BLOCKED with minutes message when blocked`<br>`[경계] POST /api/auth/login — should round remaining time up to whole minutes in the message`<br>수동 확인: 화면 표시 |

**AC에 없는데 넣은 시나리오**

| 시나리오 | 이유 |
| --- | --- |
| `[경계] LoginAttemptLimiter — should not count successful logins toward the ip limit` | 판단 3. 확정 사양의 "20번 **실패**"를 지킨다 |
| `[정상] MemberService.login — should count the attempt before comparing password` | PRD M3의 "비교 전에 센다" 순서를 단위 테스트로 박는다. AC 7의 동시성 보장이 이 순서에 기댄다 |
| `[경계] LoginAttemptLimiter — should not create an ip counter when a success arrives after the ip counter expired` | AC 검증 갭. 판단 3의 "성공은 IP 카운터 1을 되돌린다"가 만료 뒤에 음수·무기한 키를 만들지 않는지 지킨다 (변이 D) |
| `[경계] LoginAttemptLimiter — should not count a blocked attempt toward the ip counter` | AC 검증 갭. 판단 2의 "막힌 시도는 세지 않는다"를 IP 쪽에서도 지킨다 (변이 P) |
| `[경계] POST /api/auth/login — should return 400 when email is 255 characters` | 보안 점검 🟡. 이메일이 Redis 키가 되므로 가입과 같은 254자로 막는다 |

**커버리지:** AC 8개 / 시나리오 25개 / 미커버 0개 (AC 6·8의 일부는 수동 확인). 25개 중 5개는 AC 검증 뒤 갭을 메우려, 1개는 보안 점검 뒤 더했다

---

## AC 검증

> `harness:ac-verifier` (2026-11-20, HEAD 0e4bcdc). 아래는 검증자 출력 원문이다. 구현자는 맨 아래 `### 구현자 이견`에만 쓴다.

**결과: AC 8개 중 충족 6, 부분 충족 2, 미충족 0.** 부분 충족은 AC 3과 AC 5다.

- 기준 실행: `./gradlew test --rerun` 결과 82개 전부 통과, 실패 0(XML 리포트 집계).
- 변이 검사: 저장소 밖 scratchpad에 `git archive`로 꺼낸 사본에서 돌렸다.
- 저장소는 끝까지 `git status` 깨끗, HEAD 0e4bcdc 그대로다.
- 판정 표기는 이모지 대신 글자로 쓴다: [충족] / [부분 충족] / [미충족].

### 판정표

| AC | 판정 | 근거 테스트 | 구현 | 설명 |
|---|---|---|---|---|
| 1. 5번 실패 → 맞는 비밀번호 6번째 → 429, 남은 초 | [충족] | `LoginAttemptLimiterTest.java:122-131`(남은 초가 1~900 사이), `MemberServiceTest.java:230-240`(비교 안 함), `AuthControllerTest.java:119-127`(`Retry-After: 840` 그대로), `MemberLoginAttemptIntegrationTest.java:150-162`(429 + 헤더 있음) | `LoginAttemptLimiter.java:26-28,49-55`, `MemberService.java:53`(비교 전 acquire), `GlobalExceptionHandler.java:21-27` | 변이 N(Retry-After 헤더 삭제)을 테스트 2개가 잡았다 |
| 2. 4번 실패 → 성공, 그 뒤 다시 5번 틀려야 막힘 | [충족] | `MemberLoginAttemptIntegrationTest.java:94-110`(401×4, 200, 401×5, 429 순서를 정확히 단언), `LoginAttemptLimiterTest.java:113-120`, `MemberServiceTest.java:222-227` | `MemberService.java:61`, `LoginAttemptLimiter.java:36-40`(DEL) | 변이 M(succeeded 호출 제거)이 통합 테스트(:109)와 단위 테스트에 걸렸다. 변이 G(`>=`를 `>`로)도 잡혔다 |
| 3. 5번째 실패 직후 남은 시간 ≤900초, 5번째 실패 기준 | [부분 충족] | `LoginAttemptLimiterTest.java:122-131`(≤900), `:133-145`(앞 4번의 만료를 100초로 줄인 뒤 5번째 → 890 초과), `:147-157`(막힌 요청이 만료를 늘리지 않음) | `LoginAttemptLimiter.java:26,29-30` | 적힌 조건은 맞다. 변이 A(막힌 요청도 셈)와 B(이메일 만료를 첫 실패에만)가 잡혔다. 그러나 차단이 끝나는 쪽 경계가 테스트에 없고, 구현이 그 경계에서 어긋난다(아래 발견 1) |
| 4. 실패마다 이메일 만료를 15분으로 다시 | [충족] | `LoginAttemptLimiterTest.java:74-88`(만료를 10초로 줄이고 실패 3번 → 매번 890 초과) | `LoginAttemptLimiter.java:29-30` | 변이 B가 :87·:144에서 잡혔다. 첫 실패의 만료 값은 따로 단언하지 않는다(사소함) |
| 5. 같은 IP 다른 이메일 20번 → 21번째 429, IP 만료는 첫 실패 기준 15분 | [부분 충족] | `LoginAttemptLimiterTest.java:159-168`(21번째 차단; 20번째 통과는 루프 안 acquire로 암묵 확인), `:90-100`(만료를 다시 잡지 않음) | `LoginAttemptLimiter.java:27,31` | 변이 C(IP 만료를 매번)는 잡혔다. 그러나 IP 구간이 **15분**이라는 값은 어디서도 단언하지 않는다. 변이 O(IP 만료를 60초로 고정)가 살아남았다. 21번째 차단 테스트도 예외 타입만 보고 남은 초는 안 본다 |
| 6. IP별로 따로 셈(nginx가 넘긴 실제 IP) | [충족] | `LoginAttemptLimiterTest.java:102-111`, `AuthControllerTest.java:102-116` + 내가 따로 돌린 실제 Tomcat 확인(아래) | `AuthController.java:35`(`getRemoteAddr()`), `application.yml:25`(`forward-headers-strategy: native`), `infra/nginx/nginx.conf:43`(XFF를 `$remote_addr`로 덮어씀) | 변이 H(IP 상수)가 잡혔다. XFF 반영은 저장소 테스트에 없다(판단 4). 대신 scratch 사본에서 RANDOM_PORT로 확인했다: XFF A로 20번 401, 21번째 429(`Retry-After: 899`), XFF B는 401, 헤더 없음도 401. 설정 줄을 지운 대조 실행에서는 B가 429로 테스트가 실패했다. 이 설정이 실제로 동작을 바꾼다는 뜻이다 |
| 7. 같은 이메일 틀린 비밀번호 20개 동시 → 비교 5번까지 | [충족] | `MemberLoginAttemptIntegrationTest.java:113-148`(진짜 Redis·매퍼, 비교를 세는 인코더, `isEqualTo(5)`라 "이하"보다 강함) | `LoginAttemptLimiter.java:22-33`(Lua 한 번), `MemberService.java:53` | 변이 F(확인과 증가를 Lua와 Java 호출로 쪼갬)와 I(비교 뒤 acquire) 모두 :147에서 실패했다 |
| 8. 화면이 429에서 "…N분 후…" 표시 | [충족] | `MemberLoginAttemptIntegrationTest.java:164-176`(문구 정확 일치 "15분"), `AuthControllerTest.java:130-137`(61초 → "2분") | `RetryLaterException.java:10`(올림), `ErrorCode.java:13`, `frontend/src/pages/LoginPage.tsx:38`, `frontend/src/api/client.ts:33,38-43` | 변이 J(내림)가 :136에서 잡혔다. 화면 코드는 읽어서 429 본문의 message가 그대로 표시되는 경로를 확인했다. 실제 화면 표시는 issue-3.md의 수동 확인 기록에 기댄다. 나는 브라우저로 다시 해 보지 않았다 |

### 특별히 확인해 달라던 4가지

1. **막힌 요청이 카운터를 늘리는가 — 이메일은 안 늘리고, 테스트도 있다. IP 쪽은 테스트가 없다.**
   - 이메일 카운터: 늘리지 않는다. `LoginAttemptLimiter.java:28`에서 INCR 전에 반환한다. Redis 실험에서 이메일 5, TTL 100인 상태로 acquire하면 100을 돌려주고, 값 5와 TTL 100이 그대로이고, IP 키도 생기지 않았다.
   - 테스트(`:147-157`)는 이메일 TTL만 본다. "막힌 요청이 IP를 올림" 변이 P가 살아남았다(판단 2, AC 밖).
2. **이메일 만료가 마지막 실패 기준인가 — 맞다.** 실패마다 EXPIRE를 다시 건다(`:30`). 변이 B는 두 테스트에 잡혔다.
3. **동시 요청에서 비교가 정확히 5번인가 — 맞다.** 단언이 `isEqualTo(5)`이고, 비원자 변이 F를 잡았다.
4. **성공 시 IP 되돌리기가 음수나 만료 없는 키를 만드는가 — 구현은 만들지 않는다. 다만 이를 지키는 테스트가 없다.**
   - 구현 확인: 같은 Lua를 일회용 Redis에서 돌렸다. 키가 없을 때 succeeded를 부르면 키가 생기지 않았다(EXISTS 0). 값 1에 TTL 500인 키는 0이 되고 TTL 500이 유지됐다.
   - 테스트 공백: 조건 없이 DECR하는 변이 D가 테스트 37개를 전부 통과했다.
   - D가 실제로 들어오면 만료된 IP 키가 -1(만료 없음)이 된다. 다음 INCR이 0이 되어 만료를 안 잡으므로, 상한이 21번 이상으로 밀린다.

### 발견 1 — 차단 마지막 0.5초 미만에 요청이 통과한다 (AC 3 경계)

- **원인:** Redis `TTL`은 남은 시간을 반올림한다. 남은 시간이 500ms 미만이면 0을 돌려준다. `:26`의 `math.max(0, 0)`이 0이 되어 막지 않는다.
- **실측(일회용 redis:8.8.4-alpine 컨테이너):** 이메일 카운트 5, PTTL 396ms(TTL 0)인 상태에서 acquire하면 0을 돌려준다(통과). 그 뒤 카운트가 6, TTL이 900이 되고, 다음 시도는 900을 받는다.
- **결과:**
  - 차단 중인 요청이 비밀번호 비교까지 간다.
  - 그 시도가 틀리면 실패 한 번만으로 15분 차단이 새로 걸린다.
  - IP 쪽도 같다. 20, PTTL 395ms에서 통과해 21이 되지만, 이 키는 곧 만료된다.
- **평가:** 영향은 작다. 그래도 "15분 차단"의 끝 경계가 어긋나 있고, 이를 보는 테스트가 없다.

### 추가 시나리오 제안 (issue-3.md에 그대로 붙일 수 있는 형태)

- [ ] [경계] `LoginAttemptLimiter` — should block when the email block has less than a second left
  - 카운트 5인 키를 `PEXPIRE 400`으로 줄인다.
  - 그 뒤 acquire하면 `RetryLaterException`이 나야 한다(retryAfterSeconds ≥ 1). 지금 구현에서는 실패할 테스트다.
- [ ] [경계] `LoginAttemptLimiter` — should allow the attempt and start a new count after the email block expires
  - 키가 만료된 뒤 acquire가 통과하고 카운트가 1이어야 한다.
- [ ] [경계] `LoginAttemptLimiter` — should report about 15 minutes remaining when an ip is blocked right after its 20th failure
  - 21번째 차단에서 `retryAfterSeconds`가 890 초과 900 이하. 변이 O를 잡는다.
- [ ] [경계] `LoginAttemptLimiter` — should not create an ip counter when a success arrives after the ip counter expired
  - IP 키를 지운 뒤 `succeeded`를 부르면 키가 없어야 한다. 키가 있다면 값이 0 이상이고 TTL이 있어야 한다. 변이 D를 잡는다.
- [ ] [경계] `LoginAttemptLimiter` — should not count a blocked attempt toward the ip counter
  - 판단 2를 지키는 테스트다. 이메일로 막힌 요청 뒤 IP 카운터 값이 그대로여야 한다. 변이 P를 잡는다. AC 밖이라 선택 사항이다.

### 약한 단언 (가짜 테스트는 없다)

- **가짜 테스트에 해당하는 것은 없다.** 인자 없는 넓은 예외 단언(`Exception.class` 등)도, 단언 없는 테스트도, 목의 반환값만 다시 확인하는 테스트도 찾지 못했다.
- `MemberServiceTest.java:236-239`: `catchThrowable`으로 예외를 삼키고 `never()`만 본다.
  - `RetryLaterException`이 그대로 밖으로 나가는지는 단언하지 않는다.
  - 조이는 방법: 받은 값에 `isInstanceOf(RetryLaterException.class)`를 단언한다.
- `LoginAttemptLimiterTest.java:154-156`: 막혔는지 단언하지 않고 이메일 TTL만 본다. IP 카운터 증가는 놓친다(변이 P가 살아남은 이유).
  - 조이는 방법: 반환된 예외 타입과, 이메일·IP 카운터 값이 그대로인지를 함께 단언한다.
- `LoginAttemptLimiterTest.java:167`: 예외 타입만 본다.
  - 조이는 방법: `isInstanceOfSatisfying`으로 남은 초 범위까지 단언한다. 위 제안 3번과 같은 내용이다.
- `MemberLoginAttemptIntegrationTest.java:161`: `header().exists`만 본다. 값 전달은 컨트롤러 테스트가 보완한다.
  - 조이는 방법: 헤더 값이 1~900 사이인지 단언한다.

### 변이 검사 요약 (대상 4개 클래스, 37개)

- **잡힌 것:** A, B, C, E, F, G, H, I, J, M, N
- **살아남은 것:** D(IP 무조건 DECR), O(IP 만료 60초 고정), P(막힌 요청이 IP를 셈)
- **실행하지 않은 것:** K(`forward-headers-strategy` 삭제). MockMvc로는 원래 잡을 수 없다. 대신 위 XFF 대조 실행으로 확인했다.
- **처리 메모:**
  - 1차 실행 때 D·E·F·G·J·M과 E 재시도 일부가 Oracle 컨테이너 기동 실패(`ContainerLaunchException`)로 오염됐다. 해당 결과는 버리고 다시 돌렸다.
  - 위 판정은 실패 원인이 `AssertionError`인 경우만 "잡힘"으로 셌다.
  - 결과 파일의 "gradle exit"는 스크립트 버그로 의미가 없다. XML 리포트 집계를 기준으로 했다.

### 실행한 명령 목록

1. `cat .claude/harness.json; git log; git status; gh issue view 3` → 설정 있음, AC 8개 확인
2. `cat docs/result/scenarios/issue-3.md; git diff --stat main...HEAD` → AC 대조표·수동 확인 기록, 변경 파일 14개
3. `cat -n` LoginAttemptLimiter·MemberService·AuthController → 구현 줄 확인
4. `cat -n` RetryLaterException·BusinessException·ErrorCode·GlobalExceptionHandler·application.yml·nginx.conf → 처리기, `forward-headers-strategy`, XFF 덮어쓰기 확인
5. `cat -n` LoginAttemptLimiterTest, MemberLoginAttemptIntegrationTest → 테스트 본문 확인
6. `git diff main...HEAD` MemberServiceTest·AuthControllerTest·MemberSignUpIntegrationTest → 새 테스트 5개, 기존 테스트는 생성·호출만 바뀜
7. `sed -n 120,150p MemberServiceTest.java` → login `@BeforeEach` 스텁 확인
8. `grep` spec-fixed.md·prd/member.md·adr/member.md → M3, 마지막 실패 기준 확정(2026-11-20), 실제 IP 결정 확인
9. `cat` LoginPage.tsx, api/client.ts → 429 본문의 message가 화면에 나가는 경로 확인
10. `docker compose ps; docker ps; docker images` → 프로젝트 oracle·redis 실행 중, redis:8.8.4-alpine 이미지 있음
11. `./gradlew test` → UP-TO-DATE라 실행 안 됨. 이어서 `./gradlew test --rerun` → BUILD SUCCESSFUL, XML 집계 82개 실패 0
12. `docker run --rm redis:8.8.4-alpine` + Lua 실험
    - 첫 시도는 Git Bash 경로 변환 탓에 실패했다.
    - 두 번째 시도 결과: 남은 시간 400ms 이하에서 통과해 6/900이 됨, 무조건 DECR 없음 확인, 1→0 TTL 유지, 막힌 요청은 셈 없음
    - 컨테이너는 끝나고 지웠다.
13. `git archive HEAD backend` → scratchpad의 mut, xff 사본 생성. 변이 스크립트 DRY 실행으로 변이 적용 확인
14. 변이 1차(12개) → A·B·C·H·I·N 잡힘, 나머지는 컨테이너 실패로 무효
15. 변이 2차(D·E·F·G·J·M·O·P, 컨테이너 실패 시 재시도) → F·G·J·M 잡힘, D·O·P 살아남음, E는 다시 무효
16. E 단독 재실행 → `:180`에서 잡힘. XffScratchTest(native) 통과. 설정 줄 삭제 후 XffScratchTest → `:46`에서 실패(B=429)
17. `grep` MemberLoginIntegrationTest → 127.0.0.1에서 실패하는 로그인은 2번뿐이라 IP 상한과 엮일 일 없음
18. `git status --short; git rev-parse; docker ps -a` → 저장소 변경 없음, 남은 테스트 컨테이너 없음

코드·테스트·문서는 고치지 않았다.


### 최종 보고 (같은 검증자, 구현자가 "추가 실행 없이 바로 보고"를 요청한 뒤 다시 보낸 원문)

**결론: AC 8개 중 ✅ 6 / ⚠️ 2 / ❌ 0.** 부분 충족은 AC 3(차단이 끝나는 경계)과 AC 5(IP 구간 15분 값)다. 지시대로 추가 테스트·변이 실행은 하지 않았다. 아래 근거는 전부 이미 돌린 실행 결과다.

- **기준 실행:** 저장소에서 `./gradlew test --rerun`을 돌렸다. XML 리포트 집계 82개, 실패 0.
- **변이 검사:** 저장소가 아니라 `git archive`로 꺼낸 사본에서 했다. "잡힘"은 실패 원인이 `AssertionError`인 경우만 셌다. Oracle 컨테이너 기동 실패(`ContainerLaunchException`)로 오염된 실행은 버리고 다시 돌린 결과만 썼다.
- **저장소 상태:** `git status` 깨끗하고 HEAD는 0e4bcdc 그대로다. 코드·테스트·문서는 고치지 않았다.

#### 판정표

| AC | 판정 | 근거 테스트 | 구현 | 설명 |
|---|---|---|---|---|
| 1. 5번 실패 → 맞는 비밀번호로 6번째 → 429, 남은 초 | ✅ | `LoginAttemptLimiterTest.java:122-131`(남은 초 1~900), `MemberServiceTest.java:230-240`(비교 안 함), `AuthControllerTest.java:119-127`(`Retry-After: 840` 그대로), `MemberLoginAttemptIntegrationTest.java:150-162`(429 + 헤더 있음) | `LoginAttemptLimiter.java:26-28,49-55`, `MemberService.java:53`(비교 전 acquire), `GlobalExceptionHandler.java:21-27` | 변이 N(Retry-After 헤더 삭제)이 테스트 2개에 걸렸다(`AuthControllerTest:126`, `Integration:161`) |
| 2. 4번 실패 → 성공, 그 뒤 다시 5번 틀려야 막힘 | ✅ | `MemberLoginAttemptIntegrationTest.java:94-110`(401×4, 200, 401×5, 429를 정확히 단언), `LoginAttemptLimiterTest.java:113-120`, `MemberServiceTest.java:222-227` | `MemberService.java:61`, `LoginAttemptLimiter.java:36-40`(DEL) | 변이 M(succeeded 호출 제거)이 `Integration:109`와 `MemberServiceTest`에 걸렸다. 변이 G(`>=`를 `>`로)도 잡혔다 |
| 3. 5번째 실패 직후 남은 시간 ≤900초, 5번째 실패 기준 | ⚠️ | `LoginAttemptLimiterTest.java:122-131`(≤900), `:133-145`(앞 4번의 만료를 줄여도 5번째 뒤 남은 시간이 890 초과), `:147-157`(막힌 요청이 만료를 늘리지 않음) | `LoginAttemptLimiter.java:26,29-30` | 적힌 조건은 충족한다. 변이 A(막힌 요청도 셈)는 `:156`, 변이 B는 `:87`과 `:144`에서 잡혔다. 다만 차단이 끝나는 쪽 경계는 테스트가 없고, 구현이 그 경계에서 어긋난다(아래 발견 1) |
| 4. 실패마다 이메일 만료가 15분으로 다시 잡힘 | ✅ | `LoginAttemptLimiterTest.java:74-88`(만료를 10초로 줄이고 실패 3번 → 매번 890 초과) | `LoginAttemptLimiter.java:29-30` | 변이 B가 잡혔다. 첫 실패의 만료 값은 따로 단언하지 않는다(사소함) |
| 5. 같은 IP·다른 이메일 20번 → 21번째 429, IP 만료는 첫 실패 기준 15분 | ⚠️ | `LoginAttemptLimiterTest.java:159-168`(21번째 차단, 20번째 통과는 루프 안에서 암묵적으로 확인), `:90-100`(만료를 다시 잡지 않음) | `LoginAttemptLimiter.java:27,31` | 변이 C(IP 만료를 매번 다시 잡음)는 `:99`에서 잡혔다. 그러나 "15분"이라는 값은 어떤 테스트도 단언하지 않는다. 변이 O(IP 만료를 60초로 고정)가 살아남았다. 21번째 차단 테스트도 예외 타입만 확인한다 |
| 6. IP별로 따로 셈(nginx가 넘긴 실제 IP 기준) | ✅ | `LoginAttemptLimiterTest.java:102-111`, `AuthControllerTest.java:102-116`, scratch 사본의 실제 Tomcat 확인(`XffScratchTest`) | `AuthController.java:35`(`getRemoteAddr()`), `application.yml:25`(`forward-headers-strategy: native`), `infra/nginx/nginx.conf:43` | 변이 H(IP 상수)가 잡혔다. 저장소에는 XFF 반영을 보는 자동 테스트가 없다(판단 4). 그래서 scratch에서 RANDOM_PORT로 따로 확인했다: XFF A로 20번 401, 21번째 429(`Retry-After: 899`), XFF B 401, 헤더 없음 401. 설정 줄을 지운 대조 실행에서는 B가 429가 되어 `:46`에서 실패했다. 이 설정이 실제로 결과를 가른다. nginx를 거친 경로 자체는 확인 못 함(nginx 설정 파일만 읽음) |
| 7. 같은 이메일로 틀린 비밀번호 20개 동시 → 비교 5번까지 | ✅ | `MemberLoginAttemptIntegrationTest.java:113-148`(진짜 Redis·매퍼, 비교 횟수를 세는 인코더, `isEqualTo(5)`) | `LoginAttemptLimiter.java:22-33`(Lua 한 번), `MemberService.java:53` | 변이 F(확인과 증가를 쪼갬)와 I(비교 뒤에 acquire) 모두 `:147`에서 실패했다 |
| 8. 로그인 화면이 429를 받으면 "…N분 후 다시 시도해 주세요" | ✅ | `MemberLoginAttemptIntegrationTest.java:164-176`("15분" 문구 정확 일치), `AuthControllerTest.java:130-137`(61초 → "2분") | `RetryLaterException.java:10`(올림), `ErrorCode.java:13`, `frontend/src/pages/LoginPage.tsx:38`, `frontend/src/api/client.ts:33,38-43` | 변이 J(내림)가 `:136`에서 잡혔다. 서버 message가 화면에 그대로 나가는 경로는 코드를 읽어 확인했다. 실제 브라우저 표시는 확인 못 함(issue-3.md 수동 확인 기록에 기댐). 프론트 테스트 도구는 아직 없다 |

#### 특별히 확인해 달라던 4가지

1. **막힌 요청이 카운터를 늘리는가 — 이메일은 늘리지 않고, IP는 테스트가 없다.**
   - 이메일 카운터는 늘리지 않는다. `:28`에서 INCR보다 먼저 반환한다.
   - 일회용 Redis 실험(이메일 5, TTL 100)에서 acquire는 100을 돌려줬다. 값 5와 TTL 100이 그대로였고 IP 키도 생기지 않았다.
   - 테스트(`:147-157`)는 이메일 TTL만 본다. "막힌 요청도 IP를 올림" 변이 P가 살아남았다(판단 2, AC 밖).
2. **이메일 만료가 마지막 실패 기준인가 — 맞다.** 실패마다 EXPIRE를 다시 건다(`:30`). 변이 B가 잡혔다.
3. **동시 요청에서 비교가 정확히 5번인가 — 맞다.** `isEqualTo(5)`로 단언하고, 비원자 변이 F를 잡았다.
4. **성공 시 IP 되돌리기가 음수·무만료 키를 만드는가 — 구현은 안 만든다. 다만 지켜 주는 테스트가 없다.**
   - 일회용 Redis에서 키가 없을 때 succeeded를 부르면 키가 생기지 않았다(EXISTS 0).
   - 값 1, TTL 500인 키는 0이 되고 TTL 500이 유지됐다.
   - 무조건 DECR하는 변이 D는 테스트 37개를 전부 통과했다.
   - D가 실제로 들어가면 만료된 키가 -1(만료 없음)이 되고, 다음 INCR이 0이라 만료가 잡히지 않는다. 그만큼 IP 상한이 밀린다.

#### 발견 1 — 차단 마지막 0.5초 미만에 요청이 통과한다 (AC 3 경계)

- **원인:** Redis `TTL`은 남은 시간을 반올림해서, 500ms 미만이면 0을 준다. 그러면 `:26`의 `math.max(0, 0)`이 0이 되어 차단하지 않는다.
- **실측(일회용 redis:8.8.4-alpine):** 이메일 카운트 5, PTTL 396ms(TTL 0)에서 acquire가 0을 돌려줘 통과했다. 그 뒤 카운트 6, TTL 900이 됐고, 다음 시도는 900을 받았다.
- **결과:** 차단 중인 요청이 비밀번호 비교까지 간다. 틀리면 실패 한 번만으로 15분 차단이 새로 걸린다. IP도 같은 방식으로 통과한다(20, PTTL 395ms → 21).
- **평가:** 영향은 작다. 그래도 끝 경계를 보는 테스트가 없고, 구현이 그 경계에서 어긋난다.

#### 추가 시나리오 제안 (issue-3.md에 그대로 붙일 수 있는 형태)

- [ ] [경계] `LoginAttemptLimiter` — should block when the email block has less than a second left
  - 카운트 5인 키를 `PEXPIRE 400`으로 줄인 뒤 acquire → `RetryLaterException`, retryAfterSeconds ≥ 1.
  - 지금 구현에서는 실패할 테스트다.
- [ ] [경계] `LoginAttemptLimiter` — should allow the attempt and start a new count after the email block expires
- [ ] [경계] `LoginAttemptLimiter` — should report about 15 minutes remaining when an ip is blocked right after its 20th failure
  - retryAfterSeconds가 890 초과, 900 이하. 변이 O를 잡는다.
- [ ] [경계] `LoginAttemptLimiter` — should not create an ip counter when a success arrives after the ip counter expired
  - 변이 D를 잡는다.
- [ ] [경계] `LoginAttemptLimiter` — should not count a blocked attempt toward the ip counter
  - 판단 2를 지킨다. AC 밖이라 선택. 변이 P를 잡는다.

#### 가짜 테스트·약한 단언

**가짜 테스트는 없다.** 인자 없는 넓은 예외 단언, 단언 없는 테스트, 목 반환값만 다시 확인하는 테스트 모두 찾지 못했다. 약한 단언은 다음 4곳이다.

- `MemberServiceTest.java:236-239`: `catchThrowable`로 예외를 삼키고 `never()`만 본다. `RetryLaterException`이 그대로 밖으로 나가는지는 단언하지 않는다.
  - 조이기: 잡은 예외에 `isInstanceOf(RetryLaterException.class)`를 단언한다.
- `LoginAttemptLimiterTest.java:154-156`: 막혔는지는 단언하지 않고 이메일 TTL만 본다. 그래서 변이 P가 살아남았다.
  - 조이기: 예외 타입, 그리고 이메일·IP 카운터 값이 그대로인지를 단언한다.
- `LoginAttemptLimiterTest.java:167`: 예외 타입만 본다.
  - 조이기: `isInstanceOfSatisfying`으로 남은 초 범위까지 단언한다(위 제안 3번).
- `MemberLoginAttemptIntegrationTest.java:161`: `header().exists`만 본다. 헤더 값은 컨트롤러 테스트가 보완한다.
  - 조이기: 값이 1~900인지 단언한다.

#### 변이 검사 요약 (대상 4개 클래스, 37개)

- **잡힘:** A, B, C, E, F, G, H, I, J, M, N
- **살아남음:** D(IP 무조건 DECR), O(IP 만료 60초 고정), P(막힌 요청이 IP를 셈)
- **실행 안 함:** K(`forward-headers-strategy` 삭제). MockMvc로는 원래 볼 수 없어서, XFF 대조 실행으로 대신했다.
- **결과 파일 읽을 때 주의:** "gradle exit" 값은 스크립트 버그로 의미가 없다. 판정은 XML 리포트와 로그의 `AssertionError` 위치로 했다.

#### 백그라운드 프로세스

- 내가 띄운 백그라운드 작업 4개(기준 실행, 변이 1·2차, E 재실행 + XFF)는 모두 완료 알림을 받았다. 서버(`bootRun`)는 띄운 적이 없다.
- 일회용 Redis 컨테이너(`acv-redis-scratch`)는 실험 직후 지웠다.
- 마지막 확인 시점(19:59)에 Gradle 데몬 1개(PID 46244)가 BUSY였고, Testcontainers ryuk 컨테이너 2개가 19:59:06과 19:59:46에 새로 생겼다. 내 작업이 모두 끝난 뒤 생긴 것이라 다른 세션의 테스트 실행으로 보고 종료하지 않았다. 내 것이 아니라는 판단은 생성 시각에 근거한 추정이다.

#### 실행한 명령

1. `cat .claude/harness.json; git log; git status; gh issue view 3` → 설정 있음, AC 8개
2. `cat docs/result/scenarios/issue-3.md; git diff --stat main...HEAD` → AC 대조표와 수동 확인 기록, 변경 파일 14개
3. `cat -n` LoginAttemptLimiter·MemberService·AuthController → 구현 줄 확인
4. `cat -n` RetryLaterException·BusinessException·ErrorCode·GlobalExceptionHandler·application.yml·nginx.conf → 처리기·설정·XFF 덮어쓰기 확인
5. `cat -n` LoginAttemptLimiterTest, MemberLoginAttemptIntegrationTest → 테스트 본문
6. `git diff main...HEAD` MemberServiceTest·AuthControllerTest·MemberSignUpIntegrationTest → 새 테스트 5개, 기존 테스트는 호출부만 바뀜
7. `sed -n 120,150p MemberServiceTest.java` → login `@BeforeEach` 스텁 확인
8. `grep` spec-fixed.md·prd/member.md·adr/member.md → M3, 마지막 실패 기준 확정(2026-11-20), 실제 IP 결정
9. `cat` LoginPage.tsx, api/client.ts → message 표시 경로
10. `docker compose ps; docker ps; docker images` → 프로젝트 oracle·redis 실행 중
11. `./gradlew test` → UP-TO-DATE라 실행 안 됨. `./gradlew test --rerun` → 82개 통과, 실패 0
12. `docker run --rm redis:8.8.4-alpine` + Lua 실험 → 첫 시도는 경로 변환으로 실패. 두 번째에 위 TTL 경계·DECR 결과 확인. 컨테이너 삭제
13. `git archive HEAD backend` → scratch 사본 2개 생성. 변이 DRY 실행으로 적용 확인
14. 변이 1차(12개) → A·B·C·H·I·N 확정. 나머지는 컨테이너 실패로 무효
15. 변이 2차(D·E·F·G·J·M·O·P) → F·G·J·M 잡힘, D·O·P 살아남음, E 무효
16. E 단독 재실행 → `:180`에서 잡힘. XffScratchTest(native) 통과. 설정 삭제 후 `:46`에서 실패
17. `grep` MemberLoginIntegrationTest → 127.0.0.1에서 실패하는 로그인은 2번뿐이라 IP 상한과 엮이지 않음
18. `git status; docker ps -a; ./gradlew --status` → 저장소 변경 없음. 데몬·ryuk 상태는 위 "백그라운드 프로세스" 절

### 구현자 이견

- 판정에 이견이 없다. 발견 1(Redis `TTL` 반올림으로 차단 마지막 0.5초 미만에 통과)은 구현 결함으로 인정한다. Lua에서 `TTL` 대신 `PTTL`(밀리초)로 남은 시간을 보고 초로 올림하면 고칠 수 있다.

### 갭 처리

- 사용자 결정(2026-11-20): 검증자가 제안한 시나리오 5개를 모두 메운다.
- 5개를 `## 테스트 시나리오`의 경계 절과 AC 대조표에 더했다. 2단계(Red)로 돌아가 테스트로 옮기고, 3단계(Green)에서 발견 1을 고친다.

### 2차 검증 (갭을 메운 뒤 다시 띄운 검증자. 범위를 AC 3·5와 이번 diff로 좁힘. 원문이며, 이 문서의 절 구조를 깨지 않도록 제목 수준만 #### 로 낮췄다)

#### 이슈 #3 AC 2차 검증 (HEAD de26570)

**결과: AC 8개 중 충족 7, 부분 충족 1(AC 5, 가벼운 갭), 미충족 0.**

- AC 3은 이제 충족이다.
- AC 5는 1차 갭(변이 O)이 메워졌다. 다만 새 IP 테스트가 하한만 단언하고 상한(900 이하)은 안 본다. 1차 때 제안한 것은 "890 초과 900 이하"였다.
- 발견 1의 수정(PTTL 올림)은 맞다. PTTL이 -1이거나 -2일 때도 고치기 전과 똑같이 동작한다. 퇴행이 없다.
- 변이 검사는 3번 돌렸고 셋 다 테스트에 잡혔다.
- 저장소는 건드리지 않았다. `git status`는 시작 때와 같고(`M docs/result/scenarios/issue-3.md`만), HEAD는 그대로 de26570이다.

#### 판정표

| AC | 판정 | 근거 |
|---|---|---|
| 1 | [충족] 1차 판정 유지 | 이번 diff는 남은 시간 계산만 바꿨다. `LoginAttemptLimiterTest.java:140`(`isBetween(1, 900)`)이 그대로 통과한다. `ceil(PTTL/1000)`은 EXPIRE 900 직후에도 900을 넘지 않는다 |
| 2 | [충족] 1차 판정 유지 | diff가 INCR·EXPIRE·SUCCEEDED 경로를 건드리지 않았다(`LoginAttemptLimiter.java:31-33,38-42`) |
| 3 | **[충족]** (1차 때 부분 충족) | 아래 참고 |
| 4 | [충족] 1차 판정 유지 | 이메일 EXPIRE 줄(`:32`)은 바뀌지 않았다. `:86` 테스트도 통과한다 |
| 5 | **[부분 충족]** (갭이 줄었다) | 아래 참고 |
| 6 | [충족] 1차 판정 유지 | 키 구성(`:64-66`)과 컨트롤러는 diff 밖이다 |
| 7 | [충족] 1차 판정 유지 | 확인과 증가가 여전히 Lua 한 번 안에서 일어난다(`:23-35`). 원자성은 바뀌지 않았다 |
| 8 | [충족] 1차 판정 유지 | 남은 초의 최댓값은 고치기 전후 모두 900이다. `RetryLaterException.java:10`의 `(s+59)/60`으로 "15분"이 그대로 나온다. 통합 테스트 `MemberLoginAttemptIntegrationTest.java:175`도 같은 문구를 본다 |

##### AC 3 — 5번째 실패 직후 남은 시간은 900초 이하이고 5번째 실패 기준이다

[충족]

- **900초 이하:** `LoginAttemptLimiterTest.java:134-141`이 `isBetween(1, 900)`을 단언한다.
- **5번째 실패 기준:** `:146-155`가 앞 4번의 만료를 100초로 줄인 뒤 5번째를 실패시키고, 남은 시간이 890초를 넘는지 본다.
- **차단이 늘어나지 않음:** `:159-167`.
- **끝 경계, 직전(새 테스트):** `:196-204`. 카운트 5에 `PEXPIRE 400`을 건 뒤 acquire하면 `RetryLaterException`이 나고 남은 시간이 정확히 1이다. 0.5초 미만 구간을 직접 겨눈다. 변이 (a)를 잡았다.
- **끝 경계, 만료 뒤(새 테스트):** `:208-216`. 키를 1ms에 만료시킨 뒤 acquire가 통과하고, 카운트가 `"1"`로 새로 시작한다.
- **정확히 만료 시각:** Redis는 그 순간 키를 없는 것으로 본다. GET이 nil이니 카운트가 0이 되어 통과한다. 이는 만료 뒤 테스트와 같은 경로다.
- **구현:** `LoginAttemptLimiter.java:24,28`.

##### AC 5 — 같은 IP 20번 실패 → 21번째 429, IP 만료는 첫 실패 기준 15분

[부분 충족]

- **21번째 429:** `:171-178`.
- **첫 실패 기준(만료를 다시 잡지 않음):** `:102-110`.
- **15분 값(새 테스트):** `:220-229`가 21번째 차단의 남은 시간이 890초를 넘는지 단언한다. 변이 (b)(IP 만료 60초)를 잡았다. 실패 메시지는 "Expecting actual: 60L to be greater than: 890L"이었다.
- **남은 갭:**
  - `:228`은 `isGreaterThan(WINDOW_SECONDS - 10)`만 보고 900 이하 상한은 보지 않는다.
  - 그래서 IP 쪽만 900보다 긴 값(예: 3600)으로 바꾸는 변이는 이 클래스의 어떤 테스트에도 걸리지 않는다. 코드를 읽어서 내린 판단이고, 실행은 하지 않았다.
  - 지금 구현은 이메일과 IP가 같은 `ARGV[3]`을 쓴다(`:32-33`). 그래서 실제로 생길 위험은 낮다.
  - 하지만 AC의 "15분"을 한쪽 방향으로만 고정하고 있다.
- **구현:** `LoginAttemptLimiter.java:29,33`.

##### 판단 3·2를 지키는 새 테스트 (AC 밖)

- `:233-242`는 IP 키가 만료된 뒤 succeeded를 불렀을 때 `hasKey`가 false인지 본다. 변이 (c)(조건 없는 DECR)를 잡았다. 실패 메시지는 "Expecting value to be false but was true"였다.
- `:246-254`는 이메일로 막힌 요청 뒤 IP 카운터가 `"5"` 그대로인지 본다. 변이 P를 잡는 구조다. 여기에는 변이 실행을 쓰지 않았다(제한 3번). `catchThrowable`로 예외를 삼키지만, 막히지 않았다면 카운트가 6이 되어 실패하므로 가짜 테스트는 아니다.

#### 발견 1 수정 검토 (`LoginAttemptLimiter.java:24`, `math.ceil(PTTL/1000)`)

- **PTTL > 0:** 1~999ms는 1, 899001~900000ms는 900이 된다. 남은 시간이 1ms라도 1초로 올림해 막는다. 정확하다. Lua 숫자가 정수 응답으로 바뀔 때 잘리지만 이미 정수 값이라 문제없다.
- **PTTL = -2 (키 없음):**
  - 분기에 들어가지 않는다. GET이 nil이면 카운트가 0이라 `>= limit` 조건이 거짓이다(`:25-26,28-29`).
  - 스크립트가 도는 동안 Redis는 키를 만료시키지 않는다. 그래서 GET에서 값이 나왔는데 PTTL이 -2인 경우는 생기지 않는다.
  - 가정상 그런 일이 생겨도 `ceil(-0.002)`는 -0이다. `math.max(0, -0)`은 0이고 `wait > 0`은 거짓이라 통과해서 새로 센다. 키가 없으면 막히지 않아야 하므로 올바른 동작이다.
- **PTTL = -1 (만료 없음):**
  - `ceil(-0.001)`은 -0이라 막지 않는다(fail-open). 고치기 전의 `TTL`도 -1을 돌려줘 `math.max(0, -1)`이 0이었으니 동작이 똑같다. 퇴행이 아니다.
  - 이 코드로는 -1 키가 생기지 않는다. 이메일은 INCR 뒤 같은 스크립트에서 바로 EXPIRE한다(`:31-32`). IP는 값이 1일 때 EXPIRE한다(`:33`). DECR은 만료를 유지한다. SUCCEEDED는 값이 0보다 클 때만 DECR한다(`:40`).
  - 외부에서 손으로 만료 없는 키를 만들면 다음처럼 된다. 이메일 키는 다음 실패의 EXPIRE로 저절로 복구된다. IP 키는 상한을 넘어도 막지 않고 만료도 없이 남는다. 이는 AC 밖의 운영 사안이고, 고치기 전에도 같았다.

#### 추가 시나리오 제안

새 시나리오는 필요 없다. 기존 시나리오의 단언만 조이면 된다.

- [ ] [경계] `LoginAttemptLimiter` — should report about 15 minutes remaining when an ip is blocked right after its 20th failure
  - `:228`의 단언을 `isBetween(WINDOW_SECONDS - 9, WINDOW_SECONDS)`로 바꾼다. 1차 제안대로 "890 초과 900 이하"가 된다. 이것으로 IP 만료가 900보다 긴 변이까지 잡는다.

#### 가짜 테스트

- 없다. 새 테스트 5개 모두 구체 타입이나 값을 단언한다. 인자 없는 넓은 예외 단언도, 단언 없는 테스트도, 목 반환값만 다시 확인하는 테스트도 없다.
- 참고(가짜는 아님): `:196-204`는 `PEXPIRE 400`과 acquire 사이가 400ms를 넘으면 실패한다. 느린 CI에서는 드물게 흔들릴 수 있다. 여유를 두려면 2000ms로 바꾸고 `isEqualTo(2L)`로 단언하는 방법이 있다. 이 경우 1.5초에서 2초 사이 구간을 보게 되어 TTL 반올림 버그를 잡지 못한다. 그러니 지금 값 400ms를 유지하는 쪽이 낫다.

#### 실행한 명령 목록

1. `cat .claude/harness.json; git status --short; git diff 0e4bcdc..HEAD --stat -- backend; gh issue view 3` → 설정 있음. backend diff는 2개 파일(+77/-2). AC 8개 확인.
2. `git diff 0e4bcdc..HEAD -- backend; cat -n LoginAttemptLimiter.java` → 바뀐 것은 ACQUIRE의 `remaining()`(PTTL 올림)과 새 테스트 5개.
3. `cat -n LoginAttemptLimiterTest.java | sed -n 1,190p` → 기존 테스트 본문 확인.
4. `grep -n "^## \|^### " issue-3.md; git diff --stat issue-3.md`와 `sed -n 99,260p; sed -n 386,500p issue-3.md` → 시나리오 24개, AC 대조표, 1차 판정과 갭 처리 기록 확인.
5. `git archive HEAD backend | tar -x -C {scratchpad}/v2; docker ps` → scratch 사본 생성. Redis와 Oracle 컨테이너가 healthy인 것 확인.
6. `sh {scratchpad}/run2.sh` → scratch 사본에서 `./gradlew test --tests "LoginAttemptLimiterTest" --rerun`을 4번 돌렸다. 기준 1번과 변이 3번이다. 각 실행 뒤 원본 파일로 복구했다.
   - **base:** 14개 중 실패 0
   - **(a) `PTTL/1000`을 `TTL`로 되돌림:** 1개 실패. `should block when the email block has less than a second left`, "Expecting code to raise a throwable"
   - **(b) IP EXPIRE 900을 60으로:** 1개 실패. `should report about 15 minutes remaining when an ip is blocked right after its 20th failure`, "60L to be greater than 890L"
   - **(c) SUCCEEDED의 조건 없는 DECR:** 1개 실패. `should not create an ip counter when a success arrives after the ip counter expired`, "Expecting value to be false but was true"
7. `cat -n RetryLaterException.java; grep -n "15분|Retry-After|isBetween" MemberLoginAttemptIntegrationTest.java` → 분 올림 `(s+59)/60` 확인. 통합 테스트 :175가 "15분" 문구를 본다.
8. `until grep -q "c_decr exit" …; tail -6` → 변이 (c) 결과 수집(위 6번에 포함해 적었다).
9. `git status --short; git log -1; diff {scratch 사본} {저장소} LoginAttemptLimiter.java` → 저장소는 변경 없고 HEAD는 de26570이다. scratch 사본도 원본과 같게 복구됐다.
10. `grep -n "void |isGreaterThan|isEqualTo|isFalse|isBetween" LoginAttemptLimiterTest.java` → 위 근거에 적은 줄 번호 확인.

#### 파일

- `C:\live-streaming-project\backend\src\main\java\com\livestream\member\LoginAttemptLimiter.java`
- `C:\live-streaming-project\backend\src\test\java\com\livestream\member\LoginAttemptLimiterTest.java`
- 변이 실행 스크립트: `C:\Users\USER\AppData\Local\Temp\claude\34b09bda5ad1c4c7\8bac89f5-0fc0-4042-9551-b5de214a8fa5\scratchpad\run2.sh`
- 실행별 로그: `C:\Users\USER\AppData\Local\Temp\claude\34b09bda5ad1c4c7\8bac89f5-0fc0-4042-9551-b5de214a8fa5\scratchpad\out-base.log`, `...\out-a_ttl.log`, `...\out-b_ip60.log`, `...\out-c_decr.log` (같은 폴더)

### 구현자 이견 (2차)

- 판정에 이견이 없다. AC 5의 남은 갭은 내가 1차 제안("890 초과 900 이하")을 옮기면서 상한을 빠뜨린 것이다. 사용자가 승인한 제안 그대로 맞추는 일이라 따로 묻지 않고 메웠다.
- `LoginAttemptLimiterTest`의 IP 15분 테스트 단언을 `isBetween(WINDOW_SECONDS - 9, WINDOW_SECONDS)`로 바꿨다. 실제 코드에서 14개 통과, IP 만료만 3600초로 바꾼 변이에서 이 테스트 하나만 실패하는 것을 확인했다. 단언 하나를 조인 것이라 3차 검증은 띄우지 않았다.
- 400ms 테스트의 흔들림 우려는 검증자 의견대로 400ms를 유지한다.

---

## 보안 점검

> `/harness:security-review 3` (2026-11-20, HEAD 274c81b). 예외 대장(`securityExceptions`)은 아직 없다.

### 수집

| 항목 | 결과 |
| --- | --- |
| 타입 검사 (`typecheck`) | 오류 0 |
| 의존성 (`audit` = 프론트 `npm audit`) | 취약점 0 |
| 하드코딩된 비밀값·알려진 키 접두사 | 없음 (`backend/src/main`, `frontend/src`) |
| `.env` | 작업 트리·브랜치 커밋 모두 없음 |
| 로그 호출 | 프론트 `console.log`·백엔드 로거 호출 없음. 차단·실패를 로그에 남기지 않는다 |
| 실제 IP | `forward-headers-strategy: native`라 Tomcat은 내부 프록시 대역에서 온 `X-Forwarded-For`만 믿는다. nginx는 이 헤더를 `$remote_addr`로 덮어쓴다(`infra/nginx/nginx.conf`). `compose.yml`에서 백엔드는 포트를 열지 않고 nginx만 연다 |
| 이메일 존재 노출 | 없는 이메일도 같은 카운터로 세고 같은 429를 낸다. 차단 응답으로 가입 여부를 알 수 없다 |

### 분류

| 분류 | 항목 | 위치 | 판단 | 처리 |
| --- | --- | --- | --- | --- |
| 🟡 | 로그인 이메일에 길이 제한이 없어, 보낸 문자열이 그대로 Redis 키(`login:fail:email:{email}`)가 된다 | `LoginRequest.email` | 가입은 254자로 막고(`SignUpRequest`, DB 컬럼 `VARCHAR2(254)`) 그보다 긴 이메일의 계정은 없다. 이번 이슈가 처음으로 사용자 입력을 Redis 키에 넣는다. IP당 15분에 20개까지라 피해는 제한적이지만, 수 MB 본문을 IP를 바꿔 가며 보내면 Redis 메모리를 쓴다 | 사용자 결정으로 이번에 고쳤다. `@Size(max = 254)`, 시나리오 `[경계] POST /api/auth/login — should return 400 when email is 255 characters` |
| 🟡 | 세션 쿠키 `Secure` 없음 | `application.yml` | #2에서 넘어옴. 로컬은 http | 배포 작업 때 |
| 🟡 | `SignUpRequest`의 영어 기본 메시지 | `SignUpRequest.java` | #1에서 넘어옴. 범위 밖 | — |
| ⚪ | 남의 이메일로 5번 틀려 그 사람을 15분 막을 수 있다 | 로그인 시도 제한 | PRD member.md 74행, 확정 사양이 받아들인 동작 | — |
| ⚪ | Redis가 죽으면 로그인이 500으로 실패한다 | `LoginAttemptLimiter` | 막는 쪽으로 실패한다(fail-closed). 세션도 Redis에 있어 어차피 로그인이 안 된다 | — |
| ⚪ | 만료 없는 카운터 키(PTTL -1)는 막지 않는다 | ACQUIRE Lua | 이 코드로는 생기지 않는다(2차 검증 "발견 1 수정 검토") | — |
| ⚪ | 백엔드(Gradle) 의존성 스캔 도구 없음 | `harness.json`의 `audit` | #1·#2와 같다 | CI를 붙일 때 |

### 재스캔

🔴은 없었다. 🟡 중 이메일 길이 제한은 사용자 결정으로 이번 이슈에서 고쳤다(테스트 먼저 실패 확인 → `@Size(max = 254)`). 고친 뒤 `test` 88개 전부 통과(컴파일 포함), `audit` 취약점 0. 남은 🟡·⚪는 위 표의 이유로 의도적으로 남겼다.
