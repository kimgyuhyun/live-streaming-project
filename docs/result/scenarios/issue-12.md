# 이슈 #12 — 채널 페이지에서 방송을 본다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/12
> PRD: `docs/result/prd/channel.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

> 게이트 처리: 사용자가 자리를 비운 동안 진행했다. 사전 승인(#13 → #12 진행 지시)에 따라 시그니처·시나리오를 구현자가 정하고 승인된 것으로 진행했다. 의존성 4개(`hls.js`, `vitest`·`@testing-library/react`·`jsdom`)와 "시청 중 종료 감지는 방송 중일 때 채널 API 5초 폴링"은 사용자가 이전 세션에서 받아들인 결정이다.

---

## 시그니처

### 관련 결정

- **채널 PRD 작은 결정(시청 주소)** — `/hls/live/{채널ID}/index.m3u8`(nginx `/hls/` → MediaMTX). 비로그인도 본다(MediaMTX `read`는 인증 대상이 아니다, PRD "키 확인 연동").
- **채널 PRD 화질 / 확정 사양 5절** — 원본 하나, 화질 자동 전환(ABR)은 하지 않는다. `docs/spikes/infra-validation.md` 3절: hls.js 1.7.4 단독 재생은 0.7초 안팎에 시작, 지연 2~3초(`lowLatencyMode: true`).
- **확정 사양 5절** — "방송이 끝나면 플레이어 자리에 '방송이 종료되었습니다'를 보여 준다."
- **#11** — 채널 API의 `live`가 진행 중 방송 여부다. 송출이 끊기면 훅으로 거의 즉시, 놓쳐도 1분 대조로 `false`가 된다.
- **#13** — 채널 API의 `title`이 방송 제목(정한 적 없으면 `{채널 이름}의 방송`).

### 의존성 (사용자 승인 4개만)

| 패키지 | 종류 | 용도 |
| --- | --- | --- |
| `hls.js` | dependencies | HLS 재생(스파이크와 같은 1.7.x) |
| `vitest` | devDependencies | 테스트 러너(Vite 설정을 그대로 쓴다) |
| `@testing-library/react` | devDependencies | 컴포넌트·훅 렌더. 피어 의존성 `@testing-library/dom`은 npm이 자동으로 깐다(package.json에 따로 넣지 않는다) |
| `jsdom` | devDependencies | 테스트용 DOM |

### 타입

```ts
// ── frontend/src/pages/broadcastView.ts ─────────────────
// 플레이어 자리에 무엇을 보일지. 'ended' = 이 페이지에서 방송 중을 본 뒤 오프라인이 됨
export type BroadcastView = 'live' | 'offline' | 'ended'

// 채널 응답의 live가 올 때마다 다음 화면을 고른다(순수 함수, AC 4).
// previous가 null(첫 응답)이면 live → 'live', 아니면 'offline'.
// 'live'였다가 live=false → 'ended'. 'ended'·'offline'에서 live=true → 'live'(새 방송)
export function nextBroadcastView(previous: BroadcastView | null, live: boolean): BroadcastView

// 응답이 올 때마다 nextBroadcastView로 화면을 이어 간다. live가 undefined(아직 응답 없음)면 null
export function useBroadcastView(live: boolean | undefined): BroadcastView | null

// 방송 중일 때만 5초마다 다시 묻는다. React Query refetchInterval에 그대로 넘긴다
export const LIVE_POLL_INTERVAL_MS = 5000
export function pollInterval(live: boolean | undefined): number | false

export function hlsUrl(channelId: number): string   // '/hls/live/{id}/index.m3u8'

// ── frontend/src/pages/LivePlayer.tsx ───────────────────
// hls.js로 재생한다(lowLatencyMode). hls.js를 못 쓰는 브라우저(Safari)는 video.src에 직접 넣는다.
// 자동 재생 정책 때문에 muted로 시작한다(controls로 소리를 켠다). 사라질 때 hls.js 인스턴스를 정리한다
export function HlsPlayer(props: { src: string }): JSX.Element

// view별 플레이어 자리: live → '방송 중' + HlsPlayer, offline → '오프라인', ended → '방송이 종료되었습니다'
export function PlayerArea(props: { view: BroadcastView; channelId: number }): JSX.Element
```

`ChannelPage.tsx`는 채널 조회에 `refetchInterval: (query) => pollInterval(query.state.data?.live)`를 걸고, `useBroadcastView(channel.data?.live)`로 고른 화면을 `PlayerArea`로 그린다. 채널 이름(`h1`)·방송 제목(`h2`, #13)·소개는 그대로 둔다. 기존의 `방송 중`/`오프라인` 한 줄은 `PlayerArea`로 옮긴다.

### 설정·문서

| 파일 | 바꾸는 것 |
| --- | --- |
| `frontend/package.json` | 의존성 4개, `"test": "vitest run"` |
| `frontend/vite.config.ts` | `test: { environment: 'jsdom' }` (`/// <reference types="vitest/config" />`) |
| `.claude/harness.json` | `test`에 `(cd frontend && npm test)`를 더한다(백엔드 명령은 그대로). `testFile`은 `.java`면 Gradle, 아니면 Vitest. `testGlobs`에 `frontend/src/*.test.ts`·`*.test.tsx`. `verify`에 `npm test` |
| `CLAUDE.md` | "프론트 컨벤션"에 Vitest(위치·이름·`describe`/`it` 규칙), 명령 표에 `npm test` |

### 에러 케이스

| 상황 | 화면 |
| --- | --- |
| 없는 채널 | `채널을 찾을 수 없습니다`(#8 그대로) |
| 첫 조회 실패 | `잠시 후 다시 시도해 주세요`(#8 그대로) |
| 방송 중 폴링 한 번 실패 | 마지막으로 받은 채널로 계속 그린다(플레이어를 지우지 않는다). 다음 폴링이 다시 묻는다 |
| hls.js 재생 오류 | 이 이슈에서 따로 다루지 않는다. 방송 종료는 폴링이 판정한다(아래 판단 1) |

### 판단이 갈린 곳

1. **시청 중 종료 감지는 채널 API 폴링(방송 중일 때 5초)으로 한다.** hls.js 오류 이벤트로 판정하지 않는다. hls.js의 `manifestLoadError`·`levelLoadError`는 일시적인 끊김(네트워크 순간 장애, 키프레임 대기)과 방송 종료를 구분하기 어렵다 — 오류 하나에 "종료"를 띄우면 잘못된 종료 문구가 뜬다. 서버의 `live`는 #11이 훅과 1분 대조로 맞춰 두므로 이것이 판정 기준이다. 또 폴링은 "응답이 true → false"라는 데이터 전환이라 Vitest로 상태 전환을 테스트하기 쉽다(AC 4). 사용자가 이전 세션의 추천을 받아들인 결정이다.
2. **폴링은 방송 중일 때만 한다.** 오프라인으로 연 페이지는 다시 묻지 않는다(방송이 시작되면 새로고침이나 React Query의 창 포커스 재조회로 바뀐다). 비로그인 시청자 전부가 오프라인 채널을 계속 묻지 않게 하려는 것이다. `ended`가 되면 `live`가 false라 폴링도 멈춘다.
3. **화면 고르기는 순수 함수 `nextBroadcastView` + 얇은 훅 `useBroadcastView`로 둔다.** `ended`는 "이 페이지에서 방송 중을 본 적이 있다"는 기억이 필요해서 서버 값 하나로는 못 고른다. 기억을 훅에 두고 전환 규칙은 순수 함수로 뽑아 테스트한다.
4. **폴링 한 번 실패로 플레이어를 지우지 않는다.** 지금 `ChannelPage`는 `isError`면 에러 문구만 보인다. 폴링이 들어오면 일시적 실패 한 번에 보던 영상이 사라진다. 받은 데이터가 있으면 그것으로 계속 그린다.
5. **hls.js를 못 쓰면 `video.src`에 넣는다.** iOS Safari는 MSE가 없어 hls.js가 동작하지 않지만 HLS를 직접 재생한다. hls.js 문서의 표준 대체 경로다.
6. **`muted`로 자동 재생한다.** 브라우저는 소리 있는 자동 재생을 막는다. `controls`로 시청자가 소리를 켠다.

### 이 이슈에서 만들지 않는 것

- 화질 선택·자동 전환 — 확정 사양 5절, 720p는 미해결 1
- 시청자 수, 채팅 — 다른 도메인
- 오프라인 채널이 방송을 시작하면 스스로 재생하기 — 판단 2(폴링은 방송 중일 때만)
- 썸네일·오프라인 이미지 — 탐색 도메인

---

## 테스트 시나리오

### 정상

- [x] [정상] `nextBroadcastView` — should choose live when the first channel response is live
- [x] [정상] `nextBroadcastView` — should choose offline when the first channel response is offline
- [x] [정상] `nextBroadcastView` — should choose ended when a live channel turns offline
- [x] [정상] `useBroadcastView` — should switch from live to ended when a later response says the channel is offline
- [x] [정상] `pollInterval` — should poll every 5 seconds while the channel is live
- [x] [정상] `hlsUrl` — should build /hls/live/{channelId}/index.m3u8 for the channel
- [x] [정상] `HlsPlayer` — should load the HLS address into hls.js and attach it to the video when hls.js is supported
- [x] [정상] `PlayerArea` — should show the player for /hls/live/7/index.m3u8 when the view is live
- [x] [정상] `PlayerArea` — should show the offline notice instead of the player when the view is offline
- [x] [정상] `PlayerArea` — should show 방송이 종료되었습니다 instead of the player when the view is ended
- [x] [정상] `ChannelPage` — should show the player and the broadcast title when a visitor opens a live channel
- [x] [정상] `ChannelPage` — should replace the player with 방송이 종료되었습니다 when the next poll says the channel is no longer live
- [x] [정상] `ChannelPage` — should load /hls/live/7/index.m3u8 into the player when a visitor opens live channel 7

### 경계

- [x] [경계] `nextBroadcastView` — should stay ended when the channel is still offline after the broadcast ended
- [x] [경계] `nextBroadcastView` — should choose live when an offline channel turns live
- [x] [경계] `nextBroadcastView` — should choose live when a new broadcast starts after the previous one ended
- [x] [경계] `useBroadcastView` — should return null before the first channel response arrives
- [x] [경계] `useBroadcastView` — should stay offline when a channel that was offline from the start is fetched again
- [x] [경계] `pollInterval` — should not poll before the first channel response arrives
- [x] [경계] `HlsPlayer` — should set the HLS address as the video source when the browser plays HLS without hls.js
- [x] [경계] `HlsPlayer` — should destroy the hls.js instance when the player is removed
- [x] [경계] `ChannelPage` — should show the offline notice without a player when a visitor opens an offline channel
- [x] [경계] `pollInterval` — should poll every 5000 ms while the channel is live
- [x] [경계] `ChannelPage` — should not ask for the channel again before 5 seconds pass while the channel is live
- [x] [경계] `useBroadcastView` — should switch from ended to live when a later response says a new broadcast started
- [x] [경계] `ChannelPage` — should ask for the channel again exactly when 5 seconds pass while the channel is live

### 예외

- [x] [예외] `pollInterval` — should not poll while the channel is offline
- [x] [예외] `ChannelPage` — should keep the player when one poll fails while the channel is live

### 수동 확인 (로컬 compose + ffmpeg + 브라우저 패널 — 시나리오 수에 넣지 않는다)

Green 뒤, AC 검증 전에 `docker compose up --build -d`로 전체를 띄워 확인하고 결과를 아래 "확인 결과"와 PR에 남긴다. 키·비밀번호는 적지 않는다.

- 로그인하지 않은 브라우저(쿠키 없는 새 탭)로 방송 중인 `/channels/{id}`를 연다 → `video` 요소의 `currentTime`이 늘어나고, 요청 주소가 `/hls/live/{id}/index.m3u8`, 방송 제목이 보인다
- 오프라인 채널을 열면 `video`가 없고 오프라인 표시
- 보는 중에 ffmpeg를 끊는다 → 5초 폴링 안에 플레이어 자리에 `방송이 종료되었습니다`

**확인 결과 (2026-11-21, Green 뒤, HEAD dc7f4a7):** `docker compose up --build -d`로 새 프론트를 담아 전체를 다시 띄웠다. 채널 21과 그 키(스크래치 파일에만 있음)로 `ffmpeg -re -f lavfi -i testsrc=size=640x360:rate=30 -f lavfi -i sine -c:v libx264 -g 60 -c:a aac -t 45 -f flv "rtmp://localhost:1935/live/21?key={키}"`를 송출했다. 브라우저 패널은 #13 확인 때 로그인했던 테스트 스트리머를 `로그아웃`해 비로그인 시청자로 봤다(`GET /api/members/me` → 401). 시각은 UTC.

| 한 것 | 관찰 |
| --- | --- |
| 송출 전 `/channels/21` | `st3046016259` / `테스트 방송` / **`오프라인`**, `video` 요소 0개 ✅ |
| ffmpeg 시작(04:29:31.131) | MediaMTX `04:29:34 runOnAvailable command started`, 채널 API `live=true`를 04:29:35.303에 확인 |
| `/channels/21` 다시 열기(비로그인) | 화면 `방송 중` + `video` 1개, 방송 제목 `테스트 방송`. 첫 HLS 요청 **`/hls/live/21/index.m3u8`** → `video1_stream.m3u8`·`audio2_stream.m3u8`(MediaMTX `[HLS] [muxer live/21] created`, `is reading from muxer`). `paused=false`, `muted=true`, `readyState=4` ✅ |
| 재생 진행 | `currentTime` 04:29:50.696 → 20.13, 51.712 → 21.15, 52.719 → 22.15, 53.729 → 23.15(1초에 1초씩 늘어남) ✅ |
| ffmpeg 종료(04:30:16.362) | MediaMTX `04:30:16 runOnUnavailable command launched`, `[HLS] [muxer live/21] destroyed`. 열어 둔 페이지가 새로고침 없이 **04:30:19.753에 `방송이 종료되었습니다`**(MediaMTX 종료 뒤 약 3.4초, 5초 폴링 안), `video` 1개 → 0개 ✅ |
| 키 노출 | api-1·api-2 로그(최근 3분)에 `key=` 0줄 |
| 참고 | MediaMTX가 `part duration changed from 200ms to 234ms - this will cause an error in iOS clients` 경고를 냈다. 테스트 송출의 키프레임 간격(`-g 60`, 2초)과 LL-HLS 조각 길이가 맞지 않아 생기는 송출 측 경고로, 이 이슈의 코드와는 관계없다. iOS 실기기 재생은 확인하지 않았다 |

**Red에서 통과했던 테스트의 변이 확인 (Green):** `[경계] ChannelPage — should show the offline notice without a player when a visitor opens an offline channel`이 구현 없이 통과했다(#8의 `오프라인` 줄이 이미 있고 플레이어가 없었다). Green 커밋 뒤 `PlayerArea`가 오프라인에서도 `HlsPlayer`를 그리게 하는 변이를 넣자 Vitest 23개 중 이 테스트와 `PlayerArea` 오프라인 테스트 2개가 실패했다. 덧붙여 `ChannelPage`의 `channel.isError && channel.data === undefined`를 `channel.isError`로 되돌리는 변이에서 `should keep the player when one poll fails while the channel is live` 1개가 실패했다. 둘 다 `git checkout --`으로 되돌렸다.

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 방송 중 채널을 비로그인 시청자가 열면 `/hls/live/{채널ID}/index.m3u8` 재생, 방송 제목 | `[정상] hlsUrl — should build /hls/live/{channelId}/index.m3u8 for the channel`<br>`[정상] HlsPlayer — should load the HLS address into hls.js and attach it to the video when hls.js is supported`<br>`[경계] HlsPlayer — should set the HLS address as the video source when the browser plays HLS without hls.js`<br>`[정상] PlayerArea — should show the player for /hls/live/7/index.m3u8 when the view is live`<br>`[정상] ChannelPage — should show the player and the broadcast title when a visitor opens a live channel`<br>`[정상] ChannelPage — should load /hls/live/7/index.m3u8 into the player when a visitor opens live channel 7` (갭 처리)<br>수동 확인(실제 재생, 비로그인) |
| 2. 오프라인 채널 → 플레이어 대신 오프라인 표시 | `[정상] PlayerArea — should show the offline notice instead of the player when the view is offline`<br>`[경계] ChannelPage — should show the offline notice without a player when a visitor opens an offline channel`<br>`[예외] pollInterval — should not poll while the channel is offline`<br>수동 확인 |
| 3. 시청 중 방송이 끝나면 플레이어 자리에 "방송이 종료되었습니다" | `[정상] PlayerArea — should show 방송이 종료되었습니다 instead of the player when the view is ended`<br>`[정상] ChannelPage — should replace the player with 방송이 종료되었습니다 when the next poll says the channel is no longer live`<br>`[정상] pollInterval — should poll every 5 seconds while the channel is live`<br>`[경계] HlsPlayer — should destroy the hls.js instance when the player is removed`<br>`[예외] ChannelPage — should keep the player when one poll fails while the channel is live`<br>`[경계] pollInterval — should poll every 5000 ms while the channel is live` (갭 처리)<br>`[경계] ChannelPage — should not ask for the channel again before 5 seconds pass while the channel is live` (갭 처리)<br>`[경계] ChannelPage — should ask for the channel again exactly when 5 seconds pass while the channel is live` (2차 갭 처리)<br>수동 확인(ffmpeg 끊기) |
| 4. 상태 로직이 `방송 중`·`오프라인`·`종료됨`으로 바뀌면 맞는 화면(Vitest) | `[정상] nextBroadcastView` 3개, `[경계] nextBroadcastView` 3개<br>`[정상] useBroadcastView — should switch from live to ended when a later response says the channel is offline`<br>`[경계] useBroadcastView — should return null before the first channel response arrives`<br>`[경계] useBroadcastView — should stay offline when a channel that was offline from the start is fetched again`<br>`[경계] pollInterval — should not poll before the first channel response arrives`<br>`[경계] useBroadcastView — should switch from ended to live when a later response says a new broadcast started` (갭 처리) |

**AC에 없는데 넣은 시나리오**

| 시나리오 | 이유 |
| --- | --- |
| `[예외] ChannelPage — should keep the player when one poll fails while the channel is live` | 폴링이 들어오면서 생기는 회귀. 지금 페이지는 오류면 에러 문구만 보여, 일시적 실패 한 번에 보던 영상이 사라진다(판단 4) |
| `[경계] nextBroadcastView — should choose live when a new broadcast starts after the previous one ended` | 창 포커스 재조회로 새 방송이 잡혔을 때 `ended`에 갇히지 않게 |

**커버리지:** AC 4개 / 시나리오 28개(AC 검증 갭 처리 1차 4개·2차 1개 포함) / 미커버 0개 (AC 1~3의 실제 재생·종료는 수동 확인도 함께)

---

## AC 검증

> `harness:ac-verifier` (2026-11-21, HEAD b2b5adc). 변이 최대 3번·20분·스크래치 복사본·파일 단위 테스트·docker compose 조작 금지로 띄웠다. 아래는 검증자 출력 원문이다(제목 수준만 한 단계 낮췄다). 구현자는 맨 아래 `### 구현자 이견`에만 쓴다.

### 이슈 #12 AC 검증 결과 (브랜치 feat/channel/issue-12, HEAD b2b5adc)

**요약: AC 4개 중 충족 3개, 부분 충족 1개, 미충족 0개.** 기준 상태에서 Vitest 3개 파일 23개 테스트가 모두 통과했습니다(스크래치 복사본에서 실행). 변이는 3번 넣었고 그중 2개가 살아남았습니다. 둘 다 구현이 틀린 것이 아니라 테스트가 고정하지 않은 부분입니다.

#### AC별 판정

| AC | 판정 | 근거 테스트 | 구현 | 판단 이유 |
|---|---|---|---|---|
| 1. 방송 중인 채널을 비로그인 시청자가 열면 `/hls/live/{채널ID}/index.m3u8` 재생, 방송 제목 표시 | ⚠️ 부분 충족 | `hlsUrl — should build /hls/live/{channelId}/index.m3u8…` (broadcastView.test.ts:74), `HlsPlayer — should load the HLS address into hls.js…` (LivePlayer.test.tsx:33), `HlsPlayer — should set the HLS address as the video source…` (LivePlayer.test.tsx:40), `PlayerArea — should show the player for /hls/live/7/index.m3u8…` (LivePlayer.test.tsx:58), `ChannelPage — should show the player and the broadcast title…` (ChannelPage.test.tsx:51) | `hlsUrl` broadcastView.ts:34-36, `HlsPlayer` LivePlayer.tsx:8-25, `PlayerArea` LivePlayer.tsx:28-37, ChannelPage.tsx:31(h2 제목)·:32(`channelId` 전달). 비로그인: SecurityConfig.java:58 `GET /api/channels/*` permitAll, PRD "키 확인 연동"(MediaMTX `read`는 인증하지 않음) | 구현은 맞고, 수동 확인 기록(issue-12.md:147-148, 로그아웃 상태, 첫 요청 `/hls/live/21/index.m3u8`, `currentTime`이 1초에 1초씩 늘어남)도 있습니다. 다만 페이지 단계의 테스트가 `video` 요소가 있는지만 봅니다. 페이지의 채널 ID가 재생 주소로 이어지는지는 어떤 자동 테스트도 확인하지 않습니다. 변이 1에서 다른 채널 주소를 넣어도 살아남은 것으로 확인했습니다. 단위 테스트(주소 생성·PlayerArea)와 수동 확인으로는 충족하지만, 페이지 단계 연결은 자동으로 지켜지지 않습니다. |
| 2. 오프라인 채널을 열면 플레이어 대신 오프라인 표시 | ✅ 충족 | `PlayerArea — should show the offline notice instead of the player…` (LivePlayer.test.tsx:64), `ChannelPage — should show the offline notice without a player…` (ChannelPage.test.tsx:60), `pollInterval — should not poll while the channel is offline` (broadcastView.test.ts:68) | LivePlayer.tsx:37, broadcastView.ts:10(첫 응답이 false면 `offline`), :30 | 문구가 있는지와 `video`가 없는지(null)를 둘 다 확인합니다. Red 단계에서 미리 통과하던 테스트는 Green 뒤 변이로 실패하는 것을 확인한 기록이 있습니다(issue-12.md:153). 수동 확인에서도 `video` 0개, `오프라인`이 보였습니다(:145). |
| 3. 시청 중 방송이 끝나면 플레이어 자리에 "방송이 종료되었습니다" | ✅ 충족 | `ChannelPage — should replace the player with 방송이 종료되었습니다 when the next poll…` (ChannelPage.test.tsx:69), `PlayerArea — should show 방송이 종료되었습니다…` (LivePlayer.test.tsx:71), `HlsPlayer — should destroy the hls.js instance…` (LivePlayer.test.tsx:48), `ChannelPage — should keep the player when one poll fails…` (ChannelPage.test.tsx:80) | 폴링 ChannelPage.tsx:16 + broadcastView.ts:27-31, 화면 기억 broadcastView.ts:15-24, 문구 LivePlayer.tsx:37, 폴링 실패 시 화면 유지 ChannelPage.tsx:22 | 페이지 테스트가 가짜 시계로 실제 React Query 폴링, 실제 훅, 실제 PlayerArea를 모두 거칩니다. 화면 기억을 없앤 변이 2에서 이 테스트가 실패했습니다. 수동 확인에서는 ffmpeg 종료 후 약 3.4초 만에 문구가 떴고 `video`가 1개에서 0개로 줄었습니다(:149). "5초"라는 숫자는 AC 문구에는 없고 시그니처 판단 1에 있는 값인데, 테스트가 이 숫자를 고정하지 않습니다(변이 3이 살아남음). AC 판정과는 별개로 약한 단언으로 아래에 적었습니다. |
| 4. 상태가 `방송 중`·`오프라인`·`종료됨`으로 바뀌면 각각 맞는 화면을 고른다(Vitest) | ✅ 충족 | `nextBroadcastView` 6개 (broadcastView.test.ts:6, 10, 14, 18, 22, 26), `useBroadcastView` 3개 (:32, :42, :48), `PlayerArea` 3개 (LivePlayer.test.tsx:58, 64, 71) | broadcastView.ts:8-11, :15-24, LivePlayer.tsx:28-37 | 상태 전환은 순수 함수로 모든 경우를 확인합니다(첫 응답 방송 중/오프라인, 방송 중에서 종료됨, 종료됨 유지, 오프라인에서 방송 중, 종료됨에서 방송 중). 기억을 가진 훅이 방송 중에서 종료됨으로 바꾸는 것도 확인합니다. 상태마다 맞는 화면이 나오는지는 PlayerArea 3개가 확인합니다. Vitest로 돌아갑니다(package.json `"test": "vitest run"`, vite.config.ts `environment: 'jsdom'`). |

#### 변이 결과 (복사본: C:/Users/USER/AppData/Local/Temp/claude/1cbb40fc7d76b7b0/e5a53947-02cd-44f6-934c-3e8edfbbefcf/scratchpad/ac12)

| # | 변이 | 돌린 파일 | 결과 | 의미 |
|---|---|---|---|---|
| 1 | ChannelPage.tsx:32 `channelId={channelId}` → `{channelId + 1}` | ChannelPage.test.tsx | **살아남음** (4개 모두 통과) | 페이지가 엉뚱한 채널의 주소를 재생해도 자동 테스트가 잡지 못합니다. ChannelPage.test.tsx의 가짜 hls.js(:12-19)가 `loadSource` 호출을 기록만 하고 검사에 쓰지 않습니다. 이것이 AC 1을 ⚠️로 본 근거입니다. |
| 2 | broadcastView.ts:21 `setView(nextBroadcastView(view, live))` → `setView(live ? 'live' : 'offline')` (이전 화면 기억 제거) | broadcastView.test.ts, ChannelPage.test.tsx | **잡힘** (각 파일에서 1개씩 실패: `useBroadcastView — should switch from live to ended…`, `ChannelPage — should replace the player with 방송이 종료되었습니다…`) | AC 3·4의 핵심 경로를 테스트가 지키고 있습니다. |
| 3 | broadcastView.ts:27 `LIVE_POLL_INTERVAL_MS = 5000` → `60000` | broadcastView.test.ts, ChannelPage.test.tsx | **살아남음** (13개, 4개 모두 통과) | 테스트가 숫자 5000 대신 상수끼리 비교하고(broadcastView.test.ts:61, ChannelPage.test.tsx:74), 시계도 상수만큼만 넘깁니다. 사용자가 정한 "5초 폴링"이 고정되지 않았습니다. AC 문구에 숫자가 없어 AC 3 판정은 내리지 않았습니다. |

세 변이 모두 `git show HEAD:<파일>`로 복사본 파일을 되돌렸고, 되돌린 줄을 grep으로 확인했습니다.

#### 빈 곳을 메울 시나리오 제안

- [ ] [정상] `ChannelPage` — should load /hls/live/7/index.m3u8 into the player when a visitor opens live channel 7
  - 지금은 가짜 hls.js가 기록만 합니다. LivePlayer.test.tsx처럼 `vi.hoisted`로 인스턴스를 모아 `loadSource`에 그 주소가 들어갔는지 확인하면 됩니다.
- [ ] [경계] `pollInterval` — should poll every 5000 ms while the channel is live
  - 상수 대신 숫자 `5000`으로 확인합니다.
- [ ] [경계] `ChannelPage` — should not ask for the channel again before 5 seconds pass while the channel is live
  - 4999ms를 넘긴 뒤 `fetchChannel` 호출이 1회이고, 5000ms에 2회인지 봅니다.
- [ ] [경계] `useBroadcastView` — should switch from ended to live when a later response says a new broadcast started
  - 순수 함수로만 확인된 전환을 훅에서도 확인합니다. 낮은 우선순위입니다.

#### 가짜 테스트·약한 단언

- 가짜 테스트는 없었습니다. 비동기 테스트는 모두 `await`하고, 단언이 없는 테스트나 인자 없는 `toThrow`도 없습니다.
- ChannelPage.test.tsx:12-19: 가짜 hls.js가 호출을 기록만 하고 검사에 쓰지 않아, 페이지 단계에서 재생 주소가 고정되지 않습니다(변이 1). LivePlayer.test.tsx:6-21의 방식으로 인스턴스를 모아 `loadSource` 인자를 확인하면 됩니다.
- broadcastView.test.ts:61, ChannelPage.test.tsx:74·:85: 상수 `LIVE_POLL_INTERVAL_MS`끼리 비교해서 5초가 고정되지 않습니다(변이 3). 숫자 `5000`을 직접 쓰면 됩니다.
- broadcastView.test.ts:48 (`should stay offline … fetched again`): 같은 값 `false`로 다시 렌더하면 훅이 `live !== seenLive` 분기에 들어가지 않습니다. 그래서 상태 전환 규칙을 실제로 거치지 않고, `nextBroadcastView('offline', false)`가 `ended`를 돌려주도록 바뀌어도 통과합니다. 순수 함수에 `nextBroadcastView('offline', false)` → `'offline'` 단언을 하나 더하면 됩니다. 실제 동작에서는 같은 값이면 훅이 부르지 않으므로 영향은 작습니다.
- LivePlayer.test.tsx:58: PlayerArea가 방송 중일 때 `loadSource` 호출만 보고 `video` 요소나 `방송 중` 문구는 보지 않습니다. AC에는 영향이 없습니다.
- 가짜 hls.js가 생성자 인자를 무시해서 `lowLatencyMode: true`(LivePlayer.tsx:18, 시그니처·infra-validation 3절)가 테스트되지 않습니다. AC 범위 밖이라 참고로만 적습니다.
- `expect(await screen.findByText(...)).not.toBeNull()` (ChannelPage.test.tsx:56 등): `findByText`는 못 찾으면 그 자리에서 예외를 던지므로 `not.toBeNull`은 군더더기일 뿐 가짜는 아닙니다.

#### 실행한 명령

1. `cat .claude/harness.json && gh issue view 12 && git diff origin/main...HEAD --stat` → 설정을 확인했습니다(scenariosDir=docs/result/scenarios). AC 4개, 바뀐 파일 12개입니다.
2. `cat -n docs/result/scenarios/issue-12.md` → 시나리오 23개, AC 대조표, 수동 확인 기록(:141-153)을 확인했습니다.
3. `cat -n broadcastView.ts LivePlayer.tsx ChannelPage.tsx` + `git diff origin/main...HEAD -- ChannelPage.tsx vite.config.ts package.json harness.json` → 구현과 설정 변경을 확인했습니다.
4. `cat -n broadcastView.test.ts LivePlayer.test.tsx ChannelPage.test.tsx; ls pages api; cat -n api/channels.ts` → 테스트 본문(13개, 6개, 4개)을 읽었습니다.
5. `cat -n client.ts | head -40; grep -rn hls *.conf/*.yml; grep SecurityConfig channels; grep PRD 시청 주소/화질` → nginx.conf:64에 `location /hls/`가 있고, SecurityConfig.java:58은 `GET /api/channels/*` permitAll입니다. PRD:109의 시청 주소, :93의 화질 결정도 확인했습니다.
6. `git archive HEAD | tar -x -C …/scratchpad/ac12 && cp -r frontend/node_modules … && npx vitest run src/pages/broadcastView.test.ts src/pages/LivePlayer.test.tsx src/pages/ChannelPage.test.tsx` → 기준 상태에서 3개 파일 23개 모두 통과했습니다.
7. 변이 1: `sed` ChannelPage.tsx:32 → `npx vitest run src/pages/ChannelPage.test.tsx` → 4개 통과(살아남음), `git show HEAD:…` 로 되돌렸습니다.
8. 변이 2: `sed` broadcastView.ts:21 → `npx vitest run src/pages/broadcastView.test.ts` 1개 실패, `npx vitest run src/pages/ChannelPage.test.tsx` 1개 실패(잡힘), 되돌렸습니다.
9. 변이 3: `sed` broadcastView.ts:27 (5000→60000) → 두 파일 13개·4개 모두 통과(살아남음), 되돌렸습니다.
10. `grep spec-fixed.md "방송이 종료되었습니다|자동 전환"; grep infra-validation.md lowLatencyMode; git status --short` → 확정 사양 :104-105와 infra-validation :98을 확인했습니다. 원본 저장소 상태는 아래와 같습니다.

#### 원본 저장소 상태

`git -C C:/live-streaming-project status --short` 출력이 **비어 있습니다**. 원본 파일은 고치지 않았고 checkout·stash·commit도 하지 않았습니다. docker compose도 건드리지 않았습니다. 변이와 테스트 실행은 모두 스크래치 복사본 C:/Users/USER/AppData/Local/Temp/claude/1cbb40fc7d76b7b0/e5a53947-02cd-44f6-934c-3e8edfbbefcf/scratchpad/ac12 에서만 했습니다.

관련 파일:
- C:/live-streaming-project/frontend/src/pages/broadcastView.ts
- C:/live-streaming-project/frontend/src/pages/LivePlayer.tsx
- C:/live-streaming-project/frontend/src/pages/ChannelPage.tsx
- C:/live-streaming-project/frontend/src/pages/broadcastView.test.ts
- C:/live-streaming-project/frontend/src/pages/LivePlayer.test.tsx
- C:/live-streaming-project/frontend/src/pages/ChannelPage.test.tsx
- C:/live-streaming-project/docs/result/scenarios/issue-12.md

### 구현자 이견

- 이견 없음. AC 1 ⚠️(페이지의 채널 ID가 재생 주소로 이어지는지)는 맞는 지적이라 메운다(아래 갭 처리).
- 검증자 제안 4개를 모두 메운다. AC 1 갭은 첫 번째 하나지만, 나머지 셋(5초 숫자 고정, 5초 전 다시 묻지 않음, 훅의 종료됨 → 방송 중)은 사용자가 정한 "5초 폴링"과 훅의 전환을 테스트에 박는 것이라 값이 같다.
- 약한 단언 중 `nextBroadcastView('offline', false)` 단언, `PlayerArea` live의 `video` 확인, `lowLatencyMode` 인자 확인은 제안 목록 밖이라 이번에는 더하지 않았다(검증자가 AC 영향 없음·참고로 분류).

### 갭 처리

사전 승인에 따라 메움. 검증자가 제안한 시나리오 4개를 문구 그대로 `## 테스트 시나리오`(정상 1개, 경계 3개)와 AC 대조표(1·3·4행)에 더하고 Red → Green으로 메웠다.

#### 갭 처리 결과

4개 모두 구현이 이미 맞아 바로 통과했다(커밋 a4ae307, Vitest 27개 통과, `ChannelPage.test.tsx`는 3번 다시 돌려도 6개 통과). 저장소에 변이를 하나씩 넣어 실패를 확인하고 `git checkout --`으로 되돌렸다.

| 변이 | 실패한 테스트 |
| --- | --- |
| `ChannelPage.tsx` `channelId={channelId + 1}`(검증자 변이 1) | `ChannelPage — should load /hls/live/7/index.m3u8 into the player when a visitor opens live channel 7` |
| `LIVE_POLL_INTERVAL_MS = 4000` | `pollInterval — should poll every 5000 ms while the channel is live`, `ChannelPage — should not ask for the channel again before 5 seconds pass while the channel is live` |
| `useBroadcastView`가 `ended`에서 벗어나지 않게(`view === 'ended' ? 'ended' : …`) | `useBroadcastView — should switch from ended to live when a later response says a new broadcast started` |

`should not ask for the channel again before 5 seconds pass…`는 4999ms 대신 4500ms에서 호출 1회, 5000ms에서 2회를 본다. 테스트가 `vi.useFakeTimers({ shouldAdvanceTime: true })`라 실제로 흐른 몇 ms가 가짜 시계에 더해져 4999ms는 흔들릴 수 있다. 4000ms 변이는 잡는다(위 표).

### 2차 검증 (AC 1·3)

> `harness:ac-verifier` (2026-11-21, HEAD b8f22d4). 범위를 AC 1·3으로 좁히고 1차와 같은 제약으로 띄웠다. 아래는 출력 원문이다(제목 수준만 두 단계 낮췄다).

#### 이슈 #12 2차 AC 검증 (AC 1·3, HEAD b8f22d4)

**요약: 1차 갭은 메워졌습니다. AC 1은 ⚠️에서 ✅로 올라가고, AC 3은 ✅ 그대로입니다.** 1차에서 살아남은 변이 두 개(`channelId + 1`, 5000→60000)를 같은 방식으로 다시 넣었더니 이번에는 둘 다 테스트가 잡았습니다. 남은 것은 AC 밖의 약한 단언 하나입니다. 페이지 단계에서 "5초"가 4.5초에서 5초 사이로만 고정됩니다(변이 3이 살아남음).

기준 상태에서 스크래치 복사본으로 `ChannelPage.test.tsx` 6개와 `broadcastView.test.ts` 15개를 돌렸고 모두 통과했습니다.

##### AC별 판정

| AC | 판정 | 근거 테스트 | 구현 | 판단 이유 |
|---|---|---|---|---|
| 1. 방송 중인 채널을 비로그인 시청자가 열면 `/hls/live/{채널ID}/index.m3u8`을 재생하고 방송 제목이 보인다 | ✅ 충족 | `ChannelPage — should load /hls/live/7/index.m3u8 into the player when a visitor opens live channel 7` (frontend/src/pages/ChannelPage.test.tsx:66), `ChannelPage — should show the player and the broadcast title when a visitor opens a live channel` (ChannelPage.test.tsx:57), `hlsUrl — should build /hls/live/{channelId}/index.m3u8 for the channel` (broadcastView.test.ts:89), `HlsPlayer — should load the HLS address into hls.js and attach it to the video…` (LivePlayer.test.tsx:33), `PlayerArea — should show the player for /hls/live/7/index.m3u8 when the view is live` (LivePlayer.test.tsx:58) | 라우트의 채널 ID ChannelPage.tsx:9, 제목 `<h2>` ChannelPage.tsx:31, 채널 ID를 플레이어로 넘김 ChannelPage.tsx:32, 재생 주소 `hlsUrl` broadcastView.ts:34-36, 재생 `HlsPlayer` LivePlayer.tsx:8-25, 화면 고르기 `PlayerArea` LivePlayer.tsx:28-34. 비로그인 조회: SecurityConfig.java:58 `GET /api/channels/*` permitAll | 1차 갭이 메워졌습니다. 새 테스트(:12-24, :71)는 가짜 hls.js 인스턴스를 모아 둡니다. 그리고 라우트 `/channels/7`에서 시작해 진짜 React Query, 진짜 훅, 진짜 PlayerArea·HlsPlayer를 거친 뒤 `loadSource`가 `'/hls/live/7/index.m3u8'`로 불렸는지 확인합니다. 이 단언은 목이 돌려준 값을 보는 것이 아니라 구현이 넘긴 인자를 봅니다. `instances[0]`이 없으면 `waitFor`가 실패하므로 가짜 테스트도 아닙니다. 변이 1에서 실제로 `/hls/live/8/index.m3u8`을 받아 실패했습니다. 제목은 :62에서 `findByText('오늘은 노래')`로 확인하고, 그 값은 `name`(`하늘`)과 다른 `title`입니다. 비로그인 실재생은 수동 확인 기록(issue-12.md:145-152, 로그아웃 상태에서 `/hls/live/21/index.m3u8` 요청, `currentTime` 증가)으로 보완됩니다. |
| 3. 시청 중에 방송이 끝나면 플레이어 자리에 "방송이 종료되었습니다"가 보인다 | ✅ 충족 | `ChannelPage — should replace the player with 방송이 종료되었습니다 when the next poll says the channel is no longer live` (ChannelPage.test.tsx:96), `ChannelPage — should not ask for the channel again before 5 seconds pass while the channel is live` (ChannelPage.test.tsx:74), `pollInterval — should poll every 5000 ms while the channel is live` (broadcastView.test.ts:75), `useBroadcastView — should switch from live to ended…` (broadcastView.test.ts:32), `PlayerArea — should show 방송이 종료되었습니다 instead of the player when the view is ended` (LivePlayer.test.tsx:71), `ChannelPage — should keep the player when one poll fails while the channel is live` (ChannelPage.test.tsx:107) | 폴링 ChannelPage.tsx:16 + broadcastView.ts:27-31, 이전 화면을 기억해 종료됨을 고름 broadcastView.ts:8-11·:15-24, 문구 LivePlayer.tsx:37, 폴링이 한 번 실패해도 화면 유지 ChannelPage.tsx:22 | 페이지 테스트가 가짜 시계로 실제 폴링을 돌립니다. 문구가 나타나는지와 `video`가 사라지는지(`toBeNull`, :104)를 둘 다 봅니다. 1차 변이 3(5000→60000)이 이번에는 두 파일에서 모두 잡혔습니다(변이 2). :84의 `toEqual([1, 2])`는 4.5초까지는 다시 묻지 않고 5초에 다시 묻는다는 것을 한 번에 확인하므로 단언이 강합니다. 수동 확인에서도 ffmpeg 종료 뒤 약 3.4초에 문구가 떴고 `video`가 1개에서 0개로 줄었습니다(issue-12.md:153). |

##### 변이 결과
복사본은 C:/Users/USER/AppData/Local/Temp/claude/1cbb40fc7d76b7b0/e5a53947-02cd-44f6-934c-3e8edfbbefcf/scratchpad/ac12v2 입니다.

| # | 변이 | 돌린 파일 | 결과 | 의미 |
|---|---|---|---|---|
| 1 | ChannelPage.tsx:32 `channelId={channelId}` → `{channelId + 1}` (1차 변이 1) | ChannelPage.test.tsx | **잡힘** (6개 중 1개 실패: `should load /hls/live/7/index.m3u8…`, 받은 값 `"/hls/live/8/index.m3u8"`) | AC 1의 1차 갭이 메워졌습니다. |
| 2 | broadcastView.ts:27 `LIVE_POLL_INTERVAL_MS = 5000` → `60000` (1차 변이 3) | broadcastView.test.ts, ChannelPage.test.tsx | **잡힘** (`pollInterval — should poll every 5000 ms…` 15개 중 1개 실패, `ChannelPage — should not ask for the channel again before 5 seconds pass…` 6개 중 1개 실패) | 5초 폴링 숫자가 고정됐습니다. |
| 3 | ChannelPage.tsx:16 `pollInterval(...)`을 거치지 않고 `query.state.data?.live ? 4800 : false`로 바꿈 | ChannelPage.test.tsx | **살아남음** (6개 모두 통과) | 페이지 단계의 폴링 간격은 4.5초 초과~5초 범위로만 고정됩니다. 구현자도 이 여유를 issue-12.md:275에 적어 두었습니다(`shouldAdvanceTime` 때문에 4999ms는 흔들릴 수 있음). AC 3 문구에는 숫자가 없어 판정에는 반영하지 않았습니다. `pollInterval` 단위 테스트가 5000을 고정하고 있어서, 이 변이는 페이지가 `pollInterval`을 거치지 않는 경우에만 생깁니다. |

세 변이 모두 `git -C C:/live-streaming-project show HEAD:<파일> > <복사본 파일>`로 되돌렸고, 되돌린 줄을 grep으로 확인했습니다.

##### 남은 갭
AC 1·3에는 남은 갭이 없습니다. 아래는 AC 밖(사용자가 정한 "5초 폴링")을 조이고 싶을 때 쓸 수 있는 **선택** 제안입니다.

- [ ] [경계] `ChannelPage` — should ask for the channel again exactly when 5 seconds pass while the channel is live
  - 방법: `shouldAdvanceTime` 없이 가짜 시계를 쓰고, 첫 응답을 기다릴 때 `vi.advanceTimersByTimeAsync(0)`로 흘립니다. 그런 다음 4999ms에서 1회, 5000ms에서 2회를 확인합니다. 지금 테스트의 여유(4.5초)를 없애 변이 3을 잡게 됩니다.

##### 가짜 테스트·약한 단언
- 새 테스트 4개 중 가짜 테스트는 없습니다. 모두 `await waitFor` 또는 `await vi.advanceTimersByTimeAsync`를 쓰고, 단언이 있으며, 목이 돌려준 값이 아니라 구현이 넘긴 인자나 호출 횟수를 봅니다.
- ChannelPage.test.tsx:80-84: 위에서 말한 4.5초 여유입니다(변이 3이 살아남음). 크기는 작습니다.
- broadcastView.test.ts:71 (`should poll every 5 seconds…`)은 여전히 상수끼리 비교합니다. 다만 :75의 숫자 5000 테스트가 같은 것을 고정하므로 지금은 중복일 뿐 해롭지 않습니다.

##### 실행한 명령
1. `cat .claude/harness.json && gh issue view 12 && git log --oneline -6 && git diff --stat origin/main...HEAD` → scenariosDir=docs/result/scenarios, AC 4개, HEAD b8f22d4, 바뀐 파일 12개
2. `cat -n docs/result/scenarios/issue-12.md`(출력이 커서 파일로 저장됨) + Grep `^## ` + Read :99-276 → 시나리오 27개, AC 대조표, 1차 검증 원문, 갭 처리 기록 확인
3. `cat -n ChannelPage.tsx broadcastView.ts LivePlayer.tsx` → 구현 줄 번호 확인
4. `cat -n ChannelPage.test.tsx broadcastView.test.ts LivePlayer.test.tsx` → 새 테스트 4개 본문 확인(ChannelPage :66·:74, broadcastView :48·:75)
5. `mkdir …/scratchpad/ac12v2 && git archive HEAD | tar -x -C … && cp -r frontend/node_modules … && npx vitest run src/pages/ChannelPage.test.tsx` / `… broadcastView.test.ts` → 기준 상태 6개·15개 모두 통과
6. 변이 1: `sed` ChannelPage.tsx:32 → `npx vitest run src/pages/ChannelPage.test.tsx` → 1개 실패(잡힘), `git show HEAD:…`로 되돌리고 grep으로 확인
7. 변이 2: `sed` broadcastView.ts:27 5000→60000 → 두 파일에서 각각 1개 실패(잡힘), 되돌리고 grep으로 확인
8. 변이 3: `sed` ChannelPage.tsx:16 → 4800 하드코딩 → 6개 모두 통과(살아남음), 되돌리고 grep으로 확인, 이어서 `git status --short`
9. Grep `api/channels` (SecurityConfig.java) → :58 `GET /api/channels/*` permitAll
10. `git -C C:/live-streaming-project status --short | wc -c; git rev-parse --short HEAD` → `0`, `b8f22d4`

##### 원본 저장소 상태
`git -C C:/live-streaming-project status --short` 출력이 **비어 있습니다**(0바이트). HEAD는 그대로 b8f22d4입니다. 원본 파일 수정, checkout, stash, commit은 하지 않았습니다. 백엔드 테스트와 docker compose도 건드리지 않았습니다. 변이는 3번 썼고, 걸린 시간은 20분 이내입니다.

#### 2차 뒤 처리

남은 선택 제안(`[경계] ChannelPage — should ask for the channel again exactly when 5 seconds pass while the channel is live`)은 1차 제안 "4999ms를 넘긴 뒤 1회, 5000ms에 2회"를 내가 4.5초로 좁게 옮겨서 생긴 갭이라, 사전 승인에 따라 한 번 더 메우고 3차는 띄우지 않았다(아래).

`[경계] ChannelPage — should ask for the channel again exactly when 5 seconds pass while the channel is live`: 구현이 이미 맞아 바로 통과했다(커밋 e9b0e23, `ChannelPage.test.tsx` 7개를 5번 돌려 모두 통과). 이 테스트만 `shouldAdvanceTime` 없는 가짜 시계를 쓰고 첫 응답은 0ms씩 흘려 기다린다. 2차 검증의 변이 3(`refetchInterval`을 `pollInterval`을 거치지 않고 `live ? 4800 : false`로)을 저장소에 넣자 Vitest 28개 중 이 테스트 1개만 실패했다. `git checkout --`으로 되돌렸다. 3차 검증은 띄우지 않았다(사전 승인).

---

## 리팩터

없음. 이번 이슈의 소스 파일(`broadcastView.ts`, `LivePlayer.tsx`, `ChannelPage.tsx`)에서 근거가 분명한 대상을 찾지 못했다.
- 가짜 hls.js가 `LivePlayer.test.tsx`와 `ChannelPage.test.tsx`에 두 번 있지만 테스트 파일이라 리팩터 범위 밖이다(두 번째이기도 하다).
- `broadcastView.test.ts`의 `should poll every 5 seconds…`(상수 비교)와 `should poll every 5000 ms…`(숫자 비교)가 겹치지만, 둘 다 승인된 시나리오라 지우지 않는다.

---

## 보안 점검

> `/harness:security-review 12` (2026-11-21). 사전 승인에 따라 🔴는 고치고, 🟡는 "사용자 결정 대기"로 남긴다.

**수집:** `tsc -b`·`npm run lint` 성공, `npm audit` 0건(새 의존성 `hls.js` 1.7.4, `vitest` 5.1.1, `@testing-library/react` 16.3.3, `jsdom` 31.0.1, 피어 `@testing-library/dom` 10.4.2), 비밀값 패턴·알려진 접두사 grep 0건, `console.log/info/debug`·`dangerouslySetInnerHTML`·`innerHTML` 0건, `VITE_` 환경변수 없음, 브랜치가 커밋한 `.env` 없음. 백엔드는 바뀌지 않았다.

| 분류 | 파일·줄 | 무엇 | 판단 | 처리 |
| --- | --- | --- | --- | --- |
| 🟡 | `ChannelPage.tsx` `refetchInterval` | 비로그인 시청자의 폴링 부하. 방송 중 채널을 보는 시청자마다 5초에 한 번 `GET /api/channels/{id}`(Spring + Oracle 한 행 + 유니크 인덱스 EXISTS)를 보낸다. 시청자 N명이면 초당 N/5건 | 지금 규모(1인 플랫폼 + 데모 3~5개)에서는 문제없다. 오프라인 채널은 묻지 않고(판단 2), 익명 조회는 세션을 만들지 않는다(`curl -D -`로 `Set-Cookie` 없음 확인, `Cache-Control: no-cache…`). 같은 시청자가 이미 HLS 조각을 초당 약 5건(LL-HLS 200ms 조각) 받으므로 폴링은 시청자당 요청의 약 4%다. 다만 HLS는 nginx·MediaMTX로 가고 폴링은 Spring·DB로 간다. 시청자가 수백 명 넘게 몰리는 상황을 대비하려면 채널 조회에 짧은 캐시(예: 1~2초 `Cache-Control: max-age` + nginx 캐시, 또는 Redis)를 두는 선택지가 있다 | **사용자 결정 대기** |
| ⚪ | `ChannelPage.tsx` 제목 | 스트리머가 정한 제목을 시청자 화면에 보인다 | JSX 텍스트로만 넣는다. #13 수동 확인에서 `<img onerror>` 제목이 글자 그대로 보이고 스크립트가 돌지 않았다 | 그대로 |
| ⚪ | `LivePlayer.tsx` 재생 주소 | `hlsUrl(Number(useParams().channelId))` | 주소는 같은 출처의 `/hls/live/{숫자}/index.m3u8`뿐이다. 채널 API가 그 id로 채널을 돌려준 뒤에만 플레이어를 그린다. 시청은 원래 인증 대상이 아니다(PRD "키 확인 연동"). 재생 주소·응답에 키가 없다(키는 송출 query에만, #10) | 그대로 |
| ⚪ | `frontend/package.json` | `hls.js`가 운영 번들에 들어간다(빌드가 500kB 청크 경고) | 보안 문제가 아니라 크기 경고다. `npm audit` 0건 | 그대로(PR에 적음) |
| ⚪ | devDependencies 3개 | `vitest`·`@testing-library/react`·`jsdom` | 테스트 전용, 운영 번들에 들어가지 않는다 | 그대로 |

**결과:** 🔴 0, 🟡 1(폴링 부하 — 사용자 결정 대기). 재스캔은 위 수집과 같다.
