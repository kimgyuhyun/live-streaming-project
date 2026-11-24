# live-streaming-project

OBS로 송출한 방송을 웹에서 실시간으로 보고 채팅하는 1인 라이브 플랫폼. 백엔드 취업용 포트폴리오, 기간 3주.

## 문서

| 무엇 | 어디 |
| --- | --- |
| 기획 (기능 범위, 기술 스택, 일정, 검증 결과 요약) | `docs/plan.html` |
| 로컬 인프라 검증 기록 (측정값·방법) | `docs/spikes/infra-validation.md` |
| 확정 사양 + 용어 정의 | `docs/result/spec-fixed.md` |
| 도메인별 PRD (확정된 ADR 포함) | `docs/result/prd/{도메인}.md` |
| 3안 비교 원본 | `docs/result/adr/{도메인}.md` |
| 이슈 목록 · 순서 | `docs/result/issues.md` · `docs/result/build-order.md` |
| 이슈별 시그니처·시나리오 | `docs/result/scenarios/issue-{N}.md` |

구현 전에 해당 도메인의 PRD 기술 결정을 먼저 읽는다. 이미 내려진 결정과 어긋나는 코드는 나중에 뜯게 된다.

## 일하는 방식

- **로컬에서 먼저 완성한다.** CI/CD와 서버 배포는 로컬에서 어느 정도 완성한 뒤에 붙인다. 사용자가 하자고 하기 전에는 워크플로 파일(`.github/workflows`)을 만들지 않는다.
- **커밋·푸시는 사용자가 요청할 때만 한다.** 푸시가 원격에서 무언가(CI, 배포)를 실행시키면 푸시 전에 그 사실을 말한다.
- **상태는 확인하고 말한다.** 컨테이너·브랜치·파일 상태는 사용자가 터미널에서 직접 바꾸므로, 말하기 전에 `docker compose ps`·`git status` 등으로 확인한다.
- **구조·설정 작업**(골격, 설정, 문서)은 작업 브랜치를 `main`에 `git merge --no-ff`로 바로 합친다. PR을 만들지 않는다.
- **이슈 구현**은 아래 TDD 이슈 사이클을 따른다.

## TDD 하네스

설정은 `.claude/harness.json`. 플러그인은 `C:\tdd-harness`(`/harness:*`).

- 승인 게이트는 `/harness:tdd-loop`에서 살아 있다. 각 스킬 안의 게이트가 그대로 동작한다.
- `/harness:tdd-auto-loop`은 **스킬을 부르는 것이 곧 승인이다.** 브랜치 생성·커밋·push·PR 생성·이슈 코멘트가 승인 범위 안이다.
- 자율 루프에서는 단계마다 커밋한다. 마지막에 한 번만 커밋하지 않는다.
- 승인 범위 밖(강제 푸시, 삭제, 이력 조작, 의존성 대량 변경, 외부 서비스 실호출)은 SKIP한다.

### 이슈 사이클

`/harness:test-scenarios N` → `/harness:tdd-red N` → `/harness:tdd-green N` → `harness:ac-verifier` → `/harness:tdd-refactor N` → `/harness:security-review N` → `/harness:create-pr` → merge commit(`--no-ff`) → 이슈 클로즈

- **브랜치:** `feat/{domain}/issue-{N}`. 선행 이슈가 아직 머지 전이면 `main`이 아니라 선행 이슈 브랜치에서 딴다.
- **커밋:** `type: 설명` (type은 `feat`·`fix`·`test`·`refactor`·`docs`·`chore`). 이슈 PR에서는 `docs:` → `test:` → `feat:` → `refactor:` 순서로 TDD 단계가 남는다. 본문에 `closes #N`.
- **머지:** 이슈 PR은 merge commit(`--no-ff`). squash·rebase로 머지하지 않는다 — 단계별 커밋이 사라진다.

## 명령

| 무엇 | 명령 |
| --- | --- |
| 전체 실행 | `cp .env.example .env` 후 `docker compose up --build` → http://localhost:8000 |
| 인프라만 | `docker compose up oracle redis mediamtx` |
| 백엔드 실행 | `cd backend && ./gradlew bootRun` (루트 `.env`를 읽는다) |
| 백엔드 테스트 | `cd backend && ./gradlew test` (Testcontainers가 Oracle·Redis를 띄운다. Docker 필요) |
| 테스트 클래스 하나 | `cd backend && ./gradlew test --tests "AuthServiceTest"` (`@Nested` 안쪽 테스트까지 돈다) |
| 프론트 | `cd frontend && npm run dev` / `npm run lint` / `npm run build` / `npm test`(Vitest) |

- `./gradlew`는 **JDK 21**이 필요하다. 이 PC는 `JAVA_HOME`·PATH를 일부러 잡아 두지 않았고, JDK는 IntelliJ가 받아 둔 `~/.jdks/liberica-21.0.7`이다. 명령 앞에 `JAVA_HOME="$HOME/.jdks/liberica-21.0.7"`을 붙여 실행한다. `harness.json`의 Gradle 명령은 `JAVA_HOME`이 없으면 이 경로를 쓴다. "JDK가 없다"고 결론 내거나 환경변수를 설정하라고 하지 않는다.
- `.claude/launch.json`(앱의 브라우저 미리보기로 `backend`·`frontend` 서버를 띄우는 설정)에는 **이 PC 전용 절대 경로가 두 개** 있다. `backend`의 `runtimeExecutable`(`C:/live-streaming-project/backend/gradlew.bat`)과 `env.JAVA_HOME`(`C:/Users/USER/.jdks/liberica-21.0.7`)이다. 다른 PC에서는 저장소 위치와 JDK 21 경로에 맞게 이 둘을 고친다. JSON이라 파일 안에 주석을 달 수 없어 여기 적는다.
- 컨테이너만 내릴 때는 `docker compose down`. **`-v`(볼륨 삭제)를 쓰지 않는다** — Oracle 데이터가 지워진다. 훅과 deny 규칙이 막는다.

## 백엔드 컨벤션

Spring Boot 3.5.16, Java 21, MyBatis, Flyway, Oracle Free, Redis.

### 패키지

`com.livestream.{도메인}` 아래에 계층을 둔다. 도메인은 PRD의 도메인 이름을 따른다(예: `member`, `channel`, `stream`, `chat`, `donation`).

```
com.livestream.member
├── MemberController      HTTP 입출력만
├── MemberService         규칙·트랜잭션
├── MemberMapper          MyBatis 매퍼 인터페이스 (SQL은 resources/mapper/{도메인}/*.xml)
└── Member, ...           도메인 객체·요청/응답 DTO(record)
```

첫 이슈에서 실제로 만들어 보고 어긋나면 이 절을 고친다.

### DB 마이그레이션

- Flyway, `backend/src/main/resources/db/migration/V{번호}__{설명}.sql`. 앱이 뜰 때 적용된다.
- **이미 적용된 마이그레이션 파일은 고치지 않는다.** 바꿀 게 있으면 새 번호로 추가한다.
- 마이그레이션 "명령"은 따로 없다(`harness.json`의 `migrate`가 비어 있는 이유). SQL 파일을 만드는 것이 곧 마이그레이션이다.

### 테스트

- 위치: `backend/src/test/java/` 아래 **대상과 같은 패키지**. 파일 이름 `{대상}Test.java`.
- 시나리오 문장을 **테스트 메서드의 `@DisplayName`**에 그대로 넣는다. 하네스의 대조 스크립트가 이것만 읽는다. 메서드 이름은 영어로 짧게.
- 대상 메서드마다 `@Nested` 클래스로 묶는다. 클래스에 붙인 `@DisplayName`은 테스트 이름으로 세지 않는다.

```java
class MemberServiceTest {
    @Nested
    @DisplayName("login")
    class Login {
        @Test
        @DisplayName("should reject when password does not match")
        void rejectsWrongPassword() { ... }
    }
}
```

| 계층 | 도구 |
| --- | --- |
| 서비스·도메인 규칙 | JUnit 5 + AssertJ. Spring 없이 직접 생성, 의존성은 Mockito 또는 직접 만든 가짜 |
| 매퍼(SQL) | `@MybatisTest` + Testcontainers Oracle (첫 매퍼 이슈에서 `mybatis-spring-boot-starter-test` 추가) |
| 컨트롤러 | `@WebMvcTest` + MockMvc |
| 여러 계층을 잇는 흐름·동시성 | `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)` |

- **예외 단언은 구체적인 타입으로 한다.** `assertThrows(Exception.class, …)`·`assertThrows(RuntimeException.class, …)`·`isInstanceOf(Exception.class)`는 어떤 에러든 통과한다. 이 프로젝트의 예외 클래스를 쓰고, 필요하면 메시지나 에러 코드까지 확인한다.
- 한 테스트는 한 가지만 검사한다.
- Oracle 컨테이너는 뜨는 데 오래 걸린다. 같은 설정의 `@SpringBootTest`끼리는 Spring 컨텍스트 캐시로 컨테이너를 같이 쓴다. 테스트마다 설정을 바꿔 컨텍스트를 새로 띄우지 않는다.

## 프론트 컨벤션

React + Vite + TypeScript. E2E(Playwright)는 `frontend/playwright.config.ts`가 생기면 하네스의 E2E 게이트가 켜진다.

### 테스트

Vitest + jsdom + `@testing-library/react`(#12에서 넣음). 설정은 `vite.config.ts`의 `test`.

- 위치: 대상과 **같은 폴더**에 `{대상}.test.ts`(JSX가 있으면 `.test.tsx`). 하네스는 `frontend/src/*.test.ts(x)`를 테스트로 센다.
- 대상 함수·컴포넌트마다 `describe('{이름}')`로 묶고, 시나리오 문장을 **`it('…')`에 그대로** 넣는다. 하네스의 대조 스크립트가 `it`·`test` 이름을 읽는다.
- `describe`·`it`·`expect`·`vi`는 `vitest`에서 import한다(전역 설정을 켜지 않았다). 그래서 Testing Library가 자동 정리를 하지 않는다 — 렌더하는 파일은 `afterEach(cleanup)`을 둔다.
- 화면 상태를 고르는 규칙은 순수 함수나 얇은 훅으로 뽑아 테스트한다(예: `pages/broadcastView.ts`). jsdom에는 MediaSource가 없어 `hls.js`는 `vi.mock`으로 가짜를 쓴다.
- API는 `vi.mock('../api/{파일}.ts')`로 바꾼다. 폴링은 `vi.useFakeTimers({ shouldAdvanceTime: true })` + `vi.advanceTimersByTimeAsync`로 넘긴다.

## 미디어 (MediaMTX)

- 송출 주소 `rtmp://{서버}/live/{채널ID}?key={스트림키}`. 키 검증은 MediaMTX가 Spring에 HTTP로 묻는다(`publish`만).
- 키 재발급 순서: DB에서 이전 키 무효화 → MediaMTX API로 송출 연결 끊기.
- 화질 자동 전환(ABR)은 하지 않는다. 근거와 측정값은 `docs/spikes/infra-validation.md`.
