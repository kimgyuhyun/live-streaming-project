// 스튜디오 약관 동의(#35). 둘 다 스트리머만 부를 수 있다(아니면 403)
export type StudioTermsCode = 'STUDIO_TERMS' | 'CONTENT_GUIDELINE'

export interface StudioTermsResponse {
  agreed: boolean
}

export function fetchStudioTerms(): Promise<StudioTermsResponse> {
  throw new Error('not implemented')
}

// 필수 약관이 빠지면 400(STUDIO_TERMS_REQUIRED)
export function agreeStudioTerms(terms: StudioTermsCode[]): Promise<void> {
  void terms
  throw new Error('not implemented')
}
