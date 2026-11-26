import { useQueryClient } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { fetchStudioTerms } from '../api/studioTerms.ts'
import StudioTermsModal from './StudioTermsModal.tsx'

// StudioPage와 같은 쿼리 키. 팝업에서 동의하면 스튜디오가 다시 묻지 않게 채워 둔다
export const STUDIO_TERMS_KEY = ['studioTerms']

// 헤더 "스튜디오", 내 채널 "방송하기"(→ /studio)와 "채널 관리"(→ /me/manage)가 쓴다(#35).
// 두 화면 모두 스튜디오 API를 읽어서 약관 동의가 먼저다.
// 동의함 → 그 화면, 동의 안 함 → 지금 화면에 팝업(동의하면 그 화면으로). 동의 여부를 못 물으면(스트리머가 아니라 403 등)
// 그 화면으로 보내 원래 안내("스트리머만 쓸 수 있습니다" 등)를 보이게 한다
export function useStudioEntry(): {
  enterStudio: () => void
  enterAfterTerms: (to: string) => void
  termsModal: ReactNode
} {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  // 팝업이 열려 있으면 동의 뒤에 갈 곳
  const [target, setTarget] = useState<string | null>(null)

  async function checkTerms(to: string) {
    try {
      const terms = await queryClient.fetchQuery({ queryKey: STUDIO_TERMS_KEY, queryFn: fetchStudioTerms })
      if (terms.agreed) navigate(to)
      else setTarget(to)
    } catch {
      navigate(to)
    }
  }

  const termsModal = target ? (
    <StudioTermsModal
      onClose={() => setTarget(null)}
      onAgreed={() => {
        queryClient.setQueryData(STUDIO_TERMS_KEY, { agreed: true })
        setTarget(null)
        navigate(target)
      }}
    />
  ) : null

  return {
    enterStudio: () => void checkTerms('/studio'),
    enterAfterTerms: (to) => void checkTerms(to),
    termsModal,
  }
}
