# 이슈 #29 — 시청자가 몰려도 채널 조회는 nginx에서 1초 캐시로 답한다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/29
> PRD: `docs/result/prd/channel.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

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

- [x] [정상] `nginx channel cache` — should answer a second request for the same channel within 1 second without asking Spring again
- [x] [정상] `nginx channel cache` — should ask Spring again when the same channel is requested after 1 second
- [ ] [정상] `nginx channel cache` — should return a fresh answer from Spring when the same channel is requested after 1 second

### 경계

- [x] [경계] `nginx channel cache` — should ask Spring once when 20 viewers request the same uncached channel at the same time
- [x] [경계] `nginx channel cache` — should keep a separate cache for each channel
- [x] [경계] `nginx channel cache` — should serve the cached answer when the same channel is requested with a different query string
- [ ] [경계] `nginx channel cache` — should still answer from cache when the same channel is requested 900ms later
- [ ] [경계] `nginx channel cache` — should answer every concurrent viewer with 200 when the uncached channel is requested at the same time

### 예외

- [x] [예외] `nginx channel cache` — should pass every request for the studio channel to Spring
- [x] [예외] `nginx channel cache` — should pass every request for the logged-in member to Spring
- [x] [예외] `nginx channel cache` — should pass every channel update to Spring
- [x] [예외] `nginx channel cache` — should not cache an error response for a channel
- [ ] [예외] `nginx channel cache` — should not cache a server error response for a channel

### 수동 확인·측정 (로컬 compose + k6 — 시나리오 수에 넣지 않는다)

- `curl -i`로 같은 채널을 연달아 두 번 → `X-Cache-Status: MISS` → `HIT`, 1초 뒤 `EXPIRED`. 브라우저로 가는 `Cache-Control: no-store`는 그대로
- 캐시 뒤 같은 조건(커밋만 다름)으로 `MODE=target`·`MODE=steps`를 다시 재 `docs/perf/issue-29/after/`에 남기고, 기준선과 같은 표로 비교한다
- 측정 중 nginx 접근 로그에서 Spring까지 간 채널 조회 수(`upstream=`이 있는 줄)를 세어 캐시 적중률을 남긴다
- (AC 검증 갭 처리, 수동) `perf/channel-poll.js` — should record the step where throughput stops rising when the steps continue past 1600 req/s

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 1초 안에 다시 오면 캐시에서 답한다 | `[정상] nginx channel cache — should answer a second request for the same channel within 1 second without asking Spring again`<br>`[경계] nginx channel cache — should keep a separate cache for each channel`<br>`[경계] nginx channel cache — should still answer from cache when the same channel is requested 900ms later` (갭 처리) |
| 2. 1초가 지나면 Spring에 다시 묻는다 | `[정상] nginx channel cache — should ask Spring again when the same channel is requested after 1 second`<br>`[정상] nginx channel cache — should return a fresh answer from Spring when the same channel is requested after 1 second` (갭 처리) |
| 3. 빈 캐시에 동시 요청 → Spring에 한 번 | `[경계] nginx channel cache — should ask Spring once when 20 viewers request the same uncached channel at the same time`<br>`[경계] nginx channel cache — should answer every concurrent viewer with 200 when the uncached channel is requested at the same time` (갭 처리) |
| 4. query만 다르면 같은 캐시 | `[경계] nginx channel cache — should serve the cached answer when the same channel is requested with a different query string` |
| 5. 다른 API·쓰기·오류 응답은 캐시하지 않는다 | `[예외] nginx channel cache — should pass every request for the studio channel to Spring`<br>`[예외] nginx channel cache — should pass every request for the logged-in member to Spring`<br>`[예외] nginx channel cache — should pass every channel update to Spring`<br>`[예외] nginx channel cache — should not cache an error response for a channel`<br>`[예외] nginx channel cache — should not cache a server error response for a channel` (갭 처리) |
| 6. 캐시 전후 측정 기록, 캐시 뒤 목표 부하에서 합격 | 기준선(위) + 수동 확인·측정 + 갭 처리 수동 1개(1600 req/s 너머 포화 지점) |

**커버리지:** AC 6개 / 시나리오 13개(AC 검증 갭 처리 4개 포함) / 미커버 0개 (AC 6은 측정 기록)

---

## 수동 확인·측정 결과 (2026-11-21, Green 뒤, HEAD 931dafe)

`docker compose up --build -d nginx`로 새 설정을 담았다(api-1·2도 다시 떴다). 기준선과 같은 채널 5개·같은 스크립트·같은 자원 제한, 커밋만 다르다.

**curl 확인**

| 요청 | 결과 |
| --- | --- |
| `GET /api/channels/21` 연달아 두 번 | `200 MISS` → `200 HIT`. 둘 다 `Cache-Control: no-cache, no-store…`가 브라우저로 그대로 간다 ✅ |
| 2.1초 뒤 같은 요청 | `EXPIRED`(Spring에 다시 물음) ✅ |
| `GET /api/channels/21?x=랜덤` | `HIT`(query로 우회되지 않음) ✅ |
| `GET /api/channels/999999` 두 번 | `404 MISS` → `404 MISS`(오류는 담지 않음) ✅ |
| `GET /api/members/me` | `401`, `X-Cache-Status` 없음(캐시 location 밖) ✅ |

**목표 부하(200 req/s = 시청자 1,000명):** 12,001건, p50 0.5ms · p95 1.5ms · p99 5.8ms · max 63.7ms, 오류 0%, 합격 ✅. 워밍업 포함 채널 조회 13,502건 중 **Spring까지 간 것 229건(1.7%)**(nginx 접근 로그의 `upstream=`이 `-`가 아닌 줄)

**계단 (기준선 → 캐시 뒤)**

| req/s(시청자) | p50 | p95 | p99 | max | 오류 | nginx CPU | api-1 / api-2 | oracle |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 50 (250) | 2.4 → 1.0 | 3.2 → 4.9 | 4.3 → 6.9 | 7.8 → 8.1 | 0% | 2 → 1% | 4/4 → 1/1% | 4 → 2% |
| 100 (500) | 2.1 → 0.6 | 3.0 → 1.6 | 3.7 → 5.6 | 7.7 → 13.2 | 0% | 3 → 2% | 5/6 → 1/1% | 5 → 3% |
| 200 (1,000) | 2.1 → 0.5 | 2.7 → 1.4 | 3.6 → 5.1 | 9.5 → 13.2 | 0% | 5 → 5% | 10/10 → 2/1% | 9 → 2% |
| 400 (2,000) | 1.6 → 0.5 | 2.6 → 1.1 | 3.5 → 2.2 | 13.2 → 8.1 | 0% | 10 → 7% | 19/21 → 1/1% | 14 → 2% |
| 800 (4,000) | 1.6 → 0.5 | 2.4 → 1.0 | 3.9 → 1.8 | 19.1 → 10.2 | 0% | 17 → 12% | 33/35 → 1/1% | 20 → 3% |
| 1600 (8,000) | **60.5 → 0.5** | **522.3 → 30.0** | **1223.4 → 75.2** | 1938.4 → 298.9 | 0% | **25 → 24%** | 52/57 → 1/1% | 35 → 3% |

(지연은 ms, CPU는 코어 하나 = 100%·단계 30초 평균. 원시 결과 `docs/perf/issue-29/after/`.) 계단 전체 채널 조회 96,004건 중 **Spring까지 간 것 529건(0.55%)**.

**읽은 것**

- **Spring·DB 부하는 시청자 수와 거의 무관해졌다.** 1600 req/s에서 api CPU 55% → 1%, oracle 35% → 3%. Spring까지 가는 채널 조회는 채널 수 × (1~2초에 한 번)으로 묶인다.
- **포화 지점에서 지연이 크게 줄었다.** 1600 req/s에서 p95 522ms → 30ms, p99 1223ms → 75ms.
- **그래도 천장은 nginx다.** 1600 req/s에서 nginx CPU가 여전히 한도(`cpus: 0.25`)에 닿았고, p95가 800 req/s의 1.0ms에서 30ms로 뛰었다. 포화 지점은 여전히 800~1600 req/s 사이이고, 이제 막히는 곳은 nginx 하나뿐이다. 이 위로 올리려면 nginx CPU 한도나 업스트림 keepalive를 봐야 한다 — 이번 이슈 밖(한 번에 한 가지)이라 따로 정한다.
- **낮은 부하에서 p95·p99가 조금 늘었다(50 req/s p95 3.2 → 4.9ms).** 첫 요청이 빈 캐시에서 잠금을 기다리기 때문으로 본다. nginx의 `proxy_cache_lock`은 기다리는 요청을 응답이 오는 즉시 깨우지 않고 500ms 간격으로 확인한다. 워밍업의 max 500.2ms가 그 흔적이다. 한 번 담긴 채널은 만료 때 `use_stale updating`으로 직전 값을 주므로 기다리지 않는다. 다시 차가워지는 것은 10초(`inactive=10s`) 동안 아무도 묻지 않은 채널뿐이다. 방송 중 채널은 5초 폴링이라 그 전에 다시 물린다.
- **목표 부하 합격 기준(p95 ≤ 200ms, 오류율 < 0.1%)은 기준선·캐시 뒤 모두 넘는다.** 이 이슈의 효과는 합격 여부가 아니라 Spring·DB 부하 제거와 포화 구간 지연 감소다.

**Green 뒤 변이 확인:** Red에서 지금 설정(캐시 없음)으로도 통과한 6개를 캐시 설정의 변이로 확인했다. `build.gradle.kts`에 `nginx.conf`를 테스트 입력으로 알린 뒤 저장소에서 하나씩 넣고 `NginxChannelCacheTest`를 돌려 `git checkout --`으로 되돌렸다.

| 변이 | 실패한 테스트 |
| --- | --- |
| `proxy_cache_valid 200 60s` | `should ask Spring again when the same channel is requested after 1 second` |
| `proxy_cache_key channel`(상수) | `should keep a separate cache for each channel` 외 3개 |
| 캐시 location을 `~ ^/api/`로 | `should pass every request for the studio channel to Spring`, `…logged-in member…` |
| 캐시 location에 `limit_except GET { deny all; }` | `should pass every channel update to Spring` |
| `proxy_cache_valid any 1s` | `should not cache an error response for a channel` |
| `proxy_cache_lock on;` 삭제 | `should ask Spring once when 20 viewers request the same uncached channel at the same time`(20번 감) — 처음에는 가짜 Spring이 너무 빨라 이 변이가 살아남았다. 가짜 Spring의 채널 9를 `limit_rate`로 약 0.5초 걸리게 바꾼 뒤 잡혔다(커밋 931dafe) |

덧붙여 처음 변이에서 Gradle이 "최신"이라며 테스트를 돌리지 않았다. 테스트가 읽는 `nginx.conf`를 Gradle이 몰랐기 때문이다(`NginxInternalPathTest`도 같다). `backend/build.gradle.kts`의 테스트 입력에 넣었다(커밋 e9c4fe8).

---

## AC 검증

> `harness:ac-verifier` (2026-11-21, HEAD f2055f4). 변이 최대 3번·20분·스크래치 복사본·클래스 단위 테스트·docker compose·k6 조작 금지로 띄웠다. 아래는 검증자 출력 원문이다(제목 수준만 한 단계 낮췄다). 구현자는 맨 아래 `### 구현자 이견`에만 쓴다.

### 이슈 #29 AC 검증 결과 (브랜치 feat/channel/issue-29, HEAD f2055f4)

AC 6개를 확인했습니다. ✅ 3, ⚠️ 3, ❌ 0입니다. 구현(`infra/nginx/nginx.conf:23-25, 56-75`)은 AC 1~5를 모두 지킵니다. ⚠️ 3개 중 AC 2와 AC 5는 테스트가 AC를 일부만 검증하는 경우이고, AC 6은 PLATFORM 10절의 포화 지점 정의를 엄격하게 적용한 판정입니다.

- 변이를 넣지 않은 스크래치 복사본에서 `NginxChannelCacheTest`는 9개 모두 통과했습니다.
- 변이는 3번 돌렸습니다. 1번은 잡혔고 2번은 살아남았습니다.
- 끝난 뒤 `git -C C:/live-streaming-project status --short`의 출력은 비어 있었습니다. 원본 저장소는 바뀌지 않았습니다.

#### AC별 판정

| AC | 판정 | 근거 테스트 (@DisplayName, 파일:줄) | 구현 | 판단 이유 |
|---|---|---|---|---|
| 1. 1초 안에 다시 오면 캐시에서 답한다 | ✅ | `should answer a second request for the same channel within 1 second without asking Spring again` (NginxChannelCacheTest.java:123-130). 보조: `should keep a separate cache for each channel` (:169-177) | nginx.conf:64,66,68,73 | 연달아 두 번 요청하면 가짜 Spring까지 1번만 간다는 것을 단언합니다. nginx는 캐시 시각을 초 단위로 다룹니다. 그래서 `valid 1s`면 항목이 1~2초 남고, 1초 미만에 다시 오면 항상 HIT입니다. 상수 키 변이를 잡은 기록이 문서에 있습니다(issue-29.md:207). 약점: 두 번째 응답의 상태·본문·X-Cache-Status는 단언하지 않습니다. |
| 2. 1초가 지나면 Spring에 다시 물어 새 값을 준다 | ⚠️ | `should ask Spring again when the same channel is requested after 1 second` (:132-142) | nginx.conf:68,71 | Spring을 2번 호출한 것만 셉니다. AC의 "새 값(제목·방송 상태)을 준다"는 단언하지 않습니다. 변이 1(`proxy_cache_background_update on`)이 살아남았습니다. 이 변이에서는 만료 뒤 요청자가 직전 값을 받고, 백그라운드에서만 Spring을 호출합니다. 지금 설정은 background_update가 꺼져 있어서 만료 뒤 요청이 직접 새 값을 받아 옵니다. 구현은 맞고, 테스트가 이 동작을 지키지 못합니다. 경계: 2.1초 뒤만 봅니다. nginx 시각이 초 단위라 1~2초 사이는 결과가 정해지지 않으므로, 2.1초를 고른 것 자체는 타당합니다. |
| 3. 빈 캐시에 동시 요청이 와도 Spring에는 한 번만 간다 | ✅ | `should ask Spring once when 20 viewers request the same uncached channel at the same time` (:144-167) | nginx.conf:70 (`proxy_cache_lock on`) | 변이 2(lock 삭제)를 넣으면 `expected: 1L but was: 20L`로 실패합니다. 문서의 주장(issue-29.md:211)을 독립적으로 재현했습니다. 약점: `answer.get()`은 예외만 다시 던지고 20개 응답의 상태(200)는 단언하지 않습니다(:159-161). |
| 4. query만 다르면 같은 캐시로 답한다 | ✅ | `should serve the cached answer when the same channel is requested with a different query string` (:179-186) | nginx.conf:66 (`proxy_cache_key $uri`) | `?x=1`과 `?x=2`로 요청하면 Spring을 1번만 호출합니다. `springCalls`가 query가 붙은 줄도 셉니다(:115). 키를 `$request_uri`나 기본 키로 되돌리면 2번이 되어 실패합니다(테스트 구조로 판단했고 돌려 보지는 않았습니다). 상수 키 변이를 잡은 기록이 문서에 있습니다. |
| 5. 다른 API·쓰기·오류 응답은 캐시하지 않는다 | ⚠️ | `should pass every request for the studio channel to Spring` (:188-195), `…logged-in member…` (:197-204), `should pass every channel update to Spring` (:206-213), `should not cache an error response for a channel` (:215-222) | nginx.conf:58 (정규식 location), :68 (`valid 200`만), proxy_cache_methods 기본값 | 다른 API와 PUT은 확인합니다. 오류 응답은 404만 확인합니다. 변이 3(`proxy_cache_valid 200 500 502 503 504 1s`)이 살아남았습니다. 가짜 Spring은 5xx를 내지 않습니다. grep 결과 테스트 파일에서 `50x`가 걸린 줄은 `repeat(1500)` 한 줄뿐입니다. 시나리오 문서의 에러 표(issue-29.md:78)는 "404·5xx는 담지 않는다"고 적었는데 5xx는 테스트되지 않습니다. 구현은 200만 담으므로 맞습니다. |
| 6. 캐시 전후 측정 기록과 목표 부하 합격 (PLATFORM 10절 기준) | ⚠️ | 테스트가 아니라 기록으로 판단했습니다. issue-29.md:97-118(기준선), :165-200(캐시 뒤), docs/perf/issue-29/{baseline,after}/ | perf/channel-poll.js:54-63 (thresholds p95≤200, rate<0.001), perf/sample-stats.sh | 기록 상태는 아래 표에 정리했습니다. AC 문구의 요구는 모두 충족합니다. PLATFORM의 포화 지점 정의는 엄격하게 보면 충족하지 못합니다. |

##### AC 6 세부 (PLATFORM 10절 항목별)

| 항목 | 상태 | 확인한 것 |
|---|---|---|
| 기준선을 먼저 쟀는가 | 충족 | 기준선은 f24b7e3(= origin/main, `git diff f24b7e3 origin/main` 비어 있음)에서 쟀습니다. 커밋 578348b가 구현 커밋(7bff2e9)보다 먼저입니다. |
| 합격 기준을 측정 전에 정했는가 | 충족 | 이슈 본문에 있고, k6 thresholds로 박혀 있습니다(channel-poll.js:60-61). |
| 한 번에 한 가지만 바꿨는가 | 충족 | nginx.conf는 7bff2e9 이후 바뀌지 않았습니다. 931dafe와 HEAD 사이에는 docs/perf와 문서만 바뀌었습니다. |
| 시나리오 | 충족 | perf/channel-poll.js (target·steps 모드) |
| 도구 버전 | 충족 | k6 2.1.0. 문서(issue-29.md:65, 99)에만 있고 원시 JSON에는 없습니다. |
| 대상 커밋 SHA | 충족 | 기준선 f24b7e3, 캐시 뒤 931dafe. 931dafe 커밋 시각(08:01 UTC)이 캐시 뒤 측정 CSV(08:03~08:08 UTC)보다 앞서 일관됩니다. |
| 데이터 규모 | 충족(근거 있음) | 채널 5개, 방송 6행. "오프라인이어도 같은 SQL"이라는 주장은 ChannelMapper.xml:13-18에서 확인했습니다(live는 EXISTS 서브쿼리라 상태와 관계없이 같은 SQL입니다). |
| 원시 결과 | 충족 | summary JSON 4개. node로 단계별 수치를 다시 뽑았고, 문서 표와 일치합니다. 예: 캐시 뒤 1600단계 p95 30.0, p99 75.2, max 298.9, 오류 0, dropped 0. |
| 자원 지표 | 충족(대시보드 대신 CSV) | docker stats CSV. 마지막 단계 nginx CPU는 기준선 24~27%, 캐시 뒤 22~26%로 문서와 맞습니다. |
| 목표 부하 합격 | 충족 | after/target-summary.json: 12,001건, p95 1.51ms(`p(95)<=200` ok), failed rate 0(`rate<0.001` ok), dropped 0 |
| 포화 지점 | 약함 | 두 측정 모두 1600단계에서도 처리량이 요청한 양(1600.0 req/s)을 냈고 dropped_iterations가 0입니다. PLATFORM의 정의("처리량은 늘지 않고 지연만 증가")에 맞는 지점에는 닿지 않았습니다. "800~1600 사이"는 지연이 꺾이는 지점과 nginx CPU 한도로 추정한 값입니다. 이슈가 미리 정한 계단(최대 1600)은 그대로 지켰습니다. |
| 캐시 적중률 | 원시 근거 없음 | 229/13,502건, 529/96,004건이라는 숫자는 문서에만 있습니다. 근거인 nginx 접근 로그 집계는 저장소에 없습니다. |

#### 변이 결과

스크래치 복사본에서 실행했습니다. 경로는 `C:/Users/USER/AppData/Local/Temp/claude/1cbb40fc7d76b7b0/e5a53947-02cd-44f6-934c-3e8edfbbefcf/scratchpad/ac29`입니다. 매번 `nginx.conf.orig`로 되돌린 뒤 sed로 변이 하나만 넣었습니다. XML 결과의 timestamp로 테스트가 실제로 다시 돌았음을 확인했습니다.

| # | 변이 (nginx.conf) | 겨냥한 AC | 결과 | 실패한 테스트 |
|---|---|---|---|---|
| 0 | 없음 (기준) | - | 9/9 통과 | - |
| 1 | `proxy_cache_use_stale updating;` 뒤에 `proxy_cache_background_update on;` 추가 | AC 2 (새 값) | **살아남음** (9/9 통과) | 없음 |
| 2 | `proxy_cache_lock on;` 삭제 | AC 3 | 잡힘 | `should ask Spring once when 20 viewers …` (expected 1L, but was 20L) |
| 3 | `proxy_cache_valid 200 1s;` → `proxy_cache_valid 200 500 502 503 504 1s;` | AC 5 (5xx) | **살아남음** (9/9 통과) | 없음 |

#### 빈틈을 메울 시나리오 제안

```
- [ ] [정상] `nginx channel cache` — should return a fresh answer from Spring when the same channel is requested after 1 second
      (단언: 두 번째 응답 본문 != 첫 응답 본문. 가짜 Spring의 $request_id로 판별 → 변이 1을 잡는다)
- [ ] [예외] `nginx channel cache` — should not cache a server error response for a channel
      (가짜 Spring에 `location = /api/channels/500 { return 500 ...; }`를 두고 두 번 요청 → Spring 2회 → 변이 3을 잡는다)
- [ ] [경계] `nginx channel cache` — should still answer from cache when the same channel is requested 900ms later
      (nginx 시각이 초 단위라 900ms 뒤는 항상 HIT이므로 결과가 흔들리지 않는다. AC 1의 "1초 안" 경계)
- [ ] [경계] `nginx channel cache` — should answer every concurrent viewer with 200 when the uncached channel is requested at the same time
      (AC 3 테스트에서 20개 응답의 상태를 단언한다)
- [ ] [수동] `perf/channel-poll.js` — should record the step where throughput stops rising when the steps continue past 1600 req/s
      (PLATFORM 10절 정의대로의 포화 지점. 이슈 합격 기준은 바꾸지 않고 기록만 더한다)
```

#### 가짜 테스트와 약한 단언

비동기 await 누락, 인자 없는 예외 단언, 단언 없는 테스트, 목 반환값을 그대로 단언하는 경우는 없었습니다. 약한 부분은 다음과 같습니다.

1. **NginxChannelCacheTest.java:132-142 (AC 2).** Spring 호출 수만 셉니다. AC의 "새 값"을 지키지 못한다는 것을 변이 1로 확인했습니다. 두 번째 응답 본문이 첫 응답과 다르다는 단언을 더하면 됩니다.
2. **:110-117 `springCalls`의 `Thread.sleep(300)`.** `isEqualTo(1)`을 단언하는 테스트(:129, :166, :185)에서는 로그가 늦게 넘어오면 실제로는 2번 갔는데 1로 세어 통과할 수 있습니다. 응답 헤더 `X-Cache-Status`(HIT/MISS)도 함께 단언하거나, 기대 횟수에 닿을 때까지 기다린 뒤 일정 시간 더 늘지 않는지 보면 됩니다.
3. **:123-130, :179-186.** 두 번째 응답의 상태 코드와 본문을 확인하지 않습니다. 상태 200과, 첫 응답과 같은 본문이라는 단언을 더하면 됩니다.
4. **:159-161.** `answer.get()`은 예외만 다시 던지고, 응답 20개의 상태는 단언하지 않습니다.

#### AC 범위 밖 참고 (판정에 넣지 않음, security-review 몫)

- 캐시 키가 `$uri`이고 `ChannelController.java:23`이 `@PathVariable long channelId`입니다. 그래서 `/api/channels/021`, `/api/channels/0021` 같은 앞자리 0 변형은 키가 매번 달라져 캐시를 지나 Spring·DB로 갑니다. 그런데 Spring은 이 값을 같은 채널 21로 해석합니다. AC 4는 query만 다루므로 이번 판정에는 넣지 않았습니다. 돌려서 확인하지는 않았고, 설정과 코드를 읽고 판단한 것입니다.

#### 실행한 명령

1. `cat .claude/harness.json; git log origin/main..HEAD; git diff --stat origin/main...HEAD` → 설정 확인, 커밋 7개, 파일 16개 변경
2. `gh issue view 29` → AC 6개와 합격 기준 확인
3. Read `docs/result/scenarios/issue-29.md` → 시나리오 9개, AC 대조표, 측정 결과, 변이 기록 확인
4. `git diff origin/main...HEAD -- nginx.conf build.gradle.kts; cat -n nginx.conf` → 캐시 설정 위치 확인(:23-25, :56-75)
5. Read `NginxChannelCacheTest.java` → 테스트 9개와 단언 확인
6. `grep -n "^## " PLATFORM.md`, Read PLATFORM.md:218-233 → 10절 요구사항 확인
7. `cat -n perf/channel-poll.js perf/sample-stats.sh; ls -la docs/perf/issue-29/*` → thresholds와 결과 파일 8개 확인
8. `grep … after/target-summary.json` → 목표 부하 12,001건, p95 1.51ms, failed 0, dropped 0, threshold ok. JSON에 버전·SHA는 없음
9. grep으로 steps-summary에서 단계별 수치 추출 → 출력이 뒤섞여 node로 다시 뽑음
10. `node -e …` (baseline·after steps-summary) → 단계별 reqs, p50/p95/p99/max, 오류, dropped가 문서 표와 일치
11. `grep nginx …/steps-stats.csv | tail -18` → 마지막 단계 nginx CPU: 기준선 24~27%, 캐시 뒤 22~26%
12. `git log -1 f24b7e3; merge-base --is-ancestor; git diff --stat 931dafe HEAD; git diff --quiet 7bff2e9 HEAD -- nginx.conf` → 측정 대상 SHA가 일관됨, nginx.conf는 7bff2e9 이후 그대로
13. `git archive HEAD | tar -x -C …/scratchpad/ac29` → 스크래치 복사본 생성
14. `./gradlew test --tests "NginxChannelCacheTest"` (변이 없음, 스크래치) → 9/9 통과
15. 변이 1(sed로 background_update 추가) 후 같은 테스트 → 9/9 통과(살아남음). timestamp 08:12:01Z로 다시 돈 것 확인
16. 변이 2(sed로 lock 삭제) 후 같은 테스트 → 1개 실패(expected 1L, but was 20L)
17. 변이 3(sed로 valid에 5xx 추가) 후 같은 테스트 → 9/9 통과(살아남음)
18. `git -C C:/live-streaming-project status --short` → **출력 없음(깨끗함)**. 같은 명령에서 테스트 파일을 `50[0-9]|X-Cache-Status|body()`로 grep → 5xx와 X-Cache-Status 단언 없음, body 비교는 :172-174뿐
19. `ls mapper/channel; grep findById ChannelMapper.xml; sed -n 1,18p ChannelMapper.xml` → live는 EXISTS 서브쿼리라 상태와 관계없이 같은 SQL
20. `sed -n 20,30p ChannelController.java` → `@PathVariable long channelId`

변이 실행은 3번이었고, 테스트 실행 구간은 약 2분(17:11:25~17:12:59)이었습니다. docker compose와 k6는 실행하지 않았습니다. 스크래치 폴더 `C:/Users/USER/AppData/Local/Temp/claude/1cbb40fc7d76b7b0/e5a53947-02cd-44f6-934c-3e8edfbbefcf/scratchpad/ac29`는 지우지 않고 남겨 두었습니다. 마지막 변이(변이 3)가 적용된 상태입니다.

### 구현자 이견

- 이견 없음. ⚠️ 3개 모두 맞는 지적이라 메운다(아래 갭 처리).
- 약한 단언 2(`springCalls`의 고정 300ms 대기)는 제안 목록 밖이지만 `isEqualTo(1)` 테스트들이 기대는 것이라 같이 고친다 — 로그 줄 수가 더 늘지 않을 때까지 기다린다.
- 앞자리 0 변형(`/api/channels/021`)은 보안 점검에서 다룬다.

### 갭 처리

사용자 승인("승인, 진행") 범위에서 메움. 검증자 제안 5개 중 시나리오 4개를 문구 그대로 `## 테스트 시나리오`와 AC 대조표에 더하고 Red → Green으로 메웠다. `[수동]` 1개는 체크박스로 두면 `check-scenarios.mjs`가 테스트로 세므로 수동 확인 목록에 일반 항목으로 옮겼다.
