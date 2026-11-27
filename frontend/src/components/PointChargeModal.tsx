import { useId, useState, type FormEvent } from 'react'
import { CHARGE_MIN, chargeError, parseAmount } from '../pages/pointCharge.ts'
import { CloseIcon, CoinIcon } from './icons.tsx'
import styles from './PointChargeModal.module.css'

const QUICK_ADDS = [
  { label: '+1천', amount: 1_000 },
  { label: '+5천', amount: 5_000 },
  { label: '+1만', amount: 10_000 },
  { label: '+5만', amount: 50_000 },
  { label: '+10만', amount: 100_000 },
]

// 안내보기에 보이는 글. 확정 사양 8절의 포인트 규칙을 옮긴다
const NOTICES = [
  '충전한 포인트로 라이브 방송 중인 스트리머에게 후원할 수 있습니다.',
  '충전한 포인트는 바로 보유 포인트에 들어갑니다.',
  '한 번에 1,000~100,000포인트를 1,000포인트 단위로 충전할 수 있습니다.',
  '보유 포인트는 1,000,000포인트를 넘을 수 없으며, 넘게 되는 충전은 거부됩니다.',
  '후원은 한 번에 100포인트 이상, 100포인트 단위로 보유 포인트 안에서 할 수 있습니다.',
  '후원에 이미 사용한 포인트는 되돌릴 수 없습니다.',
  '같은 충전 요청이 두 번 들어와도(버튼을 두 번 누르거나 응답이 늦어 다시 보낸 경우) 한 번만 충전됩니다.',
  '모든 충전·후원은 포인트 내역에 한 줄씩 남으며, 보유 포인트는 언제나 내역의 합과 같습니다.',
  '포인트 충전 및 사용 내역은 헤더의 포인트 아이콘 > 내 포인트에서 확인할 수 있습니다.',
]

type Props = {
  balance: number
  onClose: () => void
}

// 포인트 충전 팝업. 금액 입력·빠른 더하기·충전 후 보유 포인트 미리 보기·안내보기.
// 충전 API가 아직 없어 충전하기를 누르면 안내만 띄운다
function PointChargeModal({ balance, onClose }: Props) {
  const titleId = useId()
  const [amount, setAmount] = useState(CHARGE_MIN)
  const [noticeOpen, setNoticeOpen] = useState(false)
  const [pending, setPending] = useState(false)
  const error = chargeError(amount, balance)

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (error) return
    setPending(true)
  }

  return (
    <div className={styles.backdrop}>
      <div className={styles.dialog} role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <div className={styles.head}>
          <h2 id={titleId} className={styles.title}>
            포인트 충전하기
          </h2>
          <button className={styles.close} type="button" aria-label="닫기" onClick={onClose}>
            <CloseIcon className={styles.closeIcon} />
          </button>
        </div>

        <form className={styles.body} onSubmit={handleSubmit}>
          <div className={styles.box}>
            <div className={styles.amountRow}>
              <label className={styles.amountLabel} htmlFor="charge-amount">
                충전할 포인트
              </label>
              <div className={styles.amountField}>
                <CoinIcon className={styles.coinIcon} />
                <input
                  id="charge-amount"
                  className={styles.amountInput}
                  inputMode="numeric"
                  placeholder="0"
                  value={amount === 0 ? '' : amount.toLocaleString('ko-KR')}
                  onChange={(e) => {
                    setAmount(parseAmount(e.target.value))
                    setPending(false)
                  }}
                />
                {amount > 0 && (
                  <button
                    className={styles.clear}
                    type="button"
                    aria-label="충전할 포인트 지우기"
                    onClick={() => setAmount(0)}
                  >
                    <CloseIcon className={styles.clearIcon} />
                  </button>
                )}
              </div>
            </div>
            <div className={styles.quickAdds}>
              {QUICK_ADDS.map(({ label, amount: add }) => (
                <button
                  key={label}
                  className={styles.quickAdd}
                  type="button"
                  onClick={() => {
                    setAmount((a) => a + add)
                    setPending(false)
                  }}
                >
                  {label}
                </button>
              ))}
            </div>
            {error && amount > 0 && (
              <p className={styles.error} role="alert">
                {error}
              </p>
            )}
          </div>

          <div className={`${styles.box} ${styles.totalRow}`}>
            <span className={styles.totalLabel}>충전 후 보유 포인트</span>
            <strong className={styles.total}>{(balance + amount).toLocaleString('ko-KR')}P</strong>
          </div>

          <p className={styles.agree}>
            내용을 확인했으며 포인트 충전에 동의합니다.
            <button className={styles.noticeLink} type="button" onClick={() => setNoticeOpen(true)}>
              안내보기
            </button>
          </p>

          {pending && (
            <p className={styles.status} role="status">
              포인트 충전은 준비 중입니다
            </p>
          )}
          <button className={styles.submit} type="submit" disabled={error !== null}>
            포인트 충전하기
          </button>
        </form>

        {noticeOpen && (
          <section className={styles.notice} aria-label="포인트 충전 안내">
            <div className={styles.noticeHead}>
              <h3 className={styles.noticeTitle}>포인트 충전 안내</h3>
              <button
                className={styles.close}
                type="button"
                aria-label="안내 닫기"
                onClick={() => setNoticeOpen(false)}
              >
                <CloseIcon className={styles.closeIcon} />
              </button>
            </div>
            <ul className={styles.noticeList}>
              {NOTICES.map((notice) => (
                <li key={notice}>{notice}</li>
              ))}
            </ul>
          </section>
        )}
      </div>
    </div>
  )
}

export default PointChargeModal
