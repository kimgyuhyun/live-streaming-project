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
| 2. 키가 다르거나·없거나·채널이 없거나·경로가 `live/{숫자}`가 아니면 401 | `[예외] StreamKeyService.matches — should return false when the key differs from the saved one`<br>`[예외] StreamKeyService.matches — should return false when the channel does not exist`<br>`[경계] StreamKeyService.matches — should return false when the channel has no key`<br>`[예외] PublishAuthService.canPublish — should deny when the key does not match`<br>`[예외] PublishAuthService.canPublish — should deny when the query has no key`<br>`[경계] PublishAuthService.canPublish — should deny when the query is empty`<br>`[예외] PublishAuthService.canPublish — should deny when the path is not live followed by a number`<br>`[경계] PublishAuthService.canPublish — should deny when the channel id in the path is too long for a number`<br>`[예외] PublishAuthIntegration — should return 401 when the key differs from the channel key`<br>`[예외] PublishAuthIntegration — should return 401 when the query has no key`<br>`[예외] PublishAuthIntegration — should return 401 when the channel does not exist`<br>`[예외] PublishAuthIntegration — should return 401 when the path is not live followed by a number` |
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

**커버리지:** AC 7개 / 시나리오 27개 / 미커버 0개 (AC 5·6은 수동 확인만, AC 7은 설정 테스트 + 수동 확인)
