# 이슈 #5 — 운영자 계정이 앱 시작 때 준비된다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/5
> PRD: `docs/result/prd/member.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

---

## 시그니처

### 관련 ADR

- **member PRD M4(운영자 계정은 앱 시작 때 환경변수로 만든다)** — `OPERATOR_EMAIL`·`OPERATOR_PASSWORD`가 둘 다 있으면, 그 이메일의 계정이 없을 때만 역할 `OPERATOR`로 만든다. 이미 있으면 아무것도 하지 않는다. 서버 2대가 동시에 시작하면 이메일 UNIQUE 제약으로 하나만 성공하고, 실패한 쪽은 "이미 있음"으로 보고 넘어간다.
- **member PRD 작은 결정(이메일 대소문자)** — 소문자로 바꿔 저장한다.
- **member PRD 작은 결정(비밀번호 해시)** — BCrypt. 기존 `PasswordEncoder` 빈을 쓴다.

### 클래스

`com.livestream.member` 아래 새 클래스 하나. `MemberService`는 건드리지 않는다(#3이 같은 파일을 고친다).

```java
// ApplicationRunner(Spring Boot가 앱 시작을 마친 직후 한 번 부르는 빈)
@Component
public class OperatorAccountInitializer implements ApplicationRunner {

    OperatorAccountInitializer(MemberMapper memberMapper, PasswordEncoder passwordEncoder,
                               @Value("${app.operator.email:}") String email,
                               @Value("${app.operator.password:}") String password);

    // 만들거나, 건너뛴다
    public void run(ApplicationArguments args);
}
```

### 설정

| 파일 | 추가 |
| --- | --- |
| `backend/src/main/resources/application.yml` | `app.operator.email: ${OPERATOR_EMAIL:}`, `app.operator.password: ${OPERATOR_PASSWORD:}` |
| `.env.example` | `OPERATOR_EMAIL=`, `OPERATOR_PASSWORD=` (값 없이) |
| `compose.yml` 공통 api 블록 | `OPERATOR_EMAIL: ${OPERATOR_EMAIL:-}`, `OPERATOR_PASSWORD: ${OPERATOR_PASSWORD:-}` |

### 동작

| 상황 | 결과 |
| --- | --- |
| 둘 다 있고 그 이메일의 계정이 없음 | 소문자 이메일, BCrypt 해시, 닉네임 `운영자`, 역할 `OPERATOR`로 저장 |
| 그 이메일의 계정이 이미 있음 | 아무것도 하지 않는다. 비밀번호·역할을 바꾸지 않는다 |
| 하나라도 비었음(빈 문자열 포함) | 만들지 않고 앱은 정상으로 뜬다 |
| 저장이 중복 오류로 실패했고 그 이메일의 계정이 이제 있음(동시 시작) | 예외 없이 끝난다 |
| 저장이 중복 오류로 실패했고 그 이메일의 계정은 없음(닉네임 `운영자`를 다른 회원이 씀) | `DuplicateKeyException`을 그대로 던진다 → 앱이 뜨지 않는다 |

### 에러 케이스

HTTP 경로가 없어 새 에러 코드를 만들지 않는다.

| 상황 | 결과 |
| --- | --- |
| 닉네임 `운영자`가 다른 계정에 있음 | `DuplicateKeyException` (앱 시작 실패) |

### 판단

- **닉네임 `운영자` 고정.** 닉네임은 NOT NULL·UNIQUE인데 PRD에 값이 없다. 환경변수를 하나 더 받는 것은 AC 범위를 넘는다.
- **닉네임이 겹치면 앱이 뜨지 않는다.** 조용히 건너뛰면 운영자 없이 서비스가 뜨고, #7의 승인을 할 사람이 없다는 걸 늦게 안다.
- **빈 문자열도 "없음"이다.** `.env.example`을 그대로 복사하면 값이 `""`로 들어온다.
- **"앱이 시작하면"의 테스트.** 운영자 값을 넣은 Spring 컨텍스트를 따로 띄우면 Oracle 컨테이너가 하나 더 뜬다. 실제 Oracle 통합 테스트에서 `run()`을 직접 부르고, 운영자 값이 없는 공용 컨텍스트가 이 빈을 가진 채 정상으로 뜨는 것으로 "시작 때 실행되고 값이 없어도 뜬다"를 본다. 환경변수를 실제로 넣고 띄워 로그인하는 것은 Green 끝에 Testcontainers DB로 수동 확인한다.

### 이 이슈에서 만들지 않는 것

- 운영자 비밀번호 규칙 검사(8~64자 영문+숫자) — AC에 없다
- 운영자 여러 명 — M4의 다시 여는 조건
- 신청 승인 API — #7

---

## 테스트 시나리오

`OperatorAccountInitializerTest`(단위, Mockito)와 `OperatorAccountIntegrationTest`(`@SpringBootTest`, 실제 Oracle·Redis).

### 정상

- [ ] [정상] `run` — should save an OPERATOR account with the hashed password when both values are set and the email has no account
- [ ] [정상] `run` — should not save anything when an account with the email already exists
- [ ] [정상] `OperatorAccountIntegration` — should return role OPERATOR from my info when logging in with the operator password after the initializer runs
- [ ] [정상] `OperatorAccountIntegration` — should still log in with the old password when the initializer runs again with a different password

### 경계

- [ ] [경계] `run` — should check and save the email in lowercase when the email has uppercase letters
- [ ] [경계] `run` — should not save anything when the password is empty
- [ ] [경계] `run` — should not save anything when the email is empty
- [ ] [경계] `run` — should not save anything when both values are empty
- [ ] [경계] `OperatorAccountIntegration` — should keep role MEMBER when a member signed up with the operator email before the initializer runs
- [ ] [경계] `OperatorAccountIntegration` — should leave one account and finish both without exception when two initializers run at once
- [ ] [경계] `OperatorAccountIntegration` — should finish without exception and leave one account when the existence check passes but the account is already saved
- [ ] [경계] `OperatorAccountIntegration` — should start the application with the initializer registered when operator values are not set

### 예외

- [ ] [예외] `run` — should finish without exception when saving hits a duplicate and the email now has an account
- [ ] [예외] `run` — should throw DuplicateKeyException when saving hits a duplicate and the email still has no account

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| Given 두 환경변수가 있고 그 이메일의 계정이 없음, When 앱이 시작하면, Then 역할 `OPERATOR` 계정이 생기고 그 비밀번호로 로그인하면 내 정보의 역할이 `OPERATOR`다 | `[정상] run — should save an OPERATOR account with the hashed password ...`<br>`[정상] OperatorAccountIntegration — should return role OPERATOR from my info ...`<br>`[경계] OperatorAccountIntegration — should start the application with the initializer registered ...`(시작 때 실행됨) |
| Given 그 이메일의 계정이 이미 있음, When 다른 비밀번호로 앱이 시작하면, Then 계정은 바뀌지 않는다(이전 비밀번호로 로그인된다) | `[정상] run — should not save anything when an account with the email already exists`<br>`[정상] OperatorAccountIntegration — should still log in with the old password ...`<br>`[경계] OperatorAccountIntegration — should keep role MEMBER when a member signed up with the operator email ...` |
| Given 환경변수가 없거나 둘 중 하나만 있음, When 앱이 시작하면, Then 운영자 계정을 만들지 않고 앱은 정상으로 뜬다 | `[경계] run — should not save anything when the password is empty`<br>`[경계] run — ... when the email is empty`<br>`[경계] run — ... when both values are empty`<br>`[경계] OperatorAccountIntegration — should start the application with the initializer registered when operator values are not set` |
| Given 계정이 없음, When 운영자 생성이 두 번 동시에 실행되면, Then 계정은 하나이고 둘 다 예외 없이 끝난다 | `[경계] OperatorAccountIntegration — should leave one account and finish both without exception when two initializers run at once`<br>`[경계] OperatorAccountIntegration — should finish without exception and leave one account when the existence check passes ...`<br>`[예외] run — should finish without exception when saving hits a duplicate and the email now has an account` |
| `.env.example`에 두 키가 값 없이 적혀 있다 | 테스트 없음 — 파일 내용으로 확인한다(Green에서 추가, AC 검증에서 읽는다) |

**AC에 없는데 추가한 시나리오**

- `[경계] run — should check and save the email in lowercase ...` — PRD 작은 결정(이메일 소문자 저장). 없으면 `Admin@x.com`으로 설정했을 때 로그인(소문자로 찾음)이 안 된다
- `[예외] run — should throw DuplicateKeyException when ... the email still has no account` — 시그니처 판단(닉네임이 겹치면 앱이 뜨지 않는다)

**커버리지:** AC 5개 / 시나리오 14개 / 미커버 0개 (AC 5는 테스트가 아닌 파일 확인)
