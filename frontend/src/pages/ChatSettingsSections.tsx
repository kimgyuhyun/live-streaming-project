import { useState, type ReactNode } from 'react'
import HelpTip from '../components/HelpTip.tsx'
import form from '../styles/form.module.css'
import styles from './StudioSettingsPage.module.css'

// 채팅 설정 칸들(참여자·모드·하이라이트·규칙·금칙어).
// 아직 저장할 API가 없어 모양만 있다. 고른 값은 이 화면에만 남고 새로고침하면 처음 값으로 돌아간다
function ChatSettingsSections() {
  const [audience, setAudience] = useState<'all' | 'followers' | 'managers'>('all')
  const [verifiedOnly, setVerifiedOnly] = useState(false)
  const [emoticonMode, setEmoticonMode] = useState(false)
  const [slowMode, setSlowMode] = useState(false)
  const [firstChatHighlight, setFirstChatHighlight] = useState(true)
  const [rules, setRules] = useState('')

  return (
    <>
      <Section title="채팅 참여자 설정">
        <div className={styles.row}>
          <span className={styles.label} id="chat-audience">
            채팅 참여 범위
          </span>
          <div className={styles.choices} role="radiogroup" aria-labelledby="chat-audience">
            {AUDIENCES.map(({ value, label }) => (
              <label key={value} className={styles.choice}>
                <input
                  type="radio"
                  name="chat-audience"
                  checked={audience === value}
                  onChange={() => setAudience(value)}
                />
                {label}
              </label>
            ))}
          </div>
        </div>
        <div className={styles.row}>
          <span className={styles.label}>본인인증 여부</span>
          <label className={styles.choice}>
            <input type="checkbox" checked={verifiedOnly} onChange={(e) => setVerifiedOnly(e.target.checked)} />
            본인인증한 시청자만 채팅 허용
          </label>
        </div>
      </Section>

      <Section title="채팅 모드 설정">
        <SwitchRow
          label="이모티콘 모드"
          help="이모티콘만 채팅할 수 있습니다"
          checked={emoticonMode}
          onChange={setEmoticonMode}
        />
        <SwitchRow
          label="저속 모드"
          help="시청자가 정해진 간격마다 한 번씩만 채팅할 수 있습니다"
          checked={slowMode}
          onChange={setSlowMode}
        />
      </Section>

      <Section title="채팅 하이라이트 설정">
        <SwitchRow
          label="첫 채팅 하이라이트"
          help="시청자가 이 채널에서 처음 쓴 채팅을 눈에 띄게 보여 줍니다"
          checked={firstChatHighlight}
          onChange={setFirstChatHighlight}
        />
      </Section>

      <Section title="채팅 규칙 설정">
        <div className={`${styles.row} ${styles.rowTop}`}>
          <label className={styles.label} htmlFor="chat-rules">
            채팅 규칙
          </label>
          <div className={styles.control}>
            <div className={styles.inline}>
              <textarea
                id="chat-rules"
                className={`${form.input} ${styles.textarea} ${styles.grow}`}
                placeholder="채팅 규칙은 한 줄씩 입력해주세요."
                value={rules}
                onChange={(e) => setRules(e.target.value)}
              />
              {/* 모양만 있다: 저장 API가 아직 없다 */}
              <button className={styles.secondaryButton} type="button" disabled={rules.trim() === ''}>
                저장
              </button>
            </div>
            <ul className={styles.notes}>
              <li>채팅 규칙은 채팅 참여 전 최초 1회 안내되며 동의를 받습니다.</li>
              <li>규칙을 변경한 경우, 이전 규칙에 동의한 시청자들에게도 다시 동의를 받습니다.</li>
            </ul>
          </div>
        </div>
        <div className={styles.row}>
          <span className={styles.label}>채팅 금칙어 설정</span>
          <div className={styles.inline}>
            <span>
              등록된 금칙어 <span className={styles.accent}>0개</span>
            </span>
            {/* 모양만 있다: 금칙어 관리 화면이 아직 없다 */}
            <button className={styles.secondaryButton} type="button">
              금칙어 관리
            </button>
          </div>
        </div>
      </Section>
    </>
  )
}

const AUDIENCES = [
  { value: 'all', label: '모든 시청자' },
  { value: 'followers', label: '팔로워 전용' },
  { value: 'managers', label: '운영자 전용' },
] as const

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className={styles.section} aria-label={title}>
      <h2 className={styles.sectionTitle}>{title}</h2>
      <div className={styles.card}>{children}</div>
    </section>
  )
}

function SwitchRow({
  label,
  help,
  checked,
  onChange,
}: {
  label: string
  help: string
  checked: boolean
  onChange: (checked: boolean) => void
}) {
  return (
    <div className={styles.row}>
      <span className={styles.label}>
        {label}
        <HelpTip label={label} text={help} />
      </span>
      <div>
        <input
          className={styles.switch}
          type="checkbox"
          role="switch"
          aria-label={label}
          checked={checked}
          onChange={(e) => onChange(e.target.checked)}
        />
      </div>
    </div>
  )
}

export default ChatSettingsSections
