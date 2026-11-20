# 이슈 #4 — 닉네임과 비밀번호를 바꾼다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/4
> PRD: `docs/result/prd/member.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

---

## 시그니처

### 관련 ADR

- **계정 M2** — 비밀번호를 바꾸면 그 회원의 세션 목록을 `FindByIndexNameSessionRepository`(Spring Session이 회원별 색인으로 세션을 찾는 저장소)에서 찾아, **지금 요청한 세션만 남기고** 지운다. 색인 키는 `LoginMember.getName()` = 회원 id다(#2).
- **계정 PRD 작은 결정(닉네임 중복)** — 닉네임은 UNIQUE 제약이 최종 방어다. 앱에서 먼저 조회하고, 동시에 들어와 조회를 함께 통과한 경우는 제약 위반(`UK_MEMBER_NICKNAME`)을 409로 바꾼다. 가입(#1)과 같은 방식이다.
- **확정 사양 2절** — 닉네임 2~12자 한글·영문·숫자. 비밀번호 8~64자, 영문과 숫자를 각각 하나 이상, 공백을 뺀 ASCII만. 가입 규칙과 같다.

### 타입

```java
// com.livestream.member — 요청 DTO (규칙은 SignUpRequest와 같은 정규식·메시지)
public record ChangeNicknameRequest(
        @NotNull @Pattern(regexp = "^[가-힣A-Za-z0-9]{2,12}$",
                message = "닉네임은 2~12자의 한글·영문·숫자만 쓸 수 있습니다") String nickname) {}

public record ChangePasswordRequest(
        // 로그인 비밀번호와 같은 제한. 넘는 값은 BCrypt 72바이트 한도에 걸리므로 비교 전에 400
        @NotBlank(message = "현재 비밀번호를 입력해 주세요")
        @Size(max = 64, message = "비밀번호는 64자 이하입니다")
        @Pattern(regexp = "^[!-~]*$", message = "비밀번호는 영문·숫자·특수문자만 쓸 수 있습니다") String currentPassword,
        @NotNull @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
                message = "비밀번호는 8~64자이고 영문과 숫자를 모두 포함해야 합니다")
        @Pattern(regexp = "^[!-~]*$", message = "비밀번호는 영문·숫자·특수문자만 쓸 수 있습니다") String newPassword) {}

public class MemberService {
    public MemberService(MemberMapper memberMapper, PasswordEncoder passwordEncoder,
                         LoginAttemptLimiter loginAttemptLimiter,
                         FindByIndexNameSessionRepository<? extends Session> sessionRepository); // 하나 추가

    // 지금 닉네임과 같으면 바꾸지 않고 그대로 돌려준다. 다른 회원이 쓰면 MEMBER_NICKNAME_DUPLICATED
    public MemberResponse changeNickname(long memberId, ChangeNicknameRequest request);

    // 현재 비밀번호가 틀리면 MEMBER_PASSWORD_MISMATCH(아무것도 바꾸지 않는다).
    // 맞으면 새 해시를 저장하고, 이 회원의 세션 중 currentSessionId만 남기고 지운다
    // (보안 점검 뒤 추가) 비교 전에 LoginAttemptLimiter.acquire(이메일, clientIp)로 센다. 막혔으면 비교하지 않고 RetryLaterException(429).
    // 맞으면 succeeded(이메일, clientIp)
    public void changePassword(long memberId, ChangePasswordRequest request, String currentSessionId, String clientIp);
}

@Mapper
public interface MemberMapper {
    void updateNickname(@Param("id") long id, @Param("nickname") String nickname);       // 추가
    void updatePasswordHash(@Param("id") long id, @Param("passwordHash") String passwordHash); // 추가
}

class MemberController {
    // 200 + MemberResponse
    @PutMapping("/me/nickname")
    MemberResponse changeNickname(@AuthenticationPrincipal LoginMember loginMember,
                                  @Valid @RequestBody ChangeNicknameRequest request);

    // 204. 지금 세션 ID는 httpRequest.getSession(false)에서 꺼내 넘긴다
    @PutMapping("/me/password")
    ResponseEntity<Void> changePassword(@AuthenticationPrincipal LoginMember loginMember,
                                        @Valid @RequestBody ChangePasswordRequest request,
                                        HttpServletRequest httpRequest);
}
```

```java
// com.livestream.common.error
public enum ErrorCode {
    // 추가. 401이 아니라 400 — 401이면 화면이 "로그인이 풀렸다"로 읽는다
    MEMBER_PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "현재 비밀번호가 맞지 않습니다");
}
```

```typescript
// frontend/src/api/members.ts — 추가
export function changeNickname(nickname: string): Promise<MemberResponse>  // PUT /members/me/nickname
export function changePassword(req: { currentPassword: string; newPassword: string }): Promise<void> // PUT /members/me/password
```

### 에러 케이스

| 상황 | 에러 코드 | HTTP |
| --- | --- | --- |
| 닉네임이 규칙(2~12자 한글·영문·숫자)에 맞지 않음 | `INVALID_INPUT` (필드 메시지) | 400 |
| 다른 회원이 쓰는 닉네임 (먼저 조회에서, 또는 동시 변경으로 UNIQUE 위반) | `MEMBER_NICKNAME_DUPLICATED` | 409 |
| 현재 비밀번호가 틀림 | `MEMBER_PASSWORD_MISMATCH` | 400 |
| 같은 이메일로 이미 5번, 같은 IP에서 이미 20번 실패함(로그인·현재 비밀번호 확인을 함께 센다). 보안 점검 뒤 추가 | `MEMBER_LOGIN_BLOCKED` + `Retry-After` | 429 |
| 새 비밀번호가 규칙(8~64자, 영문+숫자, 공백 뺀 ASCII)에 맞지 않음 | `INVALID_INPUT` (필드 메시지) | 400 |
| 로그인하지 않음 | `UNAUTHENTICATED` (보안 설정의 `anyRequest().authenticated()`) | 401 |

### 화면

`MyPage`(`/me`, #6이 만든 내 정보 화면) 아래에 폼 두 개를 더한다.

- **닉네임 변경:** 입력 하나 + "닉네임 변경" 버튼. 성공하면 `['me']` 쿼리를 다시 불러 화면과 머리글의 닉네임이 바뀐다. 실패하면 서버 `message`를 `role="alert"`로 보여 준다.
- **비밀번호 변경:** "현재 비밀번호"·"새 비밀번호" 입력 + "비밀번호 변경" 버튼. 성공하면 입력을 비우고 "비밀번호를 바꿨습니다. 다른 기기에서는 로그아웃됩니다"를 보여 준다. 실패하면 서버 `message`.

프론트 테스트 도구가 아직 없어서 화면은 수동으로 확인한다(아래 "수동 확인").

### 판단이 갈린 곳

1. **현재 비밀번호가 틀리면 400 `MEMBER_PASSWORD_MISMATCH`.** AC 2가 400을 요구한다. 401을 쓰면 화면이 "로그인이 풀렸다"로 읽어 혼란스럽다.
2. **지금 닉네임과 같은 값으로 바꾸면 200(변화 없음).** 앞서 조회(`existsByNickname`)만 하면 자기 닉네임도 "이미 사용 중"으로 409가 난다. 그래서 회원을 먼저 읽고, 같으면 그대로 돌려준다.
3. **세션 삭제는 서비스가 한다.** "현재 비밀번호 확인 → 해시 저장 → 다른 세션 삭제"의 순서를 한 메서드에 두어 단위 테스트로 묶는다. 컨트롤러는 지금 세션 ID만 꺼내 넘긴다.
4. **지금 세션은 그대로 둔다(ID도 바꾸지 않는다).** AC 3이 "A는 여전히 200"을 요구한다. 세션 ID 교체는 로그인 때만 한다(#2).
5. **해시 저장과 세션 삭제를 한 트랜잭션으로 묶지 않는다.** DB와 Redis를 함께 묶을 수 없다. 해시를 먼저 저장하고 세션을 지운다. 세션 삭제가 실패하면 500이 나지만 비밀번호는 이미 바뀌었다. 이때 다시 바꾸면 그때 지워진다.
6. ~~**현재 비밀번호를 틀리는 횟수는 세지 않는다.**~~ → **보안 점검에서 뒤집었다(사용자 결정, 2026-11-20).** 로그인 시도 제한(M3)을 그대로 건다. 원래 판단: 로그인 시도 제한(M3)은 로그인에만 건다. 남의 로그인된 브라우저를 쓰는 사람은 이 API로 현재 비밀번호를 계속 넣어 볼 수 있다. 하지만 이미 그 사람으로 로그인된 상태이고, 사양에도 제한이 없다. 보안 점검에서 다시 본다.
7. **새 비밀번호가 지금 비밀번호와 같아도 받는다.** 사양에 금지가 없다.

### 이 이슈에서 만들지 않는 것

- 이메일 변경, 비밀번호 찾기, 회원 탈퇴 (PRD "하지 않을 것")
- 이미 열린 채팅 WebSocket 끊기 (PRD M2 — 채팅 PRD에서 다룬다)
- ~~비밀번호 변경 시도 횟수 제한 (판단 6)~~ — 보안 점검 뒤 이번 이슈에 넣었다
- 프론트 테스트 도구(Vitest) 도입 — 이 화면은 폼 두 개와 API 호출뿐이라 화면 로직이 없다

---

## 테스트 시나리오

### 정상

- [x] [정상] `MemberService.changeNickname` — should save and return the new nickname when no other member uses it
- [x] [정상] `MemberService.changePassword` — should save a bcrypt hash of the new password when current password matches
- [x] [정상] `MemberService.changePassword` — should delete the member's other sessions and keep the current one
- [x] [정상] `PUT /api/members/me/nickname` — should return 200 with the changed nickname
- [x] [정상] `PUT /api/members/me/password` — should return 204 and pass the current session id to the service
- [x] [정상] `MemberAccountIntegration` — should show the new nickname in my info after changing it
- [x] [정상] `MemberAccountIntegration` — should return 401 to device B and 200 to device A after A changes the password
- [x] [정상] `MemberAccountIntegration` — should log in with the new password after the change
- [x] [정상] `MemberService.changePassword` — should count the attempt before comparing current password
- [x] [정상] `MemberService.changePassword` — should report success to the limiter when current password matches
- [x] [정상] `PUT /api/members/me/password` — should pass the client ip to the service

### 경계

- [x] [경계] `MemberService.changeNickname` — should return the member unchanged when the new nickname is the current one
- [x] [경계] `MemberService.changeNickname` — should throw MEMBER_NICKNAME_DUPLICATED when the unique constraint rejects the update
- [x] [경계] `PUT /api/members/me/nickname` — should return 400 when nickname is 1 character
- [x] [경계] `PUT /api/members/me/nickname` — should return 400 when nickname is 13 characters
- [x] [경계] `PUT /api/members/me/password` — should return 400 when new password is 7 characters
- [x] [경계] `PUT /api/members/me/password` — should return 400 when new password has no digit
- [x] [경계] `PUT /api/members/me/nickname` — should return 200 when nickname is exactly 2 characters
- [x] [경계] `PUT /api/members/me/nickname` — should return 200 when nickname is exactly 12 characters
- [x] [경계] `PUT /api/members/me/password` — should return 204 when new password is exactly 8 characters
- [x] [경계] `PUT /api/members/me/password` — should return 204 when new password is exactly 64 characters
- [x] [경계] `PUT /api/members/me/password` — should return 400 when new password is 65 characters
- [x] [경계] `PUT /api/members/me/password` — should return 400 when new password has no letter
- [x] [경계] `MemberAccountIntegration` — should accept only one of two members changing to the same nickname at once

### 예외

- [x] [예외] `MemberService.changeNickname` — should throw MEMBER_NICKNAME_DUPLICATED when another member uses the nickname
- [x] [예외] `MemberService.changePassword` — should throw MEMBER_PASSWORD_MISMATCH when current password is wrong
- [x] [예외] `MemberService.changePassword` — should not delete any session when current password is wrong
- [x] [예외] `MemberService.changePassword` — should not save a new password hash when current password is wrong
- [x] [예외] `PUT /api/members/me/nickname` — should return 400 when nickname contains a space
- [x] [예외] `PUT /api/members/me/nickname` — should return 400 when nickname contains a special character
- [x] [예외] `PUT /api/members/me/nickname` — should return 409 with code MEMBER_NICKNAME_DUPLICATED when service rejects the nickname
- [x] [예외] `PUT /api/members/me/password` — should return 400 with code MEMBER_PASSWORD_MISMATCH when service rejects current password
- [x] [예외] `MemberAccountIntegration` — should keep the old password when the change is rejected for a wrong current password
- [x] [예외] `MemberAccountIntegration` — should reject the old password with 401 after the change
- [x] [예외] `MemberAccountIntegration` — should return 401 when changing nickname without logging in
- [x] [예외] `MemberService.changePassword` — should not compare current password when attempts are blocked
- [x] [예외] `MemberAccountIntegration` — should return 429 on a password change after five wrong current passwords

### 수동 확인 (프론트 테스트 도구가 없다 — 시나리오 수에 넣지 않는다)

Green 단계에서 브라우저로 확인하고 결과를 이 문서와 PR에 남긴다.

- 내 정보 화면에서 닉네임을 바꾸면 화면과 머리글의 닉네임이 바로 바뀐다. 다른 회원의 닉네임이면 "이미 사용 중인 닉네임입니다"가 보인다
- 현재 비밀번호를 틀리면 "현재 비밀번호가 맞지 않습니다", 맞으면 "비밀번호를 바꿨습니다. 다른 기기에서는 로그아웃됩니다"가 보인다
- 브라우저 두 곳(일반 창·시크릿 창)에 로그인한 뒤 한쪽에서 비밀번호를 바꾸면, 다른 쪽은 새로고침했을 때 로그인 링크가 보인다

**확인 결과 (2026-11-20, Green):** 로컬에서 Oracle·Redis 컨테이너 + `bootRun` + Vite 개발 서버로 확인했다. 브라우저 미리보기 창은 탭끼리 쿠키를 같이 써서, 두 번째 기기는 curl(쿠키 파일)로 대신했다.

- 닉네임: 다른 회원의 닉네임을 넣으면 "이미 사용 중인 닉네임입니다". 아무도 안 쓰는 닉네임으로 바꾸면 화면의 닉네임과 머리글 링크가 바로 새 닉네임으로 바뀐다 ✅
- 비밀번호: 현재 비밀번호를 틀리면 "현재 비밀번호가 맞지 않습니다", 맞으면 "비밀번호를 바꿨습니다. 다른 기기에서는 로그아웃됩니다" ✅
- 다른 기기: 바꾸기 전에 curl로 로그인해 둔 기기 B는 `/me` 401, 브라우저(기기 A)는 새로고침해도 로그인 상태 유지. 이어서 이전 비밀번호 로그인 401, 새 비밀번호 로그인 200 ✅

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 닉네임을 2~12자 규칙에 맞게 바꾸면 바뀌고, 다른 회원이 쓰는 닉네임이면 409 | `[정상] MemberService.changeNickname — should save and return the new nickname when no other member uses it`<br>`[정상] PUT /api/members/me/nickname — should return 200 with the changed nickname`<br>`[정상] MemberAccountIntegration — should show the new nickname in my info after changing it`<br>`[경계] PUT /api/members/me/nickname — should return 400 when nickname is 1 character`<br>`[경계] PUT /api/members/me/nickname — should return 400 when nickname is 13 characters`<br>`[경계] MemberService.changeNickname — should throw MEMBER_NICKNAME_DUPLICATED when the unique constraint rejects the update`<br>`[경계] MemberAccountIntegration — should accept only one of two members changing to the same nickname at once`<br>`[예외] MemberService.changeNickname — should throw MEMBER_NICKNAME_DUPLICATED when another member uses the nickname`<br>`[예외] PUT /api/members/me/nickname — should return 409 with code MEMBER_NICKNAME_DUPLICATED when service rejects the nickname`<br>`[경계] PUT /api/members/me/nickname — should return 200 when nickname is exactly 2 characters` (AC 검증 갭)<br>`[경계] PUT /api/members/me/nickname — should return 200 when nickname is exactly 12 characters` (AC 검증 갭)<br>`[예외] PUT /api/members/me/nickname — should return 400 when nickname contains a space` (AC 검증 갭)<br>`[예외] PUT /api/members/me/nickname — should return 400 when nickname contains a special character` (AC 2차 검증 갭) |
| 2. 현재 비밀번호를 틀린 채 변경 요청하면 400이고 비밀번호는 그대로 | `[예외] MemberService.changePassword — should throw MEMBER_PASSWORD_MISMATCH when current password is wrong`<br>`[예외] MemberService.changePassword — should not delete any session when current password is wrong`<br>`[예외] PUT /api/members/me/password — should return 400 with code MEMBER_PASSWORD_MISMATCH when service rejects current password`<br>`[예외] MemberAccountIntegration — should keep the old password when the change is rejected for a wrong current password`<br>`[예외] MemberService.changePassword — should not save a new password hash when current password is wrong` (AC 검증 갭) |
| 3. 기기 A·B 로그인, A에서 비밀번호를 바꾸면 B는 `/me` 401, A는 200 | `[정상] MemberService.changePassword — should delete the member's other sessions and keep the current one`<br>`[정상] PUT /api/members/me/password — should return 204 and pass the current session id to the service`<br>`[정상] MemberAccountIntegration — should return 401 to device B and 200 to device A after A changes the password` |
| 4. 새 비밀번호로 로그인하면 성공, 이전 비밀번호는 401 | `[정상] MemberService.changePassword — should save a bcrypt hash of the new password when current password matches`<br>`[정상] MemberAccountIntegration — should log in with the new password after the change`<br>`[예외] MemberAccountIntegration — should reject the old password with 401 after the change` |
| 5. 새 비밀번호가 규칙(8~64자, 영문+숫자)에 맞지 않으면 400 | `[경계] PUT /api/members/me/password — should return 400 when new password is 7 characters`<br>`[경계] PUT /api/members/me/password — should return 400 when new password has no digit`<br>`[경계] PUT /api/members/me/password — should return 204 when new password is exactly 8 characters` (AC 검증 갭)<br>`[경계] PUT /api/members/me/password — should return 204 when new password is exactly 64 characters` (AC 검증 갭)<br>`[경계] PUT /api/members/me/password — should return 400 when new password is 65 characters` (AC 검증 갭)<br>`[경계] PUT /api/members/me/password — should return 400 when new password has no letter` (AC 검증 갭) |

**AC에 없는데 넣은 시나리오**

| 시나리오 | 이유 |
| --- | --- |
| `[경계] MemberService.changeNickname — should return the member unchanged when the new nickname is the current one` | 판단 2. 중복 조회만 하면 자기 닉네임에 409가 난다 |
| `[예외] MemberAccountIntegration — should return 401 when changing nickname without logging in` | 새 경로가 보안 설정의 `authenticated()`에 걸리는지 확인한다. 컨트롤러 테스트는 필터를 끄고 돌아서 이걸 못 본다 |
| `[정상] MemberService.changePassword — should count the attempt before comparing current password`<br>`[정상] MemberService.changePassword — should report success to the limiter when current password matches`<br>`[정상] PUT /api/members/me/password — should pass the client ip to the service`<br>`[예외] MemberService.changePassword — should not compare current password when attempts are blocked`<br>`[예외] MemberAccountIntegration — should return 429 on a password change after five wrong current passwords` | 보안 점검 🟡(사용자 결정으로 이번에 막는다). 현재 비밀번호 확인에도 로그인 시도 제한(PRD M3, #3의 `LoginAttemptLimiter`)을 건다. 막지 않으면 로그인된 세션을 쥔 사람이 이 경로로 비밀번호를 대입해 계정을 가져갈 수 있다 |

**커버리지:** AC 5개 / 시나리오 37개 / 미커버 0개 (화면은 수동 확인). 37개 중 9개는 AC 검증(1·2차) 뒤 갭을 메우려, 5개는 보안 점검 뒤 더했다

---

## AC 검증

> `harness:ac-verifier` 1차 (2026-11-20, HEAD 356a250). 아래는 검증자 출력 원문이다. 이 문서의 절 구조를 깨지 않도록 제목 수준만 한 단계씩 낮췄다.

### 이슈 #4 AC 검증 결과

AC 5개 중 3개(AC 2·3·4)는 충족, 2개(AC 1·5)는 부분 충족, 미충족은 없다. 두 AC 모두 구현은 맞지만, 규칙의 경계값 중 "받아야 하는 쪽"과 일부 규칙 조건을 테스트가 보지 않는다. 시그니처의 "판단이 갈린 곳" 1~7은 구현과 모두 일치한다.

변이 실행은 3번을 다 썼지만 유효한 결과는 2개(M2, M3)뿐이다. M1(새 비밀번호 규칙 완화)은 내 치환 스크립트 오류로 두 번 실패했다. 한 번은 치환이 적용되지 않았고, 한 번은 컴파일 오류가 났다. 그래서 AC 5의 갭은 **변이로 확인하지 못했다.** 테스트 입력을 grep으로 전수 확인한 결과로만 판정했다. 저장소 파일은 건드리지 않았다(변이는 전부 스크래치 복사본 `C:\Users\USER\AppData\Local\Temp\claude\1cbb40fc7d76b7b0\8bac89f5-0fc0-4042-9551-b5de214a8fa5\scratchpad\mut\backend`에서 했다).

| AC | 판정 |
| --- | --- |
| 1. 닉네임 2~12자로 바꾸면 바뀌고, 남이 쓰면 409 | ⚠️ 부분 충족 |
| 2. 현재 비밀번호를 틀리면 400, 비밀번호는 그대로 | ✅ 충족 (단언 하나가 약함) |
| 3. A에서 바꾸면 B는 401, A는 200 | ✅ 충족 |
| 4. 새 비밀번호 로그인 성공, 이전 비밀번호는 401 | ✅ 충족 |
| 5. 새 비밀번호가 규칙(8~64자, 영문+숫자)에 안 맞으면 400 | ⚠️ 부분 충족 |

---

#### AC 1 — Given 로그인한 회원, When 닉네임을 2~12자 규칙에 맞게 바꾸면, Then 바뀌고, 다른 회원이 쓰는 닉네임이면 409다

⚠️ 부분 충족
- **구현:** `MemberService.changeNickname` (backend/src/main/java/com/livestream/member/MemberService.java:76)이 순서대로 처리한다.
  - 79줄: 지금 닉네임과 같으면 그대로 돌려준다.
  - 82줄: 미리 조회해서 이미 쓰이면 409.
  - 88줄: `DuplicateKeyException`(UNIQUE 위반)을 409로 바꾼다.
  - 엔드포인트는 MemberController.java:35 (`PUT /me/nickname`)이다.
- **테스트 (MemberServiceTest.java):**
  - 292줄 "should save and return the new nickname…": `updateNickname` 호출과 반환값을 단언한다.
  - 324줄 "…when another member uses the nickname": 에러 코드까지 단언한다.
  - 312줄 "…unique constraint rejects…": 에러 코드까지 단언한다.
  - 301줄 "…unchanged when the new nickname is the current one": `existsByNickname=true`로 두었으므로 79줄 분기가 없으면 실패한다.
- **테스트 (MemberControllerTest.java):**
  - 278줄: 200을 확인한다.
  - 289줄·297줄: 1자와 13자에 400 + `INVALID_INPUT`을 확인한다.
  - 305줄: 409 + 코드를 확인한다.
- **테스트 (MemberAccountIntegrationTest.java):**
  - 144줄: 실제 DB에서 바꾼 뒤 `/me`에 새 닉네임이 보인다.
  - 176줄: 실제 Oracle에서 동시 변경 시 `containsExactly(MEMBER_NICKNAME_DUPLICATED)`, 즉 정확히 한쪽만 실패한다.
- **갭:** 경계의 바깥(1자·13자 거절)은 있지만 **경계의 안쪽(정확히 2자·12자 수락)이 없다.**
  - 성공 경로 입력은 "새닉네임"(4자)과 "새"+6자리(7자)뿐이다.
  - 변이 M2(`{2,12}`→`{3,11}`)를 넣어도 세 클래스가 **전부 통과했다(생존).**
  - 허용하지 않는 문자(공백·특수문자) 거절 테스트도 없다. 규칙 문구에 "한글·영문·숫자"가 들어 있다.
- 409의 끝까지 HTTP 경로(실제 서비스 + 실제 DB)는 테스트가 없다. 서비스 단위 테스트와 목을 쓴 컨트롤러 테스트로 나뉘어 있어 사슬은 이어진다. 이것은 갭으로 세지 않았다.

#### AC 2 — Given 로그인한 회원, When 현재 비밀번호를 틀린 채 변경을 요청하면, Then 400이고 비밀번호는 그대로다

✅ 충족
- **구현:**
  - MemberService.java:99에서 비밀번호를 비교하고 틀리면 바로 `MEMBER_PASSWORD_MISMATCH`를 던진다. 해시 저장(103줄)과 세션 삭제(104줄)는 그 뒤에 있다.
  - ErrorCode.java:17이 이 코드를 `BAD_REQUEST`로 정의한다.
- **테스트:**
  - MemberServiceTest.java:373: 에러 코드를 단언한다.
  - MemberServiceTest.java:383: `deleteById`가 한 번도 불리지 않는다.
  - MemberControllerTest.java:355: 400 + `MEMBER_PASSWORD_MISMATCH`.
  - MemberAccountIntegrationTest.java:206: 틀린 요청 뒤에도 **이전 비밀번호 로그인이 200**이다. 실제 DB 해시가 그대로라는 뜻이다.
- **변이 M3(검증 전에 해시 저장)는 잡혔다.**
  - 통합 테스트 206줄이 "Status expected:<200> but was:<401>"로 실패했다.
  - 서비스 테스트 351줄도 실패했지만 이유는 "호출 2회"(TooManyActualInvocations) 때문이다. 틀린 비밀번호 테스트(373·383줄)는 `updatePasswordHash`가 호출되지 않았는지 보지 않는다. 그래서 단위 수준에서 "그대로"를 지키는 것은 통합 테스트 하나뿐이다.
- **약한 단언:** 통합 테스트 209줄이 변경 요청의 응답 상태를 단언하지 않는다(아래 약한 단언 목록 참고). 400 자체는 컨트롤러 테스트와 ErrorCode로 확인되어 판정은 ✅이다.

#### AC 3 — Given 기기 A·B에 로그인한 회원, When A에서 비밀번호를 바꾸면, Then B의 쿠키로 내 정보 조회 시 401이고 A는 여전히 200이다

✅ 충족
- **구현:**
  - MemberService.java:104-106이 `findByPrincipalName(String.valueOf(memberId))`로 이 회원의 세션을 찾고, `currentSessionId`가 아닌 것만 `deleteById`로 지운다.
  - MemberController.java:46이 `getSession(false).getId()`로 지금 세션 ID를 꺼내 넘긴다.
- **테스트:**
  - MemberAccountIntegrationTest.java:154: 실제 Redis 세션 저장소와 보안·세션 필터를 거친다. 로그인을 두 번 해서 쿠키 둘을 받고, A로 바꾼 뒤 `me(B)`는 401, `me(A)`는 200이다. 색인 키(`LoginMember.getName()`)와 실제 저장소 연결까지 확인된다.
  - MemberServiceTest.java:362: `times(2)` + `containsExactlyInAnyOrder("other-1","other-2")`. 지금 세션을 지우면 3회가 되어 실패한다.
  - MemberControllerTest.java:331: `eq(SESSION_ID)`로 지금 세션 ID가 서비스에 그대로 넘어가는지 확인한다.

#### AC 4 — Given 비밀번호를 바꾼 회원, When 새 비밀번호로 로그인하면, Then 성공하고, 이전 비밀번호로는 401이다

✅ 충족
- **구현:** MemberService.java:103이 새 비밀번호를 `passwordEncoder.encode`로 해시해 저장한다. SQL은 MemberMapper.xml의 `updatePasswordHash`다.
- **테스트:**
  - MemberAccountIntegrationTest.java:167: 새 비밀번호 로그인 200.
  - MemberAccountIntegrationTest.java:215: 이전 비밀번호 로그인 401. 두 테스트 모두 변경 응답 204를 먼저 단언한다.
  - MemberServiceTest.java:351: 캡처한 해시가 새 비밀번호와 `BCrypt.matches`로 맞는다.

#### AC 5 — Given 새 비밀번호가 규칙(8~64자, 영문+숫자)에 맞지 않으면, When 바꾸면, Then 400이다

⚠️ 부분 충족
- **구현:** ChangePasswordRequest.java:14 `^(?=.*[A-Za-z])(?=.*\\d).{8,64}$`와 16줄 `^[!-~]*$`. SignUpRequest와 같은 규칙이다.
- **테스트:** MemberControllerTest.java:339(7자 → 400)와 347줄(숫자 없음 → 400)뿐이다.
- **갭:**
  - 정확히 8자 수락이 없다. 성공 경로 입력은 전부 "newpass99"(9자)다.
  - 64자 수락과 65자 거절이 없다.
  - 영문이 없는 경우(숫자만) 거절이 없다.
  - 위 셋은 grep으로 확인했다. `ChangePasswordRequest(`와 `changePassword(` 호출의 새 비밀번호는 "newpass99", "abcd123", "abcdefgh"뿐이다.
  - 따라서 정규식에서 상한(`{8,64}`→`{8,}`)이나 영문 요구(`(?=.*[A-Za-z])`)가 빠져도 테스트는 통과할 것으로 **추정한다.** M1 변이는 스크립트 오류로 실행되지 않아 **변이로는 확인하지 못했다.**
  - 이 DTO는 SignUpRequest와 정규식을 공유하지 않고 따로 적었으므로, 가입 쪽 테스트가 이 갭을 메워 주지 않는다.

---

### 판단이 갈린 곳 1~7 대조

1. 틀리면 400 `MEMBER_PASSWORD_MISMATCH`: ErrorCode.java:17. 일치한다.
2. 같은 닉네임이면 그대로 200: MemberService.java:79. 일치한다. 서비스 테스트 301줄이 막는다.
3. 세션 삭제는 서비스가 한다: MemberService.java:104. 컨트롤러는 ID만 넘긴다(46줄). 일치한다.
4. 지금 세션을 두고 ID도 바꾸지 않는다: 컨트롤러에 `changeSessionId` 같은 호출이 없고, 통합 테스트에서 A가 200이다. 일치한다.
5. 한 트랜잭션으로 묶지 않고 해시 먼저, 세션 삭제는 나중: grep "Transactional" 결과 MemberService에 없다. 순서는 103줄 → 104줄. 일치한다.
6. 틀린 횟수를 세지 않는다: `changePassword`가 `loginAttemptLimiter`를 쓰지 않는다. 일치한다.
7. 새 비밀번호가 지금 것과 같아도 받는다: 비교 코드가 없다. 일치한다.

### 추가 시나리오 제안 (시나리오 문서에 그대로 붙일 수 있다)

AC 1:
- [ ] [경계] `PUT /api/members/me/nickname` — should return 200 when nickname is exactly 2 characters
- [ ] [경계] `PUT /api/members/me/nickname` — should return 200 when nickname is exactly 12 characters
- [ ] [예외] `PUT /api/members/me/nickname` — should return 400 when nickname contains a space or special character

AC 5:
- [ ] [경계] `PUT /api/members/me/password` — should return 204 when new password is exactly 8 characters
- [ ] [경계] `PUT /api/members/me/password` — should return 204 when new password is exactly 64 characters
- [ ] [경계] `PUT /api/members/me/password` — should return 400 when new password is 65 characters
- [ ] [경계] `PUT /api/members/me/password` — should return 400 when new password has no letter

AC 2 (선택, 단위 수준 보강):
- [ ] [예외] `MemberService.changePassword` — should not save a new password hash when current password is wrong

### 약한 단언 목록

가짜 테스트(어떤 에러든 통과하는 단언, await 누락, 단언 없음, 목 반환값을 그대로 단언)는 찾지 못했다. 예외 단언은 전부 `BusinessException` + `extracting("code")`로 구체적이다. 대신 아래 약한 단언이 있다.

1. **MemberAccountIntegrationTest.java:209:** `changePassword(device(email), "wrong1234", NEW_PASSWORD);`의 응답 상태를 단언하지 않는다. 요청이 서비스에 닿기 전에 다른 이유(403, 검증 400 등)로 막혀도 "이전 비밀번호로 로그인 200"이 성립해 그냥 통과한다. 고치는 법: `.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MEMBER_PASSWORD_MISMATCH"))`를 붙인다.
2. **MemberServiceTest.java:383-388:** `catchThrowable(...)`로 어떤 예외든 삼키고 `deleteById`가 안 불린 것만 본다. `updatePasswordHash`가 안 불렸는지는 보지 않는다. 고치는 법: `verify(memberMapper, never()).updatePasswordHash(anyLong(), anyString())`를 별도 테스트(위 제안)로 둔다.
3. **MemberServiceTest.java:301-308:** 같은 닉네임일 때 반환값만 보고 `updateNickname`이 호출되지 않았는지는 보지 않는다. 지금도 `existsByNickname=true` 설정 덕분에 분기가 빠지면 실패하므로 약점은 작다. 조이려면 `verify(memberMapper, never()).updateNickname(anyLong(), anyString())`를 더한다.
4. **MemberAccountIntegrationTest.java:154-164 (아주 작음):** 바꾸기 전에 B가 200이라는 사전 조건 단언이 없다. `device()`가 로그인 200과 쿠키를 확인하므로 실질적 위험은 낮다.

### 변이 결과

스크래치 복사본에서 `MemberServiceTest`·`MemberAccountIntegrationTest`·`MemberControllerTest` 세 클래스로 돌렸다.

| 변이 | 결과 |
| --- | --- |
| M2: ChangeNicknameRequest `{2,12}`→`{3,11}` | **생존** (exit 0). AC 1의 경계 수락 갭을 확인했다 |
| M3: `changePassword`에서 현재 비밀번호 확인 전에 `updatePasswordHash` 호출 추가 | **잡힘**. 통합 테스트 206줄 "expected 200 but was 401"과 서비스 테스트 351줄 TooManyActualInvocations. 60개 중 2개 실패 |
| M1: 새 비밀번호 정규식 완화 | **무효**. 1차는 perl 치환이 적용되지 않았다(원본 그대로 실행, exit 0). 2차도 치환 미적용. 3차는 `\d` 이스케이프 오류로 컴파일 실패(BUILD FAILED in 7s). 판정은 입력 grep 근거만으로 했다 |

### 실행한 명령 목록

1. `cat .claude/harness.json && gh issue view 4 && git diff main...HEAD --stat` → scenariosDir=docs/result/scenarios, AC 5개, 바뀐 파일 15개
2. Read `docs/result/scenarios/issue-4.md` → 시그니처, 판단 1~7, 시나리오 23개, AC 대조표
3. `git diff main...HEAD -- backend/src/main frontend/src` → changeNickname·changePassword·DTO·매퍼·컨트롤러·프론트 구현 확인
4. Read `MemberAccountIntegrationTest.java` → 통합 테스트 7개, 209줄에 상태 단언이 없음
5. `git diff main...HEAD --` (MemberControllerTest·MemberServiceTest·LoginAttempt·SignUp 통합 테스트) → 단위·컨트롤러 테스트 본문 확인
6. `grep Transactional … MemberService.java; grep permitAll/authenticated SecurityConfig; grep getName LoginMember; grep Pattern SignUpRequest; docker ps` → @Transactional 없음, `anyRequest().authenticated()`, 색인 키 주석, 가입 규칙이 같음, Oracle·Redis healthy
7. `git archive HEAD backend | tar -x -C scratchpad/mut` → 스크래치 복사본 생성
8. `sh scratchpad/mutate.sh` (M1·M2·M3을 차례로 gradle test, **변이 실행 1회차**) → M1 치환 미적용(무효), M2 생존(exit 0), M3 exit 1
9. M2 로그와 test-results 목록 확인 → XML 결과 위치 확인
10. 백그라운드 출력, M3 로그, XML 확인 → M3 "60 tests completed, 2 failed", 통합 테스트 206줄 실패, 소스 원복 확인(`{2,12}` 복귀, `updatePasswordHash` 1회)
11. ServiceTest$ChangePassword XML의 failure 확인 → 351줄 savesNewPasswordHash가 TooManyActualInvocations로 실패
12. perl `\Q…\E` 치환 + gradle test (**변이 실행 2회차**) → 치환 미적용, exit 0(무효), 원복 확인
13. awk로 14줄 교체 + gradle test (**변이 실행 3회차**) → 컴파일 오류 "illegal escape character"로 BUILD FAILED(무효), 원복 확인(`8,64` 1건)
14. `grep -i error M1c.log; grep -n "ChangePasswordRequest(\|changePassword(.*\"" …; grep NEW_PASSWORD =` → 컴파일 오류 원인 확인, 새 비밀번호 입력이 "newpass99"/"abcd123"/"abcdefgh"뿐임을 확인
15. `grep -n` (MemberService·MemberController·ErrorCode 줄 번호, ServiceTest·ControllerTest의 DisplayName 줄 번호) → 위 근거의 파일:줄

### 관련 파일
- C:\live-streaming-project\backend\src\main\java\com\livestream\member\MemberService.java
- C:\live-streaming-project\backend\src\main\java\com\livestream\member\MemberController.java
- C:\live-streaming-project\backend\src\main\java\com\livestream\member\ChangePasswordRequest.java
- C:\live-streaming-project\backend\src\main\java\com\livestream\member\ChangeNicknameRequest.java
- C:\live-streaming-project\backend\src\main\java\com\livestream\common\error\ErrorCode.java
- C:\live-streaming-project\backend\src\test\java\com\livestream\member\MemberServiceTest.java
- C:\live-streaming-project\backend\src\test\java\com\livestream\member\MemberControllerTest.java
- C:\live-streaming-project\backend\src\test\java\com\livestream\member\MemberAccountIntegrationTest.java
- C:\live-streaming-project\docs\result\scenarios\issue-4.md

### 구현자 이견

- 판정에 이견이 없다. AC 1·5의 갭은 내가 가입 테스트(#1)에 있던 경계 수락 쪽을 이번 DTO에 옮기지 않은 것이다. 규칙을 `SignUpRequest`와 따로 적었으니 테스트도 따로 있어야 한다.
- 약한 단언 1(통합 테스트 209줄 응답 미단언)은 실제 약점이다. 갭을 메울 때 함께 고치는 것을 제안한다.

### 갭 처리

- 사용자 결정(2026-11-20): 검증자가 제안한 시나리오 8개를 모두 메운다.
- 8개를 `## 테스트 시나리오`와 AC 대조표에 더했다. 구현은 이미 맞을 것으로 보여, 새 테스트는 각각 그 규칙을 깨는 변이에서 실패하는지로 확인한다.
- 약한 단언 1(통합 테스트에서 틀린 현재 비밀번호 요청의 응답을 단언하지 않음)도 함께 고친다. 400과 `MEMBER_PASSWORD_MISMATCH`를 단언한다.

### 2차 검증 (갭을 메운 뒤 다시 띄운 검증자. 범위를 AC 1·5와 이번 diff로 좁힘. 원문이며, 제목 수준만 한 단계씩 낮췄다)

#### 이슈 #4 2차 AC 검증 (브랜치 `feat/member/issue-4`, HEAD d708aff)

AC 1·5의 갭은 한 곳만 빼고 메워졌다. 남은 것은 닉네임 특수문자 하나다. 시나리오 이름은 "공백 또는 특수문자"인데 테스트 입력에는 공백만 있다. 이 갭은 변이 1번으로 확인했다.

#### 판정표

| AC | 1차 | 2차 | 한 줄 근거 |
|---|---|---|---|
| 1 닉네임 2~12자, 중복이면 409 | ⚠️ | **⚠️ (경미)** | 길이 경계 1·2·12·13자는 모두 테스트된다. 특수문자를 거절하는지는 아직 아무 테스트도 보지 않는다 |
| 2 현재 비밀번호 틀리면 400, 비밀번호 그대로 | ✅ | ✅ 유지 | 약한 단언 1이 고쳐졌다. 해시를 저장하지 않는다는 서비스 테스트도 생겼다 |
| 3 A가 바꾸면 B는 401, A는 200 | ✅ | ✅ 유지 | 이번 diff에서 관련 테스트와 구현이 바뀌지 않았다 |
| 4 새 비밀번호 로그인 성공, 이전 비밀번호 401 | ✅ | ✅ 유지 | 이번 diff에서 관련 테스트와 구현이 바뀌지 않았다 |
| 5 새 비밀번호 규칙(8~64자, 영문+숫자) 위반이면 400 | ⚠️ | **✅** | 7·8·64·65자 경계와 숫자 없음·영문 없음이 모두 테스트된다 |

**AC 5개 중 ✅ 4 / ⚠️ 1 / ❌ 0**

##### AC 1 — Given 로그인한 회원, When 닉네임을 2~12자 규칙에 맞게 바꾸면, Then 바뀌고, 다른 회원이 쓰는 닉네임이면 409다

⚠️ 부분 충족 (경미)
- **구현:** `ChangeNicknameRequest`의 정규식 `^[가-힣A-Za-z0-9]{2,12}$`. 파일은 `C:\live-streaming-project\backend\src\main\java\com\livestream\member\ChangeNicknameRequest.java`(8줄)이다.
- **길이 경계는 충족한다.** 모두 `C:\live-streaming-project\backend\src\test\java\com\livestream\member\MemberControllerTest.java`에 있다.
  - 1자 400: 289줄
  - 2자 "가나" 200: 305줄, 새로 추가됨
  - 12자 200: 311줄, 새로 추가됨. 입력 "가나다라마바사아자차카타"를 세어 보니 12자다
  - 13자 400: 297줄
  - 구현자가 돌린 `{3,11}` 변이를 2자·12자 테스트가 잡는 이유: 두 입력이 각각 3자 미만, 11자 초과다.
- **남은 갭:** 317줄의 `should return 400 when nickname contains a space or special character`는 입력이 `"시청 자"` 하나뿐이다. 공백만 보고 특수문자는 보지 않는다.
  - 변이로 확인했다. 스크래치 복사본에서 정규식을 `[가-힣A-Za-z0-9!]{2,12}`로 바꾸자 MemberControllerTest와 MemberServiceTest 61개가 전부 통과했다(BUILD SUCCESSFUL, 실패 0).
  - 특수문자를 허용하는 회귀가 생겨도 막을 테스트가 없다. 그런데 테스트 이름은 막는다고 적고 있다.
  - "한 테스트는 한 가지만 검사한다"는 컨벤션에도 맞지 않는다. 둘로 나누는 것을 제안한다.
- **참고 (갭 아님):** 2자·12자 수락 테스트는 서비스 목을 스텁하지 않는다. 그래서 응답 본문은 null이고 단언은 200 상태뿐이다. 검증 계층의 경계를 보는 테스트이므로 의도에는 맞는다. 바뀐 닉네임이 반환되는지는 279줄 테스트와 통합 테스트가 본다.
- 409 단언(325줄)과 서비스·통합 테스트는 1차 판정 그대로다.

##### AC 5 — Given 새 비밀번호가 규칙(8~64자, 영문+숫자)에 맞지 않으면, When 바꾸면, Then 400이다

✅ 충족
- **구현:** `ChangePasswordRequest`의 newPassword 정규식 `^(?=.*[A-Za-z])(?=.*\d).{8,64}$`. 파일은 `C:\live-streaming-project\backend\src\main\java\com\livestream\member\ChangePasswordRequest.java`(14줄)이다.
- **테스트 (MemberControllerTest.java):**
  - 7자 400: 359줄
  - 8자 "abcd1234" 204: 375줄, 새로 추가됨
  - 64자(a 32개 + 1 32개) 204: 381줄, 새로 추가됨
  - 65자 400 + `INVALID_INPUT`: 387줄, 새로 추가됨
  - 숫자 없음 400: 367줄
  - 영문 없음 "12345678" 400 + `INVALID_INPUT`: 395줄, 새로 추가됨
- 직전·정확히·직후 경계가 양쪽 끝 모두 있다. 영문과 숫자 조건도 각각 따로 거절된다.
- 구현자의 `{9,63}`, `{8,}`, 영문 lookahead 제거 변이를 각각 8자·64자·65자·영문 없음 테스트가 잡는 구조다. 입력값으로 확인했고 다시 돌리지는 않았다.

##### 약한 단언 1 — 고쳐졌다

- `C:\live-streaming-project\backend\src\test\java\com\livestream\member\MemberAccountIntegrationTest.java` 206~214줄. 변경 요청 응답에 `status().isBadRequest()`와 `jsonPath("$.code").value("MEMBER_PASSWORD_MISMATCH")`를 단언한 뒤, 이전 비밀번호로 로그인해 200을 확인한다.
- `ErrorCode.MEMBER_PASSWORD_MISMATCH`는 `HttpStatus.BAD_REQUEST`다(`C:\live-streaming-project\backend\src\main\java\com\livestream\common\error\ErrorCode.java` 17줄). AC 2의 "400이고 그대로다"를 한 흐름에서 둘 다 단언한다.
- 함께 추가된 `C:\live-streaming-project\backend\src\test\java\com\livestream\member\MemberServiceTest.java` 393줄도 유효하다(`should not save a new password hash when current password is wrong`).
  - `@BeforeEach`(342~349줄)가 `findById`를 스텁한다. 그래서 `Optional.empty`의 `orElseThrow` 때문에 우연히 통과하는 경우가 아니다. 실제로 `passwordEncoder.matches`까지 간다(MemberService.java 99줄).
  - `catchThrowable`의 결과는 버려진다. 예외 종류는 같은 클래스의 MISMATCH 테스트가 따로 보므로 문제없다.

##### AC 2·3·4 — 1차 판정 유지

- **AC 2 ✅ 유지.** 이번 diff는 단언을 강화하고 테스트를 하나 더했을 뿐이다. 구현(MemberService.java 97~107줄)은 바뀌지 않았다.
- **AC 3 ✅ 유지.** 이번 diff에 관련 변경이 없다(`git diff 356a250..d708aff`는 테스트 파일 3개만 바꿨고 `src/main`은 그대로다).
- **AC 4 ✅ 유지.** 위와 같다.

#### 남은 갭 시나리오 제안

```
- [ ] [예외] `PUT /api/members/me/nickname` — should return 400 when nickname contains a special character
```
기존 317줄 테스트는 이름을 `should return 400 when nickname contains a space`로 좁힌다. 위 테스트는 입력 예로 `"시청자!"`를 쓰고 `INVALID_INPUT`까지 단언한다. 시나리오 문서 `C:\live-streaming-project\docs\result\scenarios\issue-4.md`의 해당 줄도 같이 고친다.

#### 가짜 테스트

새 테스트 8개와 수정 1건에서 찾지 못했다. 단언 없는 테스트, 구체적이지 않은 예외 단언, 목 반환값을 그대로 단언하는 경우가 없다. 다만 317줄은 이름이 실제 검사 범위보다 넓다. 그 내용은 위의 갭과 같다.

#### 실행한 명령

1. `cat .claude/harness.json && gh issue view 4 && git diff 356a250..d708aff --stat && git status --short` → scenariosDir 확인, AC 5개, 바뀐 테스트 파일 3개(+61/-1), 작업 트리는 시나리오 문서만 수정된 상태
2. `git diff 356a250..d708aff && git diff docs/result/scenarios/issue-4.md` → 테스트 8개 추가와 통합 테스트 단언 수정을 확인. 시나리오 문서에 새 항목 7개와 수동 확인 결과가 추가됨
3. `grep` (Request DTO 검증 어노테이션, MemberControllerTest 헬퍼·스텁) → 닉네임·비밀번호 정규식 확인. 수락 테스트는 서비스 목을 스텁하지 않음
4. `sed -n 266,350p MemberControllerTest.java`, `grep MemberController.java`, `cat ChangeNicknameRequest.java`, `grep -A25 changePassword MemberService.java` → 테스트 본문 확인. 현재 비밀번호 확인 → 해시 저장 → 세션 삭제 순서 확인
5. `grep -A30 "class ChangePassword" MemberServiceTest.java` 외, 1차 `## AC 검증` 절 grep → `@BeforeEach`에서 findById 스텁 확인. 1차 제안 문구 확인
6. `git archive HEAD backend | tar -x` 로 스크래치 `C:\Users\USER\AppData\Local\Temp\claude\1cbb40fc7d76b7b0\8bac89f5-0fc0-4042-9551-b5de214a8fa5\scratchpad\v2` 에 복사 + 닉네임 정규식에 `!` 추가하는 sed → 변이 적용 확인(8줄)
7. (변이 1회) 스크래치에서 `JAVA_HOME=… ./gradlew test --tests "MemberControllerTest" --tests "MemberServiceTest"` → BUILD SUCCESSFUL, 61개 전부 통과. 변이가 살아남아 특수문자 갭이 확인됨
8. `grep -n` 으로 각 DisplayName의 줄 번호와 `ErrorCode.MEMBER_PASSWORD_MISMATCH` 정의 확인 → 근거 줄 번호 수집

저장소 파일은 고치지 않았다. 원래 코드(변이 없음)로 테스트를 다시 돌리지는 않았다. 구현자가 보고한 138개 통과는 다시 확인하지 않았다.

### 구현자 이견 (2차)

- 판정에 이견이 없다. 남은 갭은 내가 "공백 또는 특수문자" 시나리오를 공백 입력 하나로만 옮긴 것이다. 사용자가 승인한 제안 그대로 맞추는 일이라 따로 묻지 않고 메웠다.
- 시나리오를 "공백"과 "특수문자" 둘로 나누고 테스트도 나눴다(`시청자!` 입력 추가). 실제 코드에서 통과, 닉네임 정규식에 `!`를 허용한 변이에서 새 테스트만 실패하는 것을 확인했다. 테스트 하나를 더한 것이라 3차 검증은 띄우지 않았다.

---

## 리팩토링

`/harness:tdd-refactor 4` (2026-11-20, HEAD 907f72e). 고칠 것이 없어 코드를 바꾸지 않았다.

- 이번 이슈에서 바뀐 파일 안에 세 번 반복되는 로직이 없다. `findById(...).orElseThrow()`가 서비스에 세 번째로 나왔지만 한 줄이라 뽑아도 나아지는 게 없다.
- 비밀번호·닉네임 정규식과 메시지가 `SignUpRequest`·`LoginRequest`·`ChangePasswordRequest`·`ChangeNicknameRequest`에 따로 적혀 있다. 상수로 모으려면 이번 이슈 밖 파일을 고쳐야 해서 두었다. 2차 검증에서 나온 갭도 이 중복 때문에 생겼다(가입 쪽 테스트가 이 DTO를 지켜 주지 않는다).

---

## 보안 점검

> `/harness:security-review 4` (2026-11-20, HEAD 907f72e). 예외 대장(`securityExceptions`)은 아직 없다.

### 수집

| 항목 | 결과 |
| --- | --- |
| 타입 검사 (`typecheck`) | 오류 0 |
| 의존성 (`audit` = 프론트 `npm audit`) | 취약점 0 |
| 하드코딩된 비밀값·알려진 키 접두사 | 없음 (`backend/src/main`, `frontend/src`) |
| `.env` | 작업 트리·브랜치 커밋 모두 없음 |
| 로그 호출 | 프론트 `console.log`·백엔드 로거 호출 없음 |
| 인증 | 두 경로 모두 `anyRequest().authenticated()`에 걸린다. 비로그인 401은 통합 테스트가, `permitAll` 변이로 Green에서 확인 |
| 위조 요청 | `PUT`도 `OriginValidationFilter`가 검사한다(안전한 메서드는 GET·HEAD·OPTIONS·TRACE뿐) |
| 비밀번호 저장 | 새 비밀번호는 BCrypt 해시만 저장. 현재 비밀번호 확인 전에는 아무것도 바꾸지 않는다(서비스·통합 테스트) |
| 입력 길이 | 현재·새 비밀번호 모두 64자·ASCII 제한이 BCrypt 비교 전에 걸린다(72바이트 한도 500 방지) |

### 분류

| 분류 | 항목 | 위치 | 판단 | 처리 |
| --- | --- | --- | --- | --- |
| 🟡 | 현재 비밀번호를 틀리는 횟수를 세지 않는다 | `MemberService.changePassword` | 로그인된 세션을 쥔 사람(공용 PC, 훔친 쿠키)이 이 API로 현재 비밀번호를 제한 없이 넣어 볼 수 있다. 맞히면 비밀번호를 바꾸고 주인의 다른 기기를 모두 로그아웃시켜 계정을 가져간다. 로그인 경로는 #3이 막았는데 이 경로가 우회로가 된다. 시그니처 판단 6에서 미뤄 둔 항목 | 사용자 결정으로 이번에 고쳤다. 비교 전에 `LoginAttemptLimiter.acquire(이메일, IP)`, 맞으면 `succeeded`. 로그인 실패와 같은 카운터라 5번 틀리면 로그인·비밀번호 변경이 함께 15분 막힌다. 시나리오 5개(`93387a1`), 구현 `fix:` 커밋 |
| 🟡 | 세션 쿠키 `Secure` 없음 | `application.yml` | #2에서 넘어옴. 로컬은 http | 배포 작업 때 |
| 🟡 | `SignUpRequest`의 영어 기본 메시지 | `SignUpRequest.java` | #1에서 넘어옴. 범위 밖 | — |
| ⚪ | 비밀번호를 바꿔도 지금 세션 ID를 바꾸지 않는다 | `MemberController.changePassword` | 세션 고정은 로그인 때 ID를 바꿔 막는다(#2). 이미 인증된 세션이고, AC 3이 A를 그대로 두라고 한다. 시그니처 판단 4 | — |
| ⚪ | 해시 저장 뒤 세션 삭제가 실패하면 500, 다른 기기는 남는다 | `MemberService.changePassword` | DB와 Redis는 한 트랜잭션으로 묶을 수 없다. 다시 바꾸면 지워진다. 시그니처 판단 5 | — |
| ⚪ | 이미 열린 채팅 WebSocket은 끊기지 않는다 | — | PRD M2. 채팅 PRD에서 다룬다 | — |
| ⚪ | 백엔드(Gradle) 의존성 스캔 도구 없음 | `harness.json`의 `audit` | #1~#3과 같다 | CI를 붙일 때 |

### 재스캔

🔴은 없었다. 🟡 중 현재 비밀번호 대입은 사용자 결정으로 이번 이슈에서 고쳤다(테스트 5개 먼저 실패 확인 → `LoginAttemptLimiter` 적용). 고친 뒤 `test` 144개 전부 통과(컴파일 포함), `typecheck` 오류 0, `audit` 취약점 0. 프론트는 바뀌지 않았다 — 비밀번호 변경 폼은 서버 `message`를 그대로 보여 주므로 429의 "N분 후" 문구도 그대로 보인다(브라우저로는 확인하지 않았다). 남은 🟡·⚪는 위 표의 이유로 의도적으로 남겼다.
