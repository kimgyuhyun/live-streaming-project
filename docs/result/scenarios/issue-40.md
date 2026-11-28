# 이슈 #40 — 한 곳(IP)에서 시청을 많이 열어도 5개까지만 센다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/40
> PRD: `docs/result/prd/viewer.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

---

## 시그니처

### 관련 결정

- **이슈 본문 "이 이슈에서 정한 것"** — 아래 값은 전부 거기서 그대로 옮겼다.
  - nginx `/hls/`가 `X-Forwarded-For`(앞단 프록시가 원래 요청한 쪽 주소를 적어 넘기는 헤더)를 넘긴다. 값은 앞단 `real_ip` 설정으로 고친 진짜 주소(`$remote_addr`).
  - MediaMTX `hlsTrustedProxies`(MediaMTX가 이 주소에서 온 `X-Forwarded-For`를 시청자 주소로 믿게 하는 설정)에 nginx가 있는 도커 대역을 넣는다.
  - 집계: (IP, 경로)마다 최대 5세션. 한도는 설정값 `app.viewer.max-sessions-per-ip`, 기본 5.
  - 설정 뒤 진짜 주소가 보이는지를 먼저 확인한다 → 아래 "수동 확인"에 기록했다(보인다).
- **viewer PRD V1** — 세션에서 `remoteAddr`·`path`만 읽는다. 주소는 저장하지 않고 로그에도 남기지 않는다(AC 4). IP는 집계 중 메모리에서만 쓴다(PRD 하지 않을 것 "시청 세션 주소 저장·분석").
- **#39 집계 위에 더한다** — `ViewerCounter.aggregate`가 채널별 세션 수를 셀 때 (채널, IP)마다 한도를 넘는 세션을 버린다. 판(`ViewerSnapshot`)·잠금·MediaMTX 클라이언트는 바꾸지 않는다.
- **기존 패턴** — 설정값은 생성자의 `@Value("${app....}")`로 받는다(`MediaMtxClient`·`StreamKeyService`와 같다). 값은 `application.yml`의 `app:` 아래에 둔다.

### 타입

```java
// ── com.livestream.broadcast ───────────────────────────

@Component
class ViewerCounter {
    // 생성자에 한도를 더한다. application.yml: app.viewer.max-sessions-per-ip: 5
    ViewerCounter(MediaMtxClient mediaMtxClient, BroadcastMapper broadcastMapper, ViewerSnapshot viewerSnapshot,
                  @Value("${app.viewer.max-sessions-per-ip}") int maxSessionsPerIp);

    // (#39 동작 그대로) + 같은 경로에서 같은 IP의 세션은 maxSessionsPerIp개까지만 센다.
    // IP = remoteAddr에서 끝의 ":포트"를 뗀 것(같은 IP의 다른 포트는 같은 IP). IP는 저장·로그하지 않는다
    void aggregate();
}
```

```yaml
# infra/nginx/nginx.conf — location /hls/
proxy_set_header X-Forwarded-For $remote_addr;   # 시청자가 보낸 값을 이어 붙이지 않고 덮어쓴다

# infra/mediamtx/mediamtx.yml
hlsTrustedProxies: ['172.27.0.0/16']

# compose.yml — 기본 네트워크 대역을 고정한다(위 값과 같아야 한다)
networks:
  default:
    ipam:
      config:
        - subnet: 172.27.0.0/16
```

### 에러 케이스

| 상황 | 처리 | HTTP |
| --- | --- | --- |
| MediaMTX 세션 목록을 못 읽음(응답이 깨짐 포함) | #39 그대로 — 그 회차를 건너뛰고 실패만 로그(예외 이름만, 메시지·주소 없음) | — (주기 작업) |

새 에러 코드는 없다.

### 판단이 갈린 곳

- **도커 대역을 어떻게 정하나** — compose 기본 네트워크 대역은 도커가 그때그때 고른다(지금 로컬은 `172.27.0.0/16`, ADR이 적은 "nginx 내부 주소 `172.27.*`"와 같다). 대역이 바뀌면 `hlsTrustedProxies`가 nginx를 못 믿고 모든 세션이 nginx 주소 하나로 보여 채널마다 5명이 상한이 된다. 그래서 `compose.yml`에서 기본 네트워크 대역을 지금 값으로 고정했다.
  - 대안 1: `172.16.0.0/12`처럼 도커 기본 풀 전체를 믿기 — 고정이 필요 없지만, 서버의 도커 풀 설정에 따라 조용히 깨진다.
  - 대안 2: nginx↔MediaMTX 전용 네트워크 — 별칭·DNS가 얽혀 설정이 커진다.
  - 결과: 고정한 대역이 서버에 이미 있는 다른 네트워크와 겹치면 `docker compose up`이 대역 겹침 오류로 멈춘다(조용히 깨지지 않는다). 서버 배포 때 확인한다.
  - 로컬에서 대역 고정을 처음 적용하면 compose가 네트워크를 다시 만든다. 이때 컨테이너가 네트워크 별칭을 잃어 서로 이름을 못 찾았다 → `docker compose --profile demo up -d --force-recreate`로 다시 만들어 해결했다(볼륨은 그대로).
- **`X-Forwarded-For`를 덮어쓴다** — `$proxy_add_x_forwarded_for`(받은 값 뒤에 이어 붙임)를 쓰면 시청자가 꾸민 주소가 맨 앞에 남는다. MediaMTX가 어느 쪽을 읽든 꾸밀 여지를 없애려고 `$remote_addr`(real_ip가 고친 주소)로 덮어쓴다. `/api/`·`/ws`와 같은 방식이다.
- **로컬의 MediaMTX 직접 포트** — 로컬은 `127.0.0.1:8888`을 열어 두었고, 그 요청은 도커 게이트웨이(고정 대역 안)에서 온다. 그래서 로컬에서는 8888로 직접 보내면 `X-Forwarded-For`를 꾸밀 수 있다. 서버는 8888을 열지 않는다(`compose.prod.yml`의 `mediamtx.ports`는 1935뿐). 보안 점검에서 다시 본다.
- **IP를 뽑는 법** — MediaMTX `remoteAddr`는 `주소:포트`다(IPv6는 `[주소]:포트`). 마지막 `:` 앞을 IP로 쓴다. 브라우저 탭마다 포트가 다르므로 포트까지 쓰면 한도가 동작하지 않는다.
- **한도로 버린 세션 수를 로그에 남기나** — 남기지 않는다. 이슈에 없고, 회차 로그(`sessions={}`)는 MediaMTX가 준 세션 수 그대로 둔다.

### 이 이슈에서 만들지 않는 것

- 순위 집합·방송 목록(#41), 규모별 측정(#42)
- IP 한도에 걸린 시청자에게 알리기 — 시청은 막지 않는다. 세지 않을 뿐이다
- nginx 설정을 자동 테스트로 박기 — AC 3은 수동 확인 항목이다(이슈 본문)
- E2E(Playwright 설정이 없다)

---

## 테스트 시나리오

IP는 문서용 대역(`203.0.113.0/24`, `198.51.100.0/24`)을 쓴다.

### 정상

- [ ] [정상] `ViewerCounter.aggregate` — should write A=7 when channel A has 8 sessions from one IP and 2 sessions from two other IPs
- [ ] [정상] `ViewerCounter.aggregate` — should write A=5 and B=5 when one IP has 5 sessions on A and 5 on B
- [ ] [정상] `ViewerCounter.aggregate` — should write A=5 and B=3 when one IP has 8 sessions on A and 3 on B
- [ ] [정상] `ViewerCountIntegration` — should store A=7 in the current snapshot when live channel A has 8 sessions from one IP and 2 from two other IPs
- [ ] [정상] `ViewerCountIntegration` — should store A=5 and B=5 in the current snapshot when one IP has 5 sessions on live A and 5 on live B
- [ ] [정상] `ViewerCountIntegration` — should leave no viewer IP in any Redis key or value after aggregating
- [ ] [정상] `ViewerCountIntegration` — should leave no viewer IP in the log after aggregating

### 경계

- [ ] [경계] `ViewerCounter.aggregate` — should write A=5 when one IP has exactly 5 sessions on channel A
- [ ] [경계] `ViewerCounter.aggregate` — should write A=5 when one IP has 6 sessions on channel A
- [ ] [경계] `ViewerCounter.aggregate` — should count sessions from the same IP on different ports as one IP
- [ ] [경계] `ViewerCounter.aggregate` — should write A=2 when the limit is set to 2 and one IP has 3 sessions on channel A

### 예외

없음 — Red 기록 참고.

### Red 기록

- 만든 stub: `ViewerCounter` 생성자 인자 `maxSessionsPerIp`(`@Value("${app.viewer.max-sessions-per-ip}")`)와 `application.yml`의 `app.viewer.max-sessions-per-ip: 5`(없으면 Spring 컨텍스트가 뜨지 않아 모든 통합 테스트가 나쁜 실패를 낸다), 채널별 셈을 맡는 private `countByChannel`(본문 `UnsupportedOperationException`). `aggregate`의 채널별 셈 루프를 이 호출로 바꿨다.
- **시나리오 한 줄을 뺐다** — `[예외] ViewerCountIntegration — should leave no viewer IP in the log when the session list cannot be read`. 세션 목록을 못 읽으면 `aggregate`가 셈에 닿기 전에 #39의 실패 처리(예외 이름만 로그)로 끝난다. 이 이슈가 바꾸는 코드를 지나지 않아 구현 없이 통과하는 테스트가 된다. AC 4는 나머지 두 시나리오(Redis·로그)가 덮는다.
- **stub이 #39 테스트를 깨뜨린다(의도)** — `aggregate`가 stub을 부르므로 판을 쓰는 #39 테스트(`ViewerCounterTest` 3개, `ViewerCountIntegrationTest` 13개)가 Red 동안 함께 실패한다. Green의 첫 구현에서 돌아온다. stub에서 셈을 미리 채우면 "정확히 5개"·"A=5, B=5" 시나리오가 구현 없이 통과한다.
- 기존 테스트 손질(컴파일): `new ViewerCounter(...)`에 한도 5(`ViewerCounterTest` 1곳, `ViewerCountIntegrationTest` 3곳). 통합 테스트의 가짜 MediaMTX는 세션을 `"경로|주소:포트"`로 받으면 그 주소를 `remoteAddr`로 준다(경로만 쓰면 예전 기본 주소 그대로).
- 실행: 단위(`ViewerCounterTest`)는 파일로 돌려 하나씩 실패를 봤고, 통합(`ViewerCountIntegrationTest`)은 컨테이너 기동 때문에 다 쓰고 파일 단위로 한 번.
- 결과: 시나리오 11개 = 단위 7 + 통합 4. **11개 전부 실패, 구현 없이 통과한 것 0개.** 모두 `UnsupportedOperationException: not implemented`(stub)에 닿았다.
  - 같은 실행에서 #39 테스트 16개도 같은 stub 메시지로 실패, 판을 쓰지 않는 #39 테스트 1개(`…no snapshot has been made`)는 통과.

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 같은 IP 세션 8개 + 다른 IP 2개가 A에 → A=7 | `[정상] ViewerCounter.aggregate — …A=7…8 sessions from one IP…`<br>`[정상] ViewerCountIntegration — …store A=7…`<br>`[경계] ViewerCounter.aggregate — …exactly 5 sessions…`·`…6 sessions…`<br>`[경계] ViewerCounter.aggregate — …same IP on different ports as one IP`<br>`[경계] ViewerCounter.aggregate — …limit is set to 2…` |
| 2. 같은 IP가 A에 5, B에 5 → A=5, B=5(경로마다) | `[정상] ViewerCounter.aggregate — …A=5 and B=5…`<br>`[정상] ViewerCountIntegration — …store A=5 and B=5…`<br>`[정상] ViewerCounter.aggregate — …A=5 and B=3…8 sessions on A and 3 on B` |
| 3. 로컬 도커에서 nginx 경유 시청 → `remoteAddr`가 nginx 내부 주소가 아니라 요청한 쪽 주소(수동) | 수동 확인 — 아래 "수동 확인" 절에 시각과 관측값을 기록했다(자동 테스트 없음) |
| 4. 집계 → 로그·Redis 어디에도 시청자 IP가 없다 | `[정상] ViewerCountIntegration — …no viewer IP in any Redis key or value…`<br>`[정상] ViewerCountIntegration — …no viewer IP in the log after aggregating` |

**커버리지:** AC 4개(자동 3 + 수동 1) / 시나리오 11개 / 미커버 0개

AC에 없는데 추가한 것:
- `…same IP on different ports as one IP` — `remoteAddr`에 포트가 붙어 온다. 포트까지 IP로 보면 탭마다 따로 세져 한도가 동작하지 않는다(이슈 "IP마다").
- `…limit is set to 2…` — 한도가 고정 숫자가 아니라 설정값(`app.viewer.max-sessions-per-ip`)인지 본다(이슈 "한도는 설정값").
- `…A=5 and B=3…` — 한 경로의 한도 초과가 다른 경로의 수를 깎지 않는지 본다(AC 2 "경로마다").

---

## 수동 확인

AC 3 — 로컬 도커에서 nginx를 거친 시청 세션의 `remoteAddr`(2026-11-28, KST). 실제 주소는 남기지 않고 "누구의 주소와 같은가"만 남긴다.

**방법**

- 세션 목록은 api-1 컨테이너 안에서 `GET http://mediamtx:9997/v3/hlssessions/list`(내부 비밀번호 Basic 인증)로 읽었다. `remoteAddr`의 포트를 뗀 주소를 (a) nginx 컨테이너 주소, (b) nginx 접근 로그의 `$remote_addr`(nginx가 본 요청한 쪽), (c) 다른 컨테이너(api-2) 주소, (d) 꾸며 보낸 `X-Forwarded-For` 값과 같은지만 비교해 출력했다.
- 요청: 호스트에서 `curl -L http://localhost:8000/hls/live/62/index.m3u8`(데모 방송). 호스트 요청은 도커 포트 연결을 거쳐 nginx에 도커 게이트웨이 주소로 닿는다 — 로컬에서 "요청한 쪽 주소"는 이것이다.

**관측**

| 시각 | 상태 | 한 일 | 세션 `remoteAddr` |
| --- | --- | --- | --- |
| 18:52:12 | 설정 전(main 설정) | 호스트 → nginx → `/hls/live/62/index.m3u8` | **nginx 컨테이너 주소와 같음**, 요청한 쪽과 다름 (스파이크 4절·ADR의 관측 재현) |
| 18:52~18:56 | — | `docker compose build nginx` → 대역 고정 때문에 네트워크 재생성 → `docker compose --profile demo up -d --force-recreate` | — |
| 18:56:09 | 설정 후(이 브랜치) | 호스트 → nginx | nginx 주소와 **다름**, nginx가 본 요청한 쪽(호스트 쪽 게이트웨이)과 **같음** |
| 18:56:21 | 설정 후 | api-2 컨테이너 → `http://nginx/hls/live/62/index.m3u8` | nginx 주소와 다름, **api-2 컨테이너 주소와 같음** |
| 18:56:21 | 설정 후 | 호스트 → nginx, `X-Forwarded-For: 203.0.113.9`를 꾸며 보냄 | 꾸민 주소와 **다름**, 호스트 쪽 게이트웨이와 같음(nginx가 덮어썼다) |

- 세 경우 모두 nginx 내부 주소가 아니라 요청한 쪽 주소가 보였다 → 이슈의 "안 보이면 멈춘다" 조건에 걸리지 않아 계속 진행했다.
- 설정 후에도 재생 경로는 그대로 동작했다: 쿠키를 이어 쓰는 `curl`로 `index.m3u8`(200) → 그 안의 화질별 주소(200). 세션이 IP에 묶이는 MediaMTX 동작(스파이크 1절 R)과 충돌하지 않는다 — 같은 시청자의 요청은 늘 같은 `X-Forwarded-For`로 간다.
- 세 주소는 모두 고정 대역(`172.27.0.0/16`) 안이다. 로컬에서는 요청한 쪽도 도커 망 안(게이트웨이·컨테이너)이기 때문이다. 서버에서는 edge-caddy가 넘긴 진짜 사용자 주소를 nginx `real_ip`가 고쳐 넘긴다.
