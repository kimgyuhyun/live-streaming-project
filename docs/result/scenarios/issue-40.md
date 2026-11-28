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

- [x] [정상] `ViewerCounter.aggregate` — should write A=7 when channel A has 8 sessions from one IP and 2 sessions from two other IPs
- [x] [정상] `ViewerCounter.aggregate` — should write A=5 and B=5 when one IP has 5 sessions on A and 5 on B
- [x] [정상] `ViewerCounter.aggregate` — should write A=5 and B=3 when one IP has 8 sessions on A and 3 on B
- [x] [정상] `ViewerCountIntegration` — should store A=7 in the current snapshot when live channel A has 8 sessions from one IP and 2 from two other IPs
- [x] [정상] `ViewerCountIntegration` — should store A=5 and B=5 in the current snapshot when one IP has 5 sessions on live A and 5 on live B
- [x] [정상] `ViewerCountIntegration` — should leave no viewer IP in any Redis key or value after aggregating
- [x] [정상] `ViewerCountIntegration` — should leave no viewer IP in the log after aggregating

### 경계

- [x] [경계] `ViewerCounter.aggregate` — should write A=5 when one IP has exactly 5 sessions on channel A
- [x] [경계] `ViewerCounter.aggregate` — should write A=5 when one IP has 6 sessions on channel A
- [x] [경계] `ViewerCounter.aggregate` — should count sessions from the same IP on different ports as one IP
- [x] [경계] `ViewerCounter.aggregate` — should write A=2 when the limit is set to 2 and one IP has 3 sessions on channel A

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

### Green 기록

- 구현: `ViewerCounter.countByChannel` 하나. 세션을 (채널ID → (IP → 세션 수))로 모은 뒤 채널마다 `min(IP별 수, maxSessionsPerIp)`를 더한다. IP는 `remoteAddr`의 마지막 `:` 앞(`ipOf`). 첫 구현으로 이번 이슈 11개와 Red 때 함께 깨졌던 #39 테스트 16개가 모두 돌아왔다.
- **포트 없는 `remoteAddr`는 다루지 않는다** — MediaMTX는 늘 `주소:포트`로 준다. `hlsTrustedProxies`로 `X-Forwarded-For`를 읽은 세션도 로컬에서 `주소:포트` 모양인 것을 확인했다(숫자만 가려서 봤다).
- 인프라 설정(nginx `/hls/`의 `X-Forwarded-For`, MediaMTX `hlsTrustedProxies`, compose 기본 네트워크 대역)은 시그니처 절의 값 그대로다. 자동 테스트는 없고 "수동 확인"이 본다.
- 커버리지 명령(`coverage`)이 설정에 없어 측정하지 않았다.
- 실행: 이슈 두 파일(`ViewerCounterTest` 12·`ViewerCountIntegrationTest` 18) 통과 → 백엔드 전체 527개 통과(실패 0, Red 전 516 + 이번 11) → 프론트 전체 26파일 162개 통과(프론트는 바꾸지 않았다). Red에서는 전체 스위트를 따로 돌리지 않았다(바뀐 곳이 `ViewerCounter`·설정 한 줄뿐이라 이슈 두 파일로 봤다).
- **로컬 도커 확인(19:08~19:09, KST)** — 이 브랜치로 `docker compose up -d --build api-1 api-2`. 호스트에서 쿠키 없이 `/hls/live/62/index.m3u8`을 7번 열어(요청마다 새 세션, 같은 IP) 집계 로그 `sessions=7` → 채널 62 조회 `viewerCount` **5**. 같은 때 api-1·api-2 로그 2분 치와 로컬 Redis 전체(키 24개, 자료형별로 값까지)에서 그 시청자 주소 0건.

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

---

## AC 검증

> `harness:ac-verifier` 출력 원문(2026-11-28). 구현자는 고치지 않는다.

## 이슈 #40 AC 검증 (브랜치 feat/viewer/issue-40, main과 비교)

AC 4개가 모두 충족됐다. 이번에 직접 돌린 것은 모두 통과했다.
- 백엔드: 19:10에 이슈 테스트 두 파일을 돌렸다(BUILD SUCCESSFUL in 49s). 결과 XML 기준으로 `ViewerCounter.aggregate`는 12개, `ViewerCountIntegration`은 18개이고 둘 다 failures 0·errors 0이다.
- `check-scenarios.mjs 40 main...feat/viewer/issue-40`: 시나리오 11, 더한 테스트 11, 불일치 0.
- AC 3·4는 지금 돌고 있는 로컬 도커에서 따로 교차 확인했다. 주소는 출력하지 않고 "어느 주소와 같은가"만 비교했다.

### AC 1 — 같은 IP의 세션 8개와 다른 IP의 세션 2개가 채널 A에 있을 때 집계하면 A=7
✅ 충족
- 테스트:
  - 단위: `should write A=7 when channel A has 8 sessions from one IP and 2 sessions from two other IPs` (C:\live-streaming-project\backend\src\test\java\com\livestream\broadcast\ViewerCounterTest.java:137). 8개(203.0.113.7, 포트는 각각 다름) + 1개(203.0.113.8) + 1개(198.51.100.4)를 넣고, 판 전체를 `isEqualTo(Map.of(11L, 7L))`로 단언한다.
  - 통합: `should store A=7 in the current snapshot when live channel A has 8 sessions from one IP and 2 from two other IPs` (C:\live-streaming-project\backend\src\test\java\com\livestream\broadcast\ViewerCountIntegrationTest.java:415). 실제 Oracle·Redis와 가짜 MediaMTX로 돌리고, 판에 `containsEntry(A방송id, "7")`이 있는지 본다. 한도를 직접 주지 않고 Spring이 `application.yml`의 `app.viewer.max-sessions-per-ip: 5`를 넣으므로, 설정이 실제로 연결됐는지도 함께 확인된다.
  - 경계(단위): `…exactly 5 sessions…` → 5 (ViewerCounterTest.java:179), `…6 sessions…` → 5 (:190). 하나 넘을 때 바로 깎이는 것과 딱 한도일 때 다 세는 것을 둘 다 본다.
  - 경계(단위): `should count sessions from the same IP on different ports as one IP` (:201). 같은 IP·다른 포트 6개와, 같은 포트를 쓰는 다른 IP(`203.0.113.70:40000`) 1개를 넣어 6이 나오는지 본다. 이 테스트는 포트를 떼지 않거나 주소 앞부분 일치로 묶는 잘못된 구현을 모두 잡아낸다.
  - 설정값(단위): `should write A=2 when the limit is set to 2 and one IP has 3 sessions on channel A` (:215). 한도가 상수로 박혀 있지 않은지 본다.
- 구현:
  - `ViewerCounter.countByChannel`이 (채널ID → (IP → 세션 수))로 모은 뒤 `Math.min(count, maxSessionsPerIp)`를 더한다 (C:\live-streaming-project\backend\src\main\java\com\livestream\broadcast\ViewerCounter.java:60-71). `aggregate`가 이 결과를 쓴다(:47).
  - IP는 `ipOf` = `remoteAddr`의 마지막 `:` 앞부분이다(:74-76).
  - 한도는 생성자의 `@Value("${app.viewer.max-sessions-per-ip}")`로 받는다. 값은 C:\live-streaming-project\backend\src\main\resources\application.yml:52-53에 있다.
- 근거: 단위 테스트는 목이 돌려준 값이 아니라 구현이 `viewerSnapshot.write`에 넘긴 인자를 캡처해 단언한다. 경계 4, 5, 6, 8을 모두 본다(4 이하는 같은 파일의 1개짜리 IP와 AC 2의 B=3이 덮는다).
- 실물 교차 확인: 호스트에서 쿠키 없이 `/hls/live/62/index.m3u8`을 7번 열었다(요청마다 새 세션이 생기고 IP는 같다). 집계 로그는 `sessions=7`이었고, 채널 62 조회의 `viewerCount`는 4초 간격 세 번 모두 **5**였다.

### AC 2 — 같은 IP가 A에 5개, B에 5개 세션을 열었을 때 집계하면 A=5, B=5 (경로마다 센다)
✅ 충족
- 테스트:
  - 단위: `should write A=5 and B=5 when one IP has 5 sessions on A and 5 on B` (ViewerCounterTest.java:151). `isEqualTo(Map.of(11L, 5L, 12L, 5L))`로 단언한다.
  - 통합: `should store A=5 and B=5 in the current snapshot when one IP has 5 sessions on live A and 5 on live B` (ViewerCountIntegrationTest.java:428). 판에 두 칸이 모두 5인지 본다.
  - 보조(단위): `should write A=5 and B=3 when one IP has 8 sessions on A and 3 on B` (ViewerCounterTest.java:165). 한 경로에서 한도를 넘은 것이 다른 경로의 수를 깎지 않는지 본다.
- 구현: 바깥 맵의 키가 채널ID이고 IP별 수는 채널마다 따로 쌓인다(ViewerCounter.java:62-66). 그래서 한도는 (채널, IP) 단위로 걸린다.
- 근거: IP 전체 기준(경로 무시)으로 잘못 세면 A+B=10이 5로 깎인다. 그러면 위 두 테스트와 8/3 테스트(→ 5와 0 같은 값)가 실패한다. 경로를 가르는 경계가 단언으로 막혀 있다.

### AC 3 — 로컬 도커에서 nginx를 거쳐 시청하면 세션 목록의 `remoteAddr`가 nginx 내부 주소가 아니라 요청한 쪽 주소다(수동 확인, 결과 기록)
✅ 충족 (근거는 시나리오 문서 "## 수동 확인" 기록이고, 지금 컨테이너에서 다시 재현해 교차 확인했다)
- 기록(C:\live-streaming-project\docs\result\scenarios\issue-40.md "## 수동 확인"):
  - 18:52에 설정 전 상태를 봤다. `remoteAddr`가 nginx 주소와 같았다.
  - 18:56에 설정 후 상태를 봤다. 호스트 요청, api-2 요청, `X-Forwarded-For`를 꾸민 요청 세 가지 모두 nginx 주소와 달랐고 요청한 쪽과 같았다.
- 교차 확인(직접 본 것, 19:10~19:12):
  - 돌고 있는 설정이 이 브랜치 값과 같다.
    - nginx 컨테이너 `/etc/nginx/conf.d/default.conf:121`은 `location /hls/` 안에서 `proxy_set_header X-Forwarded-For $remote_addr;`다.
    - mediamtx 컨테이너 `/mediamtx.yml:14`는 `hlsTrustedProxies: ['172.27.0.0/16']`다.
    - `live-streaming_default` 네트워크 대역은 `172.27.0.0/16`이다.
  - 요청마다 고유한 쿼리 태그를 붙여 세 요청을 보냈다(셋 다 리다이렉트를 따라가 200). 그다음 api-1 안에서 `GET http://mediamtx:9997/v3/hlssessions/list`(사용자 `spring` + `MEDIAMTX_API_PASSWORD`, Basic 인증)로 세션 3개를 읽었다. 각 세션의 `remoteAddr`에서 포트를 떼고 비교만 출력했다.

    | 요청 | `remoteAddr`가 `주소:포트` 모양 | nginx 컨테이너 주소와 | nginx 접근 로그의 `$remote_addr`(요청한 쪽)와 | 그 밖 |
    | --- | --- | --- | --- | --- |
    | 호스트 → nginx | 예 | **다름** | **같음** | — |
    | 호스트 → nginx, `X-Forwarded-For: 203.0.113.9`를 꾸며 보냄 | 예 | 다름 | 같음 | 꾸민 값과 **다름**(덮어씀) |
    | api-2 → `http://nginx/hls/…` | 예 | 다름 | 같음 | api-2 컨테이너 주소와 **같음** |

  - 호스트 쪽 요청 주소(도커 게이트웨이)는 nginx 컨테이너 자기 주소와 다르다. 따라서 "nginx 주소와 다르고 요청한 쪽과 같다"는 비교는 둘을 실제로 구분한다.
- 근거: 기록된 관측과 지금의 재현이 같은 결과다. 설정 전(nginx 주소로 보임)은 다시 재현하지 않았다. 기록과 스파이크 4절에 기댄다.

### AC 4 — 집계해도 로그·Redis 어디에도 시청자 IP가 남지 않는다
✅ 충족
- 테스트:
  - `should leave no viewer IP in any Redis key or value after aggregating` (ViewerCountIntegrationTest.java:444). 집계한 뒤 `redisDump()`(:472)를 만든다. 이 덤프는 Redis 키 전체의 이름과 값(STRING·HASH·SET·ZSET·LIST 자료형마다)을 담는다. 여기에 `doesNotContain("203.0.113.7", "198.51.100.4")`를 단언한다.
  - `should leave no viewer IP in the log after aggregating` (:456). `OutputCaptureExtension`으로 출력을 잡는다. 먼저 `contains("시청자 수 집계 sessions=8")`로 집계 로그가 실제로 잡혔는지 확인하고, 그다음 두 IP가 없는지 본다. 아무것도 안 잡혀서 통과하는 경우를 막아 두었다.
- 구현:
  - 로그는 두 줄뿐이다. `시청자 수 집계 sessions={} broadcasts={} elapsedMs={}`(ViewerCounter.java:54)와 `시청자 수 집계 실패 cause={예외 클래스 이름} elapsedMs={}`(:44)다. 주소가 들어갈 자리가 없다.
  - IP는 `countByChannel` 안의 지역 맵에서만 쓰인다(:60-71). 판에는 방송ID → 수만 넘어간다(:47-53).
  - `MediaMtxClient`·`ViewerSnapshot`에는 로그 호출이 없다(grep 결과 0건).
- 실물 교차 확인: 위 AC 3 요청 직후 세션 목록에 서로 다른 시청자 IP가 2개 있었다. 각 IP를 숫자 경계 정규식으로 셌다.
  - api-1·api-2 로그 5분 치(119줄, 그중 집계 줄 35개): **0건**
  - 로컬 Redis 전체(키 26개, 자료형별 값까지): **0건**
- 근거: 테스트와 실물 모두에서 키, 값, 로그를 통째로 훑었다. 특정 키만 본 것이 아니다.

## 요약
AC 4개 중 ✅ 4 / ⚠️ 0 / ❌ 0

## 추가 시나리오 제안 (조이는 용도, 필수 아님)
없음. AC 1의 경계(4·5·6·8), 포트 무시, 설정값, AC 2의 경로 분리가 모두 단언으로 막혀 있다.

## 가짜 테스트 목록
발견하지 못했다.
- 단위 테스트의 `written()`은 `viewerSnapshot.write`에 넘어간 인자를 캡처한다. 목이 돌려준 값을 단언하는 형태가 아니다.
- `doesNotContain`만 쓰는 부정 단언은 약해지기 쉽다. 로그 테스트는 `contains("…sessions=8")`로 출력이 잡혔음을 먼저 확인한다. Redis 테스트는 같은 집계가 판을 쓰는 것을 AC 1·2 통합 테스트가 따로 보장한다. 다만 Redis 테스트 혼자서는 "덤프가 비어 있지 않다"를 단언하지 않는다. 판을 쓰지 않는 구현이라면 이 테스트만으로는 못 잡는다. 그래도 같은 파일의 A=7·A=5/B=5 테스트가 함께 실패하므로 가짜로 보지 않는다.
- 예외 단언이 없다(이번 이슈에 예외 시나리오 없음. Red 기록에 뺀 이유가 있다).

## 확인하지 않은 것
- 백엔드 전체 스위트와 프론트 스위트는 돌리지 않았다. 이슈 테스트 두 파일만 돌렸다(전체 결과는 Green 기록의 527개 통과에 기댄다).
- 설정 전 상태(세션이 nginx 주소로 보임)는 다시 재현하지 않았다. 문서 기록 18:52의 관측에 기댄다.
- 서버 경로는 확인하지 않았다. 서버에서는 edge → nginx `real_ip`(`set_real_ip_from 10.250.0.0/24`) → MediaMTX를 거쳐 진짜 사용자 주소가 닿아야 한다. 로컬에서는 요청한 쪽도 도커 대역 안(게이트웨이·컨테이너)이다. 이 AC는 "로컬 도커"를 조건으로 하므로 판정에는 영향이 없다.
- `compose.prod.yml`에서 8888을 열지 않는다는 점은 읽지 않았다. 로컬 직접 포트 8888로 `X-Forwarded-For`를 꾸밀 수 있다는 점은 시그니처 절에 적혀 있고 보안 점검 소관이다.
- `remoteAddr`에 `:`가 없거나 null이면 `ipOf`가 `StringIndexOutOfBoundsException`/NPE를 던진다. 이 예외는 `RestClientException` catch 밖이라 그 회차가 실패한다. JDK 예외 메시지(`begin 0, end -1, length N`)에는 주소가 없어 AC 4에는 영향이 없다. MediaMTX는 늘 `주소:포트`로 준다는 것이 문서의 전제다(Green 기록). AC 밖이라 판정에 넣지 않았다.
- 지금 돌고 있는 api 이미지의 내용이 HEAD와 같은지는 직접 대조하지 않았다. api 컨테이너는 19:08:30에 생성됐고, 마지막 코드 커밋은 d99f229(19:03:59)다. 그 뒤 b605537(19:09:30)은 문서만 바꿨다. 실물에서 7세션 → 5가 나와 한도 로직이 이미지에 들어 있는 것은 확인했다.
- 처음 AC 4 교차 확인 스크립트 끝에서 `viewerCount`가 0으로 나왔다. 스크립트가 Redis 키마다 `exec`을 하느라 오래 걸려, 그사이 세션이 만료됐기 때문이다(약 30초). 바로 다음 조회에서 로그가 `sessions=0`으로 바뀐 것으로 확인했다. 그다음 7세션 재현에서 5를 확인했다.

## 실행한 명령 목록
1. `cat .claude/harness.json && gh issue view 40 && git diff main...HEAD --stat` → 설정 확인, AC 4개 확인, 변경 8파일
2. `cat docs/result/scenarios/issue-40.md` → 시그니처, 시나리오 11개, Red/Green 기록, AC 대조, 수동 확인 기록
3. `git diff main...HEAD -- backend compose.yml infra` → `ViewerCounter.countByChannel`/`ipOf`, yml 한도, 테스트 11개 본문, nginx·mediamtx·compose 설정
4. `sed -n '/## AC 검증/,$p' docs/result/scenarios/issue-39.md` → 출력 형식 확인
5. `grep -n "V1" docs/result/prd/viewer.md; sed -n 25,50p …` → V1 결정(IP당 5세션, 같은 경로 기준, `remoteAddr`·`path`만 읽기, 주소 저장·로그 금지)
6. `cd backend && JAVA_HOME="$HOME/.jdks/liberica-21.0.7" ./gradlew test --tests "ViewerCounterTest" --tests "ViewerCountIntegrationTest"` → BUILD SUCCESSFUL in 49s
7. `node …/check-scenarios.mjs 40 main...feat/viewer/issue-40` → 시나리오 11, 더한 테스트 11, 불일치 0
8. `docker compose ps; git log -3` → 7개 서비스 Up, api-1·api-2 생성 19:08:30, 커밋 시각 확인
9. `git show --stat b605537 d99f229; date` → b605537은 문서만, 코드는 d99f229(19:03:59)
10. `docker compose exec -T nginx grep …` (처음에는 Git Bash 경로 변환 때문에 출력이 없어 `MSYS_NO_PATHCONV=1`로 다시 실행) → default.conf:121 `X-Forwarded-For $remote_addr`, mediamtx `/mediamtx.yml:14` `hlsTrustedProxies`, 네트워크 대역 172.27.0.0/16
11. `sed -n 60,117p compose.yml; cat infra/nginx/Dockerfile; grep authHTTPExclude …` → mediamtx.yml은 마운트, nginx.conf는 이미지에 복사, 시청(read)은 인증 제외
12. `grep setBasicAuth MediaMtxClient.java; docker compose exec -T api-1 sh -c 'command -v curl …'` → Basic 사용자 `spring`, api-1에 curl 있음, 비밀번호 환경변수 설정됨
13. `sed -n 5,9p infra/nginx/nginx.conf; curl -s http://localhost:8000/api/channels/62` → 접근 로그 첫 칸이 `$remote_addr`, 채널 62 `live:true`
14. scratchpad `ac3.sh` 1차 → 세 요청 모두 302, 세션 0개(리다이렉트를 따라가지 않아 세션이 생기지 않음)
15. `tail` 테스트 출력 + `curl -D -` → 테스트 성공 확인. 리다이렉트가 `?cookieCheck=1&t=…`로 쿼리를 유지함
16. `ac3.sh` 2차(`curl -L`) → 200×3, 세션 3개. 세 경우 모두 nginx 주소와 다르고 요청한 쪽과 같음, 꾸민 값과 다름, api-2 주소와 같음, 모두 `주소:포트` 모양
17. scratchpad `ac4.sh` → 시청자 IP 2개, api 로그 5분 치 0건, Redis 26키 0건. 마지막 집계 `sessions=3`. 직후 `viewerCount` 0(세션 만료, 18번 참고)
18. `curl …/api/channels/62 ×3; docker compose logs --since 30s … | grep 집계; redis-cli get viewers:current` → 그때는 `sessions=0`이어서 0이었음을 확인
19. `curl -L …/hls/live/62/index.m3u8 ×7; sleep 4 ×3; curl …/api/channels/62` → 집계 `sessions=7`, `viewerCount` 5가 세 번
20. `grep -o '<testsuite …' build/test-results/test/TEST-*Viewer*.xml; grep -n …` → `ViewerCounter.aggregate` 12, `ViewerCountIntegration` 18, failures 0·errors 0. 근거 줄 번호 수집
21. `grep -n "DisplayName(\"should store A=7…\|redisDump\|log\.…" …; grep -rn remoteAddr backend/src/main/java; grep -n max-sessions-per-ip …` → 통합 테스트 줄 번호, 로그 호출 2개뿐, `remoteAddr`는 `ipOf`에서만 사용, 테스트 설정에는 한도를 덮어쓰는 값이 없음

### 구현자 이견

- 판정에는 이견이 없다. "확인하지 않은 것"에 대한 사실 보충만 남긴다.
  - 서버에서 8888: `compose.prod.yml`의 `mediamtx.ports`는 `!override`로 `"1935:1935"` 하나뿐이다. 서버에서는 MediaMTX HLS 포트에 바깥에서 직접 닿지 않는다.
  - 포트 없는 `remoteAddr`: Green 기록대로 "일어나지 않는 경우에 대한 처리는 넣지 않는다"(CLAUDE.md)를 따랐다. 회차가 실패해도 다음 회차(5초 뒤)가 다시 센다.
