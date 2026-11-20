# 이슈 #8 — 채널 페이지를 보고 스트리머가 채널 정보를 고친다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/8
> PRD: `docs/result/prd/channel.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

> 게이트 처리: 사용자가 자리를 비운 동안 진행했다. 사전 승인(밤새 #7~#9 진행 지시)에 따라 시그니처·시나리오를 구현자가 정하고 승인된 것으로 진행했다.

---

## 시그니처

### 관련 ADR

- **확정 사양 3절(채널)** — 채널 이름 2~20자(처음에는 닉네임), 소개 0~200자. 스트리머가 바꿀 수 있다. 채널 페이지에는 이름·소개·방송 상태(방송 중/오프라인).
- **채널 PRD 작은 결정(채널 ID)** — 숫자 일련번호. 비밀이 아니므로 채널 페이지 주소에 그대로 쓴다.
- **채널 PRD 작은 결정(패키지)** — 채널 정보는 `com.livestream.channel`. 방송 상태는 #11에서 `broadcast` 패키지가 채운다. 이 이슈에서는 늘 오프라인이다.
- **#7 판단 6** — 빈 소개는 Oracle에서 NULL로 저장된다. 읽을 때 빈 문자열로 바꾼다.

### 타입

```java
// com.livestream.channel
// live는 #11 전까지 늘 false. description은 NULL이면 ""
public record ChannelResponse(long id, String name, String description, boolean live) {}

public record UpdateChannelRequest(
        @NotBlank(message = "채널 이름을 입력해 주세요")
        @Size(min = 2, max = 20, message = "채널 이름은 2~20자입니다") String name,
        @NotNull(message = "소개를 보내 주세요")
        @Size(max = 200, message = "소개는 200자 이하입니다") String description) {}

@Mapper
public interface ChannelMapper {
    void insert(Channel channel);                       // #7
    Optional<Channel> findById(long id);                // 추가
    Optional<Channel> findByMemberId(long memberId);    // 추가
    void update(@Param("id") long id, @Param("name") String name,
                @Param("description") String description);   // 추가
}

@Service
public class ChannelService {
    public ChannelService(ChannelMapper channelMapper);
    public ChannelResponse find(long channelId);              // 없으면 CHANNEL_NOT_FOUND
    public ChannelResponse findMine(long memberId);           // 채널이 없으면 CHANNEL_NOT_FOUND
    // 없으면 CHANNEL_NOT_FOUND, 주인이 아니면 CHANNEL_NOT_OWNER(저장하지 않는다)
    public ChannelResponse update(long channelId, long memberId, UpdateChannelRequest request);
}

@RestController
class ChannelController {
    @GetMapping("/api/channels/{channelId}")   // 200 + ChannelResponse. 로그인 없이 볼 수 있다
    ChannelResponse channel(@PathVariable long channelId);
    @PutMapping("/api/channels/{channelId}")   // 200 + ChannelResponse
    ChannelResponse update(@PathVariable long channelId, @AuthenticationPrincipal LoginMember loginMember,
                           @Valid @RequestBody UpdateChannelRequest request);
    @GetMapping("/api/studio/channel")         // 200 + 내 채널. 스튜디오가 채널 id와 지금 값을 읽는다
    ChannelResponse myChannel(@AuthenticationPrincipal LoginMember loginMember);
}
```

```java
// com.livestream.common.error
public enum ErrorCode {
    // 추가
    CHANNEL_NOT_FOUND(HttpStatus.NOT_FOUND, "채널을 찾을 수 없습니다"),
    CHANNEL_NOT_OWNER(HttpStatus.FORBIDDEN, "내 채널만 고칠 수 있습니다");
}
```

`SecurityConfig`
- `GET /api/channels/*` → `permitAll()` (비로그인 시청자)
- `/api/studio/**` → `hasRole("STREAMER")`. #9의 키 발급·조회도 이 아래에 둔다
- `PUT /api/channels/*`는 기존 `anyRequest().authenticated()` + 출처 검증. 주인 확인은 서비스가 한다(채널마다 다르므로 경로 규칙으로 못 막는다)

### 에러 케이스

| 상황 | 에러 코드 | HTTP |
| --- | --- | --- |
| 없는 채널 id | `CHANNEL_NOT_FOUND` | 404 |
| 다른 스트리머(주인이 아닌 회원)의 채널을 고침 | `CHANNEL_NOT_OWNER` | 403 |
| 이름 1자·21자·공백만·없음, 소개 201자·없음 | `INVALID_INPUT` (기존, 처음 걸린 필드의 메시지) | 400 |
| 스튜디오를 스트리머가 아닌 회원이 엶 | `FORBIDDEN` (#7) | 403 |
| 로그인하지 않고 고침·스튜디오 | `UNAUTHENTICATED` (기존) | 401 |

### 화면

- `src/api/channels.ts` — `interface ChannelResponse { id: number; name: string; description: string; live: boolean }`, `fetchChannel(channelId: number)`, `fetchMyChannel()`, `updateChannel(channelId: number, req: { name: string; description: string })`
- `/channels/:channelId` 채널 페이지(props 없는 페이지) — 채널 이름(h1), 소개, 방송 상태 "오프라인"(`live`가 false). 404면 "채널을 찾을 수 없습니다"
- `/studio` 스튜디오(props 없는 페이지) — 스트리머만. 채널 이름·소개 입력란(지금 값으로 채움)과 저장 버튼, 내 채널 페이지 링크. 성공하면 `['myChannel']`·`['channel', id]`를 무효화하고 "저장했습니다". 실패하면 서버 메시지. 스트리머가 아니면 "스트리머만 쓸 수 있습니다", 비로그인이면 로그인 링크
- `RootLayout` 머리글 — 역할이 `STREAMER`면 "스튜디오" 링크

### 판단이 갈린 곳

1. **고치는 API는 채널 id를 받는다(`PUT /api/channels/{id}`).** AC 4("다른 스트리머의 채널을 고치려 하면 403")가 성립하려면 남의 채널을 가리킬 수 있어야 한다. 주인 확인은 서비스에서 회원 id와 채널의 `member_id`를 비교한다.
2. **스튜디오가 내 채널을 읽는 길은 `/api/studio/channel` 하나.** 스튜디오(#9 키, #13 제목)가 모두 스트리머 전용이라 `/api/studio/**`를 역할 규칙으로 한 번에 막는다.
3. **방송 상태는 `live: boolean`.** 확정 사양의 상태가 "방송 중/오프라인" 둘뿐이다. #11이 실제 값을 채우고, #12가 제목·시청자 수를 더한다.
4. **채널 이름 앞뒤 공백은 그대로 둔다.** 확정 사양에 없다. 공백만 있는 이름은 `@NotBlank`로 400.
5. **길이는 Java 문자(UTF-16) 기준.** 한글은 한 글자가 하나라 확정 사양과 같다. DB는 `VARCHAR2(n CHAR)`라 한글 200자도 들어간다 — 통합 테스트로 확인한다.
6. 매퍼 SQL은 #6·#7처럼 `@SpringBootTest` 통합 테스트로 확인한다. `ChannelMapper`에 메서드를 더하므로 #7 통합 테스트의 익명 구현(`StreamerApplicationReviewIntegrationTest`)에도 더한다.

### 이 이슈에서 만들지 않는 것

- 실제 방송 상태 — #11
- 플레이어·방송 제목·시청자 수·채팅 — #12·#13·채팅 도메인
- 스트림 키·OBS 안내 — #9

---

## 테스트 시나리오

### 정상

- [x] [정상] `ChannelService.find` — should return name, description and offline status when the channel exists
- [x] [정상] `ChannelService.findMine` — should return the channel of the member when the member owns one
- [x] [정상] `ChannelService.update` — should save the new name and description and return them when the owner updates
- [x] [정상] `GET /api/channels/{channelId}` — should return 200 with name, description and live false when service finds the channel
- [x] [정상] `GET /api/studio/channel` — should return 200 with the channel of the logged-in streamer
- [x] [정상] `PUT /api/channels/{channelId}` — should return 200 with the updated channel when the owner sends a valid name and description
- [x] [정상] `ChannelIntegration` — should show name, description and offline status to a visitor without login
- [x] [정상] `ChannelIntegration` — should save a 20-character name and a 200-character description and show them on the channel page when the owner updates
- [x] [정상] `ChannelIntegration` — should return the channel of the logged-in streamer from the studio

### 경계

- [x] [경계] `ChannelService.find` — should return an empty description when the stored description is null
- [x] [경계] `PUT /api/channels/{channelId}` — should accept a name of 2 characters
- [x] [경계] `PUT /api/channels/{channelId}` — should accept a name of 20 characters
- [x] [경계] `PUT /api/channels/{channelId}` — should accept an empty description
- [x] [경계] `PUT /api/channels/{channelId}` — should accept a description of 200 characters
- [x] [경계] `PUT /api/channels/{channelId}` — should return 400 with code INVALID_INPUT when the name has 1 character
- [x] [경계] `PUT /api/channels/{channelId}` — should return 400 with code INVALID_INPUT when the name has 21 characters
- [x] [경계] `PUT /api/channels/{channelId}` — should return 400 with code INVALID_INPUT when the description has 201 characters
- [x] [경계] `ChannelIntegration` — should save a 2-character name and an empty description and show the description as empty on the channel page

### 예외

- [x] [예외] `ChannelService.find` — should throw CHANNEL_NOT_FOUND when the channel does not exist
- [x] [예외] `ChannelService.findMine` — should throw CHANNEL_NOT_FOUND when the member has no channel
- [x] [예외] `ChannelService.update` — should throw CHANNEL_NOT_OWNER without saving when another member updates the channel
- [x] [예외] `ChannelService.update` — should throw CHANNEL_NOT_FOUND when the channel does not exist
- [x] [예외] `GET /api/channels/{channelId}` — should return 404 with code CHANNEL_NOT_FOUND and its message when service reports the channel does not exist
- [x] [예외] `PUT /api/channels/{channelId}` — should return 400 with code INVALID_INPUT when the name is blank
- [x] [예외] `PUT /api/channels/{channelId}` — should return 400 with code INVALID_INPUT when the description is missing
- [x] [예외] `PUT /api/channels/{channelId}` — should return 403 with code CHANNEL_NOT_OWNER and its message when service rejects a member who does not own the channel
- [x] [예외] `ChannelIntegration` — should return 404 with code CHANNEL_NOT_FOUND when a visitor requests a channel id that does not exist
- [x] [예외] `ChannelIntegration` — should return 403 with code CHANNEL_NOT_OWNER and keep the channel unchanged when another streamer updates it
- [x] [예외] `ChannelIntegration` — should return 403 with code FORBIDDEN when a member who is not a streamer opens the studio channel

### 수동 확인 (테스트 도구가 없는 화면 — 시나리오 수에 넣지 않는다)

프론트 테스트 도구(Vitest)는 #12에서 넣는다. 아래는 Green 단계에서 브라우저로 확인하고 결과를 이 문서와 PR에 남긴다.

- 로그인하지 않고 `/channels/{id}`를 열면 채널 이름·소개·"오프라인"이 보인다
- 없는 id로 열면 "채널을 찾을 수 없습니다"
- 스트리머로 로그인하면 머리글에 "스튜디오" 링크가 있고, `/studio`에 지금 이름·소개가 채워져 있다
- 이름·소개를 바꿔 저장하면 "저장했습니다"가 나오고, 채널 페이지에 바뀐 값이 보인다
- 이름을 1자로 저장하면 서버 메시지 "채널 이름은 2~20자입니다"가 보인다

**확인 결과 (2026-11-21, Green):** 로컬 개발 DB(compose Oracle)로 `backend`(:8080)·`frontend`(:5173) 미리보기를 띄웠다. 이번 이슈는 새 마이그레이션이 없다(V3은 #7로 main에 들어감). 운영자 계정이 `.env`에 없어서 테스트 계정 둘(`st820640@`·`op820640@example.com`)을 API로 가입시키고, 둘째의 역할만 SQL로 `OPERATOR`로 바꿨다. 신청 → 운영자 승인은 curl(ASCII 데이터)로 했다.

- (#7 경로 덤) 대기 목록에 신청 1건, 승인 200 `APPROVED`, 승인 뒤 신청자의 기존 쿠키로 `/api/members/me` 401 ✅
- 스트리머로 다시 로그인 → 머리글 "st820640 스튜디오 로그아웃" ✅
- `/studio`: 이름 입력란에 `st820640`(닉네임), 소개 빈칸, "내 채널 페이지" 링크 `/channels/1` ✅
- 이름 `A`로 저장 → "채널 이름은 2~20자입니다" ✅
- 이름 `Night Talk`, 소개 `late night chat channel`로 저장 → "저장했습니다" ✅
- `/channels/1`: "Night Talk / 오프라인 / late night chat channel" ✅. 로그아웃 뒤(머리글 "로그인 가입")에도 같게 보인다 ✅
- `/channels/99999` → "채널을 찾을 수 없습니다" ✅
- 로그아웃 상태로 `/studio` → "로그인이 필요합니다" ✅. 스트리머가 아닌 회원의 "스트리머만 쓸 수 있습니다" 문구는 브라우저로 보지 않았다(API 403은 통합 테스트가 본다)
- 콘솔 오류는 네트워크 응답(백엔드 기동 전 502, 의도한 400·401·404)뿐, 스크립트 오류 없음
- 덤으로 본 것: 대기 목록의 `appliedAt`이 `2026-11-20T15:37:27.72948`로 나왔다. DB 컨테이너 시각(UTC)이 시간대 없이 그대로 나간다. 운영자 화면이 없어 이번 범위에서는 고치지 않았다(아래 보안 점검이 아니라 사용자 결정 목록에 올린다)

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 비로그인 시청자가 채널 페이지 → 이름·소개·`오프라인` | `[정상] ChannelService.find — should return name, description and offline status when the channel exists`<br>`[정상] GET /api/channels/{channelId} — should return 200 with name, description and live false when service finds the channel`<br>`[정상] ChannelIntegration — should show name, description and offline status to a visitor without login`<br>`[경계] ChannelService.find — should return an empty description when the stored description is null` |
| 2. 없는 채널 ID → 404 | `[예외] ChannelService.find — should throw CHANNEL_NOT_FOUND when the channel does not exist`<br>`[예외] GET /api/channels/{channelId} — should return 404 with code CHANNEL_NOT_FOUND and its message when service reports the channel does not exist`<br>`[예외] ChannelIntegration — should return 404 with code CHANNEL_NOT_FOUND when a visitor requests a channel id that does not exist` |
| 3. 주인 스트리머가 이름 2자·20자, 소개 0자·200자로 바꾸면 바뀌고, 이름 1자·21자나 소개 201자면 400 | `[정상] ChannelService.update — should save the new name and description and return them when the owner updates`<br>`[정상] PUT /api/channels/{channelId} — should return 200 with the updated channel when the owner sends a valid name and description`<br>`[경계] PUT /api/channels/{channelId} — should accept a name of 2 characters`<br>`[경계] PUT /api/channels/{channelId} — should accept a name of 20 characters`<br>`[경계] PUT /api/channels/{channelId} — should accept an empty description`<br>`[경계] PUT /api/channels/{channelId} — should accept a description of 200 characters`<br>`[경계] PUT /api/channels/{channelId} — should return 400 with code INVALID_INPUT when the name has 1 character`<br>`[경계] PUT /api/channels/{channelId} — should return 400 with code INVALID_INPUT when the name has 21 characters`<br>`[경계] PUT /api/channels/{channelId} — should return 400 with code INVALID_INPUT when the description has 201 characters`<br>`[정상] ChannelIntegration — should save a 20-character name and a 200-character description and show them on the channel page when the owner updates`<br>`[경계] ChannelIntegration — should save a 2-character name and an empty description and show the description as empty on the channel page` |
| 4. 다른 스트리머의 채널을 고치려 하면 403 | `[예외] ChannelService.update — should throw CHANNEL_NOT_OWNER without saving when another member updates the channel`<br>`[예외] PUT /api/channels/{channelId} — should return 403 with code CHANNEL_NOT_OWNER and its message when service rejects a member who does not own the channel`<br>`[예외] ChannelIntegration — should return 403 with code CHANNEL_NOT_OWNER and keep the channel unchanged when another streamer updates it` |

**AC에 없는데 넣은 시나리오**

| 시나리오 | 이유 |
| --- | --- |
| `[정상] ChannelService.findMine — should return the channel of the member when the member owns one`<br>`[예외] ChannelService.findMine — should throw CHANNEL_NOT_FOUND when the member has no channel`<br>`[정상] GET /api/studio/channel — should return 200 with the channel of the logged-in streamer`<br>`[정상] ChannelIntegration — should return the channel of the logged-in streamer from the studio`<br>`[예외] ChannelIntegration — should return 403 with code FORBIDDEN when a member who is not a streamer opens the studio channel` | 이슈 본문 "스트리머는 스튜디오에서 채널 이름·소개를 고친다". 스튜디오가 내 채널 id와 지금 값을 읽을 길이 있어야 화면이 고칠 수 있다. 새 경로 규칙(`/api/studio/**` 스트리머 전용)을 확인한다 |
| `[예외] ChannelService.update — should throw CHANNEL_NOT_FOUND when the channel does not exist` | id를 경로로 받으므로 없는 id가 들어온다. 없으면 주인 확인을 할 수 없다 |
| `[예외] PUT /api/channels/{channelId} — should return 400 with code INVALID_INPUT when the name is blank`<br>`[예외] PUT /api/channels/{channelId} — should return 400 with code INVALID_INPUT when the description is missing` | 이름 2자 미만의 한 형태(공백만)와 본문에 소개가 빠진 요청. 검증 없이 DB로 가면 NOT NULL 위반(500)이 난다 |

**커버리지:** AC 4개 / 시나리오 29개 / 미커버 0개

---

## AC 검증

> `harness:ac-verifier` (2026-11-21, HEAD da46ec2). 변이 최대 3번·20분·스크래치 복사본·클래스 단위 테스트로 제한해 띄웠다. 아래는 검증자 출력 원문이다(제목 수준만 한 단계 낮췄다). 구현자는 맨 아래 `### 구현자 이견`에만 쓴다.

### 이슈 #8 AC 검증 결과 (feat/channel/issue-8, HEAD da46ec2)

**AC 4개 모두 충족(✅ 4 / ⚠️ 0 / ❌ 0).** HEAD에서 `Channel*Test` 29개가 모두 통과했습니다. 스크래치 복사본에서 변이 3번을 돌렸고, 세 번 다 테스트가 잡아냈습니다. 가짜 테스트는 없었습니다. 저장소 작업 트리는 건드리지 않았습니다(마지막에 `git status --short` 출력이 비어 있음).

#### AC별 판정

| AC | 판정 | 근거 테스트 (@DisplayName, 파일:줄) | 구현 (파일:줄) | 판단 이유 |
| --- | --- | --- | --- | --- |
| 1. 비로그인 시청자가 채널 페이지를 보면 이름·소개·`오프라인`이 보인다 | ✅ | `should show name, description and offline status to a visitor without login` (ChannelIntegrationTest.java:123)<br>`should return name, description and offline status when the channel exists` (ChannelServiceTest.java:46)<br>`should return 200 with name, description and live false when service finds the channel` (ChannelControllerTest.java:78)<br>`should return an empty description when the stored description is null` (ChannelServiceTest.java:56) | SecurityConfig.java:56 (`GET /api/channels/*` permitAll)<br>ChannelController.java:22-25<br>ChannelService.java:17-19, 45-48 (live는 늘 false, NULL 소개는 "")<br>ChannelPage.tsx:23-25 (h1 이름, `live ? '방송 중' : '오프라인'`, 소개) | 통합 테스트가 쿠키 없이 실제 보안 필터를 거쳐 GET을 보내고, name·description·live=false를 단언한다. 변이 1(permitAll 제거)에서 이 테스트를 포함해 5개가 실패했다. 화면은 문서 issue-8.md:169에 기록된 수동 확인만 있다(프론트 테스트 도구는 아직 없음). |
| 2. 없는 채널 ID를 조회하면 404 | ✅ | `should return 404 with code CHANNEL_NOT_FOUND when a visitor requests a channel id that does not exist` (ChannelIntegrationTest.java:137)<br>`should throw CHANNEL_NOT_FOUND when the channel does not exist` (ChannelServiceTest.java:65)<br>`should return 404 with code CHANNEL_NOT_FOUND and its message when service reports the channel does not exist` (ChannelControllerTest.java:92) | ChannelService.java:39-42 (`findChannel` orElseThrow)<br>ErrorCode.java `CHANNEL_NOT_FOUND(HttpStatus.NOT_FOUND, …)`<br>ChannelPage.tsx:17-18 | 통합 테스트가 실제 DB의 `MAX(id)+1000`으로 비로그인 GET을 보내고, 404와 code를 단언한다. 서비스 테스트는 예외 타입 `BusinessException`과 코드까지 단언한다. |
| 3. 주인 스트리머가 이름 2자·20자, 소개 0자·200자로 바꾸면 바뀌고, 이름 1자·21자나 소개 201자면 400 | ✅ | 바뀜(실제 DB, 보안 필터 포함): `should save a 20-character name and a 200-character description and show them on the channel page when the owner updates` (ChannelIntegrationTest.java:147), `should save a 2-character name and an empty description and show the description as empty on the channel page` (:163)<br>통과: `should accept a name of 2 characters` (ChannelControllerTest.java:137), `…20 characters` (:145), `should accept an empty description` (:154), `should accept a description of 200 characters` (:162)<br>400: `should return 400 with code INVALID_INPUT when the name has 1 character` (:173), `…name has 21 characters` (:183), `…description has 201 characters` (:193)<br>저장: `should save the new name and description and return them when the owner updates` (ChannelServiceTest.java:106) | UpdateChannelRequest.java:8-12 (`@NotBlank @Size(min=2,max=20)`, `@NotNull @Size(max=200)`)<br>ChannelController.java:27-31 (`@Valid`)<br>ChannelService.java:28-37<br>ChannelMapper.xml:23-26 (UPDATE) | 경계값 6개(2/20, 0/200 통과, 1/21/201 거절)가 모두 각자 테스트로 있다. 통과 쪽 네 값은 통합 테스트가 다시 GET해서 실제로 저장됐는지 확인한다. 한글 20자·200자로 `VARCHAR2(n CHAR)`에 들어가는지도 본다. 400 테스트는 code·message와 함께 `verify(never()).update`까지 단언한다. 변이 3(name `min=1,max=21`, 소개 `max=201`)에서 400 테스트 3개가 모두 실패했다. |
| 4. 다른 스트리머의 채널을 고치려 하면 403 | ✅ | `should return 403 with code CHANNEL_NOT_OWNER and keep the channel unchanged when another streamer updates it` (ChannelIntegrationTest.java:177)<br>`should throw CHANNEL_NOT_OWNER without saving when another member updates the channel` (ChannelServiceTest.java:118)<br>`should return 403 with code CHANNEL_NOT_OWNER and its message when service rejects a member who does not own the channel` (ChannelControllerTest.java:226) | ChannelService.java:30-32 (`channel.getMemberId() != memberId`면 throw. `memberId`는 primitive `long`이라 박싱 비교 문제 없음)<br>ErrorCode.java `CHANNEL_NOT_OWNER(HttpStatus.FORBIDDEN, …)` | 통합 테스트는 실제 승인 흐름으로 스트리머 둘을 만들고, B의 세션으로 A의 채널에 PUT을 보낸다. 403과 code를 단언하고 이름이 바뀌지 않았는지도 확인한다. 서비스 테스트는 `never().update`를 단언한다. 변이 2(주인 확인을 `if (false)`로)에서 이 두 테스트가 실패했다. |

#### 갭을 메울 시나리오 제안

⚠️·❌가 없어서 반드시 넣어야 할 것은 없습니다. 아래 두 개는 AC 충족에는 영향이 없고, 단언을 조이는 선택 사항입니다.

- [ ] [예외] `ChannelIntegration` — should keep the description unchanged when another streamer updates the channel. 지금 :186은 이름만 확인하고, 소개가 그대로인지는 서비스 테스트의 `never().update`에만 기대고 있다.
- [ ] [예외] `PUT /api/channels/{channelId}` — should not call the service when the name is blank. :203·:212의 400 테스트에는 다른 400 테스트와 달리 `verify(never())`가 없다.

#### 가짜 테스트

없음.
- 예외 단언은 모두 `isInstanceOf(BusinessException.class).extracting("code").isEqualTo(ErrorCode.X)` 형태다. `Exception`이나 `RuntimeException`으로 넓게 받는 것이 없다.
- 비동기 호출이 없다. MockMvc는 동기로 돈다.
- 단언이 없는 테스트가 없다.
- ChannelControllerTest의 accept 4개는 목이 요청을 그대로 돌려주는 값을 단언한다(`serviceEchoesRequest`, :65-71). 하지만 핵심 단언은 실제 Bean Validation을 통과한 200 상태다. 저장 여부는 통합 테스트(:147, :163)가 실제 DB로 따로 확인하므로 목 반환값만 검사하는 테스트에 해당하지 않는다.

#### 확인하지 않은 것

- **프론트 화면(ChannelPage·StudioPage):** 코드만 읽었다. 브라우저 확인은 하지 않았고, 구현자의 수동 확인 기록(issue-8.md:162-173)에 기댄다. "스트리머만 쓸 수 있습니다" 문구는 구현자도 보지 않았다고 적어 두었다.
- **비로그인 PUT → 401:** 시그니처의 에러 표에는 있지만 이 이슈에 해당 테스트가 없다. AC 범위 밖이라 판정에 넣지 않았다.
- **전체 테스트 스위트:** 지시대로 돌리지 않았다. `Channel*Test`와 변이용 클래스만 돌렸다.
- **400 경계의 실제 필터 경로:** 1자·21자·201자 거절은 `@WebMvcTest(addFilters=false)`에서만 확인된다. 검증(`@Valid`)은 필터와 관계없는 같은 경로라 충분하다고 판단했다. 통합 테스트로 400을 다시 확인하지는 않았다.

#### 실행한 명령과 읽은 파일

1. `cat .claude/harness.json && gh issue view 8 && git diff origin/main...HEAD --stat` → 설정 확인, AC 4개, 변경 파일 18개
2. `cat -n docs/result/scenarios/issue-8.md` → 시나리오 29개, AC 대조표, 수동 확인 기록
3. `cat -n` ChannelController / ChannelService / UpdateChannelRequest / ChannelResponse / ChannelMapper.java / ChannelMapper.xml, `git diff origin/main...HEAD -- backend/src/main/java/com/livestream/common` → 구현과 ErrorCode·SecurityConfig 변경 확인
4. `cat -n` ChannelServiceTest / ChannelIntegrationTest, `grep` Channel.java의 필드 타입 → 테스트 본문 확인, `memberId`가 `long`임을 확인
5. `cat -n` ChannelControllerTest.java → 테스트 본문 확인
6. (백그라운드) `cd backend && JAVA_HOME=… ./gradlew test --tests "Channel*Test"` → BUILD SUCCESSFUL, 44s
7. `cat -n` ChannelPage.tsx / channels.ts / StudioPage.tsx, `grep "Mapping(\"/api/channels"` → 화면 코드 확인, `/api/channels` 매핑은 ChannelController에만 있음
8. `git archive HEAD backend | tar -x -C …/scratchpad/verify8` → 스크래치 복사본 생성. 이때 `git status --short` 출력 없음
9. `sed`로 스크래치의 SecurityConfig에서 GET permitAll 줄 삭제 (변이 1 준비)
10. `grep -n "2~20\|200자…" spec-fixed.md prd/channel.md` → 확정 사양 69-70줄의 이름 2~20자, 소개 0~200자, 방송 상태 확인
11. 테스트 결과 XML에서 `<testsuite>` 집계 → 8 + 14 + 7 = 29개, 실패 0
12. 변이 1: `./gradlew test --tests "ChannelIntegrationTest"` (스크래치) → 7개 중 5개 실패. 비로그인 조회와 404 테스트 포함, 잡힘
13. 변이 2: SecurityConfig를 `git show HEAD:`로 되돌리고 서비스의 주인 확인을 `if (false)`로 바꾼 뒤 `--tests ChannelServiceTest --tests ChannelIntegrationTest` → 15개 중 2개 실패(`CHANNEL_NOT_OWNER` 서비스·통합), 잡힘
14. 변이 3: 서비스를 되돌리고 `@Size`를 (1,21)과 201로 넓힌 뒤 `--tests ChannelControllerTest` → 14개 중 3개 실패(1자·21자·201자), 잡힘
15. `git status --short && git rev-parse --short HEAD` → 출력이 비어 있어 작업 트리 깨끗함, HEAD는 da46ec2

스크래치 복사본: C:\Users\USER\AppData\Local\Temp\claude\1cbb40fc7d76b7b0\ad17e800-4c27-40f4-ae12-ad63eb406d97\scratchpad\verify8\backend. 지금은 변이 3이 적용된 상태로 남아 있습니다(UpdateChannelRequest.java).

### 구현자 이견

- **선택 제안 2개는 넣지 않았다.** 검증자가 ⚠️·❌가 아닌 "단언을 조이는 선택 사항"이라고 적었다. 사전 승인 범위는 ⚠️·❌를 메우는 것이라 1차 검증으로 끝냈다(2차 검증 없음). 첫째(남의 수정에 소개도 그대로)는 서비스가 `update`를 부르지 않는 한 이름·소개가 같이 남는 한 문장 UPDATE라 이름 확인으로 같은 것을 본다. 둘째(공백 이름에 서비스 미호출)는 `@Valid`가 실패하면 컨트롤러 메서드가 불리지 않는 같은 경로를 1자·21자 테스트의 `never()`가 이미 본다.
- 스크래치 복사본(`verify8`)은 저장소 밖이라 그대로 둔다.

---

## 리팩터

고칠 대상 없음(2026-11-21). 이번 이슈가 바꾼 `backend/src/main` 8개·`frontend/src` 5개 파일을 다섯 기준으로 봤다. 서비스는 공개 메서드 3개에 "채널 찾기"(`findChannel`)·"응답 만들기"(`toResponse`)가 이미 나뉘어 있고, 컨트롤러는 위임 한 줄씩이다. `findMine`의 `orElseThrow`는 `findChannel`과 모양이 같지만 조회 키가 달라(회원 id) 두 번째 반복이라 묶지 않았다. 프론트 화면은 테스트 도구가 없어 손대지 않았다(#12에서 Vitest).

---

## 보안 점검

> `/harness:security-review 8` (2026-11-21, HEAD da46ec2). 대장(`securityExceptions`)은 설정에 없다.

**수집 결과:** 타입 검사(`compileJava compileTestJava`, `tsc -b`) 오류 없음 · `npm audit` 취약점 0 · 비밀값 패턴·알려진 키 접두사 없음 · `.env` 커밋·스테이지 없음 · 프론트 `console.log`·`dangerouslySetInnerHTML`·`VITE_` 없음 · channel 패키지 로그 출력 없음

| 분류 | 파일·줄 | 내용 | 판단 |
| --- | --- | --- | --- |
| 🔴 | — | 없음 | |
| 🟡 | — | 없음 | |
| ⚪ | `PUT /api/channels/{id}` | 남의 채널 id를 넣는 요청(IDOR) | 서비스가 세션의 회원 id와 채널의 `member_id`를 비교해 403. 통합 테스트(스트리머 둘)와 변이(주인 확인 제거 → 실패)로 확인. 회원 id는 세션에서만 읽는다 |
| ⚪ | `GET /api/channels/*` permitAll | 비로그인 공개 | 확정 사양("로그인하지 않아도 시청")대로다. 응답은 id·이름·소개·방송 여부뿐이고 주인 회원 id·이메일은 싣지 않는다. 채널 id는 비밀이 아니다(PRD 작은 결정) |
| ⚪ | `ChannelPage.tsx`·`StudioPage.tsx` | 채널 이름·소개(사용자 입력) 출력 | React가 글자로만 넣는다(`dangerouslySetInnerHTML` 없음) |
| ⚪ | `/api/studio/**` | 역할 규칙이 세션의 역할을 본다 | 승인되면 #7이 그 회원 세션을 지우므로 낡은 `MEMBER` 세션으로 스튜디오에 못 들어오는 일은 다시 로그인하면 풀린다. 권한이 더 높은 쪽으로 낡는 경로(스트리머 회수)는 기능이 없다 |
| ⚪ | `UpdateChannelRequest` | 입력 검증 | 이름 `@NotBlank @Size(2,20)`, 소개 `@NotNull @Size(max=200)`. DB 컬럼도 `VARCHAR2(20 CHAR)`·`(200 CHAR)`라 검증을 지나 DB에서 터지는 길이 없다. SQL은 바인드 변수 |

즉시 수정할 항목이 없어 코드를 고치지 않았다. 재스캔 대상 변경도 없다.
