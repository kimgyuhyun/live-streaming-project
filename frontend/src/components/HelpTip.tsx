import tip from '../styles/tooltip.module.css'
import styles from './HelpTip.module.css'
import { HelpIcon } from './icons.tsx'

// 이름 옆 작은 ? 버튼. 마우스를 올리거나 포커스하면 설명이 뜬다
function HelpTip({ label, text }: { label: string; text: string }) {
  return (
    <button
      className={`${styles.button} ${tip.tooltip} ${tip.alignStart} ${tip.wrap}`}
      type="button"
      aria-label={`${label} 설명: ${text}`}
      data-tooltip={text}
    >
      <HelpIcon className={styles.icon} />
    </button>
  )
}

export default HelpTip
