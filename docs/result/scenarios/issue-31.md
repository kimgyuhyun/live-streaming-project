# 이슈 #31 — 공개 API는 IP당 요청 수를 제한한다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/31
> PRD: `docs/result/prd/channel.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

---

## 시그니처

### 관련 결정

- **이슈 본문 "이 이슈에서 정할 것" (사용자와 정함, 2026-11-24, 측정 전).** 이슈 #31 코멘트에도 같은 값을 남겼다.
  1. **한도:** IP당 `rate=10r/s`, `burst=40 nodelay`. 근거: 시청자 한 명은 채널당 5초에 한 번(0.2 req/s, #12). 한 IP(NAT) 뒤 시청자 50명이 모두 폴링해도 10 req/s. 페이지를 처음 열 때 몰리는 요청은 버스트 40이 받는다.
  2. **셀 범위:** `/api/` 전체(캐시 location `/api/channels/{id}` 포함). 캐시 HIT도 센다. `limit_req`는 캐시를 보기 전에 돌아 "캐시를 지나친 요청만" 셀 수 없다. 남용 경로(없는 채널 id는 캐시 location, 앞자리 0은 `/api/`)가 두 location에 걸쳐 있다. `/`·`/hls/`·`/ws`는 세지 않는다 — HLS 조각은 시청자 한 명이 몇 초마다 받아 한도를 금방 넘는다.
  3. **로그인 시도 제한(#3)과의 관계:** 로그인도 nginx 제한 안에 둔다. 단위가 달라(초당 요청 수 대 15분 실패 횟수) 부딪히지 않는다. nginx가 낸 429는 `RATE_LIMITED`로, Spring이 낸 429(`MEMBER_LOGIN_BLOCKED`)는 그대로 보내 프론트가 구분한다.
  4. **합격 기준:** 정상 시청자 200 req/s(1,000명, 50명씩 IP 20개) + 남용 1,200 req/s(IP 하나가 없는 채널 id를 바꿔 가며)를 함께 걸었을 때, 제한 뒤 **정상 시청자 오류 0건(429 포함)·p95 ≤ 50ms**. 남용 400~3,200 req/s 계단은 기록만 하고 판정하지 않는다.
     - 처음에는 "p95 ≤ 남용 없을 때 기준선의 1.5배"로 정했다가 시그니처 단계에서 바꿨다. #29 기준선 p95가 약 1.5ms라 1.5배(약 2.3ms)는 측정 흔들림만으로 떨어진다.
     - 남용 1,200을 고른 이유: 정상 시청자와 합쳐 1,400 req/s로, 이슈가 적은 캐시 없는 포화 지점(약 1,400~1,500 req/s) 근처다. 남용 요청은 404라 캐시에 담기지 않고 전부 Spring·DB로 간다.
     - 제한은 Spring·DB를 지키지 nginx CPU를 지키지 않는다(429도 nginx가 하나하나 처리한다). 로컬 nginx는 `cpus: 0.25`에서 약 1,600 req/s가 천장이었다(#29). "초당 수천 건" 계단에서 nginx 천장이 드러나면 그것은 별도 이슈다.
- **#29** — 채널 조회 1초 캐시, 앞자리 0 id는 캐시하지 않고 `/api/`로 보낸다(우회 자체는 이 이슈). 측정 원칙(기준선 먼저, 한 번에 한 가지, 결과를 저장소에).
- **AC 4 / 기존 설정** — `set_real_ip_from 10.250.0.0/24` + `real_ip_header X-Forwarded-For`가 이미 있다. 그 뒤의 `$binary_remote_addr`가 실제 사용자 IP다.
- **member PRD** — Spring은 nginx가 넘긴 `X-Forwarded-For`를 믿는다(`server.forward-headers-strategy: native`). 바꾸지 않는다.

### nginx 설정 (`infra/nginx/nginx.conf`)

```nginx
# http 수준. real_ip를 거친 뒤의 주소(edge가 보낸 실제 사용자 IP)로 센다
limit_req_zone $binary_remote_addr zone=api_per_ip:10m rate=10r/s;
limit_req_status 429;

server {
    # nginx가 직접 낸 429만 이 JSON으로 바꾼다.
    # Spring이 보낸 429(MEMBER_LOGIN_BLOCKED)는 proxy_intercept_errors가 꺼져 있어 그대로 간다
    error_page 429 = @rate_limited;
    location @rate_limited {
        default_type "application/json; charset=utf-8";
        return 429 '{"code":"RATE_LIMITED","message":"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요"}';
    }

    location /api/                         { limit_req zone=api_per_ip burst=40 nodelay; ... }   # 기존 그대로 + 이 줄
    location ~ ^/api/channels/[1-9][0-9]*$ { limit_req zone=api_per_ip burst=40 nodelay; ... }   # 캐시 HIT도 센다
    # / · /hls/ · /ws · /internal/ 은 세지 않는다
}
```

### 프론트 (`frontend/src/pages/ChannelPage.tsx`)

채널을 한 번도 받지 못했을 때 404가 아니면 지금은 고정 문구(`잠시 후 다시 시도해 주세요`)를 보인다. 이것을 `errorMessage(channel.error)`(`api/client.ts`, 이미 있음)로 바꿔 오류 본문의 `message`를 보인다. 본문이 JSON이 아니면 `errorMessage`가 같은 고정 문구를 준다. 새 함수·Props는 없다. 다른 화면(로그인·가입·마이·스튜디오)은 이미 `errorMessage`를 쓴다.

### 에러 케이스

| 상황 | 응답 | 낸 곳 |
| --- | --- | --- |
| 한 IP가 `/api/**`에 초당 10건 + 버스트 40을 넘게 보냄 | `429 {"code":"RATE_LIMITED","message":"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요"}` — Spring으로 가지 않는다 | nginx |
| 로그인 실패가 쌓임(#3) | `429 {"code":"MEMBER_LOGIN_BLOCKED", ...}` 그대로 | Spring |

### 테스트 방식

```java
// com.livestream.channel.NginxRateLimitTest — Spring 없이, Testcontainers만 (NginxChannelCacheTest와 같은 틀)
// 네트워크 둘
//   edge    : 서브넷 10.250.0.0/24 (server-infra의 edge 대역). 여기서 보낸 X-Forwarded-For는 믿어야 한다
//   outside : 그 밖의 대역. 여기서 보낸 X-Forwarded-For는 믿지 않아야 한다
// 입구 nginx  : 저장소의 infra/nginx/nginx.conf 그대로, 두 네트워크에 다 붙는다
// 가짜 Spring : nginx 하나에 api-1·api-2·mediamtx 별칭. 요청마다 "메서드 URI X-Forwarded-For"를 로그에 남긴다
//               → 사용자 IP별로 Spring까지 간 횟수를 센다. POST /api/auth/login은 429 MEMBER_LOGIN_BLOCKED
// 요청 보내는 쪽: 같은 nginx:1.30.7-alpine 이미지(curl이 들어 있다)를 edge·outside에 하나씩 띄워 exec로 curl
// 테스트끼리 제한 영역을 같이 쓰므로 테스트마다 다른 사용자 IP(X-Forwarded-For)를 쓴다
```

- 새 의존성·새 이미지 없음.
- 프론트는 `ChannelPage.test.tsx`에 하나 더한다(기존 `vi.mock('../api/channels.ts')`).

### 측정 (AC 6, 수동 — `perf/rate-limit.js`, `perf/compose.perf.yml`)

- 호스트의 k6로 보내면 모든 요청이 한 IP(도커 게이트웨이)에서 와 정상 시청자도 한도에 걸린다. 그래서 **k6를 `10.250.0.0/24` 망 안의 컨테이너(`grafana/k6`)로 돌려 Caddy처럼 `X-Forwarded-For`를 붙인다.** `perf/compose.perf.yml`이 그 망을 만들고 nginx를 붙인다(측정 전후 같은 설정).
- 정상 시청자: 200 req/s, `GET /api/channels/{id}`(#29와 같은 채널 5개·비율), 시청자 1,000명을 50명씩 IP 20개로.
- 남용: IP 하나가 `GET /api/channels/{없는 id}`를 매번 다른 id로.
- 순서: ① 시청자만·제한 전(기준선) ② 시청자 + 남용 1,200·제한 전 ③ 시청자 + 남용 1,200·제한 후(판정) ④ 시청자만·제한 후. 남용 계단 400·800·1,600·3,200은 제한 전후 기록만.
- 결과: `docs/perf/issue-31/`(원시 요약 JSON, `perf/sample-stats.sh` 자원 CSV), 시나리오·k6 버전·대상 커밋을 함께 적는다.

### 이 이슈에서 만들지 않는 것

- nginx CPU 한도(`cpus: 0.25`) 조정 — 위 합격 기준 설명. 측정에서 천장이 드러나면 별도 이슈
- Spring 쪽 요청 수 제한, `Retry-After` 헤더
- 홈 화면(`HomePage`)의 오류 표시 — 지금 오류 표시 자체가 없다
- `/ws` 핸드셰이크 제한
- 실제 서버에서 Caddy를 거친 확인 — 배포 단계

---

## 테스트 시나리오

### 정상

- [x] [정상] `nginx rate limit` — should answer every request with 200 when 50 viewers behind one IP poll at 10 requests per second for 5 seconds
- [x] [정상] `nginx rate limit` — should count requests by the X-Forwarded-For address when they come from the edge range
- [x] [정상] `nginx rate limit` — should not limit HLS requests when one IP sends more than the limit

### 경계

- [x] [경계] `nginx rate limit` — should answer every request with 200 when one IP sends 40 requests at once
- [x] [경계] `nginx rate limit` — should reject requests for a cached channel with 429 when one IP goes over the limit

### 예외

- [x] [예외] `nginx rate limit` — should answer 429 when one IP sends 60 requests at once to the public API
- [x] [예외] `nginx rate limit` — should not pass rejected requests to Spring when one IP goes over the limit
- [x] [예외] `nginx rate limit` — should answer the rate limit 429 with a JSON body whose code is RATE_LIMITED
- [x] [예외] `nginx rate limit` — should keep answering another IP with 200 when one IP goes over the limit
- [x] [예외] `nginx rate limit` — should ignore X-Forwarded-For sent from outside the edge range
- [x] [예외] `nginx rate limit` — should pass the login block 429 from Spring through unchanged
- [x] [예외] `ChannelPage` — should show the message from the error body when the channel request is rejected with 429

### 수동 확인·측정 (로컬 compose + k6 — 시나리오 수에 넣지 않는다)

- 제한 전: ① 시청자만(기준선) ② 시청자 + 남용 1,200 req/s ③ 남용 계단 400·800·1,600·3,200 — `docs/perf/issue-31/before*/`
- 제한 후: 같은 조건으로 ②·③·① — `docs/perf/issue-31/after*/`. 판정은 제한 후 ②(정상 시청자 오류 0건·p95 ≤ 50ms)
- 측정 중 nginx 접근 로그로 남용 요청 중 Spring까지 간 수와 429 수를 센다
- 브라우저: 한도를 넘긴 상태에서 채널 페이지를 열면 "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요"가 보인다

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 한도를 넘으면 429, Spring에는 가지 않는다 | `should answer 429 when one IP sends 60 requests at once to the public API`<br>`should not pass rejected requests to Spring when one IP goes over the limit`<br>`should reject requests for a cached channel with 429 when one IP goes over the limit` |
| 2. 한도 안의 요청(한 IP 뒤 여럿의 5초 폴링 포함)은 정상 | `should answer every request with 200 when 50 viewers behind one IP poll at 10 requests per second for 5 seconds`<br>`should answer every request with 200 when one IP sends 40 requests at once` |
| 3. 다른 IP는 영향받지 않는다 | `should keep answering another IP with 200 when one IP goes over the limit` |
| 4. edge 대역의 `X-Forwarded-For`로 세고, 그 밖의 것은 믿지 않는다 | `should count requests by the X-Forwarded-For address when they come from the edge range`<br>`should ignore X-Forwarded-For sent from outside the edge range` |
| 5. 프론트가 429 본문(`code`·`message`)을 읽어 문구를 보인다 | `should answer the rate limit 429 with a JSON body whose code is RATE_LIMITED`<br>`ChannelPage — should show the message from the error body when the channel request is rejected with 429` |
| 6. 제한 전후 측정이 저장소에 남고 합격 기준을 넘는다 | 수동 확인·측정 |

AC에 없는데 둔 것 (이슈의 "정할 것"에서 온다):
- `should not limit HLS requests when one IP sends more than the limit` — 셀 범위 결정(`/hls/`는 세지 않는다)
- `should pass the login block 429 from Spring through unchanged` — 로그인 시도 제한과 겹치지 않게 하는 결정

**커버리지:** AC 6개 / 시나리오 12개(+ 수동 측정) / 미커버 0개 (AC 6은 수동)

---

## Red 기록 (2026-11-24)

- 테스트 12개를 더했다: `NginxRateLimitTest` 11개(컨테이너를 띄우는 파일이라 다 쓰고 파일 단위로 한 번 돌렸다), `ChannelPage.test.tsx` 1개. stub는 없다(구현은 `nginx.conf`와 `ChannelPage.tsx`의 한 줄).
- `check-scenarios.mjs 31 main`: 시나리오 12 · 더한 테스트 12 · 불일치 0
- 전체: 백엔드 401건 중 5건 실패(이번에 더한 것만), 프론트 32건 중 1건 실패(이번에 더한 것)
- **좋은 실패 6건** — 모두 단언에서 실패
  - 429·캐시 채널 429·외부 `X-Forwarded-For` 무시: 응답이 전부 200
  - Spring에 가지 않음: Spring까지 간 수 60(60 미만이어야 한다)
  - `RATE_LIMITED` 본문: 가짜 Spring의 200 본문
  - `ChannelPage`: 고정 문구만 있고 본문의 message가 없다
- **구현 없이 통과한 6건** — 지금은 제한이 없어 "막지 않는다"를 말하는 테스트는 당연히 통과한다. Green 뒤 설정 변이로 실패하는지 확인한다(#14·#29와 같은 처리)

| 테스트 | Green 뒤 넣을 변이 |
| --- | --- |
| 50명 폴링 초당 10건 → 모두 200 | `rate=5r/s` |
| 40건 한꺼번에 → 모두 200 | `burst=20` |
| edge에서 온 요청은 `X-Forwarded-For`별로 센다 | 키를 `$realip_remote_addr`(edge 주소)로 |
| HLS는 세지 않는다 | `limit_req`를 server 수준으로 |
| 다른 IP는 계속 200 | 키를 상수로 |
| Spring의 로그인 차단 429는 그대로 | `proxy_intercept_errors on` |

- 덧붙여 확인한 것: 가짜 Spring 로그의 `X-Forwarded-For`가 테스트가 보낸 사용자 IP로 찍혔다(Spring까지 간 수 60을 그 IP로 셌다). edge 대역 흉내(10.250.0.0/24 망)와 기존 `set_real_ip_from`이 의도대로 맞물린다.

---

## Green 기록 (2026-11-24)

- 구현: `infra/nginx/nginx.conf`(제한 영역, `/api/`·캐시 location의 `limit_req`, 429 JSON), `frontend/src/pages/ChannelPage.tsx`(404가 아닌 오류에 `errorMessage`)
- 순서: `ChannelPage` → `/api/` 제한(429·Spring에 가지 않음·외부 `X-Forwarded-For` 무시 통과) → 캐시 location 제한(캐시 채널 429 통과) → 429 JSON
- 전체: 백엔드 401건·프론트 32건 통과. 커버리지 명령은 비어 있어(`coverage`) 재지 않았다. 구현이 nginx 설정이라 줄 커버리지로 볼 것도 없다
- **테스트를 한 번 고쳤다(사용자 승인).** `should answer the rate limit 429 with a JSON body whose code is RATE_LIMITED`가 60건을 보낸 뒤 `docker exec`를 새로 띄워 본문을 받았는데, 그 사이(100~300ms)에 초당 10건 토큰이 다시 차 마지막 요청이 200이었다. 본문 요청을 같은 curl 실행의 61번째로 옮겼다(`bodyAfterBurst`). 문장·단언은 그대로. 고친 테스트를 제한 전 nginx로 돌려 실패하는 것을 다시 확인했다
- **#29 캐시 테스트 간헐 실패 1회.** 캐시 location에 `limit_req`를 넣은 뒤 한 번, `should return a fresh answer from Spring when the same channel is requested after 1 second`가 2.1초 뒤에도 같은 본문을 받았다. 이어서 단독 3회·`Nginx*` 묶음 3회·전체 1회는 모두 통과했다. 두 테스트 클래스는 컨테이너를 따로 띄우고 그 테스트는 IP당 한도(초당 10건)에 닿을 만큼 보내지 않으므로 제한과 무관한 시각 문제로 본다(캐시 시각이 초 단위라 1초 캐시가 1~2초 남고, 테스트는 2.1초를 기다린다). 다시 나오면 따로 본다

### 변이 확인 (Red에서 구현 없이 통과한 6건)

`nginx.conf`를 하나씩 바꿔 `NginxRateLimitTest`를 돌리고 Green 사본으로 되돌렸다.

| 변이 | 실패한 테스트 |
| --- | --- |
| `rate=5r/s` | 없음 — 살아남음(아래) |
| `rate=1r/s` | 50명 폴링 초당 10건 → 모두 200 |
| `burst=20` | 40건 한꺼번에 → 모두 200 |
| 키를 `$realip_remote_addr`(edge 주소)로 | edge에서 온 요청은 `X-Forwarded-For`별로 센다, 40건 한꺼번에 |
| `limit_req`를 server 수준으로 | HLS는 세지 않는다 |
| 키를 상수(`$server_name`)로 | 40건 한꺼번에 — **다른 IP는 계속 200은 살아남음**(아래) |
| `proxy_intercept_errors on` | Spring의 로그인 차단 429는 그대로 |

- `rate=5r/s`가 사는 것은 받아들인다. 5초에 50건이면 버스트 40 + 5초 동안 다시 차는 25건으로 모두 받는다. 이 테스트가 지키는 것은 AC 2("NAT 뒤 여럿의 폴링은 정상")이지 정확한 초당 한도가 아니다.
- 다른 IP 테스트는 위 본문 테스트와 같은 구조(한도를 넘긴 뒤 `docker exec`를 새로 띄워 다른 IP로 한 건)라 그 사이에 토큰이 차 키가 상수여도 통과한다. `X-Forwarded-For`별로 센다 테스트도 같은 구조다(변이 3에서는 다른 테스트들이 같은 버킷을 비워 둔 덕에 실패했다).

### 약한 테스트 두 개를 고쳤다 (변이 확인 뒤, 사용자에게 묻고 추천안대로)

- `should count requests by the X-Forwarded-For address when they come from the edge range` — 사용자 A 60건과 사용자 B 1건을 curl 한 번으로 잇고 마지막 응답을 본다. 키 상수·키 `$realip_remote_addr` 변이 모두에서 실패
- `should keep answering another IP with 200 when one IP goes over the limit` — outside 클라이언트가 3초 동안 계속 보내는 사이(1초 뒤)에 edge에서 다른 IP로 한 건. 처음에는 1,000건으로 보냈는데 429가 빨라 다른 IP가 보내기 전에 끝나 키 상수 변이가 살았다. 시간으로 바꾼 뒤 키 상수 변이에서 실패
- 문장·단언의 뜻은 그대로. 고친 뒤 Green 설정에서 두 번 통과, 전체 백엔드 401건 통과, `check-scenarios` 불일치 0

---

## 수동 확인·측정 결과 (2026-11-24, Green 뒤)

- **도구·조건:** k6 2.1.0(`grafana/k6:2.1.0`, edge 대역 `10.250.0.0/24` 안의 컨테이너 — `perf/compose.perf.yml`), 스크립트 `perf/rate-limit.js`, 자원은 `perf/sample-stats.sh`(약 2초마다 `docker stats`). 로컬 compose 자원 제한은 #29와 같다(api 2대 각 1 CPU, oracle 1 CPU, nginx 0.25 CPU·64MB). 데모 시뮬레이터는 측정 동안 멈췄다(#29 기준선처럼 송출 없음). 채널 21·1·41·42·43(21이 60%)
- **대상:** 제한 전 = 브랜치 `514d101`에서 `infra/nginx/nginx.conf`만 main(`959882b`) 것으로 바꿔 nginx를 다시 빌드. 제한 후 = `514d101` 그대로. 이 브랜치는 Spring 코드를 바꾸지 않았다. 두 경우 모두 nginx를 다시 띄울 때 api 두 대도 다시 떴다
- **원시 결과:** `docs/perf/issue-31/{before,after}/{baseline,judge,steps}-summary.json`, 자원 `…-stats.csv`
- 남용 요청은 없는 채널 id라 404다. k6는 404·429를 모두 실패로 세므로 남용자의 "오류 100%"는 뜻이 없다

### 판정 (남용 1,200 req/s + 시청자 200 req/s, 본 측정 60초)

| | 시청자 p50 | p95 | p99 | max | 시청자 오류 | 시청자 429 | 남용 처리량 | 남용 429 | api-1 / api-2 | oracle | nginx |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 시청자만, 제한 전(기준선) | 0.6 | 1.7 | 7.3 | 34.5 | 0% | 0 | — | — | 2 / 2% | 2% | 3% |
| 남용 1,200, 제한 전 | 21.8 | 27.3 | **485.0** | **5,681** | 0% | 0 | 986 req/s(나머지는 VU 부족으로 못 보냄), p50 1,980ms | 0 | **96 / 96%** | 28% | 23% |
| **남용 1,200, 제한 후** | **0.4** | **1.0** | **7.1** | 154.6 | **0%** | **0** | 1,200 req/s, p50 0.3ms | 71,360 / 72,000 | 5 / 7% | 3% | 16% |
| 시청자만, 제한 후 | 0.6 | 1.7 | 3.8 | 10.8 | 0% | 0 | — | — | 1 / 1% | 3% | 3% |

(지연 ms, CPU는 코어 하나 = 100%·본 측정 60초 평균)

**합격 ✅** — 제한 후 정상 시청자 오류 0건(429 0건)·p95 1.0ms ≤ 50ms.

**정직하게 적을 것: 합격 기준은 제한 전에도 넘었다(p95 27.3ms).** 시청자 요청은 대부분 nginx 캐시(#29)에서 답해 Spring이 포화해도 중앙값·p95가 크게 늘지 않는다. 피해는 꼬리에 났다 — 제한 전 p99 485ms·max 5.7초, Spring 두 대 CPU 96%(한도), 남용 요청 p50 2초. 측정 전에 정한 기준이 제한 효과를 가르지 못한 것이다. 기준은 바꾸지 않고(이슈 코멘트대로) 이 사실을 남긴다. 다음 측정 이슈에서는 p99나 Spring CPU를 기준에 넣는 것을 검토한다.

**남용 요청 중 Spring까지 간 수(제한 후, nginx 접근 로그):** 남용을 건 180초(판정 60 + 계단 120) 동안 250,893건 중 1,880건 = 초당 10건 × 180초 + 버스트 40 × 2회와 같다. 나머지 249,013건은 429. 시청자 요청 66,128건 중 429는 0건.

### 남용 계단 (기록만, 각 30초, 시청자 200 req/s를 계속 건다)

| 남용 req/s | 시청자 p95 제한 전 → 후 | 시청자 p99 | 시청자 오류 | 남용 처리량 | api-1 / api-2 | oracle | nginx |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 400 | 1.2 → 0.9 | 3.0 → 4.1 | 0% → 0% | 400 → 400 | 39/42 → 6/6% | 16 → 4% | 15 → 9% |
| 800 | 17.2 → 1.0 | 23.0 → 4.3 | 0% → 0% | 800 → 800 | 43/42 → 2/3% | 25 → 3% | 25 → 15% |
| 1,600 | **417.9 → 1.3** | 719.4 → 100.0 | 0% → 0% | 1,580 → 1,600 | 60/61 → 3/3% | 37 → 3% | 25 → 21% |
| 3,200 | **1,119.6 → 102.0** | 1,619.3 → 601.1 | **0.033%** → 0% | 2,263 → 3,163 | 75/79 → 3/3% | 45 → 4% | 24 → 19% |

**읽은 것**

- **Spring·DB 부하가 남용량과 무관해졌다.** 제한 뒤 api CPU 2~7%, oracle 3~4%로 남용 400~3,200 모두 같다. 제한 전에는 남용 1,200에서 api가 한도(96%)에 닿았다.
- **제한 뒤에도 남용 3,200에서 시청자 p95가 102ms로 늘었다.** 그때 nginx CPU 평균은 19%(한도 25%), k6 컨테이너는 92%였다. 2초 표본 평균이라 nginx가 순간적으로 CPU 한도에 닿았는지, 같은 도커 VM에서 도는 부하 발생기(k6)가 밀렸는지 이 측정으로는 가를 수 없다. 남용 1,600까지는 p95 1.3ms라 판정 지점(1,200)과 그 두 배까지는 영향이 없다. 3,200 너머는 nginx CPU 한도·부하 발생기 분리와 함께 별도로 본다(이슈 본문의 "nginx CPU 천장이 드러나면 별도 이슈").
- 제한 전 남용 3,200은 VU 부족으로 2,263 req/s만 냈다(dropped). 같은 표에서 처리량 칸을 함께 봐야 한다.

### 브라우저 확인 (2026-11-24, 측정 뒤 로컬 compose, 제한 설정)

- 호스트에서 curl로 몰아 보내는 방식은 curl을 띄우는 틈(약 0.35초)에 토큰이 차 채널 조회가 통과했다(429 824건 사이로 `/api/channels/21` 200)
- 페이지 안에서 4초 동안 10ms마다 5건씩 `fetch('/api/channels/0021')`(1,020건)을 보내며 앱 안 이동으로 `/channels/41`을 열자 "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요"만 보였다 ✅
- 함께 본 것: nginx가 막은 요청마다 error 로그에 한 줄(`limiting requests`)을 남긴다(위 curl 확인에서 825줄). 남용이 초당 수천 건이면 접근 로그와 같은 양이 error 로그에도 쌓인다 — 보안 점검에서 분류한다

---

## AC 검증

`harness:ac-verifier` 출력 원문(2026-11-24). 구현자는 이 표를 고치지 않는다.

# 이슈 #31 AC 검증 (브랜치 feat/channel/issue-31, HEAD d978008)

**요약: AC 6개 중 ✅ 4개, ⚠️ 2개, ❌ 0개.** 자동 테스트는 백엔드 11개, 프론트 11개를 이번에 직접 돌렸고 모두 통과했다. 측정은 다시 돌리지 않고, 저장된 k6 원시 JSON과 자원 CSV를 직접 읽어 판정했다.

구현은 nginx.conf:17·18·48–52·66·79에 이슈 코멘트에서 정한 값(10r/s, burst 40 nodelay)이 그대로 들어가 있다. 두 ⚠️는 구현이 틀려서가 아니다. 테스트가 정한 한도 값을 위아래로 묶지 못해서다.

---

### AC 1 — 한도를 넘으면 429이고 Spring에는 가지 않는다

⚠️ 부분 충족 (구현은 맞다. 경계를 위쪽에서 묶지 못한다)
- **테스트** (모두 `backend/src/test/java/com/livestream/channel/NginxRateLimitTest.java`):
  - `should answer 429 when one IP sends 60 requests at once to the public API` (:235)
  - `should not pass rejected requests to Spring when one IP goes over the limit` (:243)
  - `should reject requests for a cached channel with 429 when one IP goes over the limit` (:227)
  - `should answer every request with 200 when one IP sends 40 requests at once` (:219, 아래쪽 경계)
- **구현:** `C:\live-streaming-project\infra\nginx\nginx.conf`
  - :17 `limit_req_zone $binary_remote_addr zone=api_per_ip:10m rate=10r/s`
  - :66은 `/api/`, :79는 캐시 location의 `limit_req ... burst=40 nodelay`
  - :48–52는 `error_page 429 = @rate_limited`. 캐시 location은 자기 `error_page`가 없어 server 수준 설정을 물려받는다
- **강한 부분:** :249의 `springCalls(ip) == answered`는 가짜 Spring 로그를 사용자 IP별로 센다. 그래서 "429 받은 요청은 Spring에 가지 않았다"를 실제로 확인한다.
- **빈틈:** 429 단언이 `contains(429)` 하나뿐이다(:231, :239). 60건 중 한 건만 429여도 통과한다. 40건 테스트는 burst ≥ 약 39만 보장한다.
  - 그래서 다음 설정도 현재 테스트를 모두 통과할 것으로 추론한다(실행해 확인하지는 않았다): `burst=55`(60건 중 약 3건만 429), `rate=50r/s`.
  - 즉 "41번째부터 429"라는 위쪽 경계와 정한 rate 값이 테스트로 묶여 있지 않다.

### AC 2 — 한도 안의 요청(한 IP 뒤 여럿의 5초 폴링 포함)은 모두 정상

⚠️ 부분 충족 (측정은 지속 폴링 정상을 보여 준다. 자동 테스트는 버스트에 가려진다)
- **테스트:**
  - `should answer every request with 200 when 50 viewers behind one IP poll at 10 requests per second for 5 seconds` (:184–195). 0.1초 간격 50건, 모두 200
  - 40건 한꺼번에 테스트 (:219)
- **빈틈:** 5초 동안 50건은 버스트 40 + 재충전만으로 받아진다.
  - 계산하면 1 + 40 + 5r ≥ 50이라 rate가 약 2r/s만 돼도 통과한다.
  - 시나리오 문서 :184·192도 `rate=5r/s` 변이가 살아남았다고 스스로 적었다.
  - 이 AC가 말하는 상황은 **지속되는** NAT 뒤 폴링이다. 버스트가 바닥난 뒤의 정상 상태는 자동 테스트가 보지 않는다.
- **보완 근거 (측정, 수동):** `C:\live-streaming-project\docs\perf\issue-31\after\judge-summary.json`
  - 시청자 IP 20개가 각각 10 req/s로 90초(워밍업 30 + 본 측정 60) 보냈다.
  - `responses_429{role:viewer,phase:warmup}` 0건(6,012건 중), `{phase:main}` 0건(12,012건 중), `http_req_failed{role:viewer,phase:main}` rate 0.
  - 구현과 측정은 맞다. 하지만 회귀를 막는 자동 테스트가 없어 ⚠️로 둔다.

### AC 3 — 서로 다른 IP는 영향받지 않는다

✅ 충족
- **테스트:**
  - `should keep answering another IP with 200 when one IP goes over the limit` (:261–281). outside 클라이언트가 3초 동안 계속 보내는 도중(1초 뒤)에 edge에서 다른 IP로 한 건을 보내고 200을 확인한다.
  - `should count requests by the X-Forwarded-For address when they come from the edge range` (:198–208). A가 60건을 보낸 직후 같은 curl 실행에서 B가 1건을 보내고, 마지막 응답이 200인지 본다.
- **구현:** nginx.conf:17. 키가 `$binary_remote_addr`이다.
- **근거:** 두 테스트 모두 같은 curl 실행 또는 진행 중인 홍수 안에서 다른 IP를 보낸다. 그래서 키 상수 변이를 잡는다(시나리오 문서 :195–199의 변이 기록과도 맞다).
- **측정:** after/judge에서 남용 IP가 429 71,360건을 받는 동안 시청자 429는 0건, p95 1.0ms였다.
- **약한 점:** :269–275에서 홍수를 보낸 쪽이 실제로 429를 받고 있었는지(전제 조건)를 단언하지 않는다. 출력을 `/dev/null`로 버린다. 홍수가 아무 이유로든 실패해도 테스트는 통과한다. 키 상수 변이에서 실패했다는 기록이 간접 증거일 뿐이다.

### AC 4 — edge 대역의 X-Forwarded-For로 세고, 그 밖의 출처는 믿지 않는다

✅ 충족
- **테스트:**
  - `should count requests by the X-Forwarded-For address when they come from the edge range` (:198). 10.250.0.0/24 서브넷 망을 만드는 코드는 :68–71.
  - `should ignore X-Forwarded-For sent from outside the edge range` (:284–295). outside 망에서 요청마다 다른 XFF 60개를 보내 429가 나온다. XFF를 믿었다면 전부 200이었을 것이다.
- **구현:** nginx.conf:11–12(`set_real_ip_from 10.250.0.0/24`, `real_ip_header X-Forwarded-For`, 기존 설정)와 :17의 `$binary_remote_addr`.
- **근거:**
  - `$realip_remote_addr` 변이가 실패했다(시나리오 :187).
  - 가짜 Spring 로그에 사용자 IP가 찍히는 것을 :249가 이용한다.
  - 측정에서도 k6를 perf-edge(10.250.0.0/24) 망에 두었을 때(`perf/compose.perf.yml`) 시청자 IP 20개와 남용 IP가 따로 세어졌다.
- 실제 Caddy를 거친 확인은 이슈에서 범위 밖으로 뺐다(배포 단계, 시나리오 :90).

### AC 5 — 429 본문(code·message)을 읽어 "잠시 후 다시 시도" 문구를 보인다

✅ 충족
- **테스트:**
  - `should answer the rate limit 429 with a JSON body whose code is RATE_LIMITED` (NginxRateLimitTest.java:253–258). 같은 curl 실행의 61번째 응답 본문을 본다.
  - `ChannelPage — should show the message from the error body when the channel request is rejected with 429` (`C:\live-streaming-project\frontend\src\pages\ChannelPage.test.tsx:163`)
- **구현:**
  - nginx.conf:49–52: `return 429 '{"code":"RATE_LIMITED","message":"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요"}'`
  - `C:\live-streaming-project\frontend\src\pages\ChannelPage.tsx:25`: 404가 아니면 `errorMessage(channel.error)`
  - `frontend/src/api/client.ts:21–33`(본문 JSON 파싱 → `ApiError.body`)과 :38–44(`message` 꺼내기)
- **근거:**
  - 프론트 테스트는 기본 문구와 다른 전체 문자열을 `findByText`로 정확히 맞춘다. 그래서 고정 문구로는 통과하지 못한다(Red에서 실패한 기록 :154).
  - 브라우저 수동 확인도 있다(시나리오 :242–246).
- **약한 점:**
  - nginx 쪽 단언이 `contains("\"code\":\"RATE_LIMITED\"")`뿐이다. `message` 필드도, JSON으로 파싱되는지도, 상태 코드 429도 확인하지 않는다.
  - nginx 본문 → `api()` 파싱 → `ApiError` 경로를 잇는 자동 테스트가 없다. `frontend/src/api/`에 테스트 파일이 없고, 프론트 테스트는 `ApiError`를 손으로 만든다.
  - 다만 본문이 깨져도 `errorMessage`의 기본 문구 "잠시 후 다시 시도해 주세요"가 보인다. 그래서 AC 문장의 결과 자체는 유지된다.
- **참고:** 프론트는 `code`를 분기에 쓰지 않고 `message`만 보인다. RATE_LIMITED와 MEMBER_LOGIN_BLOCKED는 메시지 내용으로 구분된다. AC 위반으로 보지는 않는다.

### AC 6 — 제한 전후를 같은 조건으로 재고 결과가 저장소에 남으며, 제한 뒤 합격 기준을 넘는다

✅ 충족
- **시나리오:** `C:\live-streaming-project\perf\rate-limit.js`. 합격 기준이 thresholds로 :84–85에 박혀 있다(`p(95)<=50`, `rate==0`).
- **도구 버전:** `perf/compose.perf.yml:20` `grafana/k6:2.1.0`. 시나리오 문서 :205에도 적혀 있다.
- **대상 커밋:** 문서 :206에 있다. `514d101`과 `959882b`가 실제로 있는 커밋인 것을 확인했다.
- **원시 결과와 자원 지표:** `docs/perf/issue-31/{before,after}/{baseline,judge,steps}-summary.json`과 `-stats.csv` 12개 파일이 모두 있다.
- **판정 (`after/judge-summary.json`):**
  - `http_req_failed{role:viewer,phase:main}` rate 0 (fails 12,012, passes 0)
  - `responses_429{role:viewer,phase:main}` 0
  - `http_req_duration{role:viewer,phase:main}` p95 1.0027ms
  - 두 threshold 모두 `"ok":true`
  - 남용 72,000건(= 1,200 req/s × 60s) 중 429는 71,360건
- **문서 표 대조:** 판정표와 계단표의 수치를 JSON과 맞춰 봤고 일치한다.
  - 제한 전 남용 처리량 59,139/60 = 986 req/s
  - 3,200 계단: 제한 전 67,901/30 = 2,263, 제한 후 94,892/30 = 3,163
  - 시청자 p95: 417.9 → 1.3, 1,119.6 → 102.0
- **참고 (판정에는 영향 없음):**
  1. 합격 기준은 제한 전에도 통과했다(before/judge 시청자 p95 27.25ms, `"ok":true`). 기준이 제한 효과를 가르지 못한다. 문서 :223이 스스로 밝혔다. 제한 효과는 p99(485 → 7.1ms)와 api CPU(96% → 5–7%)에서 드러난다.
  2. **측정 스크립트 결함:** `rate-limit.js:73`의 `dropped_iterations{role,phase}` 하위 지표는 모든 파일에서 0이다. 하지만 태그 없는 `dropped_iterations`는 before/judge 12,862건, before/steps 28,726건, after/steps 1,111건이다. 시나리오 태그가 dropped_iterations에 붙지 않아 생긴 문제로 보인다. 문서의 "VU 부족으로 못 보냄" 주장은 태그 없는 값이 뒷받침한다. 하지만 스크립트 요약 표의 dropped 칸은 항상 0을 찍어 오해를 낳는다.
  3. 남용자 `http_req_duration` min이 음수다(before/judge −360ms, after/judge −591ms). 도커 VM 시계가 튄 것으로 보이며, 지연 수치의 신뢰도에 대한 주의 사항이다. 시청자 min은 양수였다.
  4. "Spring까지 간 1,880건"(문서 :225)은 nginx 접근 로그를 센 값이다. 그 원시 로그는 저장소에 없다.
  5. `docs/result/scenarios/issue-31.md`의 브라우저 확인 절(:242–246)은 아직 커밋되지 않았다(`git status`에서 modified).

---

## 빈틈을 메울 시나리오 제안

- [ ] [경계] `nginx rate limit` — should answer no more than 42 of 60 requests with 200 when one IP sends them at once
  - AC 1. 버스트 위쪽 경계를 묶는다. 200 개수를 41~42로 단언한다.
- [ ] [경계] `nginx rate limit` — should reject requests with 429 when one IP that used its burst keeps sending 20 requests per second for 3 seconds
  - AC 1. rate 위쪽 경계를 묶는다.
- [ ] [정상] `nginx rate limit` — should answer every request with 200 when one IP that used its burst keeps polling at 8 requests per second for 5 seconds
  - AC 2. 버스트를 먼저 비운 뒤의 지속 폴링을 본다. rate 5r/s 변이를 잡는다.
- [ ] [예외] `nginx rate limit` — should answer the rate limit 429 with a JSON body whose message tells the user to try again later
  - AC 5. 본문을 JSON으로 파싱해 `message`까지 단언한다.

## 가짜 테스트 / 약한 단언

조건 없이 통과하는 가짜 테스트는 없다. `await` 누락, 단언 없음, 목 값을 그대로 단언하는 경우도 없다. 약한 단언은 다음 넷이다.

1. **NginxRateLimitTest.java:231, :239의 `assertThat(statuses).contains(429)`**
   - 문제: 429가 한 건만 있어도 통과한다.
   - 조이는 법: 200 개수를 `isBetween(41, 42)`로, 429 개수를 `isGreaterThanOrEqualTo(18)`로 단언한다.
2. **:249의 `.isLessThan(60)`**
   - 문제: `isEqualTo(answered)` 부분은 강하다. 하지만 `< 60`은 거의 아무것도 묶지 않는다.
   - 조이는 법: `answered`를 41~42로 단언한다.
3. **:257의 `contains("\"code\":\"RATE_LIMITED\"")`**
   - 문제: message 필드, JSON 형식, 상태 코드 429를 확인하지 않는다.
   - 조이는 법: `-w '%{http_code}'`로 429를 함께 받고, 본문을 JSON으로 파싱해 code와 message를 모두 단언한다.
4. **:269–275의 홍수 출력을 버림**
   - 문제: 넘친 IP가 실제로 제한받고 있었다는 전제를 단언하지 않는다.
   - 조이는 법: 홍수 쪽 상태 코드를 모아 429가 있는지 함께 확인한다.

## 실행한 명령

1. `cat .claude/harness.json && gh issue view 31 --comments` → harness.json의 scenariosDir 확인. 이 호출은 코멘트(측정 전에 정한 값)만 보여 줬다.
2. `gh api rate_limit --jq .resources.core; gh issue view 31` → 남은 호출 5000/5000. 이슈 본문의 AC 6개를 확인했다.
3. `cat -n infra/nginx/nginx.conf && git status && git diff --stat main...HEAD && git diff` → 구현 줄 번호 확인. 브랜치 변경 19개 파일. 작업 트리에는 issue-31.md만 수정돼 있다.
4. Read `docs/result/scenarios/issue-31.md` → AC 대조표, 변이 기록, 측정표를 확인했다.
5. Read `NginxRateLimitTest.java` → 테스트 11개의 본문과 단언을 확인했다.
6. `git diff main...HEAD -- frontend/ infra/ && cat -n ChannelPage.tsx && cat -n api/client.ts` → `errorMessage` 사용과 `api()` 파싱 경로를 확인했다.
7. `JAVA_HOME=... ./gradlew test --tests "NginxRateLimitTest*"` (백그라운드) → BUILD SUCCESSFUL. JUnit XML 기준 tests=11, failures=0, errors=0.
8. `cat -n perf/rate-limit.js perf/compose.perf.yml && ls -la docs/perf/issue-31/{before,after} && ls perf/` → 스크립트, k6 2.1.0, 결과 파일 12개, sample-stats.sh가 있다.
9. node로 `{before,after}/{judge,baseline}-summary.json` 추출 → after 시청자 main은 fail 0, 429 0, p95 1.00ms, thresholds ok. before 시청자 main p95 27.25ms, p99 485ms. 남용자 min이 음수인 것을 발견했다.
10. node로 `{before,after}/steps-summary.json` 추출, `after/judge-stats.csv` 헤더 확인, `perf/sample-stats.sh` 읽기, `git log -1 514d101`·`959882b` → 계단 수치가 문서와 일치하고 두 커밋이 있다.
11. node로 태그 없는 dropped_iterations 확인, awk로 judge-stats.csv CPU 평균 계산 → 태그 붙은 dropped는 항상 0, 태그 없는 값은 12,862, 28,726, 1,111. after/judge 전체 구간 평균은 api 약 6%, nginx 12%.
12. Gradle 출력 파일 읽기와 JUnit XML grep → 테스트 11개의 이름과 시간 확인, 모두 통과.
13. `npx vitest run ChannelPage.test.tsx` → 1개 파일, 테스트 11개 통과.
14. `ls frontend/src/api/; grep client.test.ts; grep -rn errorMessage` → api 폴더에 테스트 파일이 없다. `errorMessage`는 Login, MyPage, SignUp, ChannelPage에서 쓴다.
15. `grep -n` ChannelPage.test.tsx와 compose.perf.yml → 새 테스트는 :163, k6 이미지는 :20.

코드와 테스트는 고치지 않았고, 측정도 다시 돌리지 않았다.
