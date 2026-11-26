import type { StudioTermsCode } from '../api/studioTerms.ts'

// 스튜디오 약관 본문(직접 작성, 포트폴리오용 예시). 버전은 백엔드 StudioTerms와 같아야 한다
export interface StudioTermsDoc {
  code: StudioTermsCode
  title: string
  required: true
  version: number
  body: string[]
}

export const STUDIO_TERMS_DOCS: StudioTermsDoc[] = []
