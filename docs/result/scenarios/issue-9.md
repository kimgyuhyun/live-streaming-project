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

---

## AC 검증

> `harness:ac-verifier` (2026-11-21, HEAD 663e8a8). 변이 최대 3번·20분·스크래치 복사본·클래스 단위 테스트로 제한해 띄웠다. 아래는 검증자 출력 원문이다(제목 수준만 한 단계 낮췄다). 구현자는 맨 아래 `### 구현자 이견`에만 쓴다.

### 이슈 #9 AC 검증 결과 (브랜치 feat/channel/issue-9, HEAD 663e8a8)

**결론:** AC 6개 중 5개는 충족, 1개(AC 5)는 부분 충족이다. AC 5는 키프레임 1~2초 안내에 자동 테스트가 없다. 화면 테스트 도구는 #12에서 들어올 예정이라, 지금은 수동 확인 기록과 코드 읽기로만 확인했다.

대상 테스트 19개는 전부 통과했다. 변이는 스크래치 복사본에서 3번 돌렸고, 세 번 모두 예상한 테스트만 정확히 실패했다. 저장소 작업 트리는 건드리지 않았다(`git status --short` 결과 비어 있음).

#### AC별 판정

| AC | 판정 | 근거 테스트 (@DisplayName, 파일:줄) | 구현 (파일:줄) | 판단 이유 |
|---|---|---|---|---|
| 1. 키가 없는 스트리머가 발급하면 응답에 전체 키, DB에는 SHA-256 해시와 앞 4자리만 | ✅ | `should return the full key and save only its SHA-256 hash and first 4 characters when the streamer has no key` (StreamKeyServiceTest.java:66)<br>`should return the full key and store only its SHA-256 hash and first 4 characters when a streamer without a key issues one` (StreamKeyIntegrationTest.java:136) | StreamKeyService.java:31-37, 56-64<br>ChannelMapper.xml:28-31<br>V4__add_stream_key_to_channel.sql:3-6 | 통합 테스트가 실제 Oracle의 `STREAM_KEY_HASH`·`STREAM_KEY_PREFIX` 값을, 테스트가 따로 계산한 `sha256Hex(key)`·`key.substring(0,4)`와 비교한다. 변이 1(SQL에서 prefix 저장 빼기)에서 이 테스트가 실패했다. "만"은 직접 단언하지 않는다. 대신 구조로 보장된다: 채널 테이블 컬럼(V3:2-11, V4)에 43자 키가 들어갈 자리가 없고, DB에 쓰는 경로는 `updateStreamKey` 하나뿐이다. |
| 2. 키가 있는 스트리머가 조회하면 앞 4자리만, 전체 키는 응답 어디에도 없음 | ✅ | `should return only the first 4 characters and never the full key when a streamer with a key views it in the studio` (StreamKeyIntegrationTest.java:149)<br>`should return only the first 4 characters and the OBS server when the streamer has a key` (StreamKeyServiceTest.java:143)<br>`should return 200 with the key prefix and the OBS server` (StreamKeyControllerTest.java:238) | StreamKeyService.java:39-41<br>StreamKeyResponse.java (필드 `keyPrefix`, `obsServer`뿐) | 응답 본문 전체에 대해 `doesNotContain(key)`와 `doesNotContain(key.substring(4,12))`를 단언한다. 응답 타입에 키 필드가 없고 DB에도 전체 키가 없어서, 구조상 새어 나갈 수 없다. 변이 1에서도 이 테스트가 실패해 실제 DB 경로를 탄다는 것도 확인했다. |
| 3. 재발급하면 새 전체 키가 응답에, 저장된 해시가 새 키의 해시로 | ✅ | `should replace the saved hash and prefix with those of the new key when the streamer already has a key` (StreamKeyServiceTest.java:77)<br>`should store the hash of the new key when a streamer with a key reissues it` (StreamKeyIntegrationTest.java:166) | StreamKeyService.java:31-37 (키가 있든 없든 덮어씀) | 통합 테스트는 두 가지를 본다: 새 키가 이전 키와 다른지, DB 해시가 `sha256Hex(newKey)`인지. 변이 3("키가 이미 있으면 저장 안 함")에서 이 두 테스트만 실패했다. |
| 4. 키는 32바이트 난수를 URL에 안전한 문자로 만든 것 | ✅ | `should make the key from 32 random bytes written in URL-safe characters` (StreamKeyServiceTest.java:104)<br>`should make a different key on each issue` (StreamKeyServiceTest.java:116) | StreamKeyService.java:18, 20 (`SecureRandom`), 49-53 (`Base64.getUrlEncoder().withoutPadding()`) | 경계 확인: 정확히 43자, `[A-Za-z0-9_-]`, 디코딩하면 정확히 32바이트. 31바이트(42자)나 33바이트(44자)면 실패한다. 보안 난수(`SecureRandom`)를 쓴다는 것은 테스트로 구분할 수 없어 코드로만 확인했다. |
| 5. OBS 안내: 서버 `rtmp://{서버}/live`, 스트림 키 칸 `{채널ID}?key={키}`, 키프레임 1~2초 | ⚠️ | `should return the OBS server and the stream key field value made of the channel id and the key` (StreamKeyServiceTest.java:90)<br>`should return the configured OBS server and the stream key field value with the channel id when a streamer issues a key` (StreamKeyIntegrationTest.java:180)<br>키프레임: 자동 테스트 없음 | StreamKeyService.java:36<br>application.yml:45<br>StreamKeySection.tsx:21, 53-58, 60 | 서버 주소와 스트림 키 칸 값은 백엔드 통합 테스트로 확인된다(`rtmp://localhost/live`, `{channelId}?key={key}`). 단위 테스트에서 MEMBER_ID=1, CHANNEL_ID=5로 값을 달리 둬서 회원 ID와 채널 ID를 헷갈리면 잡힌다. 키프레임 문구는 StreamKeySection.tsx:60에 있고 시나리오 문서 154-161줄에 수동 확인 기록이 있지만 자동 테스트는 없다. 화면이 서버 주소와 마스킹된 값을 제대로 그리는지도 자동 검증이 없다. |
| 6. 스트리머가 아닌 회원·비로그인이 발급·조회하면 403·401 | ✅ | `should return 403 with code FORBIDDEN when a member who is not a streamer issues a key` (:192)<br>`... views the key` (:203)<br>`should return 401 with code UNAUTHENTICATED when a visitor without login issues a key` (:214)<br>`... views the key` (:222) (모두 StreamKeyIntegrationTest.java) | SecurityConfig.java:57 (`/api/studio/**` → `hasRole("STREAMER")`) | 상태 코드와 `$.code`까지 단언한다. 구현자는 `permitAll()`로 변이 확인을 했다. 나는 따로 `.authenticated()`로 바꿔 봤고(변이 2), 403 테스트 2개만 실패하고 401 테스트 2개는 통과했다. 403 테스트가 역할을 실제로 구분한다는 뜻이다. |

**요약:** AC 6개 중 — ✅ 5 / ⚠️ 1 / ❌ 0

#### 갭을 메울 시나리오 제안 (AC 5)

#12에서 Vitest가 들어오면 붙일 수 있는 형태다.

- [ ] [정상] `StreamKeySection` — should show the keyframe interval guidance of 1 to 2 seconds when the studio is shown
- [ ] [정상] `StreamKeySection` — should show the OBS server from the stream key response when the studio is shown
- [ ] [정상] `StreamKeySection` — should show the full stream key field value `{channelId}?key={key}` when a key has just been issued
- [ ] [경계] `StreamKeySection` — should show only `{channelId}?key={prefix}••••` and never the full key when the page is loaded with an existing key

#### 가짜 테스트 여부

없다.
- 예외 단언은 전부 구체적이다: `isInstanceOf(BusinessException.class)`에 더해 `extracting("code").isEqualTo(ErrorCode.CHANNEL_NOT_FOUND)`까지 본다(StreamKeyServiceTest.java:131-134, 163-166).
- 단언 없는 테스트도, `await` 누락 같은 비동기 문제도 없다.
- StreamKeyControllerTest.java:219, 238은 목 서비스가 준 값을 그대로 단언한다. 다만 이것은 라우팅과 JSON 직렬화를 보려는 의도된 컨트롤러 테스트다. 어느 AC도 이 테스트만으로 판정하지 않았고, 같은 내용을 통합 테스트가 실제 경로로 확인한다.
- 서비스 테스트는 매퍼를 목으로 쓰지만, 반환값이 아니라 `ArgumentCaptor`로 저장 요청에 넘어간 인자를 잡아 테스트가 따로 계산한 SHA-256과 비교한다.

#### 확인하지 않은 것

- **프론트엔드 화면:** 실행도 테스트도 하지 않았다(도구 없음). 브라우저 수동 확인도 다시 하지 않았고, 시나리오 문서의 기록과 코드 읽기만 했다.
- **구현자의 `permitAll()` 변이:** 그대로 재현하지 않았다. 대신 `.authenticated()` 변이를 돌렸다.
- **OPERATOR 역할 차단:** OPERATOR로 `/api/studio/stream-key`를 부르는 테스트는 없다. RoleHierarchy가 없는 것(grep 결과 없음)과 `hasRole("STREAMER")`로 미루어 403일 것이라고 코드로만 판단했다. AC의 "스트리머가 아닌 회원"은 MEMBER 테스트로 덮인다고 봤다.
- **응답 헤더:** 조회 응답의 헤더에 전체 키가 없는지는 따로 보지 않았다(본문만 단언됨).
- **전체 테스트 스위트:** 지시대로 돌리지 않았다(구현자 보고: 235개 통과).
- **`SecureRandom` 사용:** 테스트로 구분할 수 없는 부분이라 코드로만 확인했다.

#### 실행·읽은 것 목록

1. `cat .claude/harness.json && gh issue view 9 && git diff origin/main...HEAD --stat` → 설정 확인, AC 6개, 변경 파일 19개
2. Read `docs/result/scenarios/issue-9.md` → 시나리오 19개, AC 대조표, 수동 확인과 401/403 변이 기록
3. `cat -n` StreamKeyService/StreamKeyController/IssuedStreamKeyResponse/StreamKeyResponse/V4, `git diff origin/main...HEAD` Channel·ChannelMapper·ChannelMapper.xml·application.yml·.env.example·compose.yml·StreamerApplicationReviewIntegrationTest·StudioPage → 구현 확인
4. `cat -n` StreamKeyServiceTest.java, StreamKeyControllerTest.java → 테스트 본문
5. `cat -n` StreamKeyIntegrationTest.java, StreamKeySection.tsx, api/streamKey.ts → 통합 테스트와 화면
6. `grep` SecurityConfig·PRD C1 → SecurityConfig.java:57 `hasRole("STREAMER")`, PRD channel.md:30-38 C1
7. `git archive HEAD backend | tar -x` → `C:\Users\USER\AppData\Local\Temp\claude\1cbb40fc7d76b7b0\ad17e800-4c27-40f4-ae12-ad63eb406d97\scratchpad\verify9\backend`, `docker info` → 28.4.0
8. 기준 실행 `./gradlew test --tests "StreamKey*Test"` (스크래치) → BUILD SUCCESSFUL. 결과 XML: issue 6, find 3, POST 1, GET 1, Integration 8 = 19개, 실패 0
9. 변이 1: ChannelMapper.xml `updateStreamKey`에서 `stream_key_prefix` 저장 제거 → 19개 중 2개 실패(통합 테스트 :136, :149). 서비스·컨트롤러 테스트는 통과
10. 변이 2: 1을 되돌린 뒤 SecurityConfig `/api/studio/**`를 `.authenticated()`로 → 19개 중 2개 실패(403 테스트 :192, :203). 401 테스트는 통과
11. 변이 3: 2를 되돌린 뒤 StreamKeyService.issue를 `if (channel.getStreamKeyHash() == null)`일 때만 저장하게 → 19개 중 2개 실패(StreamKeyServiceTest :77, StreamKeyIntegrationTest :166)
12. `git status --short` (저장소) → 비어 있음, HEAD 663e8a8. V3 채널 스키마 확인
13. `grep` RoleHierarchy/enum Role, application.yml·ChannelMapper.xml 줄 번호 → RoleHierarchy 없음, Role = MEMBER/STREAMER/OPERATOR, obs-server는 application.yml:45, updateStreamKey는 ChannelMapper.xml:28

스크래치 복사본에는 변이 3이 그대로 남아 있다(StreamKeyService.java:35). 저장소와는 상관없다.

### 구현자 이견

- **AC 5 ⚠️ — #12까지 범위 밖으로 인정한다(#1 AC 8 선례). 사용자 확인 필요.** 사전 승인은 "⚠️면 검증자 제안을 그대로 더해 Red→Green으로 메운다"였지만, 이 갭의 제안 4개는 모두 화면(`StreamKeySection`) 테스트라 프론트 테스트 도구가 있어야 한다. Vitest는 "화면 상태 로직이 처음 생기는 #12에서 넣는다"고 이미 정해져 있다(CLAUDE.md 프론트 컨벤션, `docs/result/issues.md` 셋업 체크리스트, #12 AC). #1에서도 같은 종류의 갭(`SignUpPage`)을 사용자가 "#12까지 범위 밖"으로 정했다. 이 결정을 밤사이 혼자 뒤집어 #12의 도구 도입을 당겨 오지 않았다. 대신
  - 서버 쪽 AC 5(서버 주소, 스트림 키 칸 `{채널ID}?key={키}`)는 통합 테스트가 실제 설정값으로 본다.
  - 키프레임 1~2초 문구와 화면 표시(발급 직후 전체 값, 새로고침 뒤 `앞4자리••••`)는 위 "확인 결과"의 브라우저 수동 확인으로 대신한다.
  - 검증자의 `StreamKeySection` 시나리오 4개는 #12에서 Vitest를 넣을 때 그대로 추가한다(아래 "#12로 넘기는 시나리오").
- **2차 검증은 띄우지 않았다.** 메운 테스트가 없어 다시 볼 대상이 없다.
- 선택적으로 언급된 OPERATOR 403은 넣지 않았다. 역할 계층이 없고 규칙이 `hasRole("STREAMER")` 하나라 MEMBER와 같은 줄에서 막힌다.

### #12로 넘기는 시나리오

- `[정상] StreamKeySection — should show the keyframe interval guidance of 1 to 2 seconds when the studio is shown`
- `[정상] StreamKeySection — should show the OBS server from the stream key response when the studio is shown`
- ``[정상] StreamKeySection — should show the full stream key field value `{channelId}?key={key}` when a key has just been issued``
- ``[경계] StreamKeySection — should show only `{channelId}?key={prefix}••••` and never the full key when the page is loaded with an existing key``

---

## 리팩터

고칠 대상 없음(2026-11-21). `StreamKeyService`는 공개 메서드 2개에 키 만들기(`newKey`)·해시(`sha256Hex`)·채널 찾기가 이미 나뉘어 있고, 숫자(32바이트·앞 4자리)는 상수다. 컨트롤러는 위임 한 줄씩이다. `findChannel`(회원 id로 찾고 없으면 404)은 `ChannelService.findMine`과 모양이 같지만 두 번째 반복이라 묶지 않았다(세 번째가 생기면 `ChannelMapper` 쪽 공통화를 검토). 프론트 `StreamKeySection`은 테스트 도구가 없어 손대지 않았다(#12에서 Vitest).

---

## 보안 점검

> `/harness:security-review 9` (2026-11-21, HEAD 663e8a8). 대장(`securityExceptions`)은 설정에 없다.

**수집 결과:** 타입 검사(`compileJava compileTestJava`, `tsc -b`) 오류 없음 · `npm audit` 취약점 0 · 비밀값 패턴·알려진 키 접두사 없음 · `.env` 커밋 없음(`.env.example`은 주석 처리된 기본 주소 한 줄만 더함, 비밀값 아님) · 프론트 `console.log`·브라우저 저장소(`localStorage`·`sessionStorage`) 사용 없음 · channel 패키지 로그 출력 없음

| 분류 | 파일·줄 | 내용 | 판단 |
| --- | --- | --- | --- |
| 🔴 | — | 없음 | |
| 🟡 | — | 없음 | |
| ⚪ | `StreamKeyService`·V4 | 키 저장 방식 | PRD C1대로 SHA-256 해시(64자)와 앞 4자리만 저장한다. 키는 `SecureRandom` 32바이트라 솔트·느린 해시가 필요 없다(C1). 전체 키를 담는 컬럼이 없어 DB가 새도 송출할 수 없다 |
| ⚪ | `POST /api/studio/stream-key` 응답 | 전체 키가 응답 본문에 있다 | 발급·재발급 응답에만 있다(C1, AC 1). 로그에 남기지 않고, 화면은 메모리(`useMutation` 결과)에만 두며 브라우저 저장소에 쓰지 않는다. Spring Security 기본 헤더가 `Cache-Control: no-cache, no-store`를 붙인다 |
| ⚪ | `GET /api/studio/stream-key` | 전체 키가 새는지 | 응답 타입에 키 필드가 없다(앞 4자리·서버 주소뿐). 통합 테스트가 응답 본문에 키와 그 조각이 없음을 단언한다 |
| ⚪ | `/api/studio/**` | 권한 | 스트리머 전용(#8 규칙). 회원 403·비로그인 401을 통합 테스트 4개와 변이(`permitAll`·`authenticated`)로 확인. POST는 출처 검증 필터를 거친다. 키는 세션 회원의 채널에만 발급되어 남의 채널 키를 바꿀 경로(IDOR)가 없다 |
| ⚪ | 앞 4자리 공개 | 키 엔트로피 | 43자 중 4자(24비트)만 보여 줘 남은 234비트는 추측할 수 없다(시그니처 판단 5) |
| ⚪ | 재발급 | 이전 키로 하던 송출 | 이 이슈는 DB의 해시만 바꾼다. 키 확인과 송출 끊기는 #10(이슈 본문이 범위를 나눔) |

즉시 수정할 항목이 없어 코드를 고치지 않았다. 재스캔 대상 변경도 없다.
