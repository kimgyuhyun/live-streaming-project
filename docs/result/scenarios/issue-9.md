# 이슈 #9 — 스튜디오에서 스트림 키를 발급하고 OBS 설정을 본다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/9
> PRD: `docs/result/prd/channel.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

> 게이트 처리: 사용자가 자리를 비운 동안 진행했다. 사전 승인(밤새 #7~#9 진행 지시)에 따라 시그니처·시나리오를 구현자가 정하고 승인된 것으로 진행했다.

---

## 시그니처

### 관련 ADR

- **채널 C1(스트림 키는 해시와 앞 4자리만 저장)** — 채널에 키의 SHA-256 해시와 앞 4자리만 저장한다. 발급·재발급 응답에만 전체 키를 담는다. 조회 API는 앞 4자리만 준다. 확인(#10)은 들어온 키를 해시해 `MessageDigest.isEqual`로 비교한다 — 그래서 해시는 다시 계산할 수 있는 고정 형식(소문자 16진수 64자)으로 둔다.
- **채널 PRD 작은 결정(키 생성)** — 보안 난수(`SecureRandom`) 32바이트를 URL에 안전한 문자(Base64 URL, 패딩 없음 → 43자 `[A-Za-z0-9_-]`)로 만든다.
- **채널 PRD 검증에서 정한 것(송출 주소)** — OBS: 서버 `rtmp://{서버}/live`, 스트림 키 칸 `{채널ID}?key={스트림키}`.
- **채널 PRD 작은 결정(승인 처리)** — 키는 승인 때 만들지 않는다. 스트리머가 스튜디오에서 처음 발급한다.
- **이슈 본문** — 송출 연결 끊기(재발급 때 MediaMTX 호출)는 #10이다. 이 이슈의 재발급은 DB의 해시·앞 4자리만 바꾼다.
- **#8 판단 2** — 스튜디오 API는 `/api/studio/**`(스트리머 전용) 아래에 둔다.

### DB

`backend/src/main/resources/db/migration/V4__add_stream_key_to_channel.sql`

```sql
-- 키가 없으면 둘 다 NULL. 해시는 SHA-256 소문자 16진수(64자)
ALTER TABLE channel ADD (
    stream_key_hash   VARCHAR2(64),
    stream_key_prefix VARCHAR2(4)
);
```

### 타입

```java
// com.livestream.channel
public class Channel {
    // 추가 (키가 없으면 null)
    String streamKeyHash; String streamKeyPrefix;
}

// 발급·재발급 응답. 전체 키는 여기에만 있다
public record IssuedStreamKeyResponse(String streamKey, String keyPrefix, String obsServer, String obsStreamKey) {}
// 스튜디오 조회 응답. keyPrefix는 키가 없으면 null
public record StreamKeyResponse(String keyPrefix, String obsServer) {}

@Mapper
public interface ChannelMapper {
    // 추가. findById·findByMemberId는 두 컬럼도 읽는다
    void updateStreamKey(@Param("id") long id, @Param("hash") String hash, @Param("prefix") String prefix);
}

@Service
public class StreamKeyService {
    // obsServer는 설정 app.obs-server (환경변수 APP_OBS_SERVER, 기본 rtmp://localhost/live)
    public StreamKeyService(ChannelMapper channelMapper, @Value("${app.obs-server}") String obsServer);

    // 키가 없든 있든 새 키를 만들어 해시·앞 4자리를 저장하고 전체 키를 한 번 돌려준다.
    // obsStreamKey = "{채널ID}?key={키}". 채널이 없으면 CHANNEL_NOT_FOUND
    public IssuedStreamKeyResponse issue(long memberId);

    // 앞 4자리(없으면 null)와 OBS 서버. 채널이 없으면 CHANNEL_NOT_FOUND
    public StreamKeyResponse find(long memberId);
}

@RestController
class StreamKeyController {
    @PostMapping("/api/studio/stream-key")   // 200 + IssuedStreamKeyResponse (발급·재발급 같은 요청)
    IssuedStreamKeyResponse issue(@AuthenticationPrincipal LoginMember loginMember);
    @GetMapping("/api/studio/stream-key")    // 200 + StreamKeyResponse
    StreamKeyResponse find(@AuthenticationPrincipal LoginMember loginMember);
}
```

설정 — `application.yml`에 `app.obs-server: ${APP_OBS_SERVER:rtmp://localhost/live}`. `.env.example`과 `compose.yml`(api 환경변수)에 `APP_OBS_SERVER`를 더한다. 서버 배포 때 실제 주소로 채운다.

`SecurityConfig`는 바꾸지 않는다. `/api/studio/**` 스트리머 전용(#8), 로그인 필요(401), 출처 검증이 그대로 걸린다.

### 에러 케이스

| 상황 | 에러 코드 | HTTP |
| --- | --- | --- |
| 스트리머가 아닌 회원이 발급·조회 | `FORBIDDEN` (#7) | 403 |
| 로그인하지 않고 발급·조회 | `UNAUTHENTICATED` (기존) | 401 |
| 스트리머인데 채널이 없음(승인 트랜잭션상 생기지 않음) | `CHANNEL_NOT_FOUND` (#8) | 404 |

### 화면

- `src/api/streamKey.ts` — `IssuedStreamKeyResponse`, `StreamKeyResponse` 타입, `issueStreamKey()`, `fetchStreamKey()`
- `/studio`의 "스트림 키·OBS 설정" 절(새 컴포넌트 `StreamKeySection`, props `{ channelId: number }`)
  - 키가 없으면 "아직 발급하지 않았습니다"와 "발급" 버튼. 있으면 `abcd`로 시작하는 가린 키(`abcd••••`)와 "재발급" 버튼, "재발급하면 이전 키는 바로 쓸 수 없습니다" 안내
  - 발급·재발급 직후에만 전체 키와 "이 키는 지금 한 번만 보여 줍니다" 안내
  - OBS 설정: 서버 `obsServer`, 스트림 키 칸에 넣을 값 — 발급 직후엔 `obsStreamKey` 전체, 그 밖엔 `{channelId}?key=abcd••••`
  - "OBS 출력 설정에서 키프레임 간격을 1~2초로 설정하세요"
  - 성공하면 `['streamKey']`를 무효화한다

### 판단이 갈린 곳

1. **발급과 재발급은 같은 요청(`POST /api/studio/stream-key`)이다.** 둘 다 "새 키를 만들어 덮어쓴다"이고, 데모 스크립트(C3)도 "발급(이미 있으면 재발급)"으로 한 번에 부른다.
2. **OBS 서버 주소는 서버 설정에서 준다.** 배포 주소가 환경마다 다르다. 화면에 박으면 로컬·서버 빌드가 갈린다.
3. **키프레임 안내 문구는 화면에만 둔다.** 환경과 무관한 고정 문구다. 프론트 테스트 도구가 아직 없어(#12) 브라우저 수동 확인으로 본다.
4. **해시는 소문자 16진수(`HexFormat`)로 저장한다.** #10이 같은 함수로 다시 계산해 비교한다.
5. **앞 4자리는 키의 첫 4글자 그대로다.** 43자 중 4자(24비트)만 보여 줘 나머지 234비트는 남는다.
6. 매퍼 SQL은 `@SpringBootTest` 통합 테스트로 확인한다. `ChannelMapper`에 메서드를 더하므로 #7 통합 테스트의 익명 구현(`StreamerApplicationReviewIntegrationTest`)에도 더한다.

### 이 이슈에서 만들지 않는 것

- 키 확인(MediaMTX → Spring), 재발급 때 송출 연결 끊기 — #10
- 방송 상태·연결 상태 — #11·#13

---

## 테스트 시나리오

### 정상

- [x] [정상] `StreamKeyService.issue` — should return the full key and save only its SHA-256 hash and first 4 characters when the streamer has no key
- [x] [정상] `StreamKeyService.issue` — should replace the saved hash and prefix with those of the new key when the streamer already has a key
- [x] [정상] `StreamKeyService.issue` — should return the OBS server and the stream key field value made of the channel id and the key
- [x] [정상] `StreamKeyService.find` — should return only the first 4 characters and the OBS server when the streamer has a key
- [x] [정상] `POST /api/studio/stream-key` — should return 200 with the full key, prefix, OBS server and OBS stream key value when service issues a key
- [x] [정상] `GET /api/studio/stream-key` — should return 200 with the key prefix and the OBS server
- [x] [정상] `StreamKeyIntegration` — should return the full key and store only its SHA-256 hash and first 4 characters when a streamer without a key issues one
- [x] [정상] `StreamKeyIntegration` — should return only the first 4 characters and never the full key when a streamer with a key views it in the studio
- [x] [정상] `StreamKeyIntegration` — should store the hash of the new key when a streamer with a key reissues it
- [x] [정상] `StreamKeyIntegration` — should return the configured OBS server and the stream key field value with the channel id when a streamer issues a key

### 경계

- [x] [경계] `StreamKeyService.issue` — should make the key from 32 random bytes written in URL-safe characters
- [x] [경계] `StreamKeyService.issue` — should make a different key on each issue
- [x] [경계] `StreamKeyService.find` — should return no prefix when the streamer has no key

### 예외

- [x] [예외] `StreamKeyService.issue` — should throw CHANNEL_NOT_FOUND when the member has no channel
- [x] [예외] `StreamKeyService.find` — should throw CHANNEL_NOT_FOUND when the member has no channel
- [x] [예외] `StreamKeyIntegration` — should return 403 with code FORBIDDEN when a member who is not a streamer issues a key
- [x] [예외] `StreamKeyIntegration` — should return 403 with code FORBIDDEN when a member who is not a streamer views the key
- [x] [예외] `StreamKeyIntegration` — should return 401 with code UNAUTHENTICATED when a visitor without login issues a key
- [x] [예외] `StreamKeyIntegration` — should return 401 with code UNAUTHENTICATED when a visitor without login views the key

### 수동 확인 (테스트 도구가 없는 화면 — 시나리오 수에 넣지 않는다)

프론트 테스트 도구(Vitest)는 #12에서 넣는다. 아래는 Green 단계에서 브라우저로 확인하고 결과를 이 문서와 PR에 남긴다.

- 키가 없는 스트리머의 `/studio`에 "아직 발급하지 않았습니다", "발급" 버튼, OBS 서버 `rtmp://localhost/live`, 키프레임 1~2초 안내가 보인다
- "발급"을 누르면 전체 키(43자)와 "지금 한 번만 보여 줍니다", 스트림 키 칸 값 `{채널ID}?key={전체 키}`가 보인다
- 새로고침하면 전체 키는 사라지고 `앞4자리••••`와 "재발급" 버튼, `{채널ID}?key=앞4자리••••`가 보인다
- "재발급"을 누르면 다른 전체 키가 보인다

**확인 결과 (2026-11-21, Green):** 로컬 개발 DB(compose Oracle)로 `backend`·`frontend` 미리보기를 띄웠다. 앱이 뜨면서 V4가 개발 DB에 적용됐다(이 PR의 마이그레이션은 확정본). #8 수동 확인 때 만든 테스트 스트리머(`st820640@example.com`, 채널 1, 키 없음)로 로그인했다. 키 값은 이 문서에 옮기지 않는다.

- 발급 전 `/studio`: "스트림 키 아직 발급하지 않았습니다", "발급" 버튼, OBS 서버 `rtmp://localhost/live`, 스트림 키 "키를 발급하면 보입니다", "OBS 출력 설정에서 키프레임 간격을 1~2초로 설정하세요" ✅
- "발급": 전체 키(43자)와 "이 키는 지금 한 번만 보여 줍니다", OBS 스트림 키 칸 값 `1?key={전체 키}`, 버튼이 "재발급"으로 ✅
- 새로고침: 전체 키는 화면 어디에도 없고(앞 4자리 뒤 조각도 없음) `앞4자리••••`, `1?key=앞4자리••••`, "재발급하면 이전 키는 바로 쓸 수 없습니다" ✅
- "재발급": 앞의 키와 다른 43자 키, `[A-Za-z0-9_-]`만 ✅
- 개발 DB `channel` 1번 행: `stream_key_hash` 길이 64, `stream_key_prefix`는 재발급한 키의 앞 4자리 ✅
- 콘솔 오류는 네트워크 응답(앞선 확인의 400·401·404, 백엔드 기동 전 502)뿐, 스크립트 오류 없음

**401·403 테스트 변이 확인 (Green):** 이 4개는 #8의 `/api/studio/**` 스트리머 전용 규칙이 이미 막고 있어 Red에서 구현 없이 통과했다. `SecurityConfig`에서 이 규칙을 `permitAll()`로 바꾸자 `StreamKeyIntegrationTest` 8개 중 정확히 이 4개만 실패했다. `git checkout --`으로 되돌렸다.

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 키가 없는 스트리머가 발급 → 응답에 전체 키, DB에는 SHA-256 해시와 앞 4자리만 | `[정상] StreamKeyService.issue — should return the full key and save only its SHA-256 hash and first 4 characters when the streamer has no key`<br>`[정상] POST /api/studio/stream-key — should return 200 with the full key, prefix, OBS server and OBS stream key value when service issues a key`<br>`[정상] StreamKeyIntegration — should return the full key and store only its SHA-256 hash and first 4 characters when a streamer without a key issues one` |
| 2. 키가 있는 스트리머가 스튜디오에서 조회 → 앞 4자리만, 전체 키는 응답 어디에도 없음 | `[정상] StreamKeyService.find — should return only the first 4 characters and the OBS server when the streamer has a key`<br>`[정상] GET /api/studio/stream-key — should return 200 with the key prefix and the OBS server`<br>`[정상] StreamKeyIntegration — should return only the first 4 characters and never the full key when a streamer with a key views it in the studio`<br>`[경계] StreamKeyService.find — should return no prefix when the streamer has no key` |
| 3. 키가 있는 스트리머가 재발급 → 새 전체 키가 응답에, 저장된 해시가 새 키의 해시로 | `[정상] StreamKeyService.issue — should replace the saved hash and prefix with those of the new key when the streamer already has a key`<br>`[정상] StreamKeyIntegration — should store the hash of the new key when a streamer with a key reissues it` |
| 4. 발급된 키 = 32바이트 난수를 URL에 안전한 문자로 | `[경계] StreamKeyService.issue — should make the key from 32 random bytes written in URL-safe characters`<br>`[경계] StreamKeyService.issue — should make a different key on each issue` |
| 5. OBS 안내: 서버 `rtmp://{서버}/live`, 스트림 키 칸 `{채널ID}?key={키}`, 키프레임 1~2초 | `[정상] StreamKeyService.issue — should return the OBS server and the stream key field value made of the channel id and the key`<br>`[정상] StreamKeyIntegration — should return the configured OBS server and the stream key field value with the channel id when a streamer issues a key`<br>(키프레임 안내 문구는 화면 고정 문구 — 수동 확인) |
| 6. 스트리머가 아닌 회원·비로그인이 발급·조회 → 403·401 | `[예외] StreamKeyIntegration — should return 403 with code FORBIDDEN when a member who is not a streamer issues a key`<br>`[예외] StreamKeyIntegration — should return 403 with code FORBIDDEN when a member who is not a streamer views the key`<br>`[예외] StreamKeyIntegration — should return 401 with code UNAUTHENTICATED when a visitor without login issues a key`<br>`[예외] StreamKeyIntegration — should return 401 with code UNAUTHENTICATED when a visitor without login views the key` |

**AC에 없는데 넣은 시나리오**

| 시나리오 | 이유 |
| --- | --- |
| `[예외] StreamKeyService.issue — should throw CHANNEL_NOT_FOUND when the member has no channel`<br>`[예외] StreamKeyService.find — should throw CHANNEL_NOT_FOUND when the member has no channel` | 서비스가 회원 id로 채널을 찾는다. 없을 때 NPE(500) 대신 정해진 코드를 낸다 |

**커버리지:** AC 6개 / 시나리오 19개 / 미커버 0개 (AC 5의 키프레임 안내 문구는 수동 확인)
