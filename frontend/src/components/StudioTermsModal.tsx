import type { ReactElement } from 'react'

export interface StudioTermsModalProps {
  // 취소·X. 저장하지 않는다
  onClose: () => void
  // 동의 저장 성공 뒤
  onAgreed: () => void
}

// 스튜디오 약관 동의 팝업(#35)
function StudioTermsModal(props: StudioTermsModalProps): ReactElement {
  void props
  throw new Error('not implemented')
}

export default StudioTermsModal
