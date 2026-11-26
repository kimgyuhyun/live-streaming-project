import { useQueryClient } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { fetchStudioTerms } from '../api/studioTerms.ts'
import StudioTermsModal from './StudioTermsModal.tsx'

// StudioPage와 같은 쿼리 키. 팝업에서 동의하면 스튜디오가 다시 묻지 않게 채워 둔다
export const STUDIO_TERMS_KEY = ['studioTerms']

// 헤더 "스튜디오", 내 채널 "방송하기·채널 관리"가 쓴다(#35).
// 동의함 → /studio, 동의 안 함 → 지금 화면에 팝업. 동의 여부를 못 물으면(스트리머가 아니라 403 등) /studio로 보내
// 스튜디오가 원래 안내("스트리머만 쓸 수 있습니다" 등)를 보이게 한다
export function useStudioEntry(): { enterStudio: () => void; termsModal: ReactNode } {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)

  async function checkTerms() {
    try {
      const terms = await queryClient.fetchQuery({ queryKey: STUDIO_TERMS_KEY, queryFn: fetchStudioTerms })
      if (terms.agreed) navigate('/studio')
      else setOpen(true)
    } catch {
      navigate('/studio')
    }
  }

  const termsModal = open ? (
    <StudioTermsModal
      onClose={() => setOpen(false)}
      onAgreed={() => {
        queryClient.setQueryData(STUDIO_TERMS_KEY, { agreed: true })
        setOpen(false)
        navigate('/studio')
      }}
    />
  ) : null

  return { enterStudio: () => void checkTerms(), termsModal }
}
