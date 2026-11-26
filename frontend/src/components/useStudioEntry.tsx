import type { ReactNode } from 'react'

// 헤더 "스튜디오", 내 채널 "방송하기·채널 관리"가 쓴다(#35).
// 동의함 → /studio, 동의 안 함 → 지금 화면에 팝업, 동의 여부 조회가 403(스트리머 아님) → /studio
export function useStudioEntry(): { enterStudio: () => void; termsModal: ReactNode } {
  throw new Error('not implemented')
}
