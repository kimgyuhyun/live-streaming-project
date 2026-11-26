# 이슈 #35 — 스튜디오에 처음 들어갈 때 약관에 동의한다

> GitHub: https://github.com/kimgyuhyun/live-streaming-project/issues/35
> PRD: `docs/result/prd/channel.md`
> 담당: kimgyuhyun
> 상태: 시나리오 승인

---

## 시그니처

### 관련 결정

- **채널 PRD 작은 결정(스튜디오 약관 동의, #35)** — 동의는 약관 항목마다 `STUDIO_TERMS_AGREEMENT`(회원·약관 코드·약관 버전·동의 시각)에 남긴다. 필수 항목은 스튜디오 이용약관·콘텐츠 가이드라인(각 버전 1). 지금 버전의 필수 항목에 모두 동의하지 않은 스트리머는 `/api/studio/**`(동의 여부 조회·동의 API 제외)가 403(`STUDIO_TERMS_NOT_AGREED`).
- **#9·#13** — `/api/studio/**`는 스트리머만(`SecurityConfig`). 내 채널은 세션으로 정한다(`@AuthenticationPrincipal LoginMember`).
- **사용자 결정(2026-11-26)** — 서버에 저장하고 서버가 막는다. 약관 전문은 다른 서비스 약관을 옮기지 않고 이 서비스 기능으로 직접 쓴다(포트폴리오용 예시, 법적 효력 없음을 밝힌다).

### DB

`backend/src/main/resources/db/migration/V8__create_studio_terms_agreement.sql`

```sql
-- 스튜디오 약관 동의(#35). 약관 항목·버전마다 한 줄. 약관을 바꾸면 버전을 올려 다시 동의받는다
CREATE TABLE studio_terms_agreement (
    member_id     NUMBER       NOT NULL,
    terms_code    VARCHAR2(30) NOT NULL,
    terms_version NUMBER(3)    NOT NULL,
    agreed_at     TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    -- 같은 버전에 두 번(동시에) 동의해도 한 줄
    CONSTRAINT pk_studio_terms_agreement PRIMARY KEY (member_id, terms_code, terms_version),
    CONSTRAINT fk_studio_terms_agreement_member FOREIGN KEY (member_id) REFERENCES member (id)
);
```

### 타입

```java
// ── com.livestream.channel ─────────────────────────────

// 지금 버전의 스튜디오 약관. 둘 다 필수. 약관을 바꾸면 version만 올린다(프론트 studioTermsText.ts의 버전과 같이)
public enum StudioTerms {
    STUDIO_TERMS(1), CONTENT_GUIDELINE(1);
    public int version();
}

// terms가 없으면 400 INVALID_INPUT(@NotNull). 모르는 코드는 역직렬화 실패로 400 INVALID_INPUT
public record AgreeStudioTermsRequest(@NotNull List<StudioTerms> terms) {}

public record StudioTermsResponse(boolean agreed) {}

// 동의 기록 한 줄
public record StudioTermsAgreement(String termsCode, int termsVersion) {}

public interface StudioTermsMapper {
    List<StudioTermsAgreement> findByMember(long memberId);
    // 같은 (회원, 코드, 버전)이 있으면 아무것도 하지 않는다
    void insertIfAbsent(@Param("memberId") long memberId, @Param("termsCode") String termsCode,
                        @Param("termsVersion") int termsVersion);
}
// SQL: resources/mapper/channel/StudioTermsMapper.xml

public class StudioTermsService {
    // 지금 버전의 필수 약관에 모두 동의했으면 true
    public boolean hasAgreed(long memberId);
    // 필수 약관이 하나라도 빠지면 STUDIO_TERMS_REQUIRED(아무것도 저장하지 않는다). 이미 동의한 항목은 그대로 둔다
    public void agree(long memberId, Collection<StudioTerms> terms);
}

@RestController
class StudioTermsController {
    @GetMapping("/api/studio/terms")            StudioTermsResponse terms(LoginMember);           // 200
    @PostMapping("/api/studio/terms-agreement") ResponseEntity<Void> agree(LoginMember, @Valid AgreeStudioTermsRequest); // 204
}

// /api/studio/** 중 위 두 경로를 뺀 요청에서, hasAgreed가 false면 BusinessException(STUDIO_TERMS_NOT_AGREED)
// (보안 필터 뒤라 회원·운영자는 여기 오기 전에 403 FORBIDDEN)
class StudioTermsInterceptor implements HandlerInterceptor {
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler);
}
// WebMvcConfigurer(StudioTermsWebConfig)로 /api/studio/** 에 등록, 두 경로 제외

// ── com.livestream.common.error.ErrorCode 추가 ─────────
STUDIO_TERMS_NOT_AGREED(HttpStatus.FORBIDDEN, "스튜디오 약관에 동의해야 합니다"),
STUDIO_TERMS_REQUIRED(HttpStatus.BAD_REQUEST, "필수 약관에 모두 동의해 주세요"),
```

### 프론트

```typescript
// api/studioTerms.ts
export type StudioTermsCode = 'STUDIO_TERMS' | 'CONTENT_GUIDELINE'
export function fetchStudioTerms(): Promise<{ agreed: boolean }>   // 스트리머가 아니면 403(ApiError)
export function agreeStudioTerms(terms: StudioTermsCode[]): Promise<void>

// components/studioTermsText.ts — 약관 본문(직접 작성). 버전은 백엔드 StudioTerms와 같다
export interface StudioTermsDoc { code: StudioTermsCode; title: string; required: true; version: number; body: string[] }
export const STUDIO_TERMS_DOCS: StudioTermsDoc[]

// components/StudioTermsModal.tsx — 약관 동의 팝업(모두 동의하기, 항목별 체크·자세히 보기, 취소·X, 스튜디오 가입)
interface StudioTermsModalProps {
  onClose: () => void   // 취소·X. 저장하지 않는다
  onAgreed: () => void  // 동의 저장 성공 뒤
}

// components/useStudioEntry.tsx — 헤더 "스튜디오", 내 채널 "방송하기·채널 관리"가 쓴다
export function useStudioEntry(): { enterStudio: () => void; termsModal: ReactNode }
//   동의함 → /studio, 동의 안 함 → 지금 화면에 팝업, 동의 여부 조회가 403(스트리머 아님) → /studio(기존 "스트리머만" 화면)
//   팝업에서 동의하면 → /studio

// pages/StudioPage.tsx — /studio로 바로 들어와도 동의 여부를 먼저 본다.
//   동의 안 했으면 팝업만 그리고 스튜디오 API(내 채널·키)를 부르지 않는다. 팝업 취소 → 홈(/), 동의 → 스튜디오를 그린다
```

### 에러 케이스

| 상황 | 에러 코드 | HTTP |
| --- | --- | --- |
| 동의 전에 스튜디오 API(내 채널 조회·제목 변경·키 발급·키 조회) | `STUDIO_TERMS_NOT_AGREED` (추가) | 403 |
| 동의 요청에 필수 약관이 빠짐(빈 목록 포함) | `STUDIO_TERMS_REQUIRED` (추가) | 400 |
| `terms`가 없음, 모르는 약관 코드 | `INVALID_INPUT` (기존) | 400 |
| 회원·운영자가 동의 여부 조회·동의 API 호출 | `FORBIDDEN` (기존, `/api/studio/**` 스트리머 전용) | 403 |

### 판단이 갈린 곳

- **차단 위치** — 서비스 메서드마다 검사하지 않고 인터셉터 한 곳에서 막는다. 스튜디오 API가 새로 생겨도 빠뜨리지 않는다. 대가로 스튜디오 요청마다 동의 조회 쿼리가 하나 더 나간다. 스튜디오는 스트리머 본인만 쓰는 화면이라 요청이 적다.
- **동의 여부 응답** — `{agreed}`만 준다. 약관 목록·문구는 프론트(`studioTermsText.ts`)가 갖는다. 약관 버전은 백엔드 `StudioTerms`와 프론트 파일 두 곳에 있으므로 같이 올린다.
- **운영자** — 헤더에 스튜디오 버튼이 보이지만(편의상) 동의 여부 조회부터 403이라 팝업 없이 기존 "스트리머만 쓸 수 있습니다" 화면으로 간다. 이슈 본문의 "팝업에서 가입하면…" 문장보다 단순하다. AC에는 영향 없다.
- **기존 테스트** — `/api/studio/**`를 부르는 통합 테스트(`ChannelIntegrationTest`·`StreamKeyIntegrationTest`·`StreamKeyReissueIntegrationTest`)는 스트리머를 만든 뒤 약관에 동의시킨다. 컨트롤러 테스트(`ChannelControllerTest`·`StreamKeyControllerTest`, `@WebMvcTest`)는 인터셉터가 끼므로 `StudioTermsService`를 가짜로 두고 "동의함"으로 답하게 한다. Red 단계에서 같이 고친다.

### 이 이슈에서 만들지 않는 것

- 동의 철회, 운영자용 동의 기록 조회 화면
- 약관 버전을 올렸을 때 "바뀐 내용" 안내(버전만 올리면 다시 동의받는 구조까지만)
- 클립 제작허용 안내 항목(클립 기능을 하지 않는다)
- E2E(Playwright 설정이 없다)

---

## 테스트 시나리오

### 정상

- [x] [정상] `StudioTermsService.hasAgreed` — should return false when the member has agreed to nothing
- [x] [정상] `StudioTermsService.agree` — should record STUDIO_TERMS and CONTENT_GUIDELINE at their current versions when the member agrees to both
- [x] [정상] `StudioTermsService.hasAgreed` — should return true after the member agrees to both required terms
- [x] [정상] `StudioTermsIntegration` — should return agreed false when a streamer who has not agreed asks for the terms status
- [x] [정상] `StudioTermsIntegration` — should return 204 and then agreed true when a streamer agrees to both required terms
- [x] [정상] `StudioTermsIntegration` — should let the streamer read the studio channel after agreeing to the terms
- [x] [정상] `StudioTermsIntegration` — should let the streamer issue a stream key after agreeing to the terms
- [x] [정상] `StudioTermsModal` — should check every item when 모두 동의하기 is clicked
- [x] [정상] `StudioTermsModal` — should send both terms codes and call onAgreed when 스튜디오 가입 is clicked after checking both items
- [x] [정상] `StudioTermsModal` — should show the terms text with its version when 자세히 보기 is clicked
- [x] [정상] `useStudioEntry` — should go to /studio without the popup when the streamer has agreed
- [x] [정상] `useStudioEntry` — should show the terms popup and stay on the page when the streamer has not agreed
- [x] [정상] `useStudioEntry` — should go to /studio after the streamer agrees in the popup
- [x] [정상] `StudioPage` — should show the studio when the streamer has agreed
- [x] [정상] `Header` — should show the terms popup when a streamer who has not agreed clicks 스튜디오
- [x] [정상] `MyChannelPage` — should show the terms popup when a streamer who has not agreed clicks 방송하기
- [x] [정상] `MyChannelPage` — should show the terms popup when a streamer who has not agreed clicks 채널 관리

### 경계

- [x] [경계] `StudioTermsService.hasAgreed` — should return false when the member agreed to only one of the two required terms
- [x] [경계] `StudioTermsService.hasAgreed` — should return false when the member agreed to an older version of a required terms
- [x] [경계] `StudioTermsService.agree` — should keep one record per terms when the member agrees twice
- [x] [경계] `StudioTermsIntegration` — should return 204 and keep one row per terms when the streamer agrees twice
- [x] [경계] `StudioTermsIntegration` — should keep one row per terms when the streamer sends two agreements at the same time
- [x] [경계] `StudioTermsModal` — should keep 스튜디오 가입 disabled when only one of the two required items is checked
- [x] [경계] `StudioTermsModal` — should uncheck every item when 모두 동의하기 is clicked while all items are checked
- [x] [경계] `StudioTermsModal` — should check 모두 동의하기 when both items are checked one by one
- [x] [경계] `StudioTermsIntegration` — should return 204 to both requests when the streamer sends two agreements at the same time
- [x] [경계] `StudioTermsModal` — should keep 스튜디오 가입 disabled when only CONTENT_GUIDELINE is checked
- [x] [경계] `StudioTermsModal` — should keep 스튜디오 가입 disabled when nothing is checked

### 예외

- [x] [예외] `StudioTermsService.agree` — should throw STUDIO_TERMS_REQUIRED without saving when CONTENT_GUIDELINE is missing
- [x] [예외] `StudioTermsService.agree` — should throw STUDIO_TERMS_REQUIRED without saving when the terms list is empty
- [x] [예외] `StudioTermsIntegration` — should return 400 with code STUDIO_TERMS_REQUIRED and save nothing when the request misses CONTENT_GUIDELINE
- [x] [예외] `StudioTermsIntegration` — should return 400 with code INVALID_INPUT when the request has an unknown terms code
- [x] [예외] `StudioTermsIntegration` — should return 400 with code INVALID_INPUT when the terms field is missing
- [x] [예외] `StudioTermsIntegration` — should return 403 with code STUDIO_TERMS_NOT_AGREED when a streamer who has not agreed reads the studio channel
- [x] [예외] `StudioTermsIntegration` — should return 403 with code STUDIO_TERMS_NOT_AGREED when a streamer who has not agreed changes the title
- [x] [예외] `StudioTermsIntegration` — should return 403 with code STUDIO_TERMS_NOT_AGREED when a streamer who has not agreed issues a stream key
- [x] [예외] `StudioTermsIntegration` — should return 403 with code STUDIO_TERMS_NOT_AGREED when a streamer who has not agreed reads the stream key
- [x] [예외] `StudioTermsIntegration` — should return 403 with code FORBIDDEN when a member who is not a streamer agrees to the terms
- [x] [예외] `StudioTermsIntegration` — should return 403 with code FORBIDDEN when an operator agrees to the terms
- [x] [예외] `StudioTermsModal` — should call onClose without saving when 취소 is clicked
- [x] [예외] `StudioTermsModal` — should call onClose without saving when the close button is clicked
- [x] [예외] `StudioTermsModal` — should show the error message and stay open when saving the agreement fails
- [x] [예외] `useStudioEntry` — should go to /studio without the popup when the terms check is rejected with 403
- [x] [예외] `StudioPage` — should show the terms popup and not request the studio channel when the streamer has not agreed
- [x] [예외] `StudioPage` — should go to the home page when the popup is cancelled on /studio
- [x] [예외] `StudioTermsService.agree` — should save nothing when CONTENT_GUIDELINE is missing
- [x] [예외] `StudioTermsIntegration` — should return 400 with code STUDIO_TERMS_REQUIRED and save nothing when the request misses STUDIO_TERMS
- [x] [예외] `useStudioEntry` — should close the popup and stay on the page when 취소 is clicked
- [x] [예외] `useStudioEntry` — should close the popup and stay on the page when the close button is clicked

### 수동 확인 (로컬 compose + 브라우저 패널 — 시나리오 수에 넣지 않는다)

- `demo/setup.mjs`를 다시 돌려 데모 채널 키 발급 전에 약관 동의가 먼저 일어나는지(AC 10), 데모 송출이 다시 붙는지
- 데모 스트리머로 로그인해 헤더 "스튜디오" → 팝업 → 모두 동의 → 스튜디오 가입 → 스튜디오. 다시 누르면 팝업 없이 스튜디오
- DB `studio_terms_agreement`에 항목마다 한 줄(코드·버전 1·시각)

**확인 결과 (2026-11-26, Green 뒤):** `docker compose up -d --build api-1 api-2 nginx`로 다시 띄웠다(Flyway `Successfully validated 8 migrations`, `Current version of schema "APP": 8`).

| 한 것 | 관찰 |
| --- | --- |
| `node --env-file=.env demo/setup.mjs` | 데모 1~3 `채널 62·63·64 준비됨`, 종료 코드 0. 동의(204) → 채널 조회 → 키 발급 순서로 지나갔다 (AC 10) ✅ |
| DB `studio_terms_agreement` | demo1~3 각각 `STUDIO_TERMS`·`CONTENT_GUIDELINE` 버전 1 한 줄씩, 6줄 ✅ |
| 데모 송출 | 키 재발급으로 끊긴 뒤 시뮬레이터가 새 키로 다시 붙어 채널 62 `live: true` ✅ |
| 약관 미동의 스트리머(확인용으로 새로 만듦)로 내 채널 → 헤더 "스튜디오" | 지금 화면 위에 "약관 동의" 팝업 ✅ |
| 자세히 보기 | 팝업 안에 "버전 1"과 스튜디오 이용약관 본문(안에서 스크롤) ✅ |
| 모두 동의하기 → 스튜디오 가입 | `GET /api/studio/terms` 200 → `POST /api/studio/terms-agreement` 204 → `/studio`로 이동, `GET /api/studio/channel`·`/stream-key` 200 ✅ |
| 내 채널 "방송하기" 다시 누름 | 팝업 없이 `/studio` ✅ |

**Red에서 통과했던 테스트의 변이 확인 (Green):** Red에서 5개가 구현 없이 통과했다. 변이를 하나씩 넣고 해당 테스트 파일을 돌린 뒤 되돌렸다.

| 변이 | 실패한 테스트 |
| --- | --- |
| `SecurityConfig` `/api/studio/**`를 `hasRole("STREAMER")` → `authenticated()` | `…403 with code FORBIDDEN when a member who is not a streamer agrees…`, `…when an operator agrees…` |
| `AgreeStudioTermsRequest.terms`의 `@NotNull` 제거 | `…400 with code INVALID_INPUT when the terms field is missing` |
| 환경변수 `SPRING_JACKSON_DESERIALIZATION_READ_UNKNOWN_ENUM_VALUES_AS_NULL=true`(모르는 코드를 null로 읽음) | `…400 with code INVALID_INPUT when the request has an unknown terms code` |
| `StudioPage`의 `agreed`를 늘 `false`로 | `StudioPage — should show the studio when the streamer has agreed` |

**구현하며 정한 것 (시그니처 밖의 세부)**

- `StudioTermsInterceptor`는 서비스를 `Supplier`로 받고, `StudioTermsWebConfig`가 `ObjectProvider::getObject`를 넘긴다. `@WebMvcTest` 슬라이스는 설정 클래스(`WebMvcConfigurer`)를 같이 띄우는데 다른 컨트롤러 테스트(회원·신청 등)에는 이 서비스가 없다. 요청이 올 때 꺼내면 그 테스트들을 고치지 않아도 된다.
- 동시에 두 번 동의하면 늦은 `MERGE`가 기본 키에 걸린다. `StudioTermsService.agree`가 `DuplicateKeyException`을 무시한다(결과가 같다). `@Transactional`은 걸지 않았다 — 항목마다 따로 저장해도 빠진 항목이 있으면 미동의로 보이고 다시 동의하면 채워진다.
- 팝업의 저장 함수는 `(codes) => agreeStudioTerms(codes)`로 감쌌다. React Query가 두 번째 인자(맥락)를 넘겨 그대로 넘기면 호출 모양이 달라진다.
- 헤더 "스튜디오"·내 채널 "방송하기·채널 관리"는 링크(`<Link>`)에서 버튼으로 바뀌었다(누르면 동의 여부를 먼저 묻는다).
- 커버리지 명령(`coverage`)이 설정에 없어 측정하지 않았다.

---

## AC 대조

| AC | 커버하는 시나리오 |
| --- | --- |
| 1. 동의 안 한 스트리머 → `agreed: false` | `[정상] StudioTermsService.hasAgreed — …agreed to nothing`<br>`[정상] StudioTermsIntegration — should return agreed false…` |
| 2. 필수 두 개 동의 → 204, 이후 `agreed: true` | `[정상] StudioTermsService.agree — should record…`<br>`[정상] StudioTermsService.hasAgreed — should return true after…`<br>`[정상] StudioTermsIntegration — should return 204 and then agreed true…` |
| 3. 필수가 빠지면 400, 저장 없음 | `[예외] StudioTermsService.agree — …CONTENT_GUIDELINE is missing`·`…list is empty`<br>`[예외] StudioTermsIntegration — …400 with code STUDIO_TERMS_REQUIRED and save nothing…`<br>`[예외] …INVALID_INPUT when…unknown terms code`·`…terms field is missing`<br>`[경계] StudioTermsService.hasAgreed — …only one of the two…` |
| 4. 다시 동의 → 204, 기록 늘지 않음 | `[경계] StudioTermsService.agree — …agrees twice`<br>`[경계] StudioTermsIntegration — …agrees twice`·`…two agreements at the same time` |
| 5. 동의 전 스튜디오 API 403, 동의 후 정상 | `[예외] StudioTermsIntegration — …STUDIO_TERMS_NOT_AGREED…` 4개(내 채널·제목·키 발급·키 조회)<br>`[정상] StudioTermsIntegration — …read the studio channel after agreeing`·`…issue a stream key after agreeing`<br>`[경계] StudioTermsService.hasAgreed — …older version…` |
| 6. 회원·운영자 동의 API → 403 | `[예외] StudioTermsIntegration — …403 with code FORBIDDEN when a member…`·`…when an operator…` |
| 7. 동의 안 한 스트리머가 스튜디오 진입(헤더·내 채널·`/studio`) → 팝업, 필수 둘 체크해야 가입 가능 | `[정상] useStudioEntry — should show the terms popup…`<br>`[정상] Header — …clicks 스튜디오`·`[정상] MyChannelPage — …clicks 방송하기`<br>`[예외] StudioPage — …not request the studio channel…`<br>`[경계] StudioTermsModal — …disabled when only one…`<br>`[정상] StudioTermsModal — …send both terms codes and call onAgreed…` |
| 8. 모두 동의하기 체크·해제, 취소·X는 닫기만 | `[정상] StudioTermsModal — …check every item…`<br>`[경계] StudioTermsModal — …uncheck every item…`·`…check 모두 동의하기 when both…`<br>`[예외] StudioTermsModal — …취소…`·`…close button…`<br>`[예외] StudioPage — …go to the home page when the popup is cancelled…` |
| 9. 동의한 스트리머 → 팝업 없이 스튜디오 | `[정상] useStudioEntry — should go to /studio without the popup…`·`…after the streamer agrees in the popup`<br>`[정상] StudioPage — should show the studio when the streamer has agreed` |
| 10. 데모 설정 스크립트가 키 발급 전에 동의 | 수동 확인(`demo/setup.mjs` 재실행). 스크립트에 단위 테스트가 없다 |

**AC에 없는데 추가한 시나리오**

- `StudioTermsModal — should show the terms text with its version when 자세히 보기 is clicked` — 사용자 결정(약관 전문을 직접 써서 보인다)
- `StudioTermsModal — should show the error message and stay open when saving the agreement fails` — 저장 실패 시 팝업이 닫히면 동의한 줄 알고 넘어간다
- `useStudioEntry — should go to /studio without the popup when the terms check is rejected with 403` — 운영자(편의상 버튼이 보임)가 팝업에 갇히지 않게(시그니처 "판단이 갈린 곳")

**커버리지:** AC 10개 / 시나리오 49개(검증 뒤 8개 추가) / 미커버 0개 (AC 10은 수동 확인)

---

## AC 검증

### 이슈 #35 AC 검증 결과 (브랜치 feat/channel/issue-35, HEAD 60d3333) — `harness:ac-verifier` 원문

코드는 고치지 않았습니다. 백엔드 `StudioTerms*` 테스트와 영향받는 기존 테스트 4개, 프론트 전체 테스트를 직접 돌렸고 모두 통과했습니다. `StreamKeyReissueIntegrationTest`는 돌리지 않았습니다. `demo/setup.mjs`도 다시 돌리지 않았습니다. 데모 채널 키를 재발급해 송출이 끊기기 때문입니다.

**요약: AC 10개 중 충족 6, 부분 충족 4, 미충족 0**

부분 충족 넷 가운데 AC 3·7·8은 구현은 맞지만 테스트가 일부 조건만 봅니다. AC 10은 구현은 맞지만 자동 테스트가 없고 수동 확인 기록만 있습니다.

#### AC 1 — 동의 안 한 스트리머가 동의 여부를 물으면 `agreed: false`
**판정: 충족**
- 테스트:
  - `should return agreed false when a streamer who has not agreed asks for the terms status` (`StudioTermsIntegrationTest.java:146-151`)
  - `should return false when the member has agreed to nothing` (`StudioTermsServiceTest.java:58-61`)
- 구현: `StudioTermsController.terms()` (`StudioTermsController.java:22-25`), `StudioTermsService.hasAgreed()` (`StudioTermsService.java:22-26`)
- 근거: 실제 가입, 신청, 승인 흐름으로 만든 스트리머 세션에서 `$.agreed == false`를 확인합니다. `/api/studio/terms`는 인터셉터에서 빠져 있어 동의 전에도 열립니다(`StudioTermsWebConfig.java:23`).

#### AC 2 — 필수 두 개에 동의하면 204, 이후 `agreed: true`
**판정: 충족**
- 테스트:
  - `should return 204 and then agreed true when a streamer agrees to both required terms` (`StudioTermsIntegrationTest.java:154-160`)
  - `should record STUDIO_TERMS and CONTENT_GUIDELINE at their current versions…` (`StudioTermsServiceTest.java:94-101`)
- 구현:
  - `StudioTermsController.agree()`가 `noContent()`를 반환합니다 (`StudioTermsController.java:27-32`).
  - `agree()`는 항목마다 `insertIfAbsent(code, version)`을 부릅니다 (`StudioTermsService.java:34-40`).
  - SQL은 MERGE입니다 (`StudioTermsMapper.xml:12-18`). 테이블은 `V8__create_studio_terms_agreement.sql:2-10`에 있습니다.
- 근거: 204 다음에 실제 DB를 거쳐 `agreed:true`가 나오는 것을 단언합니다. 두 번째 GET의 상태 코드는 단언하지 않지만, `jsonPath`가 본문을 검사하므로 문제는 없습니다.

#### AC 3 — 필수 약관이 하나라도 빠지면 400이고 아무것도 저장되지 않는다
**판정: 부분 충족**
- 테스트:
  - `should return 400 with code STUDIO_TERMS_REQUIRED and save nothing when the request misses CONTENT_GUIDELINE` (`StudioTermsIntegrationTest.java:220-231`). 400, 코드, `rowsOf == 0`을 단언합니다.
  - 모르는 코드와 `terms` 누락은 400 `INVALID_INPUT`을 확인합니다 (`StudioTermsIntegrationTest.java:234-251`).
  - 서비스 단위 테스트는 CONTENT_GUIDELINE 누락과 빈 목록을 봅니다 (`StudioTermsServiceTest.java:115-130`).
- 구현: `!terms.containsAll(EnumSet.allOf(StudioTerms.class))`이면 저장 전에 예외를 던집니다 (`StudioTermsService.java:31-33`). 구현 자체는 대칭이라 맞습니다.
- 빠진 것:
  1. **STUDIO_TERMS가 빠진 요청(CONTENT_GUIDELINE만 보냄)은 어느 테스트에도 없습니다.** 구현을 `terms.contains(CONTENT_GUIDELINE)`만 보도록 잘못 바꿔도 지금 테스트는 모두 통과합니다. 빈 목록에도 CONTENT_GUIDELINE이 없기 때문입니다.
  2. 서비스 단위 테스트 두 개는 이름에 "without saving"이 들어 있지만 예외만 단언하고 저장 여부는 보지 않습니다(아래 "가짜 테스트" 참고).

#### AC 4 — 이미 동의한 스트리머가 다시 동의하면 204이고 기록이 늘지 않는다
**판정: 충족**
- 테스트:
  - `should return 204 and keep one row per terms when the streamer agrees twice` (`StudioTermsIntegrationTest.java:182-192`). 두 번 모두 204이고 DB 행 수가 2입니다.
  - `should keep one record per terms when the member agrees twice` (`StudioTermsServiceTest.java:104-112`)
  - 동시 요청은 `StudioTermsIntegrationTest.java:195-217`에서 봅니다.
- 구현:
  - MERGE `WHEN NOT MATCHED`로 넣습니다 (`StudioTermsMapper.xml:13-17`).
  - 기본 키는 (member_id, terms_code, terms_version)입니다 (`V8…sql:8`).
  - 동시에 들어온 늦은 요청의 `DuplicateKeyException`은 무시합니다 (`StudioTermsService.java:37-39`).
- 참고: 동시 요청 테스트는 두 응답의 상태 코드를 버립니다(`:209-211`). 한쪽이 500이어도 행 수만 2면 통과합니다. 순차 경우만으로 AC 4는 충족하지만, 단언을 조이기를 권합니다.

#### AC 5 — 동의 전 스튜디오 API 4개는 403(`STUDIO_TERMS_NOT_AGREED`), 동의 후에는 지금처럼 동작
**판정: 충족**
- 동의 전 403 테스트 4개:
  - 내 채널 조회 (`StudioTermsIntegrationTest.java:254-261`)
  - 제목 변경 (`:264-275`)
  - 키 발급 (`:278-285`)
  - 키 조회 (`:288-295`)
  - 넷 다 403과 `$.code`를 함께 단언합니다.
- 동의 후 정상 동작:
  - 채널 조회 200 (`:163-169`), 키 발급 200 (`:172-179`)
  - 제목 변경과 키 조회는 기존 `ChannelIntegrationTest`(`setTitle` `:125`, 제목 테스트 `:247` 이하)와 `StreamKeyIntegrationTest`(`get(KEY_PATH)` `:162`)가 맡습니다. 두 테스트의 스트리머 준비 단계에 `studioTermsService.agree(...)`가 추가됐고(diff 확인), 이번에 둘 다 통과했습니다.
- 버전 경계: `should return false when the member agreed to an older version…` (`StudioTermsServiceTest.java:80-86`)
- 구현:
  - `StudioTermsInterceptor.preHandle` (`StudioTermsInterceptor.java:25-32`)
  - `/api/studio/**`에 등록하고 두 경로를 뺍니다 (`StudioTermsWebConfig.java:20-24`).
  - `BusinessException`은 `GlobalExceptionHandler.java:14-17`이 응답으로 바꿉니다.
  - 스튜디오 경로는 위 4개와 약관 2개가 전부입니다(`ChannelController.java:33,39`, `StreamKeyController.java:19,24`).
  - MediaMTX 내부 API는 `/internal/**`라 인터셉터 대상이 아닙니다 (`SecurityConfig.java:54`).

#### AC 6 — 회원·운영자가 동의 API를 부르면 403
**판정: 충족**
- 테스트:
  - 회원: `…403 with code FORBIDDEN when a member who is not a streamer agrees…` (`StudioTermsIntegrationTest.java:298-307`)
  - 운영자: `…when an operator agrees…` (`:310-321`)
- 구현: `SecurityConfig.java:59`의 `/api/studio/**` `hasRole("STREAMER")`
- 근거: 시나리오 문서 213행에 변이 확인 기록이 있습니다(`authenticated()`로 바꾸면 두 테스트가 실패). 이 변이는 제가 재현하지 않았습니다.

#### AC 7 — 동의 안 한 스트리머가 헤더 "스튜디오", 내 채널 "방송하기·채널 관리", `/studio` 직접 진입 중 어디로 들어와도 팝업이 뜨고, 필수 두 개를 체크해야 "스튜디오 가입"이 눌린다
**판정: 부분 충족**
- 테스트:
  - 헤더 (`Header.test.tsx`, `it('should show the terms popup when a streamer who has not agreed clicks 스튜디오')`)
  - 내 채널 **"방송하기"만** (`MyChannelPage.test.tsx`)
  - `/studio` 직접 진입: `should show the terms popup and not request the studio channel…` (`StudioPage.test.tsx`)
  - 하나만 체크했을 때 비활성 (`StudioTermsModal.test.tsx:63-69`)
  - 둘 다 체크한 뒤 가입 (`StudioTermsModal.test.tsx:43-53`)
- 구현:
  - `Header.tsx:33,94,99`, `MyChannelPage.tsx:19,50,54,60`. 두 버튼 모두 `enterStudio`를 씁니다.
  - `StudioPage.tsx:18-25,38-49`. 동의하기 전에는 `myChannel` 쿼리를 켜지 않습니다.
  - `StudioTermsModal.tsx:109`의 `disabled={!allChecked || …}`
- 빠진 것:
  1. AC에 이름이 적힌 **"채널 관리" 버튼 테스트가 없습니다.** 코드(`MyChannelPage.tsx:54`)는 맞습니다.
  2. 비활성 테스트는 STUDIO_TERMS만 체크한 경우 하나뿐입니다. CONTENT_GUIDELINE만 체크한 경우와 아무것도 체크하지 않은 처음 상태는 검증하지 않습니다. 버튼을 `!checked.includes('CONTENT_GUIDELINE')`로 잘못 막아도 통과합니다.

#### AC 8 — "모두 동의하기"는 전부 체크하고 해제한다. "취소"·X는 스튜디오로 가지 않고 닫는다
**판정: 부분 충족**
- 테스트:
  - 모두 체크 (`StudioTermsModal.test.tsx:35-41`)
  - 모두 해제 (`:71-78`)
  - 하나씩 체크하면 "모두 동의하기"도 체크됨 (`:80-87`)
  - 취소와 X를 누르면 `onClose` 1회 호출, 저장 0회 (`:89-105`)
  - `/studio`에서 취소하면 홈으로 이동 (`StudioPage.test.tsx`)
- 구현:
  - `StudioTermsModal.tsx:56`: `setChecked(allChecked ? [] : allCodes)`
  - `:40, :103`: 닫기와 취소가 `onClose`를 부릅니다.
  - `useStudioEntry.tsx:30`: `onClose={() => setOpen(false)}`. 이동하지 않습니다.
- 빠진 것: 모달 테스트는 콜백이 호출되는지만 봅니다. **헤더나 내 채널에서 연 팝업을 취소·X로 닫았을 때 실제로 닫히고 지금 화면에 남는지(`/studio`로 가지 않는지)는 어느 테스트도 보지 않습니다.** `useStudioEntry` 테스트에 취소 경로가 없습니다. 코드는 맞습니다.

#### AC 9 — 동의한 스트리머가 스튜디오 버튼을 누르면 팝업 없이 스튜디오로 간다
**판정: 충족**
- 테스트:
  - `useStudioEntry — should go to /studio without the popup when the streamer has agreed` (`useStudioEntry.test.tsx:166-173`)
  - `…after the streamer agrees in the popup` (`:185-195`)
  - `StudioPage — should show the studio when the streamer has agreed`
- 구현:
  - `useStudioEntry.tsx:18-26`: `agreed`면 `navigate('/studio')`
  - `QueryClient`에 기본 `staleTime`이 없어(`main.tsx:9`) 누를 때마다 서버에 다시 묻습니다.
- 근거: 첫 테스트는 다이얼로그가 없다고 단언하지 않습니다. 다만 라우트가 바뀌어 버튼 화면이 사라지므로 팝업이 남을 수 없습니다.

#### AC 10 — 데모 설정 스크립트가 데모 채널 키를 발급하기 전에 약관에 먼저 동의한다
**판정: 부분 충족 (구현은 확인했고 자동 테스트는 없습니다)**
- 구현: `demo/setup.mjs:88-92`에서 `POST /studio/terms-agreement`를 보내고 204를 기대합니다. 그다음 `:94`에서 채널을 조회하고 `:101`에서 키를 발급합니다. `call`은 `/api`를 붙입니다 (`:27`). 순서와 경로가 맞습니다.
- 테스트: 없습니다. 시나리오 문서 241행이 "수동 확인"으로 정해 뒀고, 201-202행에 실행 기록이 있습니다(종료 코드 0, DB 6줄). 저는 이 기록을 재현하지 않았습니다.
- 엄격한 기준으로는 "테스트 없음"이지만, 이슈 계획 단계에서 수동 확인으로 합의한 항목이라 부분 충족으로 적었습니다.

#### 갭이 있는 AC에 붙일 시나리오 제안

```
- [ ] [예외] `StudioTermsIntegration` — should return 400 with code STUDIO_TERMS_REQUIRED and save nothing when the request misses STUDIO_TERMS
- [ ] [예외] `StudioTermsService.agree` — should save nothing when CONTENT_GUIDELINE is missing   (기존 예외 테스트와 분리해 mapper.findByMember(...).isEmpty() 단언)
- [ ] [경계] `StudioTermsModal` — should keep 스튜디오 가입 disabled when only CONTENT_GUIDELINE is checked
- [ ] [경계] `StudioTermsModal` — should keep 스튜디오 가입 disabled when nothing is checked
- [ ] [정상] `MyChannelPage` — should show the terms popup when a streamer who has not agreed clicks 채널 관리
- [ ] [예외] `useStudioEntry` — should close the popup and stay on the page when 취소 is clicked
- [ ] [예외] `useStudioEntry` — should close the popup and stay on the page when the close button is clicked
```

AC 10에 자동 검증이 필요하다면 이 정도가 가능합니다(선택 사항입니다).
```
- [ ] [정상] `demo/setup.mjs` — should call terms-agreement before stream-key   (fetch를 가짜로 바꿔 호출 순서 단언)
```

#### 약한 테스트 목록

1. `StudioTermsServiceTest.java:115-121`, `:124-130`
   - 이름은 "…without saving…"인데 예외 코드만 단언합니다. 이름이 약속한 "저장 없음"을 검증하지 않습니다.
   - 조이는 법: 저장 여부를 보는 테스트를 따로 두고 `assertThat(mapper.findByMember(MEMBER_ID)).isEmpty()`를 단언합니다. 한 테스트에 한 가지만 검사하는 규칙도 지켜집니다.
2. `StudioTermsIntegrationTest.java:209-211`(동시 동의)
   - `result.get()`의 상태 코드를 버립니다. 한쪽이 500이어도 행 수만 2면 통과합니다.
   - 조이는 법: 두 결과를 모아 `containsOnly(204)`로 단언합니다.
3. `useStudioEntry.test.tsx:166-173`
   - 이름에 "without the popup"이 있지만 다이얼로그가 없다는 단언이 없습니다. 지금은 라우트가 바뀌어 사실상 문제없습니다.
   - 조이는 법: 원하면 `queryByRole('dialog')`가 null인지 단언합니다.

인자 없는 `toThrow()`, `await` 누락, 단언 없는 테스트, 목이 돌려준 값을 그대로 단언하는 테스트는 찾지 못했습니다. 백엔드 예외 단언은 모두 `BusinessException`과 `ErrorCode`로 구체적입니다.

#### AC 밖의 참고 (판정에 반영하지 않음)

- 이슈 본문은 "자세히 보기"에서 "준비 중" 문구만 보인다고 했는데, 구현은 약관 전문을 보여 줍니다(`studioTermsText.ts`). 시나리오 문서 16행의 2026-11-26 사용자 결정을 따른 것이고 AC는 아닙니다. 이슈 본문을 고칠지는 사람이 정할 일입니다.
- 이슈 본문은 운영자가 "팝업에서 가입하면 '스트리머만…'"이 나온다고 했지만, 구현은 팝업 없이 바로 `/studio`로 갑니다(`useStudioEntry.tsx:23-25`). 시나리오 문서 127행에 판단이 적혀 있습니다.

### 구현자 이견

- **AC 3·7·8의 테스트 갭은 메운다.** 검증자 제안 7개를 그대로 시나리오에 넣고, 동시 동의의 상태 코드는 기존 테스트를 고치지 않고 새 시나리오 `[경계] StudioTermsIntegration — should return 204 to both requests when the streamer sends two agreements at the same time`로 따로 본다(한 테스트 한 가지). 2단계(Red)로 돌아가 테스트를 더하고 3단계를 다시 돈다.
- **약한 테스트 1(서비스 "without saving")** — 새 시나리오 `should save nothing when CONTENT_GUIDELINE is missing`이 저장 여부를 따로 본다. 기존 두 테스트 이름은 승인된 문장이라 그대로 둔다.
- **약한 테스트 3(`useStudioEntry` 동의함)** — 검증자 판단대로 라우트가 바뀌면 팝업이 남을 수 없어 더하지 않는다.
- **AC 10은 범위 밖(자동 테스트)으로 인정한다.** 이슈 계획 때 수동 확인으로 정했고, 데모 스크립트는 실제 서버·운영자 계정이 있어야 의미가 있다. fetch를 가짜로 바꾸면 "호출 순서"만 보고 서버가 실제로 막는지는 못 본다(그건 AC 5 통합 테스트가 본다). 실행 기록은 위 "확인 결과"에 있다.
- **AC 밖 참고 두 가지** — 이슈 본문을 사용자 결정에 맞게 고친다(PR 전에 이슈 본문의 "자세히 보기"·운영자 문장을 갱신).

### 갭을 메운 결과

추가한 시나리오 8개는 구현이 이미 맞아 처음부터 통과했다. 검증자가 말한 잘못된 구현을 하나씩 넣어 새 테스트가 잡는지 보고 되돌렸다. 전체: 백엔드 433개·프론트 55개 통과, 시나리오 49 = 테스트 49.

| 변이 | 실패한 테스트 |
| --- | --- |
| `StudioTermsService.agree`가 `CONTENT_GUIDELINE`만 확인 | `…400 with code STUDIO_TERMS_REQUIRED and save nothing when the request misses STUDIO_TERMS` (새 테스트만 실패 — 검증자 지적대로 기존 테스트로는 못 잡았다) |
| 팝업 가입 버튼이 `CONTENT_GUIDELINE`만 확인 | `should keep 스튜디오 가입 disabled when only CONTENT_GUIDELINE is checked` |
| `useStudioEntry` 팝업 닫기가 `/studio`로 이동 | `should close the popup and stay on the page when 취소 is clicked`, `…when the close button is clicked` |
| 내 채널 "채널 관리" 버튼에 `enterStudio`를 빼기 | `MyChannelPage — should show the terms popup when a streamer who has not agreed clicks 채널 관리` |

동시 동의의 상태 코드(`should return 204 to both requests…`)는 변이로 확인하지 않았다. 두 요청이 실제로 부딪히는지가 실행마다 달라, `DuplicateKeyException` 처리를 빼도 매번 실패한다고 보장할 수 없다.

---

## 리팩터

대상: 이번 이슈가 바꾼 소스 23개(`git diff main...HEAD`, 테스트 제외). 시작 기준선 백엔드 433·프론트 55 통과.

**고친 것 없음.** 다섯 기준(중복·이름·단일 책임·불필요한 복잡도·컨벤션)으로 봤고, 고칠 근거가 있는 항목이 없었다.

| 본 것 | 판단 |
| --- | --- |
| "스트리머 또는 운영자" 조건(`Header`·`MyChannelPage`), `.pillButton` CSS(같은 두 곳), 동의 뒤 `setQueryData(STUDIO_TERMS_KEY, …)`(`StudioPage`·`useStudioEntry`) | 각각 두 번. 세 번째가 나올 때 뽑는다 |
| `StudioTermsInterceptor`가 서비스를 `Supplier`로 받음 | `@WebMvcTest` 슬라이스 때문에 필요(위 "구현하며 정한 것") |
| 약관 버전·코드 | 백엔드 `StudioTerms` enum, 프론트 `studioTermsText.ts`에만 있다. 흩어진 리터럴 없음 |
| CSS | 공통 변수만 쓴다. 새 색·간격 없음 |
| 계층·에러 처리 | 기존 채널 코드와 같은 모양(Controller → Service → MyBatis Mapper, `BusinessException(ErrorCode)`, 레코드 DTO) |

---

## 보안 점검

HEAD `b315a99` 기준(이번 이슈가 바꾼 백엔드 12개·프론트 11개 소스).

| 점검 | 결과 |
| --- | --- |
| 타입 | `gradlew compileJava compileTestJava`, `npx tsc -b` 통과 |
| 의존성 | `npm audit` 취약점 0. 이번 이슈는 의존성을 바꾸지 않았다 |
| 비밀값 | 소스·`demo/setup.mjs`에 비밀값 패턴·알려진 키 접두사 없음. `.env`가 스테이지·이 브랜치 커밋에 없음 |
| 로그 | 바뀐 파일에 `console.log`·`log.info/debug/warn` 없음(동의 기록·회원 정보를 남기지 않는다) |

**🔴 즉시 수정: 없음. 🟡 권장 수정: 없음.**

**⚪ 무시 가능 (판단 근거)**

- **동의 API의 위조 요청** — `POST /api/studio/terms-agreement`는 안전하지 않은 메서드라 `OriginValidationFilter`(허용 출처 검사)를 거친다. 다른 사이트가 로그인한 스트리머 대신 동의시킬 수 없다.
- **차단 우회** — 인터셉터 제외 경로는 정확히 `/api/studio/terms`·`/api/studio/terms-agreement` 둘이다. 끝 슬래시(`/terms/`)는 Spring 6 경로 매칭이 컨트롤러에 닿지 않게 하고(404), 세미콜론 경로는 Spring Security 방화벽이 거절한다. 회원·운영자는 인터셉터 전에 `hasRole("STREAMER")`에서 403이다.
- **SQL** — `StudioTermsMapper.xml`은 `#{}` 바인딩만 쓴다(`${}` 문자열 끼워 넣기 없음). 약관 코드는 enum이라 정해진 두 값만 들어온다.
- **입력 크기** — `terms` 목록 길이에 상한을 두지 않았다. 값은 enum 두 가지뿐이고 본문 크기는 서버 기본 제한을 따른다. 처리는 `containsAll` 한 번이다.
- **오류 응답의 내부 정보** — 새 예외는 `BusinessException`(코드·문구)뿐이다. 동시 동의의 `DuplicateKeyException`은 서비스가 삼키고 밖으로 내보내지 않는다. 그 밖의 DB 오류는 기존과 같은 Spring Boot 기본 오류 응답(메시지·스택 미포함 기본값)이다.
- **부분 저장** — `agree`에 트랜잭션을 걸지 않아 두 항목 중 하나만 저장될 수 있다. 그래도 `hasAgreed`가 false라 스튜디오는 열리지 않고, 다시 동의하면 채워진다(Green "구현하며 정한 것").
- **XSS** — 약관 본문은 정적 문자열이고 React가 텍스트로 넣는다.

예외 대장(`securityExceptions`)은 설정에 없다. 반복될 ⚪가 없어 만들지 않았다.
