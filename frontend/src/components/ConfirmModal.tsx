import { useId, type ReactNode } from 'react'
import { CloseIcon } from './icons.tsx'
import styles from './ConfirmModal.module.css'

export interface ConfirmModalProps {
  title: string
  // 안내 문구. 가운데 정렬로 보인다
  children: ReactNode
  confirmLabel: string
  onConfirm: () => void
  // 취소·X
  onClose: () => void
}

// 한 번 더 확인받는 팝업(취소 / 확인). 모양은 스튜디오 약관 팝업과 같다
function ConfirmModal({ title, children, confirmLabel, onConfirm, onClose }: ConfirmModalProps) {
  const titleId = useId()

  return (
    <div className={styles.backdrop}>
      <div className={styles.dialog} role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <div className={styles.head}>
          <h2 id={titleId} className={styles.title}>
            {title}
          </h2>
          <button className={styles.close} type="button" aria-label="닫기" onClick={onClose}>
            <CloseIcon className={styles.closeIcon} />
          </button>
        </div>
        <div className={styles.body}>{children}</div>
        <div className={styles.actions}>
          <button className={styles.cancel} type="button" onClick={onClose}>
            취소
          </button>
          <button className={styles.confirm} type="button" onClick={onConfirm}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}

export default ConfirmModal
