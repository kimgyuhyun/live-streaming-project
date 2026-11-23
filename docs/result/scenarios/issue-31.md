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
