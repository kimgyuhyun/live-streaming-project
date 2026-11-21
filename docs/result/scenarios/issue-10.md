# 이슈 #10 — 키가 맞을 때만 송출되고, 재발급하면 이전 송출이 끊긴다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/10
> PRD: `docs/result/prd/channel.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

> 게이트 처리: 사용자가 자리를 비운 동안 진행했다. 사전 승인(#10 → #11 진행 지시)에 따라 시그니처·시나리오를 구현자가 정하고 승인된 것으로 진행했다.

---

## 시그니처

### 관련 ADR

- **채널 C1(키는 해시와 앞 4자리만)** — 들어온 키를 #9와 같은 형식(SHA-256 소문자 16진수)으로 해시해 `MessageDigest.isEqual`(어디서 달라지든 같은 시간이 걸리는 비교)로 저장된 해시와 비교한다. 해시 계산은 `StreamKeyService` 한 곳에만 둔다.
- **채널 PRD 검증에서 정한 것(키 확인 연동)** — MediaMTX가 `authMethod: http`로 송출 시작 때 Spring에 묻는다. Spring은 `action: publish`만 판단한다. 시청(`read`)·API 호출(`api`)은 MediaMTX의 `authHTTPExclude`로 인증 대상에서 뺀다. 2xx면 허용, 그 밖은 거부(스파이크 1절 C·G·H).
- **채널 PRD 검증에서 정한 것(재발급 순서)** — DB에서 이전 키를 바꾼 **다음** MediaMTX API로 그 채널의 송출 연결을 끊는다. 반대로 하면 OBS가 2초 만에 이전 키로 다시 붙는다(스파이크 I·J). 끊을 연결은 `GET /v3/rtmpconns/list`에서 `state == publish`이고 `path == live/{채널ID}`인 항목, 끊기는 `POST /v3/rtmpconns/kick/{id}`(스파이크 E·F).
- **채널 PRD 검증에서 정한 것(MediaMTX API)** — Spring만 도커 내부망으로 부른다. 호스트 포트로 열지 않는다(연결 목록 응답의 `query`에 키가 그대로 있다).
- **채널 PRD 작은 결정(키 확인 엔드포인트 위치)** — `/internal/` 아래. nginx는 넘기지 않는다. MediaMTX는 내부 이름 `api`로 Spring 2대를 부른다.
- **채널 PRD 작은 결정(한 채널 동시 송출)** — MediaMTX `overridePublisher: false`.
- **채널 PRD 작은 결정(패키지)** — 키 확인·MediaMTX 호출은 `com.livestream.broadcast`, 키 저장·재발급은 `com.livestream.channel`.

### 타입

```java
// ── com.livestream.channel ─────────────────────────────

// 재발급 뒤 그 채널의 송출 연결을 끊는 쪽. 구현은 broadcast.MediaMtxClient.
// channel이 broadcast를 모르게 하려고 channel 쪽에 둔다(의존 방향: broadcast → channel)
public interface PublisherDisconnector {
    void disconnect(long channelId);
}

@Service
public class StreamKeyService {
    // 생성자에 PublisherDisconnector 하나 추가
    public StreamKeyService(ChannelMapper channelMapper, @Value("${app.obs-server}") String obsServer,
                            PublisherDisconnector publisherDisconnector);

    // #9 동작 + 저장을 마친 다음 publisherDisconnector.disconnect(채널ID).
    // @Transactional을 걸지 않는다 — 매퍼의 UPDATE가 바로 커밋되고, 그 뒤에 끊는다.
    // 끊기가 실패해도(예외) 재발급은 성공한다. 경고 로그에는 채널 id와 예외 클래스 이름만 남긴다(키·응답 본문 없음)
    public IssuedStreamKeyResponse issue(long memberId);

    // 채널이 없거나, 키가 없거나, 해시가 다르면 false. key는 null이 아니다(호출하는 쪽이 거른다)
    public boolean matches(long channelId, String key);
}

// ── com.livestream.broadcast ───────────────────────────

// MediaMTX가 보내는 JSON. 그 밖의 필드(ip, user, password, token, id, protocol, userAgent)는 받지 않는다
public record MediaMtxAuthRequest(String action, String path, String query) {}

@Service
public class PublishAuthService {
    public PublishAuthService(StreamKeyService streamKeyService);

    // action == "publish", path가 정확히 live/{숫자 1~18자리}, query에 key 값이 있고 그 채널의 키와 맞으면 true.
    // query는 & 로 나눈 key=값 목록. 키는 URL에 안전한 문자라 디코딩하지 않는다
    public boolean canPublish(MediaMtxAuthRequest request);
}

@RestController
class PublishAuthController {
    // 200 빈 본문(허용) / 401 빈 본문(거부). 응답에 키를 담지 않는다
    @PostMapping("/internal/mediamtx/auth")
    ResponseEntity<Void> authorize(@RequestBody MediaMtxAuthRequest request);
}

@Component
class MediaMtxClient implements PublisherDisconnector {
    // RestClient.Builder(스프링 부트가 주는 것), 주소는 app.mediamtx.api-url
    MediaMtxClient(RestClient.Builder builder, @Value("${app.mediamtx.api-url}") String apiUrl);

    // GET /v3/rtmpconns/list → state == publish && path == live/{channelId} 인 연결마다 POST /v3/rtmpconns/kick/{id}.
    // 없으면 아무것도 하지 않는다. API 오류는 RestClientException 그대로 던진다(StreamKeyService가 받는다)
    @Override public void disconnect(long channelId);
}
```

### 설정·인프라

| 파일 | 바꾸는 것 |
| --- | --- |
| `application.yml` | `app.mediamtx.api-url: ${MEDIAMTX_API_URL:http://localhost:9997}`. `spring.http.client.connect-timeout: 2s`, `read-timeout: 2s`(MediaMTX가 응답하지 않아도 재발급 요청이 묶이지 않게) |
| `src/test/resources/config/application.yml` (새 파일) | `app.mediamtx.api-url: http://localhost:19997`. 모든 테스트에 같은 값이라 컨텍스트 캐시는 그대로다. 재발급 통합 테스트가 이 포트에 가짜 MediaMTX(JDK `HttpServer`)를 띄운다 |
| `SecurityConfig` | `.requestMatchers("/internal/**").permitAll()` |
| `OriginValidationFilter` | `/internal/**`는 출처 검사를 하지 않는다(`shouldNotFilter`). MediaMTX 요청에는 Origin·Referer가 없다 |
| `compose.yml` | `x-api`에 `MEDIAMTX_API_URL: http://mediamtx:9997`, 네트워크 별칭 `api`(api-1·api-2가 같은 이름). mediamtx 포트는 그대로(9997을 열지 않는다) |
| `infra/mediamtx/mediamtx.yml` | `api: yes`(내부 :9997), `authMethod: http`, `authHTTPAddress: http://api:8080/internal/mediamtx/auth`, `authHTTPExclude`에 `read`·`api`·`metrics`·`pprof`·`playback`(1.21 기본값은 빈 목록), `pathDefaults.overridePublisher: false` |
| `infra/nginx/nginx.conf` | `location /internal/ { return 404; }` — 없으면 `location /`의 `try_files`가 200 + `index.html`을 준다 |

### 에러 케이스

| 상황 | 응답 |
| --- | --- |
| 키가 다름·없음·채널 없음·경로가 `live/{숫자}` 아님·`action`이 `publish` 아님 | 401, 빈 본문 (MediaMTX가 송출을 거부) |
| 깨진 JSON | 400 `INVALID_INPUT`(기존 처리기). MediaMTX는 2xx가 아니면 거부한다 |
| 재발급 뒤 MediaMTX API 실패·연결 거부·시간 초과 | 재발급은 200. 경고 로그만 |

### 판단이 갈린 곳

1. **끊기 호출은 인터페이스(`PublisherDisconnector`)를 channel에 두고 broadcast가 구현한다.** 키 확인은 broadcast → channel(`matches`)로 부르므로, 재발급이 broadcast 클래스를 직접 부르면 두 패키지가 서로를 부른다. 이벤트(`@TransactionalEventListener`)도 후보였지만 `issue()`에 트랜잭션이 없어 기본 설정으로는 불리지 않고, 순서가 코드에 덜 드러난다.
2. **해시 계산은 `StreamKeyService.matches`에 둔다.** #9의 해시 함수와 같은 곳에 있어야 형식이 어긋나지 않는다. broadcast는 MediaMTX 요청을 해석(`action`·`path`·`query`)만 한다.
3. **"저장을 마친 다음"은 진짜 DB로 확인한다.** 단위 테스트의 호출 순서(InOrder)만으로는 누가 나중에 `@Transactional`을 걸어 끊기가 커밋 전에 불리는 것을 못 잡는다. 통합 테스트의 가짜 MediaMTX가 목록 요청을 받는 순간 **다른 DB 연결로** 채널의 해시를 읽어, 그게 새 키의 해시인지 본다.
4. **API 실패는 `StreamKeyService`가 받는다.** 클라이언트는 예외를 그대로 던지고, 재발급 쪽이 "끊기 실패는 재발급 실패가 아니다"를 정한다(AC 4). 로그에 예외 메시지를 남기지 않는다 — 연결 목록 응답에는 키가 들어 있어, 파싱 오류 메시지에 본문 조각이 섞일 수 있다.
5. **`action`이 `publish`가 아니면 거부한다.** `read`·`api`는 MediaMTX 설정에서 빼므로 Spring까지 오지 않는다. 설정이 빠져 다른 action이 오면 열어 주기보다 막는 쪽이 안전하다.
6. **경로의 채널 id는 숫자 1~18자리만.** 더 길면 `long`을 넘으므로 파싱 전에 거른다(500 대신 401).
7. **nginx 차단은 설정 파일 확인 테스트 + compose에서 curl.** PRD C2 결과 "nginx가 `/internal/`을 넘기지 않는 것을 테스트(nginx 설정 확인)로 박는다"를 따른다.
8. **컨트롤러 단독 테스트(`@WebMvcTest`)는 두지 않는다.** 이 엔드포인트의 요점이 보안 필터(출처 검사·인증 예외)를 통과하는지라, 필터를 모두 거치는 통합 테스트로 본다.

### 이 이슈에서 만들지 않는 것

- "채널 주인이 스트리머가 아님" 거부 — PRD 작은 결정에 있지만 AC에 없고, 채널은 승인 트랜잭션에서만 생기며 스트리머 권한 회수가 없어(PRD 하지 않을 것) 그 상태가 생기지 않는다.
- `720/*` 내부 재송출 허용 — 720p는 하지 않는다(PRD 하지 않을 것). 지금은 `live/{숫자}`가 아니면 모두 거부.
- 방송 시작·종료 기록, 훅, 1분 대조 — #11.
- MoQ 끄기(`moq: no`, 스파이크 1절 7번) — 이 이슈의 AC와 무관하고 포트를 호스트에 열지 않는다. 보안 점검에 적는다.

---

## 테스트 시나리오

### 정상

- [x] [정상] `StreamKeyService.matches` — should return true when the key hashes to the saved hash of the channel
- [x] [정상] `StreamKeyService.issue` — should disconnect the channel's publisher after saving the new key
- [x] [정상] `PublishAuthService.canPublish` — should allow when action is publish, path is live and the channel id, and the query key matches
- [x] [정상] `MediaMtxClient.disconnect` — should kick the connection publishing to live and the channel id
- [x] [정상] `OriginValidationFilter` — should pass a POST without Origin or Referer when the path is under /internal/
- [x] [정상] `PublishAuthIntegration` — should return 200 for a publish check without Origin or login when the key of the channel matches
- [x] [정상] `StreamKeyReissueIntegration` — should ask MediaMTX to disconnect only after the new key hash is committed when a streamer reissues the key
- [x] [정상] `nginx.conf` — should answer /internal/ with 404 without passing it to Spring

### 경계

- [x] [경계] `StreamKeyService.matches` — should return false when the channel has no key
- [x] [경계] `PublishAuthService.canPublish` — should find the key among other query parameters
- [x] [경계] `PublishAuthService.canPublish` — should deny when the query is empty
- [x] [경계] `PublishAuthService.canPublish` — should deny when the channel id in the path is too long for a number
- [x] [경계] `MediaMtxClient.disconnect` — should kick nothing when no connection publishes to that path
- [x] [경계] `OriginValidationFilter` — should reject a POST without Origin when the path only starts with /internal but is not under /internal/
- [x] [경계] `PublishAuthService.canPublish` — should pass an 18-digit channel id in the path to the key check when the path is live followed by the id
- [x] [경계] `PublishAuthService.canPublish` — should deny without calling the key check when the channel id in the path has 19 digits
- [x] [경계] `PublishAuthIntegration` — should return 401 when the path is live followed by a 19-digit number larger than the long range
- [x] [경계] `PublishAuthService.canPublish` — should deny when the channel id in the path starts with zero

### 예외

- [x] [예외] `StreamKeyService.matches` — should return false when the key differs from the saved one
- [x] [예외] `StreamKeyService.matches` — should return false when the channel does not exist
- [x] [예외] `StreamKeyService.issue` — should still return the new key when disconnecting the publisher fails
- [x] [예외] `PublishAuthService.canPublish` — should deny when the key does not match
- [x] [예외] `PublishAuthService.canPublish` — should deny when the query has no key
- [x] [예외] `PublishAuthService.canPublish` — should deny when the path is not live followed by a number
- [x] [예외] `PublishAuthService.canPublish` — should deny when the action is not publish
- [x] [예외] `MediaMtxClient.disconnect` — should throw when the MediaMTX API responds with an error
- [x] [예외] `PublishAuthIntegration` — should return 401 when the key differs from the channel key
- [x] [예외] `PublishAuthIntegration` — should return 401 when the query has no key
- [x] [예외] `PublishAuthIntegration` — should return 401 when the channel does not exist
- [x] [예외] `PublishAuthIntegration` — should return 401 when the path is not live followed by a number
- [x] [예외] `StreamKeyReissueIntegration` — should return 200 with a new key when the MediaMTX API is unreachable

### 수동 확인 (로컬 compose + ffmpeg — 시나리오 수에 넣지 않는다)

AC 5·6·7. Green 뒤, AC 검증 전에 `docker compose up --build -d`로 전체를 띄워 확인하고 결과를 아래 "확인 결과"와 PR에 남긴다. 키·운영자 계정 값은 적지 않는다.

- AC 5: 맞는 키로 ffmpeg 송출 → 스튜디오 API로 재발급 → 송출이 끊긴다 → 이전 키로 다시 송출하면 거부된다
- AC 6: 송출 중인 채널로 두 번째 ffmpeg 송출 → 거부, 첫 송출은 계속된다
- AC 7: `curl http://localhost:8080/internal/mediamtx/auth` (GET·POST) → 404, api 로그에 그 요청이 없다
- 덧붙여: 9997 포트가 호스트에 열려 있지 않다(`docker compose port mediamtx 9997`)

**확인 결과 (2026-11-21, Green 뒤, HEAD 4a9614a):** `docker compose up --build -d`로 전체(oracle·redis·api-1·api-2·mediamtx·nginx)를 띄웠다. `.env`에 운영자 계정이 없어 #8 수동 확인과 같이 테스트 계정 둘을 API로 가입시키고 하나의 역할만 SQL로 `OPERATOR`로 바꿨다(로컬 개발 DB). 가입 → 스트리머 신청 → 운영자 승인 → `POST /api/studio/stream-key`를 curl(ASCII JSON)로 해서 채널 21과 키(43자)를 받았다. 키는 스크래치 파일에만 두고 화면·문서에 옮기지 않았다. 송출 원본은 `ffmpeg -re -f lavfi -i testsrc=size=640x360:rate=30 -f lavfi -i sine -c:v libx264 -g 60 -c:a aac -f flv "rtmp://localhost:1935/live/21?key={키}"`.

| AC | 한 것 | 관찰 |
| --- | --- | --- |
| 5 | 맞는 키로 송출 | MediaMTX `[conn 172.27.0.1:42572] is publishing to path 'live/21'`, `GET /hls/live/21/index.m3u8` 200 ✅ |
| 5 | 송출 중 `POST /api/studio/stream-key`(재발급) | 응답 200, 새 키 ≠ 이전 키. 같은 초(01:55:42)에 `[conn 172.27.0.1:42572] closed: terminated`, ffmpeg `Error submitting a packet to the muxer: End of file`로 종료, HLS 404 ✅ |
| 5 | 이전 키로 다시 송출 | ffmpeg `Server error: authentication failed`, MediaMTX `failed to authenticate: server replied with code 401` ✅ |
| 6 | 새 키로 첫 송출 중에 같은 채널로 두 번째 송출 | 두 번째: `[conn 172.27.0.1:37658] closed: someone is already publishing to path 'live/21'`. 첫 송출(`conn …:34926`)은 닫히지 않았고 HLS 200 그대로 ✅ |
| 7 | 호스트에서 `curl http://localhost:8080/internal/mediamtx/auth` (GET·POST), `/api/../internal/...`·`/api/%2e%2e/internal/...`(`--path-as-is`) | 4개 모두 404. nginx 접근 로그 `upstream=-`(Spring으로 넘기지 않음), api-1·api-2 로그에 `/internal/mediamtx` 0줄 ✅ |
| 덧붙여 | `docker compose port mediamtx 9997` | `no port 9997/tcp` — 호스트에 열려 있지 않다 ✅ |
| 덧붙여 | nginx 컨테이너에서 `nslookup api` | 172.27.0.5·172.27.0.6 = api-1·api-2의 IP. 별칭 `api`가 두 대를 묶는다 ✅ |
| 덧붙여 | 키가 로그에 남는지 | mediamtx·api-1·api-2 로그 전체에서 `key=` 0줄 ✅ |

**Red에서 통과했던 테스트의 변이 확인 (Green):** 3개가 구현 없이 통과했다 — 끊기 실패 시 재발급 성공 2개(Red의 `issue()`는 끊기를 부르지 않았다)와 `/internalx` 거부(기존 출처 검사 동작). Green 커밋 뒤 두 변이를 넣었다.
- `issue()`의 `disconnectPublisher(...)`(예외를 받는 감싸기)를 `publisherDisconnector.disconnect(...)` 직접 호출로 → `StreamKeyServiceTest`·`StreamKeyReissueIntegrationTest` 17개 중 끊기 실패 2개 + 커밋 순서 1개(첫 발급이 연결 거부로 500)가 실패.
- 출처 검사 예외를 `request.getRequestURI().startsWith("/internal")`로 → `OriginValidationFilterTest` 8개 중 `/internalx` 1개만 실패.
둘 다 `git checkout --`으로 되돌렸다.

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 채널 7과 그 키로 `publish`·`live/7`·`key={키}` 확인 요청 → 200 | `[정상] StreamKeyService.matches — should return true when the key hashes to the saved hash of the channel`<br>`[정상] PublishAuthService.canPublish — should allow when action is publish, path is live and the channel id, and the query key matches`<br>`[경계] PublishAuthService.canPublish — should find the key among other query parameters`<br>`[정상] OriginValidationFilter — should pass a POST without Origin or Referer when the path is under /internal/`<br>`[정상] PublishAuthIntegration — should return 200 for a publish check without Origin or login when the key of the channel matches` |
| 2. 키가 다르거나·없거나·채널이 없거나·경로가 `live/{숫자}`가 아니면 401 | `[예외] StreamKeyService.matches — should return false when the key differs from the saved one`<br>`[예외] StreamKeyService.matches — should return false when the channel does not exist`<br>`[경계] StreamKeyService.matches — should return false when the channel has no key`<br>`[예외] PublishAuthService.canPublish — should deny when the key does not match`<br>`[예외] PublishAuthService.canPublish — should deny when the query has no key`<br>`[경계] PublishAuthService.canPublish — should deny when the query is empty`<br>`[예외] PublishAuthService.canPublish — should deny when the path is not live followed by a number`<br>`[경계] PublishAuthService.canPublish — should deny when the channel id in the path is too long for a number`<br>`[예외] PublishAuthIntegration — should return 401 when the key differs from the channel key`<br>`[예외] PublishAuthIntegration — should return 401 when the query has no key`<br>`[예외] PublishAuthIntegration — should return 401 when the channel does not exist`<br>`[예외] PublishAuthIntegration — should return 401 when the path is not live followed by a number`<br>`[경계] PublishAuthService.canPublish — should pass an 18-digit channel id in the path to the key check when the path is live followed by the id` (갭 처리)<br>`[경계] PublishAuthService.canPublish — should deny without calling the key check when the channel id in the path has 19 digits` (갭 처리)<br>`[경계] PublishAuthIntegration — should return 401 when the path is live followed by a 19-digit number larger than the long range` (갭 처리) |
| 3. 재발급 저장을 마친 **다음** MediaMTX API로 `live/{채널ID}` 송출 연결을 끊는다 | `[정상] StreamKeyService.issue — should disconnect the channel's publisher after saving the new key`<br>`[정상] MediaMtxClient.disconnect — should kick the connection publishing to live and the channel id`<br>`[정상] StreamKeyReissueIntegration — should ask MediaMTX to disconnect only after the new key hash is committed when a streamer reissues the key` |
| 4. 송출 중인 연결이 없거나 MediaMTX API가 실패해도 재발급은 성공 | `[경계] MediaMtxClient.disconnect — should kick nothing when no connection publishes to that path`<br>`[예외] MediaMtxClient.disconnect — should throw when the MediaMTX API responds with an error`<br>`[예외] StreamKeyService.issue — should still return the new key when disconnecting the publisher fails`<br>`[예외] StreamKeyReissueIntegration — should return 200 with a new key when the MediaMTX API is unreachable` |
| 5. 로컬 compose: 맞는 키 송출 → 재발급 → 끊기고 이전 키로 다시 붙으면 거부 | 수동 확인 |
| 6. 로컬 compose: 같은 채널 두 번째 송출 거부, 첫 송출 유지 | 수동 확인 (`overridePublisher: false`) |
| 7. 바깥에서 `/internal/` 요청 → Spring에 닿지 않음(404) | `[정상] nginx.conf — should answer /internal/ with 404 without passing it to Spring` + 수동 확인(curl) |

**AC에 없는데 넣은 시나리오**

| 시나리오 | 이유 |
| --- | --- |
| `[예외] PublishAuthService.canPublish — should deny when the action is not publish` | PRD "Spring은 publish만 판단". 설정이 빠졌을 때 열어 주지 않게 |
| `[경계] OriginValidationFilter — should reject a POST without Origin when the path only starts with /internal but is not under /internal/` | 출처 검사 예외가 `/internal/` 밖으로 새지 않는지 |
| `[경계] PublishAuthService.canPublish — should deny when the channel id in the path starts with zero` | 보안 점검 🔴. `live/007`이 채널 7의 키로 허용되면 MediaMTX에서는 `live/7`과 다른 경로라 같은 채널 두 번째 송출이 되고, 재발급 때 `live/7`만 끊어 이전 키 송출이 살아남는다(사용자 스토리 4) |

**커버리지:** AC 7개 / 시나리오 31개(AC 검증 갭 처리 3개, 보안 점검 1개 포함) / 미커버 0개 (AC 5·6은 수동 확인만, AC 7은 설정 테스트 + 수동 확인)

---

## AC 검증

> `harness:ac-verifier` (2026-11-21, HEAD 9e1f378). 변이 최대 3번·20분·스크래치 복사본·클래스 단위 테스트로 제한해 띄웠다. 아래는 검증자 출력 원문이다(제목 수준만 한 단계 낮췄다). 구현자는 맨 아래 `### 구현자 이견`에만 쓴다.

### 이슈 #10 AC 검증 결과 (브랜치 feat/channel/issue-10, HEAD 9e1f378)

**요약: AC 7개 중 ✅ 6 / ⚠️ 1 / ❌ 0.** AC 5·6은 다시 재현하지 않았습니다. compose를 띄우지 말라는 제약 때문이고, 판정 근거는 시나리오 문서에 남은 기록입니다.

기준선으로 이슈 관련 7개 클래스를 돌렸고 53개가 모두 통과했습니다(실패 0, 오류 0). 변이는 3번 돌렸고 2개는 테스트가 잡았으며 1개는 살아남았습니다. 저장소 작업 트리는 그대로이고, `git -C C:/live-streaming-project status --short`의 출력은 비어 있었습니다.

#### AC별 판정

| AC | 판정 | 근거 테스트 (@DisplayName, 파일:줄) | 구현 (파일:줄) | 판단 이유 |
|---|---|---|---|---|
| 1. `publish`·`live/7`·`key={키}` → 200 | ✅ | `should return 200 for a publish check without Origin or login when the key of the channel matches` (PublishAuthIntegrationTest.java:58-63, 클래스 파일 기준 85-91행)<br>`should allow when action is publish, path is live and the channel id, and the query key matches` (PublishAuthServiceTest.java:75-79)<br>`should find the key among other query parameters` (:81-85)<br>`should return true when the key hashes to the saved hash of the channel` (StreamKeyServiceTest.java:180-186)<br>`should pass a POST without Origin or Referer when the path is under /internal/` (OriginValidationFilterTest, 이번 diff로 추가된 테스트) | PublishAuthController.java:20-24, PublishAuthService.java:23-33, StreamKeyService.java:53-60, SecurityConfig.java:54, OriginValidationFilter `shouldNotFilter` | 통합 테스트는 MediaMTX 1.21이 보내는 JSON 그대로, Origin과 세션 없이 보안 필터 전체를 거쳐 200을 확인합니다. 채널과 키는 실제 흐름(가입→신청→승인→발급)으로 만듭니다. 채널 id가 7이 아니라 실제로 생긴 id이지만 의도는 같습니다. |
| 2. 키가 다름·없음, 채널 없음, 경로가 `live/{숫자}` 아님 → 401 | ⚠️ | 통합 테스트(PublishAuthIntegrationTest.java): `should return 401 when the key differs from the channel key` (:93-100), `...when the query has no key` (:102-108), `...when the channel does not exist` (:110-117), `...when the path is not live followed by a number` (:119-125, `720/`만 확인)<br>단위 테스트(PublishAuthServiceTest.java): `should deny when the path is not live followed by a number` (:116-121, 경로 7종), `...the query is empty` (:88-94, null·""·`key=`), `...the channel id in the path is too long for a number` (:96-100, 20자리), `...the query has no key`, `...the key does not match`<br>StreamKeyServiceTest의 matches 관련 테스트 3개(:188-210) | PublishAuthService.java:13 (`live/(\d{1,18})`), :27-32, :36-46; StreamKeyService.java:53-60 | AC에 적힌 네 조건은 모두 통합 테스트와 단위 테스트 양쪽에서 확인됩니다. 다만 경로 숫자 길이의 경계(18자리와 19자리)를 고정한 테스트가 없습니다. 변이 2(`{1,19}`)가 살아남았습니다. 이 변이가 들어가면 `live/9999999999999999999`(19자리, Long 범위 초과) 요청에서 `Long.parseLong`이 NumberFormatException을 던집니다. GlobalExceptionHandler에는 이 예외의 처리기가 없으므로 401이 아니라 500이 나갈 것으로 봅니다. 이 500 응답은 실제로 확인하지 않았습니다. 이런 요청은 "채널이 없음"에 해당하므로 AC 2의 401 동작이 경계에서 고정되어 있지 않습니다. 현재 구현은 18자리까지만 받아서 401을 줍니다. |
| 3. 저장을 마친 **다음** `live/{채널ID}` 송출 연결 끊기 | ✅ | `should ask MediaMTX to disconnect only after the new key hash is committed when a streamer reissues the key` (StreamKeyReissueIntegrationTest.java:244-273)<br>`should disconnect the channel's publisher after saving the new key` (StreamKeyServiceTest.java:132-142, InOrder)<br>`should kick the connection publishing to live and the channel id` (MediaMtxClientTest.java:180-193, `live/70`과 `live/7` 혼동도 확인) | StreamKeyService.java:43-44 (UPDATE 다음 disconnect, `@Transactional` 없음); MediaMtxClient.java:22-31 | 통합 테스트의 가짜 MediaMTX는 목록 요청을 받는 순간 다른 DB 연결로 해시를 읽습니다. 커밋 순서를 진짜 DB로 확인하는 셈입니다. `issue()`에 `@Transactional`을 거는 변이 3을 이 테스트가 잡았습니다(목록 요청 시점에 이전 해시가 보임). |
| 4. 연결이 없거나 API가 실패해도 재발급 성공 | ✅ | `should kick nothing when no connection publishes to that path` (MediaMtxClientTest.java:195-207)<br>`should throw when the MediaMTX API responds with an error` (:209-215, `HttpServerErrorException`으로 구체적 타입 단언)<br>`should still return the new key when disconnecting the publisher fails` (StreamKeyServiceTest.java:144-153)<br>`should return 200 with a new key when the MediaMTX API is unreachable` (StreamKeyReissueIntegrationTest.java:275-289, 연결 거부 상황에서 200과 새 해시 확인) | StreamKeyService.java:64-70 (`catch (RuntimeException)`), MediaMtxClient.java:26-31 | 연결이 없는 경우도 확인됩니다. 통합 테스트 :244의 가짜 MediaMTX가 `items: []`를 돌려줘도 재발급이 200입니다. 실패 경우는 단위 테스트(예외)와 통합 테스트(연결 거부)가 둘 다 봅니다. 시간 초과 경우는 테스트가 없습니다. 다만 AC 문구는 "실패"이고, 시간 초과도 RestClientException이라 같은 catch로 처리됩니다(코드로 확인, 실행하지 않음). |
| 5. compose와 ffmpeg: 송출 → 재발급 → 끊김 → 이전 키 거부 | ✅ (기록 근거, 재현하지 않음) | 자동 테스트 없음(AC 대조표대로 수동 확인). 기록: docs/result/scenarios/issue-10.md:169, :173-175 | mediamtx.yml:21 (`api: yes`), :27-28 (`authMethod: http`, `authHTTPAddress`); compose.yml:19 (`MEDIAMTX_API_URL`), :24 (aliases `api`) | 기록에는 재발급과 같은 초에 `closed: terminated`가 찍혔고, 이전 키로 다시 붙을 때 `server replied with code 401`이 나왔습니다. 기록 시점(4a9614a)과 HEAD 사이의 diff는 이 문서 18줄뿐이라 코드는 같습니다. "PR에 남긴다"는 아직 PR 생성 전이므로 create-pr 단계에서 챙겨야 합니다. |
| 6. compose: 같은 채널 두 번째 송출 거부, 첫 송출 유지 | ✅ (기록 근거, 재현하지 않음) | 자동 테스트 없음. 기록: issue-10.md:176 | mediamtx.yml:38 (`overridePublisher: false`) | 기록에는 두 번째 연결이 `someone is already publishing to path 'live/21'`로 닫혔고, 첫 연결은 유지되어 HLS가 200 그대로였다고 되어 있습니다. PR 기록도 AC 5와 마찬가지로 아직 남지 않았습니다. |
| 7. nginx가 바깥의 `/internal/` 요청을 Spring에 넘기지 않음(404) | ✅ | `should answer /internal/ with 404 without passing it to Spring` (NginxInternalPathTest.java:25-34, 설정 텍스트에서 블록 내용이 정확히 `return 404;`인지 확인)<br>수동 기록: issue-10.md:177 (GET·POST, `/api/../internal`, `/api/%2e%2e/internal` 4개 모두 404, `upstream=-`, api 로그 0줄) | infra/nginx/nginx.conf:41-43 | 자동 테스트는 nginx를 실제로 띄우지 않는 설정 확인입니다. 실제 동작 근거는 수동 기록이 대신합니다. 참고로 슬래시 없는 `/internal`은 `location /`의 SPA로 가서 200 index.html이 나옵니다. 이 경우도 Spring에는 닿지 않으므로 AC 위반은 아닙니다. |

#### 변이 결과 (스크래치 복사본에서 3번 실행)

스크래치 복사본 위치는 `C:\Users\USER\AppData\Local\Temp\claude\1cbb40fc7d76b7b0\d434e676-b23e-4cde-8b32-82990af677dd\scratchpad\mut`이고 `git archive HEAD`로 만들었습니다.

1. **MediaMtxClient.java:27의 `"publish".equals(state) &&` 제거**: 잡힘. `MediaMtxClientTest` 3개 중 `should kick nothing when no connection publishes to that path`가 실패했습니다.
2. **PublishAuthService.java:13의 `\d{1,18}`을 `\d{1,19}`로 변경**: **살아남음.** `PublishAuthServiceTest` 19개가 모두 통과했습니다(XML 기준 tests=19, failures=0). 이 변이가 AC 2를 ⚠️로 판정한 근거입니다.
3. **StreamKeyService.issue()에 `@Transactional` 추가**: 잡힘. `StreamKeyReissueIntegrationTest` 2개 중 커밋 순서 테스트가 실패했습니다(기대한 해시는 새 키의 SHA-256, 실제로 보인 것은 이전 해시).

#### 갭을 메울 시나리오 제안 (AC 2)

- [ ] [경계] `PublishAuthService.canPublish` — should pass an 18-digit channel id in the path to the key check when the path is live followed by the id
- [ ] [경계] `PublishAuthService.canPublish` — should deny without calling the key check when the channel id in the path has 19 digits
- [ ] [경계] `PublishAuthIntegration` — should return 401 when the path is live followed by a 19-digit number larger than the long range

#### 가짜 테스트

없습니다. 모든 테스트에 단언이 있고, 예외 단언은 구체적인 타입(`HttpServerErrorException`, `BusinessException` + 에러 코드)으로 합니다. 비동기 호출은 쓰이지 않습니다. 약한 점은 두 가지입니다.

- PublishAuthIntegrationTest의 401 테스트 4개는 따로 보면, `/internal/**` permitAll이 빠져서 Spring Security가 401을 주는 경우에도 통과할 수 있습니다. 같은 클래스의 200 테스트(:85-91)가 그 경우를 잡으므로 짝으로 보면 문제는 없습니다.
- `PublishAuthServiceTest`의 허용 테스트는 목(mock)의 true를 돌려받지만, 목이 `(7L, KEY)`에만 true를 주므로 경로·키 파싱 결과를 실제로 검사합니다. 목 반환값을 그대로 단언하는 경우가 아닙니다.

#### 실행한 명령 목록

1. `cat .claude/harness.json && gh issue view 10 && git diff --stat origin/main...HEAD`: 설정 확인(scenariosDir 등), AC 7개 확인, 21개 파일 변경 확인.
2. `cat docs/result/scenarios/issue-10.md`: 시나리오 27개, AC 대조표, 수동 확인 기록 확인.
3. `git diff origin/main...HEAD -- backend/src/main compose.yml infra backend/src/test/resources`: 구현과 설정 diff 확인.
4. `cat infra/nginx/nginx.conf` 및 broadcast 단위 테스트 3개 `cat -n`: nginx 설정과 단위 테스트 본문 확인.
5. 통합 테스트 2개 `cat -n`: 통합 테스트 본문 확인.
6. `cat -n StreamKeyServiceTest.java` 및 OriginValidationFilterTest diff: 테스트 본문 확인.
7. PRD와 스파이크에서 관련 결정 grep: 재발급 순서(I·J), overridePublisher, `/internal/` 결정 확인.
8. (저장소) `./gradlew test`에 7개 클래스를 `--tests`로 지정해 실행: BUILD SUCCESSFUL(54초).
9. `git archive HEAD | tar -x -C <scratch>/mut`: 스크래치 복사본 생성.
10. 변이 1 sed 적용 후 `./gradlew test --tests "MediaMtxClientTest"`(스크래치): 3개 중 1개 실패, 잡힘.
11. 변이 1 되돌림(`git show HEAD:...`), 변이 2 적용 후 `--tests "PublishAuthServiceTest"`: BUILD SUCCESSFUL, 살아남음.
12. 테스트 결과 XML grep(스크래치): `PublishAuthService.canPublish tests="19" failures="0"`로 테스트가 실제로 돌았음을 확인.
13. 기준선 테스트 결과 XML grep(저장소): MediaMtxClient 3, nginx 1, PublishAuthIntegration 5, PublishAuthService 19, StreamKeyReissueIntegration 2, StreamKeyService find 3 / issue 8 / matches 4, OriginValidationFilter 8로 모두 failures=0, errors=0.
14. 변이 2 되돌림, 변이 3 적용 후 되돌린 파일 두 개가 저장소와 같은지 `diff -q`: 차이 없음.
15. `./gradlew test --tests "StreamKeyReissueIntegrationTest"`(스크래치): 2개 중 1개 실패, 잡힘(1분 7초).
16. 실패 메시지 grep 및 `git -C C:/live-streaming-project status --short`: 기대값과 다른 해시가 보였고, status 출력은 비어 있음.
17. GlobalExceptionHandler의 `@ExceptionHandler` grep: BusinessException, RetryLaterException, MethodArgumentNotValid, HttpMessageNotReadable만 있고 NumberFormatException 처리기는 없음.
18. `git diff --stat 4a9614a HEAD` 및 줄 번호 grep: 수동 기록 이후 바뀐 것은 issue-10.md 18줄뿐임을 확인하고, 각 근거의 줄 번호를 수집.

#### 관련 파일

- C:\live-streaming-project\backend\src\main\java\com\livestream\broadcast\PublishAuthService.java (13행이 변이가 살아남은 곳)
- C:\live-streaming-project\backend\src\test\java\com\livestream\broadcast\PublishAuthServiceTest.java (96-100행은 20자리 경로만 확인)
- C:\live-streaming-project\docs\result\scenarios\issue-10.md (169-177행이 AC 5·6·7의 수동 기록)

구현과 테스트는 고치지 않았습니다. 스크래치 복사본은 임시 폴더에 그대로 남아 있습니다.

### 구현자 이견

없음. 검증자가 짚은 사실(변이 2 생존, 19자리에서 `Long.parseLong`이 `NumberFormatException`)은 코드와 맞다.

### 갭 처리

사전 승인에 따라 메움. 검증자가 제안한 AC 2 시나리오 3개를 문구 그대로 `## 테스트 시나리오`의 경계 절과 AC 대조표 2행에 더하고 Red → Green으로 메웠다.

새 테스트 3개는 구현이 이미 18자리까지만 받아서 바로 통과했다(커밋 36f9884). 그래서 저장소에 변이를 넣어 실패를 확인하고 `git checkout --`으로 되돌렸다.
- 경로 정규식을 `{1,19}`로 → `PublishAuthServiceTest`·`PublishAuthIntegrationTest` 27개 중 19자리 단위 테스트와 19자리 통합 테스트 2개만 실패.
- 경로 정규식을 `{1,17}`로 → `PublishAuthServiceTest` 21개 중 18자리 테스트 1개만 실패.

### 2차 검증 (AC 2만)

> `harness:ac-verifier` (2026-11-21, HEAD a7f6b71). 범위를 AC 2로 좁히고 1차와 같은 제약(변이 최대 3번·20분·스크래치 복사본·클래스 단위)으로 띄웠다. 아래는 출력 원문이다(제목 수준만 두 단계 낮췄다).

#### 이슈 #10 AC 2 재검증 (2차, feat/channel/issue-10, HEAD a7f6b71)

**판정: AC 2 충족(✅).** 1차 갭은 메워졌고, AC 2에 남은 갭은 없습니다.

1차에서 살아남았던 변이(`{1,19}`)를 이번에는 새 테스트 두 개가 잡았습니다. 하한 쪽 변이(`{1,17}`)도 18자리 테스트가 잡았습니다. 변이는 3번 돌렸고 3번 모두 잡혔습니다. 걸린 시간은 약 4분(11:06–11:10)입니다.

##### AC 2 — Given 같은 요청, When 키가 다르거나·없거나·채널이 없거나·경로가 `live/{숫자}`가 아니면, Then 401이다

**근거 테스트**
- 이번에 더한 테스트 3개. 시나리오 문서 issue-10.md:143-145에 `[x]`로, AC 대조표 197행에도 들어 있습니다.
  - `should pass an 18-digit channel id in the path to the key check when the path is live followed by the id` (PublishAuthServiceTest.java:71-77). 목은 `matches(999_999_999_999_999_999L, KEY)`에만 true를 줍니다. 그래서 18자리가 정확히 그 long 값으로 넘어가는지까지 확인합니다.
  - `should deny without calling the key check when the channel id in the path has 19 digits` (PublishAuthServiceTest.java:79-86). false가 나오는지 보고, `verify(streamKeyService, never()).matches(anyLong(), anyString())`로 키 확인을 부르지 않았는지도 봅니다.
  - `should return 401 when the path is live followed by a 19-digit number larger than the long range` (PublishAuthIntegrationTest.java:127-134). 보안 필터 전체를 거쳐 실제 HTTP 상태가 401인지 확인합니다.
- 1차부터 있던 통합 테스트 (PublishAuthIntegrationTest.java)
  - 키가 다를 때 (:93-100)
  - 키가 없을 때 (:102-108)
  - 채널이 없을 때 (:110-117)
  - 경로가 `live/{숫자}`가 아닐 때 (:119-125)
- 1차부터 있던 단위 테스트 (PublishAuthServiceTest.java)
  - 경로 7종 (:102-107)
  - 빈 query·null·`key=` (:56-62)
  - 20자리 경로 (:64-68)
  - 키 없음 (:96-100), 키 불일치 (:88-94)
- StreamKeyService.matches 테스트: 키가 없는 채널(StreamKeyServiceTest.java:188-194)과 키가 다른 경우(:196-)

**구현**
- PublishAuthService.java:13: `live/(\d{1,18})`. 18자리까지는 언제나 long에 들어갑니다.
- PublishAuthService.java:27-32: 경로가 `matches()`로 전체 일치해야 하고, 키가 null이면 거부합니다.
- PublishAuthService.java:36-46: query에서 키를 꺼냅니다.
- PublishAuthController.java:22: false면 401을 줍니다.
- StreamKeyService.java:53-60: 채널이 없거나 해시가 없으면 false, 해시 비교는 상수 시간입니다.

**판단 이유**
- 숫자 길이 경계가 양쪽 모두 고정됐습니다.
  - 상한: 19자리 테스트가 단위와 통합 두 층에서 `{1,19}` 변이를 잡았습니다. 통합 테스트의 실패 원인은 `ServletException: Request processing failed: java.lang.NumberFormatException: For input string: "9999999999999999999"`였습니다. 1차가 "500이 날 것으로 본다"고만 했던 경로를 이번에 실제로 확인한 셈입니다.
  - 하한: `{1,17}` 변이를 18자리 테스트가 잡았습니다.
- AC 2의 네 조건(키 다름, 키 없음, 채널 없음, 경로 형식 아님)은 모두 실제 DB와 보안 필터를 거치는 통합 테스트로 401이 확인됩니다.
- 키 없음 조건은 변이 3에서 확인했습니다. null 키 가드를 빼면 실제 경로에서는 NPE로 500이 됩니다. 통합 테스트가 이 경로를 막고 있습니다.

##### 변이 결과

스크래치 복사본에서만 돌렸습니다. 위치는 `C:\Users\USER\AppData\Local\Temp\claude\1cbb40fc7d76b7b0\d434e676-b23e-4cde-8b32-82990af677dd\scratchpad\mut2`이고 `git archive HEAD`로 만들었습니다.

| # | 변이 (PublishAuthService.java) | 돌린 클래스 | 결과 |
|---|---|---|---|
| 기준선 | 없음 | PublishAuthServiceTest, PublishAuthIntegrationTest | 27개 모두 통과 (canPublish 21, Integration 6) |
| 1 | :13 `\d{1,18}`을 `\d{1,19}`로 | 두 클래스 | **잡힘.** 27개 중 2개 실패, 실패한 것은 19자리 단위 테스트(NumberFormatException)와 19자리 통합 테스트(ServletException ← NumberFormatException)뿐 |
| 2 | :13 `\d{1,18}`을 `\d{1,17}`로 | PublishAuthServiceTest | **잡힘.** 21개 중 18자리 테스트 1개 실패 ("Expecting value to be true but was false") |
| 3 | :32 `key != null &&` 제거 (정규식은 원복) | 두 클래스 | **잡힘.** 27개 중 `should return 401 when the query has no key` 통합 테스트 1개만 실패 (NPE `"key" is null`으로 ServletException). 단위 테스트 21개는 모두 통과 |

##### 남은 갭 (AC 2)

없습니다. 추가 시나리오 제안도 없습니다.

##### 약한 단언 (가짜 테스트는 아님, AC 판정에는 영향 없음)

- 변이 3에서 단위 테스트 `should deny when the query has no key`(PublishAuthServiceTest.java:96-100)와 `should deny when the query is empty`(:56-62)가 살아남았습니다.
- 이유: 목의 기본 반환값 false에 기대고 있어서, 구현이 `matches(7, null)`을 불러도 통과합니다. 사실상 목의 반환값을 단언하는 것에 가깝습니다.
- 같은 동작을 통합 테스트가 잡으므로 AC는 고정되어 있습니다.
- 조이려면 이 두 테스트에도 19자리 테스트처럼 `verify(streamKeyService, never()).matches(anyLong(), anyString())`를 더하면 됩니다. 선택 사항입니다.

##### 실행한 명령

1. `cat .claude/harness.json && git log --oneline -3 && git status --short` → 설정 확인(scenariosDir=docs/result/scenarios), HEAD a7f6b71, 작업 트리 깨끗함
2. Grep `PublishAuth` (backend/src) → 4개 파일
3. Read로 PublishAuthService.java, PublishAuthController.java, PublishAuthServiceTest.java, PublishAuthIntegrationTest.java 본문 확인
4. `gh issue view 10` 및 `grep -n "AC 검증" -A200 docs/result/scenarios/issue-10.md` → AC 7개, 1차 검증 원문과 갭 처리 기록 확인
5. `cat -n StreamKeyService.java | sed -n 45,75p`, GlobalExceptionHandler의 `ExceptionHandler` grep, StreamKeyServiceTest의 matches grep → matches 구현(53-60) 확인, NumberFormatException과 NPE 처리기가 없음을 확인
6. `git archive HEAD | tar -x -C .../scratchpad/mut2` → 스크래치 복사본 생성
7. (스크래치) `./gradlew test --tests "PublishAuthServiceTest" --tests "PublishAuthIntegrationTest"` → 기준선 BUILD SUCCESSFUL, XML 기준 21+6개 failures=0
8. (스크래치) 변이 1 sed 후 같은 두 클래스 실행 → BUILD FAILED, 27개 중 2개 실패(위 표)
9. (스크래치) 변이 2 sed 후 `--tests "PublishAuthServiceTest"` 실행 → 21개 중 1개 실패
10. (스크래치) 정규식을 원복하고 변이 3 sed 후 두 클래스 실행 → 27개 중 1개 실패(통합 테스트, NPE)
11. `git -C C:/live-streaming-project status --short; git rev-parse --short HEAD; grep 18-digit/19 digits issue-10.md` → **status 출력은 비어 있음**, HEAD a7f6b71 그대로, 시나리오 문서 143-145행에 `[x]` 3개

##### 기타

- 저장소 작업 트리와 구현, 테스트는 건드리지 않았습니다. checkout, stash, 커밋도 하지 않았습니다.
- docker compose는 조작하지 않았습니다. 통합 테스트는 Testcontainers로만 돌았습니다.
- 스크래치 복사본에는 변이 3이 적용된 상태가 그대로 남아 있습니다.
- AC 범위 밖 참고: `live/007`처럼 앞에 0이 붙은 경로는 채널 7의 키로 허용됩니다. `Long.parseLong`이 7로 읽기 때문입니다. 숫자 경로이므로 AC 2 위반은 아닙니다. 필요하면 security-review에서 볼 만한 사항입니다.

관련 파일
- C:\live-streaming-project\backend\src\main\java\com\livestream\broadcast\PublishAuthService.java
- C:\live-streaming-project\backend\src\test\java\com\livestream\broadcast\PublishAuthServiceTest.java
- C:\live-streaming-project\backend\src\test\java\com\livestream\broadcast\PublishAuthIntegrationTest.java
- C:\live-streaming-project\docs\result\scenarios\issue-10.md

#### 2차 뒤 구현자 메모

- 약한 단언 2개는 선택 사항이라 손대지 않았다. 통합 테스트가 같은 동작을 잡는다.
- `live/007` 지적은 보안 점검에서 다룬다(아래 `## 보안 점검`).

---

## 리팩터

| 파일 | 기준 | 전 → 후 | 테스트 |
| --- | --- | --- | --- |
| `PublishAuthService.canPublish` | 불필요한 복잡도 | 경로가 null이면 `Matcher`를 null로 두고 다시 null을 검사하던 것을 → action 검사와 같은 줄의 조기 반환으로 | 전체 276개 통과 |

손대지 않은 것: `PublisherDisconnector`는 구현이 하나뿐인 인터페이스지만, channel → broadcast 역방향 의존을 막으려고 둔 것이다(시그니처 판단 1). 테스트 없는 코드는 이번 변경에 없다.

---

## 보안 점검

> `/harness:security-review 10` (2026-11-21). 사전 승인에 따라 🔴는 고치고, 🟡는 고치지 않고 "사용자 결정 대기"로 남긴다.

**수집:** `compileJava compileTestJava` 성공, `tsc -b` 성공, `npm audit` 0건, 비밀값 패턴·알려진 접두사 grep 0건, 브랜치가 커밋한 `.env` 없음. 새 로그는 `StreamKeyService`의 경고 한 줄(채널 id와 예외 클래스 이름만).

| 분류 | 파일·줄 | 무엇 | 판단 | 처리 |
| --- | --- | --- | --- | --- |
| 🔴 | `PublishAuthService.java` 경로 정규식 | `live/007`·`live/07`이 채널 7의 키로 허용됐다(2차 AC 검증이 짚음). MediaMTX에서는 `live/7`과 다른 경로라 같은 채널로 두 번째 송출이 되고, 재발급은 `path == live/7`인 연결만 끊으므로 **이전 키로 `live/007`에 붙은 송출이 재발급 뒤에도 살아남는다** — 사용자 스토리 4 위반 | 키를 가진 사람(유출 상황이면 공격자)이 재발급을 피해 갈 수 있다 | 고침. 시나리오 `[경계] PublishAuthService.canPublish — should deny when the channel id in the path starts with zero`를 더해 Red 확인(3개 실패, 커밋 afdd746) → 정규식을 `live/([1-9]\d{0,17})`로(커밋 dec1c5f). 전체 279개 통과 |
| 🟡 | `infra/mediamtx/mediamtx.yml` | MediaMTX API가 인증 없이 도커 내부망 전체에 열려 있다(`authHTTPExclude`에 `api`). 같은 네트워크의 다른 컨테이너(nginx 등)가 뚫리면 연결 목록(`query`에 키)을 읽고 송출을 끊을 수 있다 | PRD 결정("Spring만 내부망으로 부른다, 호스트 포트로 열지 않는다")대로다. 막으려면 API에 내부 사용자 인증(`authInternalUsers`)을 두고 Spring이 자격을 싣거나, MediaMTX·Spring만 붙는 별도 네트워크로 나눠야 한다 | **사용자 결정 대기** |
| 🟡 | `infra/mediamtx/mediamtx.yml` | 1.21은 MoQ 서버가 기본으로 켜져 있다(`moq: true`, 컨테이너 안 8892/8893). 스파이크 1절 7번은 `moq: no`를 권했다 | 호스트에 포트를 열지 않아 바깥에서 닿지 않는다. 이 이슈 AC와 무관해 이번에 넣지 않았다 | **사용자 결정 대기** |
| ⚪ | `PublishAuthController` | `/internal/mediamtx/auth`는 로그인·출처 검사 없이 받는다 | PRD 결정. nginx가 404로 막고(설정 테스트 + compose curl 4종), api 컨테이너는 호스트 포트가 없다. 키는 256비트라 내부망에서도 대입은 현실적이지 않다 | 그대로 |
| ⚪ | `nginx.conf` | 슬래시 없는 `/internal`은 `location /`의 SPA(200 index.html)로 간다 | Spring에 닿지 않는다(1차 AC 검증 확인). Spring의 엔드포인트는 모두 `/internal/` 아래다 | 그대로 |
| ⚪ | 키가 남는 곳 | 키 확인 응답은 빈 본문, `MediaMtxClient`는 연결 목록의 `query`를 읽지 않는다, 경고 로그는 예외 메시지를 남기지 않는다 | compose 수동 확인에서 mediamtx·api-1·api-2 로그 전체에 `key=` 0줄 | 그대로 |
| ⚪ | `application.yml` `spring.http.client.*-timeout: 2s` | RestClient 전역 시간 제한 | MediaMTX가 멈춰도 재발급 요청이 2초 남짓에 끝난다. 지금 RestClient를 쓰는 곳은 MediaMTX뿐이다 | 그대로 |

**재스캔:** 고친 뒤 `compileJava compileTestJava`·전체 테스트(279개) 통과. 남은 항목은 위 표의 🟡 2개(사용자 결정 대기)와 ⚪뿐이다.

**사용자 결정 (2026-11-21, 이슈 #11 머지 뒤):** 🟡 2(MoQ)는 `moq: no`로 껐다(`chore/mediamtx-moq-off`, MediaMTX 로그에 MoQ 리스너가 더 뜨지 않는 것 확인). 🟡 1(API 무인증)은 별도 이슈 #26 "MediaMTX API는 Spring만 부를 수 있다"로 넘겼다 — API 호출도 Spring 키 확인 엔드포인트를 거치게 하고 Spring이 내부 비밀번호를 싣는 방식. 네트워크 분리는 nginx가 HLS 때문에 mediamtx와 같은 망에 있어야 해서 막지 못한다.
