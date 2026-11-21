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
| 1. 방송 중 채널을 비로그인 시청자가 열면 `/hls/live/{채널ID}/index.m3u8` 재생, 방송 제목 | `[정상] hlsUrl — should build /hls/live/{channelId}/index.m3u8 for the channel`<br>`[정상] HlsPlayer — should load the HLS address into hls.js and attach it to the video when hls.js is supported`<br>`[경계] HlsPlayer — should set the HLS address as the video source when the browser plays HLS without hls.js`<br>`[정상] PlayerArea — should show the player for /hls/live/7/index.m3u8 when the view is live`<br>`[정상] ChannelPage — should show the player and the broadcast title when a visitor opens a live channel`<br>수동 확인(실제 재생, 비로그인) |
| 2. 오프라인 채널 → 플레이어 대신 오프라인 표시 | `[정상] PlayerArea — should show the offline notice instead of the player when the view is offline`<br>`[경계] ChannelPage — should show the offline notice without a player when a visitor opens an offline channel`<br>`[예외] pollInterval — should not poll while the channel is offline`<br>수동 확인 |
| 3. 시청 중 방송이 끝나면 플레이어 자리에 "방송이 종료되었습니다" | `[정상] PlayerArea — should show 방송이 종료되었습니다 instead of the player when the view is ended`<br>`[정상] ChannelPage — should replace the player with 방송이 종료되었습니다 when the next poll says the channel is no longer live`<br>`[정상] pollInterval — should poll every 5 seconds while the channel is live`<br>`[경계] HlsPlayer — should destroy the hls.js instance when the player is removed`<br>`[예외] ChannelPage — should keep the player when one poll fails while the channel is live`<br>수동 확인(ffmpeg 끊기) |
| 4. 상태 로직이 `방송 중`·`오프라인`·`종료됨`으로 바뀌면 맞는 화면(Vitest) | `[정상] nextBroadcastView` 3개, `[경계] nextBroadcastView` 3개<br>`[정상] useBroadcastView — should switch from live to ended when a later response says the channel is offline`<br>`[경계] useBroadcastView — should return null before the first channel response arrives`<br>`[경계] useBroadcastView — should stay offline when a channel that was offline from the start is fetched again`<br>`[경계] pollInterval — should not poll before the first channel response arrives` |

**AC에 없는데 넣은 시나리오**

| 시나리오 | 이유 |
| --- | --- |
| `[예외] ChannelPage — should keep the player when one poll fails while the channel is live` | 폴링이 들어오면서 생기는 회귀. 지금 페이지는 오류면 에러 문구만 보여, 일시적 실패 한 번에 보던 영상이 사라진다(판단 4) |
| `[경계] nextBroadcastView — should choose live when a new broadcast starts after the previous one ended` | 창 포커스 재조회로 새 방송이 잡혔을 때 `ended`에 갇히지 않게 |

**커버리지:** AC 4개 / 시나리오 23개 / 미커버 0개 (AC 1~3의 실제 재생·종료는 수동 확인도 함께)
