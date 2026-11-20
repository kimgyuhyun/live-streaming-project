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

- [x] [정상] `run` — should save an OPERATOR account with the hashed password when both values are set and the email has no account
- [x] [정상] `run` — should not save anything when an account with the email already exists
- [x] [정상] `OperatorAccountIntegration` — should return role OPERATOR from my info when logging in with the operator password after the initializer runs
- [x] [정상] `OperatorAccountIntegration` — should still log in with the old password when the initializer runs again with a different password
- [x] [정상] `OperatorAccountInitializer` — should save an OPERATOR account at application startup when OPERATOR_EMAIL and OPERATOR_PASSWORD are set

### 경계

- [x] [경계] `run` — should check and save the email in lowercase when the email has uppercase letters
- [x] [경계] `run` — should not save anything when the password is empty
- [x] [경계] `run` — should not save anything when the email is empty
- [x] [경계] `run` — should not save anything when both values are empty
- [x] [경계] `OperatorAccountIntegration` — should keep role MEMBER when a member signed up with the operator email before the initializer runs
- [x] [경계] `OperatorAccountIntegration` — should leave one account and finish both without exception when two initializers run at once
- [x] [경계] `OperatorAccountIntegration` — should finish without exception and leave one account when the existence check passes but the account is already saved
- [x] [경계] `OperatorAccountIntegration` — should start the application with the initializer registered when operator values are not set

### 예외

- [x] [예외] `run` — should finish without exception when saving hits a duplicate and the email now has an account
- [x] [예외] `run` — should throw DuplicateKeyException when saving hits a duplicate and the email still has no account

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| Given 두 환경변수가 있고 그 이메일의 계정이 없음, When 앱이 시작하면, Then 역할 `OPERATOR` 계정이 생기고 그 비밀번호로 로그인하면 내 정보의 역할이 `OPERATOR`다 | `[정상] run — should save an OPERATOR account with the hashed password ...`<br>`[정상] OperatorAccountIntegration — should return role OPERATOR from my info ...`<br>`[경계] OperatorAccountIntegration — should start the application with the initializer registered ...`(시작 때 실행됨)<br>`[정상] OperatorAccountInitializer — should save an OPERATOR account at application startup when OPERATOR_EMAIL and OPERATOR_PASSWORD are set`(AC 검증 후 추가: 환경변수 → 설정 → 시작 때 실행) |
| Given 그 이메일의 계정이 이미 있음, When 다른 비밀번호로 앱이 시작하면, Then 계정은 바뀌지 않는다(이전 비밀번호로 로그인된다) | `[정상] run — should not save anything when an account with the email already exists`<br>`[정상] OperatorAccountIntegration — should still log in with the old password ...`<br>`[경계] OperatorAccountIntegration — should keep role MEMBER when a member signed up with the operator email ...` |
| Given 환경변수가 없거나 둘 중 하나만 있음, When 앱이 시작하면, Then 운영자 계정을 만들지 않고 앱은 정상으로 뜬다 | `[경계] run — should not save anything when the password is empty`<br>`[경계] run — ... when the email is empty`<br>`[경계] run — ... when both values are empty`<br>`[경계] OperatorAccountIntegration — should start the application with the initializer registered when operator values are not set` |
| Given 계정이 없음, When 운영자 생성이 두 번 동시에 실행되면, Then 계정은 하나이고 둘 다 예외 없이 끝난다 | `[경계] OperatorAccountIntegration — should leave one account and finish both without exception when two initializers run at once`<br>`[경계] OperatorAccountIntegration — should finish without exception and leave one account when the existence check passes ...`<br>`[예외] run — should finish without exception when saving hits a duplicate and the email now has an account` |
| `.env.example`에 두 키가 값 없이 적혀 있다 | 테스트 없음 — 파일 내용으로 확인한다(Green에서 추가, AC 검증에서 읽는다) |

**AC에 없는데 추가한 시나리오**

- `[경계] run — should check and save the email in lowercase ...` — PRD 작은 결정(이메일 소문자 저장). 없으면 `Admin@x.com`으로 설정했을 때 로그인(소문자로 찾음)이 안 된다
- `[예외] run — should throw DuplicateKeyException when ... the email still has no account` — 시그니처 판단(닉네임이 겹치면 앱이 뜨지 않는다)

**커버리지:** AC 5개 / 시나리오 15개 / 미커버 0개 (AC 5는 테스트가 아닌 파일 확인. 15번째는 AC 검증 후 추가)

---

## Green 기록

**테스트:** 전체 96개 통과(이 이슈 14개: 단위 8 · 통합 6). 커버리지 명령(`coverage`)은 설정이 비어 있어 재지 않았다.

**변이 확인** — 구현을 잠깐 망가뜨려 테스트가 잡는지 본 뒤 되돌렸다.

| 망가뜨린 것 | 실패한 테스트 |
| --- | --- |
| 이메일 소문자 변환을 뺌 | `should check and save the email in lowercase ...` |
| 빈 값 확인 `||`를 `&&`로 | `should not save anything when the password is empty`, `... when the email is empty` |
| 중복 오류를 항상 삼킴 | `should throw DuplicateKeyException when ... the email still has no account` |
| (Red~Green 중간) 중복 오류 처리 없음 | 동시 실행 통합 테스트 2개, 단위 1개 |
| (Red~Green 중간) `@Component` 없음 | `should start the application with the initializer registered ...` |

**AC 검증 후 갭 메우기** — `OperatorAccountStartupTest`(이 클래스만 띄운 Spring 컨텍스트, DB 없음, 17초)를 더했다. 구현이 이미 있어 처음부터 통과하므로, 연결을 하나씩 끊어 실패하는 것을 Red 대신 확인했다. 전체 97개 통과(이 이슈 15개).

| 끊은 연결 | 결과 |
| --- | --- |
| yml 키 `operator.email` → `operator.emial` | 실패 |
| yml 자리표시자 `${OPERATOR_PASSWORD:}` → `${OPERATOR_PASSWRD:}` | 실패 |
| `@Value("${app.operator.email:}")` → `app.operater.email` | 실패 |

**수동 확인** — 환경변수 → 설정값 → 시작 때 실행으로 이어지는지. 공유 개발 DB를 건드리지 않게 Testcontainers DB로 띄웠다(임시 실행 클래스, 확인 후 지움).

- `OPERATOR_EMAIL=Boss@Example.com`, `OPERATOR_PASSWORD=opPass1234`로 `bootTestRun`(포트 18085)
- `POST /api/auth/login` (`boss@example.com` / 설정한 비밀번호) → 200, `GET /api/members/me` → `{"email":"boss@example.com","nickname":"운영자","role":"OPERATOR"}`
- 틀린 비밀번호 → 401

**AC 5** — `.env.example` 끝에 `OPERATOR_EMAIL=`·`OPERATOR_PASSWORD=`를 값 없이 추가했다. `compose.yml` 공통 api 블록에도 두 값을 넘긴다(비어 있으면 만들지 않는다).

---

## AC 검증

`harness:ac-verifier`의 출력 원문.

# 이슈 #5 AC 검증 — 운영자 계정이 앱 시작 때 준비된다

이슈 #5의 AC 5개 중 4개는 충족, 1개는 부분 충족이다. 부분 충족은 AC 1이다. 구현은 의도대로 동작하지만, 환경변수에서 시작 실행까지 이어지는 연결을 자동 테스트가 잡지 못한다.

이번 검증에서 `OperatorAccount*` 테스트를 다시 돌렸다. BUILD SUCCESSFUL(44초)이고, 단위 테스트 `run` 8개와 통합 테스트 6개가 실패 0, 에러 0으로 통과했다. 테스트 이름 14개는 시나리오 문서의 14개 문장과 모두 같다. 경로는 모두 `C:\live-streaming-project\.claude\worktrees\non-overlapping-manual-approval-issues-5017ff` 아래다. `OperatorAccountIntegrationTest.java`의 줄 번호는 이 파일 자체의 줄이다.

---

### AC 1 — Given 두 환경변수가 있고 그 이메일의 계정이 없음, When 앱이 시작하면, Then 역할 `OPERATOR` 계정이 생기고 그 비밀번호로 로그인하면 내 정보의 역할이 `OPERATOR`다

⚠️ 부분 충족

- **테스트**
  - `should save an OPERATOR account with the hashed password when both values are set and the email has no account` (`backend/src/test/java/com/livestream/member/OperatorAccountInitializerTest.java:53`): 저장된 Member의 role이 OPERATOR이고, `passwordEncoder.matches(PASSWORD, hash)`가 true인지 단언한다.
  - `should return role OPERATOR from my info when logging in with the operator password after the initializer runs` (`OperatorAccountIntegrationTest.java:137`): 실제 Oracle을 쓰고, 로그인으로 받은 SESSION 쿠키로 `GET /api/members/me`를 불러 `$.role == "OPERATOR"`를 확인한다(:145).
  - `should start the application with the initializer registered when operator values are not set` (:212): 빈이 1개 등록됐는지만 단언한다.
- **구현**
  - `OperatorAccountInitializer.run()` (`backend/src/main/java/com/livestream/member/OperatorAccountInitializer.java:35-52`). 이메일을 소문자로 바꾸고(:39), 계정이 없을 때만(:40) OPERATOR로 저장한다(:44).
  - `@Component` + `ApplicationRunner`(:12-13)라서 Spring이 시작 때 부른다.
  - 설정 키는 서로 맞는다.
    - `application.yml:39-41`: `app.operator.email: ${OPERATOR_EMAIL:}`, `app.operator.password: ${OPERATOR_PASSWORD:}`
    - `@Value("${app.operator.email:}")`, `@Value("${app.operator.password:}")` (:24-25)
  - insert SQL이 role 컬럼을 넣는다(`mapper/member/*.xml`의 `INSERT INTO member (email, password_hash, nickname, role)`). `/me` 응답에도 role이 들어 있다(`MemberResponse.java:3`).
- **갭: "When 앱이 시작하면"을 자동 테스트가 타지 않는다.**
  - 통합 테스트는 `new OperatorAccountInitializer(...)`를 직접 만들어 `run()`을 부른다(:81-84).
  - 그래서 환경변수 → yml 자리표시자 → `@Value` → 시작 때 실행으로 이어지는 연결은 아무 테스트도 검증하지 않는다.
  - 예를 들어 yml 키가 `app.operator.emial`로 틀리거나 `${OPERATOR_EMAIL:}`의 변수 이름이 틀려도 테스트 14개는 그대로 통과한다. `@Value`의 기본값이 `:`(빈 문자열)라서 운영자 생성이 조용히 건너뛰어지기 때문이다.
  - 이것은 AC 1의 주 경로가 경고 없이 아무 일도 하지 않게 되는 회귀다.
  - 지금 이 연결의 근거는 Green 기록의 수동 확인뿐이다(`docs/result/scenarios/issue-5.md:139-143`, bootTestRun → login 200 → `/me` role OPERATOR). 다시 돌릴 수 있는 증거가 아니다.
  - 코드를 읽은 판단으로는 구현이 의도를 만족한다. 하지만 테스트가 그 의도를 잠그지 못하므로 부분 충족으로 판정한다.
  - 시나리오 문서 :67은 컨텍스트를 하나 더 띄우면 Oracle 컨테이너가 하나 더 뜬다는 이유로 이 테스트를 뺐다. 그런데 이 연결만 보는 테스트는 DB 없이 가볍게 만들 수 있다. 예를 들어 `@SpringBootTest(classes = OperatorAccountInitializer.class, properties = {"OPERATOR_EMAIL=...", "OPERATOR_PASSWORD=..."})`에 `@MockitoBean MemberMapper`와 `PasswordEncoder`를 쓰면 된다. 자동 구성이 없으니 DataSource와 컨테이너가 뜨지 않고, `application.yml`의 자리표시자는 그대로 해석된다.

### AC 2 — Given 그 이메일의 계정이 이미 있음, When 다른 비밀번호로 앱이 시작하면, Then 계정은 바뀌지 않는다(이전 비밀번호로 로그인된다)

✅ 충족

- **테스트**
  - `should still log in with the old password when the initializer runs again with a different password` (`OperatorAccountIntegrationTest.java:149`): 실제 Oracle에서 `PASSWORD`로 만든 뒤 `"changed5678"`로 다시 실행하고, 이전 비밀번호 로그인이 200인지 단언한다(:156).
  - `should keep role MEMBER when a member signed up with the operator email before the initializer runs` (:160): 일반 가입 계정의 role이 MEMBER로 남는지 단언한다. 역할도 바뀌지 않는다는 것을 보여 준다.
  - `should not save anything when an account with the email already exists` (`OperatorAccountInitializerTest.java:65`): `verify(..., never()).insert`.
- **구현**: `OperatorAccountInitializer.java:40-41`. 계정이 있으면 바로 return한다. 업데이트하는 경로는 아예 없다.
- **근거**: 이전 비밀번호로 로그인이 200이면 해시가 그대로라는 뜻이다. role 유지도 따로 단언한다.

### AC 3 — Given 환경변수가 없거나 둘 중 하나만 있음, When 앱이 시작하면, Then 운영자 계정을 만들지 않고 앱은 정상으로 뜬다

✅ 충족

- **테스트**
  - `OperatorAccountInitializerTest.java`의 세 테스트가 각각 `verify(memberMapper, never()).insert(any())`를 단언한다. mock을 따로 stub하지 않아 `existsByEmail`은 false다. 그러므로 빈 값 검사가 없으면 insert가 일어나고 테스트가 실패한다.
    - `should not save anything when the password is empty` (:85)
    - `should not save anything when the email is empty` (:93)
    - `should not save anything when both values are empty` (:101)
  - Green 기록의 변이 확인(`||`를 `&&`로 바꾸면 2개 실패)과도 맞는다.
  - `should start the application with the initializer registered when operator values are not set` (`OperatorAccountIntegrationTest.java:212`): 운영자 값이 없는 공용 컨텍스트가 이 빈을 가진 채 정상으로 뜬다.
- **구현**: `OperatorAccountInitializer.java:36`의 `email.isBlank() || password.isBlank()`. 값이 아예 없으면 yml의 `${OPERATOR_EMAIL:}` 때문에 `""`가 들어오므로 빈 값과 같은 경로를 탄다. null은 들어올 수 없다.
- **참고(판정에는 영향 없음)**
  - `application.yml:6-8`이 `optional:file:../.env[.properties]`를 import한다. 그래서 테스트도 루트 `.env`를 읽는다.
  - 이 worktree에는 `.env`가 없다(`ls -a`로 `.env.example`만 확인). 그러니 지금은 테스트 이름의 전제("operator values are not set")가 성립한다.
  - 다만 테스트가 이 전제를 단언하지는 않는다. 개발자가 루트 `.env`에 운영자 값을 채우면 이 테스트는 다른 상황을 검증하면서도 통과한다.

### AC 4 — Given 계정이 없음, When 운영자 생성이 두 번 동시에 실행되면, Then 계정은 하나이고 둘 다 예외 없이 끝난다

✅ 충족

- **테스트**
  - `should leave one account and finish both without exception when two initializers run at once` (`OperatorAccountIntegrationTest.java:171`)
    - 스레드 2개를 CountDownLatch로 동시에 출발시킨다.
    - 계정 수가 1인지(:196), 두 결과가 모두 `"OK"`인지(:197) 단언한다.
    - 실제 경합이라 두 실행이 존재 확인을 함께 통과하는 상황이 매번 일어나지는 않는다.
  - `should finish without exception and leave one account when the existence check passes but the account is already saved` (:201)
    - `mapperMissingFirstEmailCheck()`(:99)가 첫 존재 확인만 "없음"으로 답하게 만든다. 그래서 매번 실제 Oracle의 `uk_member_email` 위반 → MyBatis-Spring의 `DuplicateKeyException` 변환 → catch 경로를 결정적으로 탄다.
    - 계정 수 1을 단언하고, 예외가 나면 테스트가 실패한다.
  - `should finish without exception when saving hits a duplicate and the email now has an account` (`OperatorAccountInitializerTest.java:109`): `doesNotThrowAnyException`.
- **구현**: `OperatorAccountInitializer.java:43-50`. `DuplicateKeyException`을 잡은 뒤 이메일이 이제 있으면 삼키고, 없으면 다시 던진다. DB 제약은 `V1__create_member.sql:9`의 `CONSTRAINT uk_member_email UNIQUE (email)`.
- **근거**: 진짜 경합과, 경합을 결정적으로 재현한 경로를 둘 다 실제 DB에서 단언한다. 이메일이 여전히 없을 때 다시 던지는 것은 `OperatorAccountInitializerTest.java:119`가 잡는다. 이것이 없으면 모든 예외를 삼키는 구현도 통과했을 것이다.

### AC 5 — `.env.example`에 두 키가 값 없이 적혀 있다

✅ 충족

- **파일**: `.env.example:11-12`에 `OPERATOR_EMAIL=`, `OPERATOR_PASSWORD=`가 있다. `git show HEAD:.env.example | cat -A`로 `OPERATOR_EMAIL=$`, `OPERATOR_PASSWORD=$`를 확인했다. 값도 뒤따르는 공백도 없다.
- 함께 바뀐 파일: `compose.yml:14-15`가 `${OPERATOR_EMAIL:-}`, `${OPERATOR_PASSWORD:-}`로 api 컨테이너에 값을 넘긴다. 값이 비면 AC 3 경로를 탄다.
- 테스트 대상이 아닌 AC라서 파일을 직접 읽어 확인했다(시나리오 문서 :114의 계획과 같다).

---

## 요약

```
AC 5개 중 — ✅ 4 / ⚠️ 1 / ❌ 0
```

## 갭이 있는 AC에 대한 추가 시나리오 제안

AC 1 (⚠️):

- [ ] [정상] `OperatorAccountInitializer` — should save an OPERATOR account at application startup when OPERATOR_EMAIL and OPERATOR_PASSWORD are set
  - 위에서 말한 DB 없는 경량 `@SpringBootTest(classes = OperatorAccountInitializer.class, properties = {"OPERATOR_EMAIL=boss@example.com", "OPERATOR_PASSWORD=opPass1234"})`에 `@MockitoBean MemberMapper`를 쓴다.
  - `verify(memberMapper).insert(argThat(m -> m.getRole() == Role.OPERATOR && m.getEmail().equals("boss@example.com")))`를 단언한다.
  - 이 테스트가 있으면 환경변수 이름, yml 키, `@Value` 키, 시작 때 실행되는지가 한 번에 잠긴다.

AC 3 보강(선택, 판정과는 무관):

- [ ] [경계] `OperatorAccountIntegration` — should resolve both operator values to blank in the shared test context when operator values are not set
  - `Environment`에서 `app.operator.email`과 `app.operator.password`가 blank인지 단언한다. 통합 테스트의 전제를 고정하는 용도다.
  - `@SpringBootTest(properties=...)`로 값을 덮어쓰면 컨텍스트 캐시 키가 바뀌어 Oracle 컨테이너가 하나 더 뜬다. 그래서 단언으로 전제를 확인하는 쪽을 권한다.

## 가짜 테스트 목록

가짜 테스트는 없다. 확인한 항목은 다음과 같다.

- 예외 단언은 구체적인 타입이다: `isInstanceOf(DuplicateKeyException.class)` (`OperatorAccountInitializerTest.java:125`).
- 단언 없는 테스트, `await` 누락, mock 반환값을 그대로 단언하는 경우는 없다. 단위 테스트는 실제 `BCryptPasswordEncoder`를 쓰고 ArgumentCaptor로 구현이 넘긴 값을 검사한다.

조여 둘 만한 약한 단언은 두 개다.

1. `OperatorAccountIntegrationTest.java:142`: 로그인 응답 상태를 단언하지 않고 바로 쿠키를 꺼낸다. 로그인이 실패하면 `/me`에서 결국 실패하므로 가짜는 아니다. 다만 실패 메시지가 원인을 가린다. `login(...).andExpect(status().isOk())`를 먼저 두는 것을 권한다.
2. `OperatorAccountIntegrationTest.java:212-214`: 빈이 1개인지만 본다. 시작 때 실행된다는 근거로는 약하다. 위의 AC 1 제안으로 보완된다.

## 실행한 명령 목록

1. `cat .claude/harness.json && gh issue view 5 && git diff origin/main...HEAD --stat` → 설정을 확인했다(scenariosDir=docs/result/scenarios). AC 5개, 변경 파일 7개, +554줄.
2. `cat -n docs/result/scenarios/issue-5.md`, `cat -n OperatorAccountInitializer.java`, `git diff origin/main...HEAD -- .env.example compose.yml application.yml` → 시나리오 14개와 AC 대조표, 구현 53줄, 설정 diff를 확인했다.
3. `cat -n OperatorAccountInitializerTest.java OperatorAccountIntegrationTest.java` → 단위 8개와 통합 6개의 본문을 읽었다.
4. `(cd backend && JAVA_HOME=... ./gradlew test --tests "OperatorAccount*")` (백그라운드) → BUILD SUCCESSFUL in 44s.
5. `cat mapper/member/*.xml; grep env build.gradle*; ls backend/src/test/resources; cat TestcontainersConfiguration.java; grep -A6 M4 docs/result/prd/member.md; git show HEAD:.env.example | cat -A`
   - insert에 role 컬럼이 있다.
   - build.gradle에는 env 관련 항목이 없고, test resources 디렉터리도 없다.
   - PRD M4를 확인했다.
   - `.env.example` 끝 두 줄은 `OPERATOR_EMAIL=$`, `OPERATOR_PASSWORD=$`다.
6. `grep import/.env application.yml; ls -a | grep env; grep OperatorAccountInitializer|app.operator backend/src` → yml :6-8이 `../.env`를 import한다. worktree에는 `.env.example`뿐이다. 다른 곳에서 참조하지 않는다.
7. `grep toLowerCase/login/role MemberService·MemberResponse·MemberController; grep unique/role migration` → 로그인 때 소문자로 조회하고 `/me` 응답에 role이 있다. `uk_member_email`, `uk_member_nickname`, `ck_member_role`이 있다.
8. 대기 루프 + `tail` 출력 파일 → BUILD SUCCESSFUL, exit 0. 결과 XML 3개가 생겼다.
9. `grep testsuite/testcase TEST-com.livestream.member.OperatorAccount*.xml` → `run`은 tests=8 failures=0 errors=0, `OperatorAccountIntegration`은 tests=6 failures=0 errors=0. 테스트 이름 14개가 시나리오 문장과 같다.

파일은 고치지 않았다.

### 구현자 이견

- **AC 1 제안은 받는다.** 시나리오로 옮겨 `## 테스트 시나리오`에 더했다(사용자 결정: 갭 메우기).
- **AC 3 보강 제안은 받지 않는다.** 공용 테스트 컨텍스트에서 운영자 값이 비어 있다고 단언하면, 루트 `.env`에 운영자 값을 실제로 채운 PC에서 그 테스트가 깨진다. 정상적인 사용이 테스트 실패가 된다. 그 전제가 깨져도 `should start the application with the initializer registered ...`는 여전히 "이 빈이 있는 채로 앱이 뜬다"를 보여 주고, 빈 값 경로는 단위 테스트 셋이 지킨다.
- **약한 단언 1번은 받는다.** 로그인 응답이 200인지 먼저 단언한다.

---

## 리팩터

대상: `backend/src/main/java/com/livestream/member/OperatorAccountInitializer.java` 하나(`application.yml`·`compose.yml`·`.env.example`은 설정 파일이라 대상 밖).

**고칠 것 없음.** 시작 기준선은 전체 97개 통과(`c6ef141`).

- 중복: 이메일 소문자 변환은 `MemberService`와 두 곳이다. 세 번째가 나오면 뽑는다.
- 복잡도: 조기 반환 둘과 try/catch 하나, 53줄. 매직 값은 닉네임 상수 하나로 이름이 붙어 있다.
- 컨벤션: "미리 확인 → 저장 → 중복 오류면 다시 판단"이 `MemberService.signUp`과 같은 모양이다.

---

## 보안 점검

**결과:** 🔴 0 · 🟡 0 · ⚪ 4

| 실행 | 결과 |
| --- | --- |
| `typecheck` (백엔드 `compileJava compileTestJava`, 프론트 `tsc -b`) | 통과 |
| `audit` (`npm audit`) | 0건. 이번 이슈는 의존성을 바꾸지 않았다 |
| 비밀값 패턴·알려진 접두사 grep (`backend/src/main`, `frontend/src`) | 0건 |
| `.env` 커밋 여부 | `.env.example`만. 끝 두 줄이 `OPERATOR_EMAIL=`·`OPERATOR_PASSWORD=`로 값이 없다(`cat -A`로 확인) |
| 바뀐 운영 코드의 로그 출력 | 없음. 이메일·비밀번호를 찍지 않는다 |

확인한 것: 운영자 비밀번호는 기존 `PasswordEncoder`(BCrypt)를 거쳐 저장된다(`OperatorAccountInitializer.java:44`). Actuator는 `health`만 노출해 `/env`로 설정값이 보이지 않는다(기본값, `application.yml`에 노출 설정 없음).

| ⚪ 항목 | 왜 넘기는가 |
| --- | --- |
| 테스트의 비밀번호 문자열(`operator1234`, `opPass1234`, `changed5678`) | Testcontainers DB와 mock에만 쓰는 더미 값 |
| 운영자 비밀번호 규칙(8~64자 영문+숫자)을 검사하지 않음 | 시그니처의 "만들지 않는 것"으로 정했다. 값을 정하는 사람이 운영자 본인이고, 로그인 시도 제한(#3)이 대입을 늦춘다. 다시 볼 조건: 운영자를 여러 명 두게 되면(M4 다시 여는 조건) |
| 운영자 비밀번호가 앱이 떠 있는 동안 필드(`password`)에 남음 | 시작 때 한 번 쓰고 끝나지만 빈이 살아 있어 메모리에 남는다. 같은 값이 이미 Spring `Environment`에도 있어 필드를 비워도 달라지지 않는다 |
| `compose.yml`이 운영자 비밀번호를 컨테이너 환경변수로 넘김(`docker inspect`로 보임) | `DB_PASSWORD`와 같은 방식. 호스트 접근 권한이 있으면 `.env`도 읽을 수 있다 |
