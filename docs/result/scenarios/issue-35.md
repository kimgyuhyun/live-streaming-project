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

- [ ] [정상] `StudioTermsService.hasAgreed` — should return false when the member has agreed to nothing
- [ ] [정상] `StudioTermsService.agree` — should record STUDIO_TERMS and CONTENT_GUIDELINE at their current versions when the member agrees to both
- [ ] [정상] `StudioTermsService.hasAgreed` — should return true after the member agrees to both required terms
- [ ] [정상] `StudioTermsIntegration` — should return agreed false when a streamer who has not agreed asks for the terms status
- [ ] [정상] `StudioTermsIntegration` — should return 204 and then agreed true when a streamer agrees to both required terms
- [ ] [정상] `StudioTermsIntegration` — should let the streamer read the studio channel after agreeing to the terms
- [ ] [정상] `StudioTermsIntegration` — should let the streamer issue a stream key after agreeing to the terms
- [ ] [정상] `StudioTermsModal` — should check every item when 모두 동의하기 is clicked
- [ ] [정상] `StudioTermsModal` — should send both terms codes and call onAgreed when 스튜디오 가입 is clicked after checking both items
- [ ] [정상] `StudioTermsModal` — should show the terms text with its version when 자세히 보기 is clicked
- [ ] [정상] `useStudioEntry` — should go to /studio without the popup when the streamer has agreed
- [ ] [정상] `useStudioEntry` — should show the terms popup and stay on the page when the streamer has not agreed
- [ ] [정상] `useStudioEntry` — should go to /studio after the streamer agrees in the popup
- [ ] [정상] `StudioPage` — should show the studio when the streamer has agreed
- [ ] [정상] `Header` — should show the terms popup when a streamer who has not agreed clicks 스튜디오
- [ ] [정상] `MyChannelPage` — should show the terms popup when a streamer who has not agreed clicks 방송하기

### 경계

- [ ] [경계] `StudioTermsService.hasAgreed` — should return false when the member agreed to only one of the two required terms
- [ ] [경계] `StudioTermsService.hasAgreed` — should return false when the member agreed to an older version of a required terms
- [ ] [경계] `StudioTermsService.agree` — should keep one record per terms when the member agrees twice
- [ ] [경계] `StudioTermsIntegration` — should return 204 and keep one row per terms when the streamer agrees twice
- [ ] [경계] `StudioTermsIntegration` — should keep one row per terms when the streamer sends two agreements at the same time
- [ ] [경계] `StudioTermsModal` — should keep 스튜디오 가입 disabled when only one of the two required items is checked
- [ ] [경계] `StudioTermsModal` — should uncheck every item when 모두 동의하기 is clicked while all items are checked
- [ ] [경계] `StudioTermsModal` — should check 모두 동의하기 when both items are checked one by one

### 예외

- [ ] [예외] `StudioTermsService.agree` — should throw STUDIO_TERMS_REQUIRED without saving when CONTENT_GUIDELINE is missing
- [ ] [예외] `StudioTermsService.agree` — should throw STUDIO_TERMS_REQUIRED without saving when the terms list is empty
- [ ] [예외] `StudioTermsIntegration` — should return 400 with code STUDIO_TERMS_REQUIRED and save nothing when the request misses CONTENT_GUIDELINE
- [ ] [예외] `StudioTermsIntegration` — should return 400 with code INVALID_INPUT when the request has an unknown terms code
- [ ] [예외] `StudioTermsIntegration` — should return 400 with code INVALID_INPUT when the terms field is missing
- [ ] [예외] `StudioTermsIntegration` — should return 403 with code STUDIO_TERMS_NOT_AGREED when a streamer who has not agreed reads the studio channel
- [ ] [예외] `StudioTermsIntegration` — should return 403 with code STUDIO_TERMS_NOT_AGREED when a streamer who has not agreed changes the title
- [ ] [예외] `StudioTermsIntegration` — should return 403 with code STUDIO_TERMS_NOT_AGREED when a streamer who has not agreed issues a stream key
- [ ] [예외] `StudioTermsIntegration` — should return 403 with code STUDIO_TERMS_NOT_AGREED when a streamer who has not agreed reads the stream key
- [ ] [예외] `StudioTermsIntegration` — should return 403 with code FORBIDDEN when a member who is not a streamer agrees to the terms
- [ ] [예외] `StudioTermsIntegration` — should return 403 with code FORBIDDEN when an operator agrees to the terms
- [ ] [예외] `StudioTermsModal` — should call onClose without saving when 취소 is clicked
- [ ] [예외] `StudioTermsModal` — should call onClose without saving when the close button is clicked
- [ ] [예외] `StudioTermsModal` — should show the error message and stay open when saving the agreement fails
- [ ] [예외] `useStudioEntry` — should go to /studio without the popup when the terms check is rejected with 403
- [ ] [예외] `StudioPage` — should show the terms popup and not request the studio channel when the streamer has not agreed
- [ ] [예외] `StudioPage` — should go to the home page when the popup is cancelled on /studio

### 수동 확인 (로컬 compose + 브라우저 패널 — 시나리오 수에 넣지 않는다)

- `demo/setup.mjs`를 다시 돌려 데모 채널 키 발급 전에 약관 동의가 먼저 일어나는지(AC 10), 데모 송출이 다시 붙는지
- 데모 스트리머로 로그인해 헤더 "스튜디오" → 팝업 → 모두 동의 → 스튜디오 가입 → 스튜디오. 다시 누르면 팝업 없이 스튜디오
- DB `studio_terms_agreement`에 항목마다 한 줄(코드·버전 1·시각)

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

**커버리지:** AC 10개 / 시나리오 41개 / 미커버 0개 (AC 10은 수동 확인)
