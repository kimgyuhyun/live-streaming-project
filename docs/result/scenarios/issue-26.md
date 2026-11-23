# 이슈 #26 — MediaMTX API는 Spring만 부를 수 있다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/26
> PRD: `docs/result/prd/channel.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

> 게이트 처리: 자율 모드로 진행했다. 시그니처·시나리오는 PRD·#10 보안 점검·이슈 본문에 정해진 방식(API 호출도 Spring 키 확인 엔드포인트가 판단, Spring은 내부 비밀번호를 Basic 인증으로 싣는다)을 그대로 따르고, 구현자가 승인된 것으로 진행했다.

---

## 시그니처

### 관련 결정

- **#10 보안 점검 🟡 1 → 사용자 결정(2026-11-21)** — MediaMTX API(내부 :9997)가 인증 없이 도커 내부망 전체에 열려 있다(`authHTTPExclude`에 `api`). 같은 망의 다른 컨테이너(nginx 등)가 뚫리면 연결 목록(`query`에 스트림 키)을 읽고 송출을 끊을 수 있다. 막는 방식은 "API 호출도 Spring 키 확인 엔드포인트를 거치게 하고 Spring이 내부 비밀번호를 싣는다". 네트워크 분리는 nginx가 HLS(:8888) 때문에 mediamtx와 같은 망에 있어야 해서 쓸 수 없다.
- **채널 PRD "키 확인 연동"** — 지금은 "Spring은 `action: publish`만 판단하고, 시청(`read`)·API 호출은 인증 대상에서 뺀다". 이 이슈가 API 호출 부분을 바꾼다(이슈 본문). PRD 표의 "키 확인 연동"·"MediaMTX API" 두 줄을 고친다.
- **채널 PRD "키 확인 엔드포인트 위치"** — `/internal/mediamtx/auth` 하나. nginx는 넘기지 않는다. API 확인도 같은 엔드포인트로 온다(MediaMTX `authHTTPAddress`는 하나뿐이다).
- **채널 PRD C1 (일정 시간 비교)** — 키 비교와 같이 비밀번호도 `MessageDigest.isEqual`로 비교한다.
- **채널 PRD 작은 결정(패키지)** — 키 확인·MediaMTX 호출은 `com.livestream.broadcast`.
- **확정 사양 (로그)** — 스트림 키는 로그·API 응답에 나오지 않는다. 내부 비밀번호도 같은 대우(AC 6).

### MediaMTX가 보내는 API 확인 요청 (1.21, `authMethod: http`)

API 요청의 Basic 인증 값이 `user`·`password`로 실려 온다. 자격 없이 부르면 둘 다 빈 문자열이다. `path`는 빈 문자열.

```json
{"ip":"172.18.0.7","user":"spring","password":"…","token":"","action":"api","path":"","protocol":"","id":null,"query":""}
```

MediaMTX는 Spring이 2xx가 아니면 그 API 요청에 401을 돌려준다.

### 타입

```java
// ── com.livestream.broadcast ─────────────────────────────

// user·password를 받는다(API 확인용). toString은 password를 싣지 않는다(AC 6) —
// 레코드 기본 toString은 모든 필드를 찍어 예외 메시지·로그로 새기 쉽다
public record MediaMtxAuthRequest(String action, String path, String query, String user, String password) {
    @Override
    public String toString();
}

// MediaMTX API 호출 확인(action: api). 내부 사용자 이름은 고정값, 비밀번호는 환경변수
@Service
public class MediaMtxApiAuthService {
    static final String INTERNAL_USER = "spring";

    MediaMtxApiAuthService(@Value("${app.mediamtx.api-password:}") String internalPassword);

    // action이 api이고, user가 INTERNAL_USER이고, password가 내부 비밀번호와 같을 때만 true.
    // 내부 비밀번호가 비었거나(설정 안 됨) 공백뿐이면 늘 false(빈 비밀번호끼리 맞는 일이 없게)
    public boolean canCallApi(MediaMtxAuthRequest request);
}

// 기존 클래스. 생성자에 내부 비밀번호 추가 — 모든 요청에 Basic 인증(INTERNAL_USER, 내부 비밀번호)을 싣는다
class MediaMtxClient implements PublisherDisconnector {
    MediaMtxClient(RestClient.Builder builder,
                   @Value("${app.mediamtx.api-url}") String apiUrl,
                   @Value("${app.mediamtx.api-password:}") String apiPassword);
}

// 기존 컨트롤러. action이 api면 MediaMtxApiAuthService, 그 밖은 지금처럼 PublishAuthService
class PublishAuthController {
    PublishAuthController(PublishAuthService publishAuthService, MediaMtxApiAuthService apiAuthService);
    ResponseEntity<Void> authorize(@RequestBody MediaMtxAuthRequest request);  // 200 / 401, 본문 없음
}
```

`PublishAuthService.canPublish`는 그대로다(action이 `publish`가 아니면 false — `api`도 false).

### 설정·인프라

| 파일 | 바꾸는 것 |
| --- | --- |
| `infra/mediamtx/mediamtx.yml` | `authHTTPExclude`에서 `action: api`를 뺀다. `read`·`metrics`·`pprof`·`playback`은 그대로 |
| `backend/src/main/resources/application.yml` | `app.mediamtx.api-password: ${MEDIAMTX_API_PASSWORD:}` |
| `backend/src/test/resources/config/application.yml` | `app.mediamtx.api-password: test-internal-password`(모든 테스트에 같은 값 — 컨텍스트 캐시 유지) |
| `compose.yml` | `x-api` 환경변수에 `MEDIAMTX_API_PASSWORD: ${MEDIAMTX_API_PASSWORD:?…}`(DB 비밀번호와 같은 방식 — 빠지면 compose가 멈춘다). mediamtx에는 넣지 않는다(판단은 Spring이 한다) |
| `.env.example` | `MEDIAMTX_API_PASSWORD=change-me` |
| `docs/result/prd/channel.md` | "키 확인 연동"·"MediaMTX API" 두 줄 |

### 에러 케이스

| 상황 | 응답 |
| --- | --- |
| `action: api`, user `spring`, password = 내부 비밀번호 | 200, 본문 없음 |
| `action: api`, password 다름·빈 값·null, user 다름 | 401, 본문 없음 |
| 내부 비밀번호가 설정되지 않음(빈 값·공백) | 어떤 `api` 요청이든 401 |
| `action: publish`에 내부 사용자·비밀번호를 실음 | 키 확인 그대로(내부 자격은 송출 허가가 아니다) |

### 판단이 갈린 곳

1. **사용자 이름은 고정값 `spring`.** 이슈 본문은 "환경변수의 내부 비밀번호"만 말한다. 사용자 이름까지 환경변수로 두면 설정 항목만 는다. AC 1이 "그 사용자·비밀번호"라 사용자 이름도 맞아야 통과한다.
2. **분기는 컨트롤러에서 action으로.** 엔드포인트가 하나라(`authHTTPAddress`는 하나뿐) 어느 판단에 넘길지 고르는 한 줄을 컨트롤러에 둔다. 두 서비스는 각자 자기 action만 true로 판단한다(설정이 빠져도 서로를 열어 주지 않는다).
3. **내부 비밀번호가 없으면 거부.** 빈 비밀번호와 빈 요청이 같다고 통과시키면 설정을 빼먹은 서버의 API가 자격 없이 열린다.
4. **compose는 `:?`로 필수.** 빠진 채로 뜨면 재발급 끊기·1분 대조가 모두 401로 조용히 실패한다(경고 로그만). 뜨기 전에 멈추는 쪽이 낫다.

### 이 이슈에서 만들지 않는 것

- MediaMTX `authInternalUsers`(MediaMTX 설정 파일에 비밀번호를 두는 방식) — 결정된 방식(Spring 판단)과 다르다.
- 네트워크 분리 — 위 결정에서 뺐다.
- (Green에서 바꿈) `MediaMtxAuthRequest.toString`은 `password`와 함께 `query`(스트림 키)도 싣지 않는다. 확정 사양 "스트림 키는 로그에 나오지 않는다"와 같은 이유이고 같은 줄이라 함께 뺐다. 테스트는 AC 6의 비밀번호만 박는다.

---

## 테스트 시나리오

### 정상

- [x] [정상] `MediaMtxApiAuthService.canCallApi` — should allow when the action is api and the user and password are the internal ones
- [x] [정상] `PublishAuthIntegration` — should return 200 for an api check without Origin or login when the user and password are the internal ones
- [x] [정상] `MediaMtxClient.disconnect` — should send the internal user and password as basic auth when listing and kicking connections
- [x] [정상] `MediaMtxClient.findAvailablePaths` — should send the internal user and password as basic auth when listing paths
- [x] [정상] `StreamKeyReissueIntegration` — should send the internal password as basic auth to the MediaMTX API when a streamer reissues the key
- [x] [정상] `BroadcastIntegration` — should send the internal password as basic auth to the MediaMTX API when reconciling
- [x] [정상] `mediamtx.yml` — should not exclude the api action from the http auth
- [x] [정상] `PublishAuthIntegration` — should answer an api check with an empty body whether it is allowed or not

### 경계

- [x] [경계] `MediaMtxApiAuthService.canCallApi` — should deny when the password is the internal password followed by one more character
- [x] [경계] `MediaMtxApiAuthService.canCallApi` — should deny even an empty password when no internal password is set

### 예외

- [x] [예외] `MediaMtxApiAuthService.canCallApi` — should deny when the password differs from the internal password
- [x] [예외] `MediaMtxApiAuthService.canCallApi` — should deny when the password is empty
- [x] [예외] `MediaMtxApiAuthService.canCallApi` — should deny when the user is not the internal user
- [x] [예외] `MediaMtxApiAuthService.canCallApi` — should deny a check whose action is not api even with the internal user and password
- [x] [예외] `PublishAuthIntegration` — should return 401 for an api check with a wrong password
- [x] [예외] `PublishAuthIntegration` — should return 401 for an api check without a user or password
- [x] [예외] `MediaMtxAuthRequest.toString` — should not include the password when the request is turned into text
- [x] [예외] `PublishAuthIntegration` — should not write the internal password to the log while handling an api check

### 수동 확인 (로컬 compose — 시나리오 수에 넣지 않는다)

AC 4·5. Green 뒤에 확인하고 결과를 아래 "확인 결과"와 PR에 남긴다. 비밀번호·키 값은 적지 않는다.

- AC 4: 내부망의 다른 컨테이너(nginx)에서 자격 없이 `wget http://mediamtx:9997/v3/rtmpconns/list` → 401. 틀린 비밀번호도 401. 덧붙여 Spring 자격으로는 200
- AC 5: 송출 중 키 재발급 → 송출이 끊긴다. 대조가 돈다(api 로그에 대조 실패 경고가 없고, 송출 중 방송이 진행 중으로 남는다)

**확인 결과 (2026-11-23, Green 뒤, HEAD a16804c):** oracle·redis는 이미 떠 있던 것을 그대로 두고, 멈춰 있던 api-1·api-2·mediamtx·nginx만 `docker compose up --build -d`로 띄웠다. 내부 비밀번호는 셸 환경변수로 임의의 난수(32자)를 넣었다(`.env`는 고치지 않았다). 마이그레이션은 V1~V6이 이미 적용돼 있어 새로 적용된 것이 없다. #10 수동 확인과 같이 테스트 계정 둘을 API로 가입시키고 하나만 SQL로 `OPERATOR`로 바꿔(로컬 개발 DB) 신청 → 승인 → 키 발급으로 채널 61과 키(43자)를 받았다. 키·비밀번호는 스크래치 파일에만 두고 문서에 옮기지 않았다.

| AC | 한 것 | 관찰 |
| --- | --- | --- |
| 4 | nginx 컨테이너(같은 내부망)에서 `wget http://mediamtx:9997/v3/rtmpconns/list` 자격 없이 | `HTTP/1.1 401 Unauthorized` ✅ |
| 4 | 같은 요청에 `spring:wrong-password` | 401 ✅ |
| 4 | 덧붙여: 자격 없이 `/v3/paths/list`, `POST /v3/rtmpconns/kick/x` | 둘 다 401 ✅ |
| 4 | 덧붙여: Spring과 같은 자격(`spring:{내부 비밀번호}`) | 200 — 자격이 맞으면 열린다 ✅ |
| 5 | 맞는 키로 ffmpeg 송출 | MediaMTX `is publishing to path 'live/61'`, HLS 200, 채널 `live: true` ✅ |
| 5 | 1분 대조: 송출 중에 DB의 진행 중 방송에 종료 시각을 직접 찍어(훅 없이 어긋나게) 둠 | 06:52:00 `live: false` → 13초 뒤 `live: true`. 대조가 인증된 API(`/v3/paths/list`)로 송출 중인 경로를 읽어 방송을 다시 시작했다 ✅ |
| 5 | 송출 중 `POST /api/studio/stream-key`(재발급) | 200, 새 키 ≠ 이전 키. 같은 초에 MediaMTX `[conn …:49472] closed: terminated`, ffmpeg 종료, HLS 404 ✅ |
| 5 | 이전 키로 다시 송출 | ffmpeg `Server error: authentication failed`, MediaMTX `failed to authenticate: server replied with code 401`, 채널 `live: false` ✅ |
| 5 | api-1·api-2 로그 | `방송 상태 대조 실패` 0줄, `송출 연결을 끊지 못했다` 0줄 ✅ |
| 6 | api-1·api-2·mediamtx·nginx 로그 전체에서 내부 비밀번호 문자열 | 0줄. `key=` 0줄, `authorization` 0줄 ✅ |
| 덧붙여 | `docker compose port mediamtx 9997` | `no port 9997/tcp` — 호스트에 열려 있지 않다 ✅ |

**Red 기록 (2026-11-23):** 시나리오 18개(매개변수 포함 테스트 27건)가 모두 실패했다 — `canCallApi` stub의 `UnsupportedOperationException`(단위 15건, api 확인 통합 6건), 단언 실패(toString 1, Basic 헤더 2, 설정 1, 재발급·대조 통합 2). 컨트롤러는 Red에서 `action: api`를 stub으로 넘기게만 바꿨다 — 그러지 않으면 api 확인 401 테스트가 기존 동작(`canPublish`가 api를 거부)으로 통과해 버린다. 전체 실행 때 `NginxChannelCacheTest`의 1초 만료 테스트 1건이 시각 경계로 한 번 실패했고, 단독 재실행 3번 중 2번(--rerun-tasks) 통과, main에서도 통과 — 이 이슈와 무관한 시각 의존 테스트다.

**Green 기록:** 전체 백엔드 377건·프론트 28건 통과. 커버리지 명령은 설정에 없다(측정하지 않음).

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 설정된 내부 비밀번호, `action: api`와 그 사용자·비밀번호 → 200 | `[정상] MediaMtxApiAuthService.canCallApi — should allow when the action is api and the user and password are the internal ones`<br>`[정상] PublishAuthIntegration — should return 200 for an api check without Origin or login when the user and password are the internal ones`<br>`[예외] MediaMtxApiAuthService.canCallApi — should deny a check whose action is not api even with the internal user and password` |
| 2. 비밀번호가 다름·빈 값·내부 비밀번호 미설정 → 401 | `[예외] MediaMtxApiAuthService.canCallApi — should deny when the password differs from the internal password`<br>`[경계] MediaMtxApiAuthService.canCallApi — should deny when the password is the internal password followed by one more character`<br>`[예외] MediaMtxApiAuthService.canCallApi — should deny when the password is empty`<br>`[경계] MediaMtxApiAuthService.canCallApi — should deny even an empty password when no internal password is set`<br>`[예외] MediaMtxApiAuthService.canCallApi — should deny when the user is not the internal user`<br>`[예외] PublishAuthIntegration — should return 401 for an api check with a wrong password`<br>`[예외] PublishAuthIntegration — should return 401 for an api check without a user or password` |
| 3. 재발급(끊기)·1분 대조에서 Spring이 내부 비밀번호를 Basic 인증으로 싣는다 | `[정상] MediaMtxClient.disconnect — should send the internal user and password as basic auth when listing and kicking connections`<br>`[정상] MediaMtxClient.findAvailablePaths — should send the internal user and password as basic auth when listing paths`<br>`[정상] StreamKeyReissueIntegration — should send the internal password as basic auth to the MediaMTX API when a streamer reissues the key`<br>`[정상] BroadcastIntegration — should send the internal password as basic auth to the MediaMTX API when reconciling` |
| 4. 로컬 compose: 내부망 다른 컨테이너에서 자격 없이 `/v3/rtmpconns/list` → 401 | `[정상] mediamtx.yml — should not exclude the api action from the http auth` + 수동 확인 |
| 5. 로컬 compose: 송출 중 재발급·1분 대조가 그대로 동작 | 수동 확인 (자동 근거: AC 3의 통합 테스트 2개) |
| 6. 키 확인 요청 처리의 로그·응답에 내부 비밀번호가 남지 않는다 | `[예외] MediaMtxAuthRequest.toString — should not include the password when the request is turned into text`<br>`[정상] PublishAuthIntegration — should answer an api check with an empty body whether it is allowed or not`<br>`[예외] PublishAuthIntegration — should not write the internal password to the log while handling an api check` |

**AC에 없는데 넣은 시나리오**

| 시나리오 | 이유 |
| --- | --- |
| `[예외] MediaMtxApiAuthService.canCallApi — should deny when the user is not the internal user` | AC 1이 "그 사용자·비밀번호"를 말한다. 사용자 이름이 맞아야 한다는 쪽을 박는다 |
| `[예외] MediaMtxApiAuthService.canCallApi — should deny a check whose action is not api even with the internal user and password` | 내부 자격이 송출 허가로 새지 않게(엔드포인트를 같이 쓴다) |

**커버리지:** AC 6개 / 시나리오 18개 / 미커버 0개 (AC 4는 설정 테스트 + 수동 확인, AC 5는 수동 확인 + AC 3 통합 테스트)

---

## AC 검증

> `harness:ac-verifier` 출력 원문 (2026-11-23). 검증자의 표는 고치지 않는다.

## 이슈 #26 AC 검증 결과 (브랜치 feat/channel/issue-26, HEAD 588f679)

**요약: AC 6개 모두 ✅ 충족, ⚠️ 0, ❌ 0.** 남은 일은 두 가지입니다. 하나는 AC 4·5의 수동 확인 결과를 PR 본문에 옮기는 것입니다(PR이 아직 없습니다). 다른 하나는 아래의 선택 보강 시나리오입니다.

관련 테스트 8개 클래스를 직접 돌렸고 75건이 모두 통과했습니다(BUILD SUCCESSFUL, 결과 XML 타임스탬프 06:54Z). 떠 있는 compose에서는 AC 4를 따로 다시 확인했고, AC 5·6은 로그로 일부를 확인했습니다. 컨테이너는 상태만 읽었고 바꾸지 않았습니다.

| AC | 판정 | 근거 (테스트·수동 확인) | 코드 위치 | 설명 |
|---|---|---|---|---|
| 1. `action: api` + 내부 사용자·비밀번호 → 200 | ✅ | `should allow when the action is api and the user and password are the internal ones` (MediaMtxApiAuthServiceTest.java:28)<br>`should return 200 for an api check without Origin or login when ...` (PublishAuthIntegrationTest.java:157)<br>직접 확인: nginx 컨테이너에서 맞는 자격(api-1 환경변수 값, 32자)으로 `/v3/rtmpconns/list` → `HTTP/1.1 200 OK` | MediaMtxApiAuthService.java:25-31, PublishAuthController.java:22-28 | 단위 테스트와 HTTP 통합 테스트(MediaMTX 1.21 요청 JSON 모양) 둘 다 있습니다. 실제 MediaMTX에서 Spring까지 가는 경로에서도 200을 확인했습니다. 사용자는 고정값 `spring`이고, 일정 시간 비교(`MessageDigest.isEqual`)를 씁니다. |
| 2. 비밀번호가 다르거나·비었거나·미설정 → 401 | ✅ | 다름: `should deny when the password differs ...` (:51), `... followed by one more character` (:34), 통합 `should return 401 for an api check with a wrong password` (PublishAuthIntegrationTest.java:163)<br>빈 값: `should deny when the password is empty` (:59, null·"" 2건), 통합 `... without a user or password` (:169)<br>미설정: `should deny even an empty password when no internal password is set` (:43, null·""·" " 3건, 요청에도 같은 빈 값을 실음)<br>덧붙인 검사: 다른 사용자(:67), api가 아닌 action(:75) | MediaMtxApiAuthService.java:17-21(빈 값·공백이면 null), :26-29 | "빈 값끼리 같으면 통과"하는 구멍을 막는 테스트가 있고, 구현도 null로 막습니다. 미설정은 단위 테스트로만 검증합니다. 컨트롤러가 false를 401로 바꾸는 부분은 다른 통합 테스트가 확인하므로 합치면 커버됩니다. compose.yml은 `:?`로 이 값을 필수로 둡니다. |
| 3. 재발급(끊기)·1분 대조에서 Basic 인증을 싣는다 | ✅ | 단위(MockRestServiceServer `header()` 매처 + `server.verify()`): `... basic auth when listing and kicking connections` (MediaMtxClientTest.java:128, list와 kick 둘 다), `... when listing paths` (:79)<br>통합(실제 Spring 컨텍스트 + 가짜 HTTP 서버가 받은 Authorization 헤더를 정확한 값으로 단언): `... when a streamer reissues the key` (StreamKeyReissueIntegrationTest.java:171), `... when reconciling` (BroadcastIntegrationTest.java:299) | MediaMtxClient.java:16-21(`defaultHeaders` → `setBasicAuth`), 호출부 StreamKeyService.issue→disconnectPublisher, BroadcastService.reconcile→findAvailablePaths | 기본 헤더로 넣었으므로 list·kick·paths 세 호출 모두에 실립니다. 재발급의 끊기 호출은 동기이고, 헤더가 안 실리면 통합 테스트가 null로 실패합니다. 목의 반환값을 그대로 단언하는 테스트가 아니라 실제로 받은 헤더를 봅니다. |
| 4. 내부망의 다른 컨테이너에서 자격 없이 `/v3/rtmpconns/list` → 401 | ✅ | 자동: `should not exclude the api action from the http auth` (MediaMtxConfigTest.java:28, 실제 infra/mediamtx/mediamtx.yml을 파싱)<br>수동 기록: issue-26.md "확인 결과" 표의 AC 4 행들<br>직접 다시 확인: nginx 컨테이너에서 자격 없이 401, `spring:wrong-password`로 401, `Basic spring:`(빈 비밀번호)로 401, 맞는 자격으로 200. 호스트 포트는 `no port 9997/tcp` | infra/mediamtx/mediamtx.yml:33-37(`action: api` 제거). 떠 있는 컨테이너의 /mediamtx.yml도 같은 내용입니다(저장소 파일 바인드 마운트) | 맞는 자격으로 200이 나왔으므로, 401은 Spring이 응답하지 않아서 생긴 전면 거부가 아니라 자격 판단의 결과입니다. **"PR에 남긴다"는 아직 하지 않았습니다**(PR 없음). create-pr 때 표를 옮겨야 합니다. |
| 5. 송출 중 재발급·1분 대조가 그대로 동작한다 | ✅ | 수동 기록: issue-26.md "확인 결과"의 AC 5 행들(ffmpeg 송출, 대조로 live 복구, 재발급 때 `closed: terminated`, 이전 키는 401)<br>자동: AC 3의 통합 테스트 2개<br>직접 확인(일부): api-1·api-2는 06:50:00에 떴고 확인 시각은 06:54:38입니다(대조가 인스턴스마다 3~4회 돌았을 시간). 그동안 `방송 상태 대조 실패` 0줄, `송출 연결을 끊지 못했다` 0줄 | StreamKeyService.java:39-45, 64-70; BroadcastReconciler.java:28-34 | 송출·재발급 시나리오 자체는 다시 돌리지 않았습니다. 테스트 채널의 키를 바꾸게 되고 ffmpeg 송출이 필요하기 때문입니다. 따라서 이 부분은 기록과 자동 테스트에 기대며, 대조 쪽만 실제로 확인했습니다. AC 4와 마찬가지로 PR에 결과를 옮기는 일이 남았습니다. |
| 6. 키 확인 요청 처리의 로그·응답에 내부 비밀번호가 없다 | ✅ | `should not include the password when the request is turned into text` (MediaMtxAuthRequestTest.java:17)<br>`should answer an api check with an empty body whether it is allowed or not` (PublishAuthIntegrationTest.java:176, 허용·거부 2건)<br>`should not write the internal password to the log while handling an api check` (:185, OutputCaptureExtension으로 허용 1건과 거부 1건을 처리하는 동안의 출력 전체)<br>직접 확인: api-1·api-2·mediamtx·nginx 로그 전체에서 실제 비밀번호 문자열 0줄, `authorization` 0줄 | MediaMtxAuthRequest.java:7-11(toString에서 password·query 제외), PublishAuthController.java:28(본문 없는 `ResponseEntity`) | 응답 본문은 빈 값으로 단언합니다. 로그 쪽은 "남지 않는다"는 음성 단언이어서, 지금은 원래 아무것도 로그를 남기지 않기 때문에 통과하는 면이 있습니다. 다만 toString 테스트가 예외·로그로 새는 주된 경로를 따로 막고 있어 충분하다고 봅니다. |

### 추가 시나리오 제안 (선택, 판정에는 영향 없음)

AC 2에 숫자·시간 경계는 없습니다. 비밀번호 비교의 경계만 조금 더 조일 수 있습니다.

- [ ] [경계] `MediaMtxApiAuthService.canCallApi` — should deny when the password is the internal password without its last character
- [ ] [경계] `MediaMtxApiAuthService.canCallApi` — should deny when the password differs from the internal password only in letter case
- [ ] [예외] `PublishAuthIntegration` — should return 401 for an api check carrying the internal password with the user `admin` (사용자 불일치를 HTTP 수준에서도 확인. 지금은 단위 테스트만 있음)

미설정 비밀번호를 HTTP 수준에서 401로 확인하는 테스트는 컨텍스트를 하나 더 띄워야 합니다(CLAUDE.md: 설정을 바꿔 컨텍스트를 새로 띄우지 않는다). 그래서 지금의 단위 테스트와 compose `:?` 조합이 맞다고 보고 제안하지 않습니다.

### 가짜 테스트

찾지 못했습니다. 18개 시나리오 모두 단언이 있습니다. 예외 단언은 쓰지 않고, 클라이언트 테스트는 `server.verify()`로 기대한 요청을 확인합니다. 통합 테스트는 가짜 서버가 실제로 받은 헤더를 정확한 값으로 비교하므로, 목의 반환값을 그대로 단언하는 테스트도 없습니다. 굳이 꼽자면 로그 음성 단언(PublishAuthIntegrationTest.java:185)이 약한 편이지만, 위에 적은 이유로 가짜 테스트로 보지는 않습니다.

### 실행한 명령 목록

1. `cat .claude/harness.json && gh issue view 26 && git diff main...feat/channel/issue-26 --stat` → 설정(scenariosDir=docs/result/scenarios), AC 6개, 변경 파일 19개
2. `git diff main...feat/channel/issue-26 -- . ':!docs/.../issue-26.md' && cat docs/result/scenarios/issue-26.md` → 전체 diff, 시그니처·시나리오 18개·AC 대조·수동 확인 기록
3. `cat -n` MediaMtxClient/PublishAuthController/MediaMtxApiAuthService/MediaMtxAuthRequest + `grep -rn "disconnect|findAvailablePaths|log.warn..."` → 구현과 호출부, 로그 문장 2개(둘 다 예외 클래스 이름만 기록)
4. `sed -n` StreamKeyService.java 25-75, BroadcastReconciler.java 20-40 → 재발급은 동기로 끊기를 부르고, 대조는 1분 주기
5. `grep -rn "internal" backend/src/main/java` → `/internal/**` permitAll(SecurityConfig.java:54), Origin 예외 필터
6. `./gradlew test --tests` 8개 클래스(백그라운드) → BUILD SUCCESSFUL. 결과 XML: canCallApi 15, toString 1, MediaMtxClient 4+4, mediamtx.yml 1, PublishAuthIntegration 12, canPublish 24, BroadcastIntegration 11, StreamKeyReissueIntegration 3, 실패 0
7. `docker compose ps`, `docker inspect` 마운트 → 6개 서비스가 Up, mediamtx.yml은 저장소 파일을 바인드 마운트
8. `docker compose exec -T mediamtx grep -A6 authHTTPExclude /mediamtx.yml`와 저장소 파일 grep → 둘 다 read·metrics·pprof·playback만 있고 api는 없음
9. nginx 컨테이너에서 `wget -S` 세 번(자격 없음, wrong-password, 빈 비밀번호 헤더) → 모두 401. `docker compose port mediamtx 9997` → no port
10. api-1·api-2의 `printenv MEDIAMTX_API_PASSWORD`(값은 출력하지 않고 길이와 일치만 확인: 32자, 같음) → nginx에서 맞는 자격으로 wget → 200. compose 로그에서 비밀번호 0, `방송 상태 대조 실패` 0, `송출 연결을 끊지 못했다` 0, `authorization` 0
11. `docker compose logs mediamtx | grep auth|401` 및 api 시작 시각 → API 401 경고는 nginx(172.27.0.7)의 확인 요청뿐. api는 06:50:00에 떴고 확인 시각은 06:54:38
12. `grep -n` DisplayName 위치 → 위 표의 파일:줄

### 관련 파일
- C:\live-streaming-project\backend\src\main\java\com\livestream\broadcast\MediaMtxApiAuthService.java
- C:\live-streaming-project\backend\src\main\java\com\livestream\broadcast\MediaMtxClient.java
- C:\live-streaming-project\backend\src\main\java\com\livestream\broadcast\PublishAuthController.java
- C:\live-streaming-project\backend\src\main\java\com\livestream\broadcast\MediaMtxAuthRequest.java
- C:\live-streaming-project\infra\mediamtx\mediamtx.yml
- C:\live-streaming-project\docs\result\scenarios\issue-26.md
- 테스트: C:\live-streaming-project\backend\src\test\java\com\livestream\broadcast\{MediaMtxApiAuthServiceTest, MediaMtxAuthRequestTest, MediaMtxClientTest, MediaMtxConfigTest, PublishAuthIntegrationTest, BroadcastIntegrationTest}.java, C:\live-streaming-project\backend\src\test\java\com\livestream\channel\StreamKeyReissueIntegrationTest.java

### 구현자 이견

없음. 추가 시나리오 제안 3개는 선택(판정에 영향 없음)이라 이번 이슈에 넣지 않았다 — AC 갭을 테스트 추가로 메우지 않는다는 진행 규칙과도 맞는다.

---

## 리팩터

> `/harness:tdd-refactor 26` (2026-11-23). 기준선: Green 뒤 전체 백엔드 377건·프론트 28건 통과.

대상(이번 이슈에서 바뀐 `backend/src/main`): `MediaMtxApiAuthService`, `MediaMtxAuthRequest`, `MediaMtxClient`, `PublishAuthController`.

| 기준 | 본 것 | 판단 |
| --- | --- | --- |
| 중복 | `"api"` 리터럴이 컨트롤러 분기와 `canCallApi` 두 곳 | 두 번째라 묶지 않는다(세 번째부터). 둘은 하는 일이 다르다(고르기 / 스스로 지키기) |
| 중복 | 내부 사용자 이름 | 이미 `MediaMtxApiAuthService.INTERNAL_USER` 한 곳에서 클라이언트·확인이 같이 쓴다 |
| 복잡도 | `canCallApi`의 조건 4개 | 한 `if`에 나란히 있고 중첩이 없다. 그대로 |
| 컨벤션 | 패키지·계층(컨트롤러는 HTTP 입출력과 action 분기 한 줄, 규칙은 서비스) | CLAUDE.md·PRD 패키지 결정과 같다 |

**고친 것 없음.** 바꿀 근거가 있는 항목이 없어 코드를 건드리지 않았다(리팩터 커밋 없음).

---

## 보안 점검

> `/harness:security-review 26` (2026-11-23). 자율 모드 — 🔴는 고치고 🟡는 보고만 한다.

**수집:** `compileJava compileTestJava` 통과, `npx tsc -b` 통과, `npm audit` 취약점 0개. 비밀값 패턴·알려진 접두사 검색 0건. `.env`는 커밋 대상·이 브랜치 커밋 어디에도 없다(이 브랜치가 건드린 것은 `.env.example`뿐).

| 분류 | 위치 | 무엇 | 판단 | 처리 |
| --- | --- | --- | --- | --- |
| 🟡 | `.env.example`, `compose.yml` | `MEDIAMTX_API_PASSWORD=change-me`를 그대로 복사해 쓰면 내부 비밀번호가 누구나 아는 값이 된다. compose의 `:?`는 빈 값만 막는다 | DB 비밀번호(`change-me`)와 같은 관례이고, 서버 배포 때는 `.env`를 따로 만든다. 막으려면 Spring이 시작할 때 알려진 자리표시자 값을 거부하게 할 수 있다 | 보고만(배포 단계에서 다룬다) |
| ⚪ | `infra/mediamtx/mediamtx.yml` 훅(`/internal/mediamtx/available·unavailable`) | 내부망의 다른 컨테이너가 방송 시작·종료 알림을 흉내 낼 수 있다 | 이 이슈 이전부터의 PRD C2 결정(훅은 인증 없이 내부망에서만). 틀어져도 1분 대조가 MediaMTX의 실제 상태(이제 인증된 API로 읽는다)로 되돌린다. 키·송출에는 닿지 않는다 | 그대로 |
| ⚪ | `/internal/mediamtx/auth` | 내부망 컨테이너가 이 엔드포인트로 내부 비밀번호를 대입해 볼 수 있다 | 비밀번호는 긴 난수(`.env.example`이 `openssl rand -base64 32`를 안내), nginx가 바깥 요청을 404로 막는다(#10). 스트림 키(256비트)와 같은 수준 | 그대로 |
| ⚪ | `MediaMtxApiAuthService.canCallApi` | 비교 시간 | `MessageDigest.isEqual` — 걸리는 시간이 설정된 비밀번호 길이에만 따른다 | 그대로 |
| ⚪ | Basic 인증이 평문 HTTP | Spring → MediaMTX(:9997)는 도커 브리지 내부 통신이다 | 같은 망의 다른 컨테이너가 남의 연결을 엿볼 수 없다(브리지는 컨테이너별 veth). 호스트 포트로 열지 않는다(`no port 9997/tcp`) | 그대로 |
| ⚪ | 로그·응답 | 내부 비밀번호·`Authorization`·`key=` | compose 로그 전체 0줄(수동 확인), 응답 본문 없음·`toString`이 `password`·`query`를 싣지 않음(테스트) | 그대로 |

**🔴 없음.** 고친 것 없음. 남은 것은 위 🟡 1개(사용자 결정 대기)와 ⚪뿐이다.
