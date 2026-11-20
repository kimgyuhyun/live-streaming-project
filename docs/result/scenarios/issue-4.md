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
    public void changePassword(long memberId, ChangePasswordRequest request, String currentSessionId);
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
6. **현재 비밀번호를 틀리는 횟수는 세지 않는다.** 로그인 시도 제한(M3)은 로그인에만 건다. 남의 로그인된 브라우저를 쓰는 사람은 이 API로 현재 비밀번호를 계속 넣어 볼 수 있다. 하지만 이미 그 사람으로 로그인된 상태이고, 사양에도 제한이 없다. 보안 점검에서 다시 본다.
7. **새 비밀번호가 지금 비밀번호와 같아도 받는다.** 사양에 금지가 없다.

### 이 이슈에서 만들지 않는 것

- 이메일 변경, 비밀번호 찾기, 회원 탈퇴 (PRD "하지 않을 것")
- 이미 열린 채팅 WebSocket 끊기 (PRD M2 — 채팅 PRD에서 다룬다)
- 비밀번호 변경 시도 횟수 제한 (판단 6)
- 프론트 테스트 도구(Vitest) 도입 — 이 화면은 폼 두 개와 API 호출뿐이라 화면 로직이 없다

---

## 테스트 시나리오

### 정상

- [ ] [정상] `MemberService.changeNickname` — should save and return the new nickname when no other member uses it
- [ ] [정상] `MemberService.changePassword` — should save a bcrypt hash of the new password when current password matches
- [ ] [정상] `MemberService.changePassword` — should delete the member's other sessions and keep the current one
- [ ] [정상] `PUT /api/members/me/nickname` — should return 200 with the changed nickname
- [ ] [정상] `PUT /api/members/me/password` — should return 204 and pass the current session id to the service
- [ ] [정상] `MemberAccountIntegration` — should show the new nickname in my info after changing it
- [ ] [정상] `MemberAccountIntegration` — should return 401 to device B and 200 to device A after A changes the password
- [ ] [정상] `MemberAccountIntegration` — should log in with the new password after the change

### 경계

- [ ] [경계] `MemberService.changeNickname` — should return the member unchanged when the new nickname is the current one
- [ ] [경계] `MemberService.changeNickname` — should throw MEMBER_NICKNAME_DUPLICATED when the unique constraint rejects the update
- [ ] [경계] `PUT /api/members/me/nickname` — should return 400 when nickname is 1 character
- [ ] [경계] `PUT /api/members/me/nickname` — should return 400 when nickname is 13 characters
- [ ] [경계] `PUT /api/members/me/password` — should return 400 when new password is 7 characters
- [ ] [경계] `PUT /api/members/me/password` — should return 400 when new password has no digit
- [ ] [경계] `MemberAccountIntegration` — should accept only one of two members changing to the same nickname at once

### 예외

- [ ] [예외] `MemberService.changeNickname` — should throw MEMBER_NICKNAME_DUPLICATED when another member uses the nickname
- [ ] [예외] `MemberService.changePassword` — should throw MEMBER_PASSWORD_MISMATCH when current password is wrong
- [ ] [예외] `MemberService.changePassword` — should not delete any session when current password is wrong
- [ ] [예외] `PUT /api/members/me/nickname` — should return 409 with code MEMBER_NICKNAME_DUPLICATED when service rejects the nickname
- [ ] [예외] `PUT /api/members/me/password` — should return 400 with code MEMBER_PASSWORD_MISMATCH when service rejects current password
- [ ] [예외] `MemberAccountIntegration` — should keep the old password when the change is rejected for a wrong current password
- [ ] [예외] `MemberAccountIntegration` — should reject the old password with 401 after the change
- [ ] [예외] `MemberAccountIntegration` — should return 401 when changing nickname without logging in

### 수동 확인 (프론트 테스트 도구가 없다 — 시나리오 수에 넣지 않는다)

Green 단계에서 브라우저로 확인하고 결과를 이 문서와 PR에 남긴다.

- 내 정보 화면에서 닉네임을 바꾸면 화면과 머리글의 닉네임이 바로 바뀐다. 다른 회원의 닉네임이면 "이미 사용 중인 닉네임입니다"가 보인다
- 현재 비밀번호를 틀리면 "현재 비밀번호가 맞지 않습니다", 맞으면 "비밀번호를 바꿨습니다. 다른 기기에서는 로그아웃됩니다"가 보인다
- 브라우저 두 곳(일반 창·시크릿 창)에 로그인한 뒤 한쪽에서 비밀번호를 바꾸면, 다른 쪽은 새로고침했을 때 로그인 링크가 보인다

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 닉네임을 2~12자 규칙에 맞게 바꾸면 바뀌고, 다른 회원이 쓰는 닉네임이면 409 | `[정상] MemberService.changeNickname — should save and return the new nickname when no other member uses it`<br>`[정상] PUT /api/members/me/nickname — should return 200 with the changed nickname`<br>`[정상] MemberAccountIntegration — should show the new nickname in my info after changing it`<br>`[경계] PUT /api/members/me/nickname — should return 400 when nickname is 1 character`<br>`[경계] PUT /api/members/me/nickname — should return 400 when nickname is 13 characters`<br>`[경계] MemberService.changeNickname — should throw MEMBER_NICKNAME_DUPLICATED when the unique constraint rejects the update`<br>`[경계] MemberAccountIntegration — should accept only one of two members changing to the same nickname at once`<br>`[예외] MemberService.changeNickname — should throw MEMBER_NICKNAME_DUPLICATED when another member uses the nickname`<br>`[예외] PUT /api/members/me/nickname — should return 409 with code MEMBER_NICKNAME_DUPLICATED when service rejects the nickname` |
| 2. 현재 비밀번호를 틀린 채 변경 요청하면 400이고 비밀번호는 그대로 | `[예외] MemberService.changePassword — should throw MEMBER_PASSWORD_MISMATCH when current password is wrong`<br>`[예외] MemberService.changePassword — should not delete any session when current password is wrong`<br>`[예외] PUT /api/members/me/password — should return 400 with code MEMBER_PASSWORD_MISMATCH when service rejects current password`<br>`[예외] MemberAccountIntegration — should keep the old password when the change is rejected for a wrong current password` |
| 3. 기기 A·B 로그인, A에서 비밀번호를 바꾸면 B는 `/me` 401, A는 200 | `[정상] MemberService.changePassword — should delete the member's other sessions and keep the current one`<br>`[정상] PUT /api/members/me/password — should return 204 and pass the current session id to the service`<br>`[정상] MemberAccountIntegration — should return 401 to device B and 200 to device A after A changes the password` |
| 4. 새 비밀번호로 로그인하면 성공, 이전 비밀번호는 401 | `[정상] MemberService.changePassword — should save a bcrypt hash of the new password when current password matches`<br>`[정상] MemberAccountIntegration — should log in with the new password after the change`<br>`[예외] MemberAccountIntegration — should reject the old password with 401 after the change` |
| 5. 새 비밀번호가 규칙(8~64자, 영문+숫자)에 맞지 않으면 400 | `[경계] PUT /api/members/me/password — should return 400 when new password is 7 characters`<br>`[경계] PUT /api/members/me/password — should return 400 when new password has no digit` |

**AC에 없는데 넣은 시나리오**

| 시나리오 | 이유 |
| --- | --- |
| `[경계] MemberService.changeNickname — should return the member unchanged when the new nickname is the current one` | 판단 2. 중복 조회만 하면 자기 닉네임에 409가 난다 |
| `[예외] MemberAccountIntegration — should return 401 when changing nickname without logging in` | 새 경로가 보안 설정의 `authenticated()`에 걸리는지 확인한다. 컨트롤러 테스트는 필터를 끄고 돌아서 이걸 못 본다 |

**커버리지:** AC 5개 / 시나리오 23개 / 미커버 0개 (화면은 수동 확인)
