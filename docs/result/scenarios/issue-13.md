# 이슈 #13 — 스튜디오에서 방송 제목을 정하고 연결 상태를 본다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/13
> PRD: `docs/result/prd/channel.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

> 게이트 처리: 사용자가 자리를 비운 동안 진행했다. 사전 승인(#13 → #12 진행 지시)에 따라 시그니처·시나리오를 구현자가 정하고 승인된 것으로 진행했다.
>
> 순서: `docs/result/build-order.md`는 #12가 먼저지만 #13을 먼저 한다. #12 AC 1("방송 제목이 보인다")이 이 이슈에서 생기는 방송 제목에 기대기 때문이다(사용자가 정한 순서).

---

## 시그니처

### 관련 결정

- **채널 PRD 작은 결정(방송 제목)** — 제목은 채널에 둔다. 방송 전에 정한 제목이 다음 방송 제목이고, 방송 중에 바꾸면 바로 반영된다. 방송 기록(`broadcast`)에는 남기지 않는다.
- **확정 사양 4절** — 방송 제목 1~50자. 정하지 않았으면 "{채널 이름}의 방송". 스튜디오에서 지금 송출이 들어오는지(연결 상태)를 본다.
- **#11** — "진행 중 방송"(`broadcast.ended_at IS NULL`)이 있으면 `Channel.live`가 true. 연결 상태는 이 값을 그대로 쓴다.
- **채널 PRD 작은 결정(패키지)** — 제목은 `com.livestream.channel`.

### DB

`backend/src/main/resources/db/migration/V6__add_title_to_channel.sql`

```sql
-- 방송 제목(확정 사양 4절, 1~50자). 채널에 둔다(PRD channel 작은 결정).
-- NULL = 정한 적 없음 → 응답에서 "{채널 이름}의 방송"
ALTER TABLE channel ADD (title VARCHAR2(50 CHAR));
```

### 타입

```java
// ── com.livestream.channel ─────────────────────────────

public class Channel {
    // 추가. 정한 적 없으면 null
    private String title;
}

// title 추가(맨 끝). 저장값이 null이면 "{name}의 방송". 채널 페이지와 스튜디오가 같은 값을 본다
public record ChannelResponse(long id, String name, String description, boolean live, String title) {}

// 확정 사양 4절: 방송 제목 1~50자. 채널 이름(UpdateChannelRequest)과 같은 방식 —
// 공백만 있는 제목은 @NotBlank가, 51자는 @Size가 막는다(공백을 깎아 저장하지 않는다)
public record UpdateTitleRequest(
        @NotBlank(message = "방송 제목을 입력해 주세요")
        @Size(max = 50, message = "방송 제목은 1~50자입니다") String title) {}

public interface ChannelMapper {
    // 추가
    void updateTitle(@Param("id") long id, @Param("title") String title);
}
// ChannelMapper.xml 공통 <sql id="columns">에 c.title 추가

public class ChannelService {
    // 추가. 내 채널의 제목을 바꾼다. 채널이 없으면 CHANNEL_NOT_FOUND
    public ChannelResponse updateTitle(long memberId, UpdateTitleRequest request);
    // toResponse: title이 null이면 name + "의 방송"
}

class ChannelController {
    // 추가. 스트리머만(SecurityConfig의 /api/studio/**). 내 채널은 세션으로 정한다(스트림 키와 같은 모양)
    @PutMapping("/api/studio/title")
    ChannelResponse updateTitle(@AuthenticationPrincipal LoginMember loginMember,
                                @Valid @RequestBody UpdateTitleRequest request);
}
```

### 프론트

| 파일 | 바꾸는 것 |
| --- | --- |
| `frontend/src/api/channels.ts` | `ChannelResponse.title: string`, `updateTitle(title: string): Promise<ChannelResponse>`(`PUT /api/studio/title`) |
| `frontend/src/pages/StudioPage.tsx` | 방송 제목 입력·저장(`TitleForm`), 연결 상태 `송출 중`/`송출 없음`(`channel.live`). 스튜디오 채널 조회를 5초마다 다시 해서 송출을 켜고 끄면 몇 초 안에 바뀐다 |
| `frontend/src/pages/ChannelPage.tsx` | 방송 제목을 보인다(텍스트로 넣는다 — React가 이스케이프한다) |

### 에러 케이스

| 상황 | 에러 코드 | HTTP |
| --- | --- | --- |
| 제목이 빈 문자열·공백만·없음 | `INVALID_INPUT`("방송 제목을 입력해 주세요") | 400 |
| 제목 51자 이상 | `INVALID_INPUT`("방송 제목은 1~50자입니다") | 400 |
| 비로그인 | `UNAUTHORIZED`(기존 보안 설정) | 401 |
| 스트리머가 아님 | `FORBIDDEN`(기존 `/api/studio/**`) | 403 |
| 스트리머인데 채널이 없음 | `CHANNEL_NOT_FOUND` | 404 |

### 판단이 갈린 곳

1. **주소는 `/api/studio/title`(세션의 내 채널)이다.** `PUT /api/channels/{id}/title`로 두면 주인 확인을 서비스가 다시 해야 한다. 스튜디오의 다른 기능(스트림 키)이 이미 "세션 = 내 채널" 모양이라 같은 모양을 따른다.
2. **응답의 `title`은 기본값을 채운 값이다.** 채널 페이지(#12 AC 1)와 스튜디오 둘 다 "보이는 제목"만 필요하다. 저장값(null)을 따로 내보내지 않는다. 채널 이름을 바꾸면 제목을 정한 적 없는 채널의 기본 제목도 따라 바뀐다(저장값이 null이므로).
3. **공백을 깎지 않는다.** 채널 이름(#8)과 같다. "앞뒤 공백을 뺀 0자"는 `@NotBlank`가 막는다.
4. **연결 상태는 따로 API를 만들지 않는다.** `GET /api/studio/channel`의 `live`(#11)가 곧 "진행 중 방송이 있다"이다. 스튜디오는 이 조회를 5초마다 다시 한다(스트리머 본인 한 명뿐이라 부하가 없다). 5초는 #12의 채널 페이지 폴링과 같은 값이다.
5. **화면 문구(`송출 중`/`송출 없음`)는 수동 확인으로 본다.** 프론트 테스트 도구는 #12에서 들어온다(CLAUDE.md). 이 이슈의 자동 테스트는 API가 `live`를 맞게 내보내는지까지다.

### 이 이슈에서 만들지 않는 것

- 플레이어, 시청 중 종료 감지, 채널 페이지 폴링 — #12
- 금지어 제목 — PRD 하지 않을 것
- 방송 기록에 제목 남기기 — PRD 작은 결정(VOD 없음)

---

## 테스트 시나리오

### 정상

- [x] [정상] `ChannelService.find` — should return the stored title when the channel has a title
- [x] [정상] `ChannelService.updateTitle` — should save the title and return it when the streamer sets a title
- [x] [정상] `PUT /api/studio/title` — should return 200 with the channel showing the new title when the streamer sends a valid title
- [x] [정상] `ChannelIntegration` — should show the new title on the channel page when the streamer changes the title during a live broadcast
- [x] [정상] `ChannelIntegration` — should return live true from the studio channel when the channel has a live broadcast
- [x] [정상] `ChannelIntegration` — should return live false from the studio channel when the channel has no live broadcast

### 경계

- [x] [경계] `ChannelService.find` — should return the channel name followed by 의 방송 as the title when no title has been set
- [x] [경계] `PUT /api/studio/title` — should accept a title of 1 character
- [x] [경계] `PUT /api/studio/title` — should accept a title of 50 characters
- [x] [경계] `ChannelIntegration` — should save a 1-character title and show it on the channel page
- [x] [경계] `ChannelIntegration` — should save a 50-character title and show it on the channel page
- [x] [경계] `ChannelIntegration` — should show 하늘의 방송 as the title on the channel page when the channel 하늘 has never set a title

### 예외

- [x] [예외] `ChannelService.updateTitle` — should throw CHANNEL_NOT_FOUND without saving when the member has no channel
- [x] [예외] `PUT /api/studio/title` — should return 400 with code INVALID_INPUT when the title has 51 characters
- [x] [예외] `PUT /api/studio/title` — should return 400 with code INVALID_INPUT when the title is only spaces
- [x] [예외] `PUT /api/studio/title` — should return 400 with code INVALID_INPUT when the title is empty
- [x] [예외] `PUT /api/studio/title` — should return 400 with code INVALID_INPUT when the title is missing
- [x] [예외] `ChannelIntegration` — should return 400 with code INVALID_INPUT and keep the previous title when the streamer sends a 51-character title
- [x] [예외] `ChannelIntegration` — should return 400 with code INVALID_INPUT and keep the previous title when the streamer sends a title of only spaces
- [x] [예외] `ChannelIntegration` — should return 403 with code FORBIDDEN when a member who is not a streamer sets a title

### 수동 확인 (로컬 compose + ffmpeg + 브라우저 패널 — 시나리오 수에 넣지 않는다)

Green 뒤, AC 검증 전에 `docker compose up --build -d`로 전체를 띄워 확인하고 결과를 아래 "확인 결과"와 PR에 남긴다. 키·비밀번호는 적지 않는다.

- 스튜디오에서 제목을 저장 → 채널 페이지에 그 제목
- 송출 없음 → 스튜디오 연결 상태 `송출 없음`. ffmpeg 송출 시작 → 몇 초 안에 `송출 중`(5초 다시 조회). 끊으면 `송출 없음`
- 송출 중에 제목을 바꾸고 채널 페이지를 다시 열면 새 제목

**확인 결과 (2026-11-21, Green 뒤, HEAD beaafe9):** `docker compose up --build -d`로 전체를 띄웠다(api 기동 로그 `Migrating schema "APP" to version "6 - add title to channel"` → `now at version v6`). #10·#11 수동 확인 때 만든 채널 21과 그 키(스크래치 파일에만 있음)로 `ffmpeg -re -f lavfi -i testsrc=size=640x360:rate=30 -f lavfi -i sine -c:v libx264 -g 60 -c:a aac -t 60 -f flv "rtmp://localhost:1935/live/21?key={키}"`를 송출했다. 화면은 브라우저 패널에서 채널 21의 스트리머로 로그인해 봤다. 시각은 UTC.

| 한 것 | 관찰 |
| --- | --- |
| 제목을 정하기 전 | `GET /api/channels/21` → `"title":"st3046016259의 방송"`(채널 이름 + "의 방송"). 스튜디오 제목 입력란도 이 값 ✅ |
| 스튜디오 첫 화면(송출 없음) | `연결 상태: 송출 없음` ✅ |
| 스튜디오에서 제목 저장 | `저장했습니다`. 채널 API `title`이 저장한 값 ✅ |
| ffmpeg 시작(04:14:28.168) | MediaMTX `04:14:32 [path live/21] runOnAvailable command started` → `is publishing to path 'live/21'`. 열어 둔 스튜디오가 다시 읽지 않고(새로고침 없이) **04:14:33.426에 `연결 상태: 송출 중`** ✅ |
| 송출 중에 제목 변경 → `/channels/21` 다시 열기 | `방송 중`, 제목 **`방송 중에 바꾼 제목`** ✅ |
| ffmpeg 종료(04:15:28.523) | MediaMTX `04:15:29 runOnUnavailable command launched`. 스튜디오 다시 열기 → `송출 없음` |
| 덧붙여(폴링 전환, 15초 송출) | 스튜디오를 열어 둔 채 ffmpeg 04:15:51.999 시작 → MediaMTX 04:15:56 available → 화면 **04:15:57.335 `송출 중`** → ffmpeg 04:16:07.083 종료·MediaMTX 04:16:07 unavailable → 화면 **04:16:07.371 `송출 없음`**. 새로고침 없이 5초 다시 조회로 바뀐다 ✅ |
| 덧붙여(XSS) | 제목을 `<img src=x onerror="window.__xss=1">t`로 저장 → 채널 페이지 `h2`의 글자가 그대로 이 문자열, `main img` 0개, `window.__xss` 없음(텍스트로 들어간다) ✅. 확인 뒤 제목을 `테스트 방송`으로 되돌렸다 |
| 덧붙여(51자) | 브라우저에서 `PUT /api/studio/title` 51자 → 400 `{"code":"INVALID_INPUT","message":"방송 제목은 1~50자입니다"}` ✅ |
| 키 노출 | api-1·api-2 로그(최근 5분)에 `key=` 0줄 |

**Red에서 통과했던 테스트의 변이 확인 (Green):** Red에서 3개가 구현 없이 통과했다. 스튜디오 `live`(#11의 조회 SQL)와 `/api/studio/**` 스트리머 제한(#9)이 이미 있어서다. Green 커밋 뒤 저장소에 변이를 하나씩 넣고 `ChannelIntegrationTest`(16개)를 돌린 뒤 `git checkout --`으로 되돌렸다.

| 변이 | 실패한 테스트 |
| --- | --- |
| `ChannelMapper.xml` live를 `THEN 0 ELSE 0`(늘 오프라인) | `should return live true from the studio channel when the channel has a live broadcast`, `should show the new title on the channel page when the streamer changes the title during a live broadcast` |
| live를 `THEN 1 ELSE 1`(늘 방송 중) | `should return live false from the studio channel when the channel has no live broadcast`, (#8) `should show name, description and offline status to a visitor without login` |
| `SecurityConfig` `/api/studio/**`를 `authenticated()`로 | `should return 403 with code FORBIDDEN when a member who is not a streamer sets a title`, (#8) `…opens the studio channel` |

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 제목 1자·50자 저장, 0자(공백만)·51자 400 | `[정상] ChannelService.updateTitle — should save the title and return it when the streamer sets a title`<br>`[정상] PUT /api/studio/title — should return 200 with the channel showing the new title when the streamer sends a valid title`<br>`[경계] PUT /api/studio/title — should accept a title of 1 character`<br>`[경계] PUT /api/studio/title — should accept a title of 50 characters`<br>`[경계] ChannelIntegration — should save a 1-character title and show it on the channel page`<br>`[경계] ChannelIntegration — should save a 50-character title and show it on the channel page`<br>`[예외] PUT /api/studio/title — should return 400 with code INVALID_INPUT when the title has 51 characters`<br>`[예외] PUT /api/studio/title — should return 400 with code INVALID_INPUT when the title is only spaces`<br>`[예외] PUT /api/studio/title — should return 400 with code INVALID_INPUT when the title is empty`<br>`[예외] PUT /api/studio/title — should return 400 with code INVALID_INPUT when the title is missing`<br>`[예외] ChannelIntegration — should return 400 with code INVALID_INPUT and keep the previous title when the streamer sends a 51-character title`<br>`[예외] ChannelIntegration — should return 400 with code INVALID_INPUT and keep the previous title when the streamer sends a title of only spaces` |
| 2. 제목을 정한 적 없는 채널 `하늘` → 방송 제목 `하늘의 방송` | `[경계] ChannelService.find — should return the channel name followed by 의 방송 as the title when no title has been set`<br>`[정상] ChannelService.find — should return the stored title when the channel has a title`<br>`[경계] ChannelIntegration — should show 하늘의 방송 as the title on the channel page when the channel 하늘 has never set a title` |
| 3. 방송 중에 제목을 바꾸면 채널 페이지를 다시 조회할 때 새 제목 | `[정상] ChannelIntegration — should show the new title on the channel page when the streamer changes the title during a live broadcast` |
| 4. 진행 중 방송이 있는/없는 채널 → 스튜디오 연결 상태 `송출 중`/`송출 없음` | `[정상] ChannelIntegration — should return live true from the studio channel when the channel has a live broadcast`<br>`[정상] ChannelIntegration — should return live false from the studio channel when the channel has no live broadcast`<br>화면 문구는 수동 확인 |

**AC에 없는데 넣은 시나리오**

| 시나리오 | 이유 |
| --- | --- |
| `[예외] ChannelService.updateTitle — should throw CHANNEL_NOT_FOUND without saving when the member has no channel` | 스튜디오 조회(`findMine`)와 같은 규칙. 없는 채널에 UPDATE가 0행으로 조용히 성공하면 안 된다 |
| `[예외] ChannelIntegration — should return 403 with code FORBIDDEN when a member who is not a streamer sets a title` | 새 쓰기 엔드포인트가 `/api/studio/**` 규칙 안에 있는지 확인 |

**커버리지:** AC 4개 / 시나리오 20개 / 미커버 0개 (AC 4의 화면 문구는 수동 확인)

---

## AC 검증

> `harness:ac-verifier` (2026-11-21, HEAD fa6dadc). 변이 최대 3번·20분·스크래치 복사본·클래스 단위 테스트·docker compose 조작 금지로 띄웠다. 아래는 검증자 출력 원문이다(제목 수준만 두 단계 낮췄다). 구현자는 맨 아래 `### 구현자 이견`에만 쓴다.

### 이슈 #13 AC 검증 결과 (HEAD fa6dadc, base origin/main)

AC 4개는 모두 ✅ 충족입니다. 변이 3개를 넣었고 3개 모두 테스트가 잡았습니다. 가짜 테스트는 없습니다. 원본 저장소는 건드리지 않았습니다. `git -C C:/live-streaming-project status --short`의 출력은 비어 있었고, HEAD는 fa6dadc 그대로입니다.

#### AC별 판정

| AC | 판정 | 근거 테스트 (@DisplayName, 파일:줄) | 구현 (파일:줄) | 판단 이유 |
|---|---|---|---|---|
| 1. 제목 1자·50자는 저장, 0자(공백만)·51자는 400 | ✅ | 통합 테스트(실제 DB):<br>- `should save a 1-character title and show it on the channel page` (ChannelIntegrationTest.java:227)<br>- `should save a 50-character title and show it on the channel page` (:238)<br>- `should return 400 with code INVALID_INPUT and keep the previous title when the streamer sends a 51-character title` (:302)<br>- `...when the streamer sends a title of only spaces` (:316)<br>컨트롤러 테스트:<br>- `should accept a title of 1 character` / `of 50 characters` (ChannelControllerTest.java:270, :278)<br>- `should return 400 with code INVALID_INPUT when the title has 51 characters` / `is only spaces` / `is empty` / `is missing` (:287, :297, :307, :317)<br>서비스 테스트:<br>- `should save the title and return it when the streamer sets a title` (ChannelServiceTest.java:176) | - UpdateTitleRequest.java:9-10 (`@NotBlank`, `@Size(max = 50)`)<br>- ChannelController.java:39-42 (`@Valid`)<br>- ChannelService.java:40-45<br>- ChannelMapper.xml:37-38<br>- V6__add_title_to_channel.sql (`VARCHAR2(50 CHAR)`) | 1·50·51자 경계를 모두 봅니다. 저장되는지는 다시 조회한 채널 페이지의 `$.title`로 확인하고, 400일 때 이전 제목이 그대로 남는지도 봅니다. 50자 테스트는 한글 `"제".repeat(50)`이라 컬럼이 바이트가 아니라 글자 수 기준인지까지 검증합니다. 400의 코드와 메시지는 컨트롤러 테스트가 확인합니다. 변이 1·2·3이 모두 이 테스트들에 잡혔습니다. |
| 2. 제목을 정한 적 없는 채널 `하늘`이면 제목이 `하늘의 방송` | ✅ | - `should show 하늘의 방송 as the title on the channel page when the channel 하늘 has never set a title` (ChannelIntegrationTest.java:251)<br>- `should return the channel name followed by 의 방송 as the title when no title has been set` (ChannelServiceTest.java:85)<br>- `should return the stored title when the channel has a title` (:75) | ChannelService.java:58 (`requireNonNullElse(title, name + "의 방송")`) | 통합 테스트가 실제로 채널 이름을 `하늘`로 바꾼 뒤 `GET /api/channels/{id}`에서 `"하늘의 방송"`을 단언합니다. 서비스 테스트는 다른 이름(`방송채널`)으로 기본값을 검증하므로 문자열을 하드코딩했다면 걸립니다. |
| 3. 방송 중에 제목을 바꾸면 채널 페이지를 다시 조회할 때 새 제목 | ✅ | `should show the new title on the channel page when the streamer changes the title during a live broadcast` (ChannelIntegrationTest.java:261) | - ChannelService.java:40-45<br>- ChannelMapper.xml:14 (`c.title` 조회), :37-38 (UPDATE) | 순서: `before`로 저장 → `broadcastService.onAvailable`로 진행 중 방송 생성 → `live=true` 확인 → `after`로 변경 → 다시 조회해 `after`. 변이 2(UPDATE를 아무것도 안 바꾸게)에서 실패했으므로 응답 echo가 아니라 DB를 거친 값을 보고 있습니다. |
| 4. 진행 중 방송이 있는/없는 채널 → 스튜디오 연결 상태 `송출 중`/`송출 없음` | ✅ (API는 자동 테스트, 화면 문구는 수동 확인) | - `should return live true from the studio channel when the channel has a live broadcast` (ChannelIntegrationTest.java:276)<br>- `should return live false from the studio channel when the channel has no live broadcast` (:288)<br>- 수동 확인: issue-13.md "확인 결과" 표 | - ChannelMapper.xml:14-17 (live CASE EXISTS)<br>- StudioPage.tsx:40 (`live ? '송출 중' : '송출 없음'`)<br>- StudioPage.tsx:13-19 (`refetchInterval: 5000`) | API: 실제 `GET /api/studio/channel`로 true/false를 단언합니다. false 쪽은 "방송했다가 끝낸 채널"이라 지난 방송 기록이 있어도 false인지까지 봅니다. live SQL은 구현자가 Green 뒤에 변이(늘 0 / 늘 1)로 확인했다고 issue-13.md에 적었고, 저는 다시 돌리지 않았습니다. 화면: 수동 기록에 `송출 없음` → `송출 중` → `송출 없음` 전환이 시각과 함께 있습니다. 기록 시점 HEAD는 beaafe9이고, 그 뒤 커밋(fa6dadc)은 issue-13.md만 바꿨습니다(`git diff --stat beaafe9 fa6dadc`로 확인). 그래서 기록이 지금 코드에도 해당합니다. |

**요약: AC 4개 중 ✅ 4 / ⚠️ 0 / ❌ 0**

#### 변이 결과 (3회, 스크래치 복사본에서만)

| # | 변이 | 실행 | 결과 |
|---|---|---|---|
| 1 | UpdateTitleRequest의 `@NotBlank`를 `@NotEmpty`로 (공백만 있는 제목 허용) | `--tests "ChannelControllerTest"` | 잡힘. `should return 400 with code INVALID_INPUT when the title is only spaces` 실패 (21개 중 1개) |
| 2 | ChannelMapper.xml의 updateTitle을 `SET title = title`로 (저장이 아무것도 안 바꿈) | `--tests "ChannelIntegrationTest"` | 잡힘. 16개 중 5개 실패: 1자 저장, 50자 저장, 51자 거절 뒤 이전 제목 유지, 공백 거절 뒤 이전 제목 유지, 방송 중 제목 변경 |
| 3 | `@Size(max = 50)`을 `max = 51`로 | `--tests "ChannelControllerTest"` | 잡힘. `should return 400 with code INVALID_INPUT when the title has 51 characters` 실패 (21개 중 1개) |

변이 3은 ChannelIntegrationTest로는 돌리지 않았습니다(횟수 제한). 같은 경계를 다루는 통합 테스트(:302)도 실패할 것으로 보지만 실행으로 확인하지는 않았습니다.

#### 갭을 메울 시나리오 제안

AC 기준으로 메워야 할 갭은 없습니다. 아래는 지금도 충족이지만 단언을 조이면 좋은 선택 사항입니다.

- [ ] [정상] `ChannelIntegration` — should keep live true on the channel page after the streamer changes the title during a live broadcast
  - 지금 AC 3 테스트는 제목을 바꾸기 전에만 `live`를 단언하고, 다시 조회할 때는 `title`만 봅니다.
- [ ] [예외] `ChannelIntegration` — should return 400 with code INVALID_INPUT and keep the previous title when the streamer sends an empty title
  - 빈 문자열 `""`은 지금 컨트롤러 테스트(WebMvcTest)에서만 봅니다.

#### 가짜 테스트·약한 단언

가짜 테스트는 없습니다. 인자 없는 예외 단언, 단언 없는 테스트, 비동기 누락은 보지 못했습니다. `ChannelServiceTest`(:172~)의 예외 단언은 `BusinessException`과 `CHANNEL_NOT_FOUND` 코드까지 확인합니다.

약한 단언이 두 군데 있지만 판정은 바꾸지 않았습니다.

- **ChannelControllerTest.java:270·:278 (1자·50자 수락)** — 서비스 목이 받은 제목을 그대로 돌려주므로(`serviceEchoesTitle`), 이 두 테스트는 "검증을 통과해 서비스까지 갔다"만 봅니다. 실제 저장은 ChannelIntegrationTest.java:227·:238이 검증하므로 갭은 아닙니다.
- **ChannelIntegrationTest.java:302·:316 (51자·공백 거절)** — `$.code`만 보고 `$.message`는 보지 않습니다. 메시지는 컨트롤러 테스트(:287·:297)가 봅니다.

#### 실행한 명령 목록

1. `cat .claude/harness.json && gh issue view 13 && git diff origin/main...HEAD --stat` → 설정 확인, AC 4개, 변경 파일 16개
2. `cat docs/result/scenarios/issue-13.md && git diff origin/main...HEAD -- backend/src/main frontend` → 시나리오 20개, AC 대조표, 수동 확인 기록, 구현 diff
3. `git diff origin/main...HEAD -- backend/src/test` → 새 테스트 본문 확인
4. `grep -n "제목" spec-fixed.md prd/channel.md` 및 `grep -rn "/api/studio" backend/src/main/java` → 사양 4절 "1~50자, 기본 {채널 이름}의 방송", PRD 작은 결정 "채널에 둔다", SecurityConfig.java:59 `/api/studio/**` STREAMER 확인
5. 테스트·구현 줄 번호를 grep으로 수집 → 위 표의 줄 번호
6. `git archive HEAD | tar -x -C .../scratchpad/ac13-mut` → 스크래치 복사본 생성
7. sed로 변이 1 적용 후 `./gradlew test --tests "ChannelControllerTest"` → 21개 중 1개 실패 (공백만 테스트)
8. 변이 1 되돌림(`git show HEAD:... >` 스크래치) → 변이 2 적용 → `./gradlew test --tests "ChannelIntegrationTest"` → 16개 중 5개 실패
9. 변이 2 되돌림 → 변이 3 적용 → `./gradlew test --tests "ChannelControllerTest"` → 21개 중 1개 실패 (51자 테스트)
10. `git -C C:/live-streaming-project status --short` → 출력 없음(깨끗함). `rev-parse` 결과 fa6dadc
11. `git diff --stat beaafe9 fa6dadc` → issue-13.md 1개 파일만 변경 (수동 확인 기록이 지금 코드에도 해당함을 확인)

#### 남아 있는 것

스크래치 복사본 `C:/Users/USER/AppData/Local/Temp/claude/1cbb40fc7d76b7b0/e5a53947-02cd-44f6-934c-3e8edfbbefcf/scratchpad/ac13-mut`에는 변이 3(`@Size(max = 51)`)이 적용된 채로 남아 있습니다. 원본 저장소와는 관계없습니다.

### 구현자 이견

- 이견 없음. ⚠️·❌가 없어 갭 처리 대상이 없다.
- 검증자의 선택 제안 2개(방송 중 제목 변경 뒤 `live` 유지, 통합 테스트의 빈 문자열)는 메우지 않았다. 검증자가 "AC 기준 갭 없음"으로 분류했고, 각각 기존 테스트가 이미 다룬다 — 제목 변경은 `live`를 읽는 SQL에 닿지 않고(`updateTitle`은 `title`만 바꾼다), 빈 문자열은 `@NotBlank` 하나가 공백만·빈 값을 같이 막아 통합 테스트의 공백만 사례와 같은 경로다. 체크박스로 남기면 `check-scenarios.mjs`가 테스트로 세므로 이 절에만 둔다.

---

## 리팩터

없음. 이번 이슈의 소스 파일(`Channel`·`ChannelController`·`ChannelMapper`·`ChannelResponse`·`ChannelService`·`UpdateTitleRequest`, `ChannelMapper.xml`, 프론트 3개)에서 근거가 분명한 대상을 찾지 못했다.
- `ChannelService`의 `findByMemberId(…).orElseThrow(CHANNEL_NOT_FOUND)`가 `findMine`·`updateTitle` 두 곳에 있다. 두 번째라 뽑지 않는다(세 번째부터).
- `StudioPage.tsx`의 `TitleForm`과 `ChannelForm`이 저장 흐름(mutation → 두 쿼리 무효화 → 저장했습니다)이 비슷하다. 프론트 테스트가 아직 없어(#12에서 들어온다) 손대지 않는다.

---

## 보안 점검

> `/harness:security-review 13` (2026-11-21). 사전 승인에 따라 🔴는 고치고, 🟡는 "사용자 결정 대기"로 남긴다.

**수집:** `compileJava`·`compileTestJava`·`tsc -b` 성공, `npm audit` 0건, 비밀값 패턴·알려진 접두사 grep 0건, `console.log/info/debug` 0건, 브랜치가 커밋한 `.env` 없음, `ChannelMapper.xml`에 `${}` 치환 없음(제목도 `#{title}` 바인딩). 새 로그 없음. 전체 백엔드 테스트 336개 통과(Green과 같은 코드, 그 뒤로는 문서만 바뀜).

| 분류 | 파일·줄 | 무엇 | 판단 | 처리 |
| --- | --- | --- | --- | --- |
| ⚪ | `UpdateTitleRequest` | 제목 입력 검증 | `@NotBlank` + `@Size(max = 50)`을 `@Valid`로 컨트롤러에서 검사하고, DB 컬럼도 `VARCHAR2(50 CHAR)`라 이중으로 막힌다. 테스트와 변이(검증자 변이 1·3)로 확인 | 그대로 |
| ⚪ | `ChannelPage.tsx`·`StudioPage.tsx` | 스트리머가 정한 제목을 시청자 화면에 보인다(저장형 XSS 경로) | JSX 텍스트로만 넣는다(`dangerouslySetInnerHTML` 없음). 수동 확인에서 `<img src=x onerror=…>` 제목이 글자 그대로 보이고 `img` 0개, 스크립트가 돌지 않았다 | 그대로 |
| ⚪ | `PUT /api/studio/title` | 새 쓰기 엔드포인트의 권한 | `/api/studio/**`(스트리머만) 안에 있고, 대상 채널을 요청이 아니라 세션의 회원으로 정해 남의 채널을 고칠 길이 없다(IDOR 없음). 출처 검사(#2)도 다른 PUT과 같이 걸린다. 비스트리머 403은 테스트 + 변이로 확인 | 그대로 |
| ⚪ | `StudioPage.tsx` 5초 다시 조회 | 폴링 부하 | 스트리머 본인만(`/api/studio/channel`, 로그인 필수) 스튜디오를 열어 둔 동안 5초에 한 번, 채널 한 행 + 유니크 인덱스로 찾는 EXISTS 한 번이다. 비로그인 시청자의 폴링은 #12에서 따로 본다 | 그대로 |
| ⚪ | `StudioPage.tsx` 5초 다시 조회 | 스튜디오를 열어 둔 동안 세션 만료(마지막 요청 후 7일)가 계속 늦춰진다 | 스튜디오를 쓰는 동안 다른 요청을 보내는 것과 같다. 탭을 닫으면 7일 뒤 만료된다 | 그대로 |

**결과:** 🔴 0, 🟡 0. 재스캔은 위 수집과 같다.
