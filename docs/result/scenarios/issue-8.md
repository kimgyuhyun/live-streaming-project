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

- [ ] [정상] `ChannelService.find` — should return name, description and offline status when the channel exists
- [ ] [정상] `ChannelService.findMine` — should return the channel of the member when the member owns one
- [ ] [정상] `ChannelService.update` — should save the new name and description and return them when the owner updates
- [ ] [정상] `GET /api/channels/{channelId}` — should return 200 with name, description and live false when service finds the channel
- [ ] [정상] `GET /api/studio/channel` — should return 200 with the channel of the logged-in streamer
- [ ] [정상] `PUT /api/channels/{channelId}` — should return 200 with the updated channel when the owner sends a valid name and description
- [ ] [정상] `ChannelIntegration` — should show name, description and offline status to a visitor without login
- [ ] [정상] `ChannelIntegration` — should save a 20-character name and a 200-character description and show them on the channel page when the owner updates
- [ ] [정상] `ChannelIntegration` — should return the channel of the logged-in streamer from the studio

### 경계

- [ ] [경계] `ChannelService.find` — should return an empty description when the stored description is null
- [ ] [경계] `PUT /api/channels/{channelId}` — should accept a name of 2 characters
- [ ] [경계] `PUT /api/channels/{channelId}` — should accept a name of 20 characters
- [ ] [경계] `PUT /api/channels/{channelId}` — should accept an empty description
- [ ] [경계] `PUT /api/channels/{channelId}` — should accept a description of 200 characters
- [ ] [경계] `PUT /api/channels/{channelId}` — should return 400 with code INVALID_INPUT when the name has 1 character
- [ ] [경계] `PUT /api/channels/{channelId}` — should return 400 with code INVALID_INPUT when the name has 21 characters
- [ ] [경계] `PUT /api/channels/{channelId}` — should return 400 with code INVALID_INPUT when the description has 201 characters
- [ ] [경계] `ChannelIntegration` — should save a 2-character name and an empty description and show the description as empty on the channel page

### 예외

- [ ] [예외] `ChannelService.find` — should throw CHANNEL_NOT_FOUND when the channel does not exist
- [ ] [예외] `ChannelService.findMine` — should throw CHANNEL_NOT_FOUND when the member has no channel
- [ ] [예외] `ChannelService.update` — should throw CHANNEL_NOT_OWNER without saving when another member updates the channel
- [ ] [예외] `ChannelService.update` — should throw CHANNEL_NOT_FOUND when the channel does not exist
- [ ] [예외] `GET /api/channels/{channelId}` — should return 404 with code CHANNEL_NOT_FOUND and its message when service reports the channel does not exist
- [ ] [예외] `PUT /api/channels/{channelId}` — should return 400 with code INVALID_INPUT when the name is blank
- [ ] [예외] `PUT /api/channels/{channelId}` — should return 400 with code INVALID_INPUT when the description is missing
- [ ] [예외] `PUT /api/channels/{channelId}` — should return 403 with code CHANNEL_NOT_OWNER and its message when service rejects a member who does not own the channel
- [ ] [예외] `ChannelIntegration` — should return 404 with code CHANNEL_NOT_FOUND when a visitor requests a channel id that does not exist
- [ ] [예외] `ChannelIntegration` — should return 403 with code CHANNEL_NOT_OWNER and keep the channel unchanged when another streamer updates it
- [ ] [예외] `ChannelIntegration` — should return 403 with code FORBIDDEN when a member who is not a streamer opens the studio channel

### 수동 확인 (테스트 도구가 없는 화면 — 시나리오 수에 넣지 않는다)

프론트 테스트 도구(Vitest)는 #12에서 넣는다. 아래는 Green 단계에서 브라우저로 확인하고 결과를 이 문서와 PR에 남긴다.

- 로그인하지 않고 `/channels/{id}`를 열면 채널 이름·소개·"오프라인"이 보인다
- 없는 id로 열면 "채널을 찾을 수 없습니다"
- 스트리머로 로그인하면 머리글에 "스튜디오" 링크가 있고, `/studio`에 지금 이름·소개가 채워져 있다
- 이름·소개를 바꿔 저장하면 "저장했습니다"가 나오고, 채널 페이지에 바뀐 값이 보인다
- 이름을 1자로 저장하면 서버 메시지 "채널 이름은 2~20자입니다"가 보인다

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
