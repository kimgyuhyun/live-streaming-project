import { useState, type FormEvent } from 'react'
import tip from '../styles/tooltip.module.css'
import styles from './ChatPanel.module.css'
import { CoinIcon, MoreVerticalIcon, PanelCloseIcon, SmileIcon } from './icons.tsx'

type Props = {
  onClose: () => void
}

// 채팅·후원은 아직 기능이 없어 모양만 있다. 보내면 안내만 띄운다
function ChatPanel({ onClose }: Props) {
  const [message, setMessage] = useState('')
  const [notice, setNotice] = useState<string | null>(null)

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setNotice('채팅은 준비 중입니다')
  }

  return (
    <aside className={styles.chat} aria-label="채팅">
      <div className={styles.header}>
        <button
          className={`${styles.iconButton} ${tip.tooltip} ${tip.alignStart}`}
          type="button"
          aria-label="채팅 접기"
          data-tooltip="채팅 접기"
          onClick={onClose}
        >
          <PanelCloseIcon className={styles.icon} />
        </button>
        <h2 className={styles.title}>채팅</h2>
        <button className={styles.iconButton} type="button" aria-label="채팅 설정">
          <MoreVerticalIcon className={styles.icon} />
        </button>
      </div>

      <div className={styles.messages}>
        <p className={styles.empty}>아직 채팅이 없습니다</p>
      </div>

      <form className={styles.form} onSubmit={handleSubmit}>
        <div className={styles.inputBox}>
          <input
            className={styles.input}
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            placeholder="채팅을 입력해주세요"
            aria-label="채팅 입력"
          />
          <button className={styles.iconButton} type="button" aria-label="이모티콘">
            <SmileIcon className={styles.icon} />
          </button>
        </div>
        {notice && (
          <p className={styles.notice} role="status">
            {notice}
          </p>
        )}
        <div className={styles.bar}>
          <button className={styles.donate} type="button" onClick={() => setNotice('후원은 준비 중입니다')}>
            <CoinIcon className={styles.icon} />
            후원하기
          </button>
          <button className={styles.send} type="submit">
            채팅
          </button>
        </div>
      </form>
    </aside>
  )
}

export default ChatPanel
