import { api } from './client.ts'

// 스튜디오 약관 동의(#35). 둘 다 스트리머만 부를 수 있다(아니면 403)
export type StudioTermsCode = 'STUDIO_TERMS' | 'CONTENT_GUIDELINE'

export interface StudioTermsResponse {
  agreed: boolean
}

export function fetchStudioTerms(): Promise<StudioTermsResponse> {
  return api<StudioTermsResponse>('/studio/terms')
}

// 필수 약관이 빠지면 400(STUDIO_TERMS_REQUIRED). 성공하면 204(본문 없음)
export function agreeStudioTerms(terms: StudioTermsCode[]): Promise<void> {
  return api<void>('/studio/terms-agreement', { method: 'POST', body: JSON.stringify({ terms }) })
}
