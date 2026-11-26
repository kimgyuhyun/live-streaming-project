import { useMutation } from '@tanstack/react-query'
import { useId, useState, type ReactElement } from 'react'
import { errorMessage } from '../api/client.ts'
import { agreeStudioTerms, type StudioTermsCode } from '../api/studioTerms.ts'
import { CloseIcon } from './icons.tsx'
import styles from './StudioTermsModal.module.css'
import { STUDIO_TERMS_DOCS } from './studioTermsText.ts'

export interface StudioTermsModalProps {
  // 취소·X. 저장하지 않는다
  onClose: () => void
  // 동의 저장 성공 뒤
  onAgreed: () => void
}

// 스튜디오 약관 동의 팝업(#35). 모든 항목이 필수라 전부 체크해야 가입 버튼이 눌린다
function StudioTermsModal({ onClose, onAgreed }: StudioTermsModalProps): ReactElement {
  const titleId = useId()
  const [checked, setChecked] = useState<StudioTermsCode[]>([])
  const [opened, setOpened] = useState<StudioTermsCode | null>(null)
  const mutation = useMutation({
    mutationFn: (codes: StudioTermsCode[]) => agreeStudioTerms(codes),
    onSuccess: onAgreed,
  })

  const allCodes = STUDIO_TERMS_DOCS.map((doc) => doc.code)
  const allChecked = allCodes.every((code) => checked.includes(code))

  function toggle(code: StudioTermsCode) {
    setChecked((current) => (current.includes(code) ? current.filter((c) => c !== code) : [...current, code]))
  }

  return (
    <div className={styles.backdrop}>
      <div className={styles.dialog} role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <div className={styles.head}>
          <h2 id={titleId} className={styles.title}>
            약관 동의
          </h2>
          <button className={styles.close} type="button" aria-label="닫기" onClick={onClose}>
            <CloseIcon className={styles.closeIcon} />
          </button>
        </div>

        <div className={styles.body}>
          <p className={styles.intro}>
            스튜디오를 이용하려면 약관 동의가 필요합니다. 스튜디오 이용약관과 콘텐츠 가이드라인을 읽고 동의한 뒤 스튜디오
            가입을 눌러 주세요.
          </p>

          <label className={`${styles.checkRow} ${styles.all}`}>
            <input
              className={styles.checkbox}
              type="checkbox"
              checked={allChecked}
              onChange={() => setChecked(allChecked ? [] : allCodes)}
            />
            모두 동의하기
          </label>

          <ul className={styles.items}>
            {STUDIO_TERMS_DOCS.map((doc) => (
              <li key={doc.code} className={styles.item}>
                <div className={styles.itemHead}>
                  <label className={styles.checkRow}>
                    <input
                      className={styles.checkbox}
                      type="checkbox"
                      checked={checked.includes(doc.code)}
                      onChange={() => toggle(doc.code)}
                    />
                    [필수] {doc.title}에 동의합니다.
                  </label>
                  <button
                    className={styles.more}
                    type="button"
                    aria-expanded={opened === doc.code}
                    onClick={() => setOpened(opened === doc.code ? null : doc.code)}
                  >
                    자세히 보기
                  </button>
                </div>
                {opened === doc.code && (
                  <section className={styles.doc} aria-label={doc.title}>
                    <p className={styles.version}>버전 {doc.version}</p>
                    {doc.body.map((line) => (
                      <p key={line}>{line}</p>
                    ))}
                  </section>
                )}
              </li>
            ))}
          </ul>

          {mutation.isError && (
            <p className={styles.error} role="alert">
              {errorMessage(mutation.error)}
            </p>
          )}
        </div>

        <div className={styles.actions}>
          <button className={styles.cancel} type="button" onClick={onClose}>
            취소
          </button>
          <button
            className={styles.join}
            type="button"
            disabled={!allChecked || mutation.isPending}
            onClick={() => mutation.mutate(allCodes)}
          >
            스튜디오 가입
          </button>
        </div>
      </div>
    </div>
  )
}

export default StudioTermsModal
