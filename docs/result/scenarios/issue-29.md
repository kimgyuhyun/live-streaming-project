# 이슈 #29 — 시청자가 몰려도 채널 조회는 nginx에서 1초 캐시로 답한다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/29
> PRD: `docs/result/prd/channel.md`
> 담당: kimgyuhyun
> 상태: 시그니처 승인

---

## 시그니처

### 관련 결정

- **#12 판단 1·2** — 방송 중 채널을 보는 시청자는 5초마다 `GET /api/channels/{id}`를 다시 묻는다(사용자 결정). 오프라인 채널은 묻지 않는다.
- **#12 보안 점검 🟡** — 비로그인 시청자의 폴링이 모두 Spring·Oracle까지 간다. 사용자가 "규모가 작아도 미리 대비한다"로 정해 이 이슈가 됐다.
- **캐시 위치 (사용자와 정함, 2026-11-21)** — nginx 하나에만 둔다. Redis 캐시는 nginx 뒤에서 채널당 초당 1건 이하만 받아 줄일 것이 없다. 같은 엔드포인트에 두 겹을 두면 반영 지연이 겹치고 지울 곳만 늘어난다. 사용자마다 다른 응답·무거운 목록 조회처럼 nginx가 캐시할 수 없는 곳이 측정으로 드러나면 그때 다룬다.
- **PLATFORM 10절** — 기준선을 먼저 재고, 합격 기준은 측정 전에 정하고(이슈 본문), 한 번에 한 가지만 바꾸고, 결과를 저장소에 남긴다.

### nginx 설정 (`infra/nginx/nginx.conf`)

```nginx
# http 수준(conf.d 파일은 http 블록 안에 들어간다)
# 채널 조회 응답만 담는다. 키 수 = 채널 수라 1MB 영역(약 8천 키)이면 충분하다
proxy_cache_path /var/cache/nginx/channel levels=1:2 keys_zone=channel:1m max_size=10m inactive=10s use_temp_path=off;

server {
    # 정규식 location이 접두사 /api/ 보다 먼저 맞는다. /api/channels/{숫자}만, 다른 하위 경로는 /api/로 간다
    location ~ ^/api/channels/[0-9]+$ {
        proxy_pass http://api;
        proxy_set_header Host $host;                 # /api/와 같은 헤더
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $remote_addr;

        proxy_cache channel;
        proxy_cache_key $uri;                        # query는 키에서 뺀다 — ?x=랜덤으로 캐시를 우회해 DB를 두드릴 수 없다
        proxy_cache_valid 200 1s;                    # 200만, 1초. 404·5xx는 담지 않는다
        proxy_cache_lock on;                         # 빈 캐시에 동시에 와도 Spring에는 하나만 간다
        proxy_cache_use_stale updating;              # 1초가 지나 새로 받는 동안 다른 요청은 직전 값으로 답한다
        proxy_ignore_headers Cache-Control Expires;  # Spring Security가 모든 응답에 no-store를 붙인다. 브라우저에는 그대로 간다
        add_header X-Cache-Status $upstream_cache_status always;   # HIT·MISS·EXPIRED·UPDATING — 확인·측정용
    }
}
```

- `proxy_cache_methods` 기본값이 GET·HEAD라 `PUT /api/channels/{id}`(채널 수정)는 캐시를 거치지 않고 매번 Spring으로 간다.
- nginx는 기본으로 `Set-Cookie`가 있는 응답을 담지 않는다. 익명 채널 조회 응답에는 쿠키가 없다(#12 확인: `Set-Cookie` 없음).
- 응답은 로그인 여부와 상관없이 같다(공개 채널 정보). 그래서 키에 쿠키를 넣지 않는다.

### 테스트 방식

nginx 동작은 설정 문자열이 아니라 **실제 nginx로** 본다. `NginxInternalPathTest`(#10)는 설정 파일 글자를 읽지만, 캐시는 "몇 번 Spring까지 갔나"가 요점이라 글자로는 증명할 수 없다.

```java
// com.livestream.channel.NginxChannelCacheTest — Spring 없이, Testcontainers만
// 1) 가짜 Spring: nginx:1.30.7-alpine 하나에 네트워크 별칭 api-1·api-2·mediamtx를 단다
//    (실제 nginx.conf가 이 이름들을 찾는다). :8080에서 요청마다 다른 $request_id를 JSON으로 돌려주고,
//    Spring처럼 Cache-Control: no-cache, no-store…·Expires: 0을 붙인다. /api/channels/404는 404.
//    /api/channels/9는 limit_rate로 약 1초에 걸쳐 응답한다(동시 요청 시험용).
//    Spring까지 간 횟수 = 가짜 Spring의 접근 로그(컨테이너 로그)에서 그 경로의 줄 수
// 2) 입구: 같은 이미지에 저장소의 infra/nginx/nginx.conf를 그대로 넣는다(Dockerfile과 같은 자리)
```

- 새 의존성은 없다(`org.testcontainers:junit-jupiter`에 들어 있는 `GenericContainer`). 이미지 `nginx:1.30.7-alpine`은 `infra/nginx/Dockerfile`이 이미 쓰는 것이다.

### 측정 (`perf/channel-poll.js`, k6 2.1.0)

| 모드 | 내용 |
| --- | --- |
| `MODE=target` | 워밍업 50 req/s 30초 → 목표 200 req/s 60초. 합격 기준을 k6 `thresholds`로 박았다(p95 ≤ 200ms, 오류율 < 0.1%) |
| `MODE=steps` | 워밍업 뒤 50 → 100 → 200 → 400 → 800 → 1600 req/s, 단계당 30초(constant-arrival-rate) |

채널 5개(21이 인기 채널 60%, 1·41·42·43이 10%씩). 자원 지표는 `perf/sample-stats.sh`가 약 2초마다 `docker stats`로 남긴다. 결과는 `docs/perf/issue-29/{baseline,after}/`.

### 에러 케이스

| 상황 | 동작 |
| --- | --- |
| Spring이 404(없는 채널)·5xx | 담지 않는다. 매번 Spring으로 간다 |
| Spring이 응답하지 않을 때 | 이 이슈에서 바꾸지 않는다(`proxy_cache_use_stale`은 `updating`만 — 오류 때 오래된 값을 주지 않는다) |

### 판단이 갈린 곳

1. **키는 `$uri`.** 기본 키(`$scheme$proxy_host$request_uri`)는 query를 포함해, `?x=랜덤`을 붙이면 매번 캐시를 지나쳐 Spring·DB로 간다. 채널 조회는 query를 쓰지 않으므로 키에서 뺀다.
2. **`proxy_cache_lock` + `use_stale updating`.** 1초마다 만료될 때 인기 채널에 몰린 요청이 한꺼번에 Spring으로 가는 것(캐시 쇄도)을 막는다.
3. **200만 1초.** 404를 담으면 막 생긴 채널이 1초 동안 없다고 보이고, 없는 id를 돌며 두드리는 요청은 어차피 키가 매번 달라 캐시로 막을 수 없다.
4. **`X-Cache-Status`를 내보낸다.** 측정·확인에 필요하고, 캐시 여부 말고는 드러나는 정보가 없다.
5. **1초 늦게 반영되는 것을 받아들인다.** 제목을 바꾸고 1초 안에 채널 페이지를 다시 열면 이전 제목이 보일 수 있다(#13 AC 3 "다시 조회할 때 새 제목"은 1초 뒤부터 성립한다). 시청 중 종료 감지는 5초 폴링이라 최대 1초 더 늦는다.

### 이 이슈에서 만들지 않는 것

- Redis·Spring 캐시 — 위 "캐시 위치"
- nginx CPU 한도(`cpus: 0.25`)·업스트림 keepalive 조정 — 기준선에서 nginx가 먼저 포화했지만(아래), 한 번에 한 가지만 바꾼다(PLATFORM 10절). 캐시 뒤 측정을 보고 따로 정한다
- 채널 페이지·스튜디오 화면 변경 — 없음

---

## 기준선 (캐시 전, 2026-11-21)

- 대상 커밋 `f24b7e3`(main, #12 머지 직후), 로컬 compose(api 2대 각 1 CPU·512MB, oracle 1 CPU·2GB, nginx 0.25 CPU·64MB, redis 0.25 CPU), 호스트 12코어, k6 2.1.0(같은 호스트)
- 데이터: 채널 5개(1·21·41·42·43), 방송 기록 6행. 채널 조회는 기본 키 한 행 + 진행 중 방송 유니크 인덱스 EXISTS라 표 크기에 거의 기대지 않는다. 모두 오프라인 상태로 쟀다(같은 SQL을 탄다 — 송출 없이 진행 중 방송 행을 넣으면 1분 대조가 끝내 버린다)
- 원시 결과: `docs/perf/issue-29/baseline/{target,steps}-summary.json`, 자원: `{target,steps}-stats.csv`

**목표 부하(200 req/s = 시청자 1,000명):** 12,001건, p50 1.8ms · p95 3.0ms · p99 4.0ms · max 17.9ms, 오류 0%, 합격 ✅

**계단:**

| req/s(시청자) | 처리량 | p50 | p95 | p99 | max | 오류 | nginx CPU | api-1 / api-2 | oracle |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 50 (250) | 50.0 | 2.4 | 3.2 | 4.3 | 7.8 | 0% | 2% | 4% / 4% | 4% |
| 100 (500) | 100.0 | 2.1 | 3.0 | 3.7 | 7.7 | 0% | 3% | 5% / 6% | 5% |
| 200 (1,000) | 200.0 | 2.1 | 2.7 | 3.6 | 9.5 | 0% | 5% | 10% / 10% | 9% |
| 400 (2,000) | 400.0 | 1.6 | 2.6 | 3.5 | 13.2 | 0% | 10% | 19% / 21% | 14% |
| 800 (4,000) | 800.0 | 1.6 | 2.4 | 3.9 | 19.1 | 0% | 17% | 33% / 35% | 20% |
| 1600 (8,000) | 1600.0 | **60.5** | **522.3** | **1223.4** | 1938.4 | 0% | **25%** | 52% / 57% | 35% |

(지연은 ms. CPU는 `docker stats` 값으로 코어 하나 = 100%, 단계 30초 평균)

**포화 지점: 800~1600 req/s 사이.** 1600에서 처리량은 목표를 냈지만 지연이 수백 배로 늘었다. 그때 **nginx가 CPU 한도(`cpus: 0.25` = 25%)에 닿았다.** api·oracle은 아직 여유가 있다. 즉 지금 구조에서 먼저 막히는 곳은 Spring·DB가 아니라 nginx다. 캐시가 nginx의 업스트림 연결·응답 중계 일을 줄여 포화 지점을 옮기는지, 아니면 nginx 자체 한도에서 그대로 막히는지를 캐시 뒤 같은 조건으로 확인한다.

---

## 테스트 시나리오

### 정상

- [ ] [정상] `nginx channel cache` — should answer a second request for the same channel within 1 second without asking Spring again
- [ ] [정상] `nginx channel cache` — should ask Spring again when the same channel is requested after 1 second

### 경계

- [ ] [경계] `nginx channel cache` — should ask Spring once when 20 viewers request the same uncached channel at the same time
- [ ] [경계] `nginx channel cache` — should keep a separate cache for each channel
- [ ] [경계] `nginx channel cache` — should serve the cached answer when the same channel is requested with a different query string

### 예외

- [ ] [예외] `nginx channel cache` — should pass every request for the studio channel to Spring
- [ ] [예외] `nginx channel cache` — should pass every request for the logged-in member to Spring
- [ ] [예외] `nginx channel cache` — should pass every channel update to Spring
- [ ] [예외] `nginx channel cache` — should not cache an error response for a channel

### 수동 확인·측정 (로컬 compose + k6 — 시나리오 수에 넣지 않는다)

- `curl -i`로 같은 채널을 연달아 두 번 → `X-Cache-Status: MISS` → `HIT`, 1초 뒤 `EXPIRED`. 브라우저로 가는 `Cache-Control: no-store`는 그대로
- 캐시 뒤 같은 조건(커밋만 다름)으로 `MODE=target`·`MODE=steps`를 다시 재 `docs/perf/issue-29/after/`에 남기고, 기준선과 같은 표로 비교한다
- 측정 중 nginx 접근 로그에서 Spring까지 간 채널 조회 수(`upstream=`이 있는 줄)를 세어 캐시 적중률을 남긴다

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 1초 안에 다시 오면 캐시에서 답한다 | `[정상] nginx channel cache — should answer a second request for the same channel within 1 second without asking Spring again`<br>`[경계] nginx channel cache — should keep a separate cache for each channel` |
| 2. 1초가 지나면 Spring에 다시 묻는다 | `[정상] nginx channel cache — should ask Spring again when the same channel is requested after 1 second` |
| 3. 빈 캐시에 동시 요청 → Spring에 한 번 | `[경계] nginx channel cache — should ask Spring once when 20 viewers request the same uncached channel at the same time` |
| 4. query만 다르면 같은 캐시 | `[경계] nginx channel cache — should serve the cached answer when the same channel is requested with a different query string` |
| 5. 다른 API·쓰기·오류 응답은 캐시하지 않는다 | `[예외] nginx channel cache — should pass every request for the studio channel to Spring`<br>`[예외] nginx channel cache — should pass every request for the logged-in member to Spring`<br>`[예외] nginx channel cache — should pass every channel update to Spring`<br>`[예외] nginx channel cache — should not cache an error response for a channel` |
| 6. 캐시 전후 측정 기록, 캐시 뒤 목표 부하에서 합격 | 기준선(위) + 수동 확인·측정 |

**커버리지:** AC 6개 / 시나리오 9개 / 미커버 0개 (AC 6은 측정 기록)
