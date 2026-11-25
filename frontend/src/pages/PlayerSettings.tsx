import { useState, type ReactElement } from 'react'
import { CheckIcon, ChevronLeftIcon, ChevronRightIcon } from '../components/icons.tsx'
import styles from './PlayerSettings.module.css'

type Page = 'main' | 'quality' | 'report'

type Props = {
  // 지금 영상의 세로 해상도로 만든 이름(예: "720p"). 아직 모르면 null
  quality: string | null
  radio: boolean
  onRadioChange: (on: boolean) => void
}

// 플레이어 설정 메뉴. 화질은 원본 하나뿐이다(화질 자동 전환을 하지 않는다, infra-validation).
// 재생 문제 제보는 아직 기능이 없어 안내만 띄운다
export function PlayerSettings({ quality, radio, onRadioChange }: Props): ReactElement {
  const [page, setPage] = useState<Page>('main')
  const qualityLabel = quality ?? '원본'

  if (page === 'quality') {
    return (
      <div className={styles.menu} role="menu" aria-label="해상도">
        <button className={`${styles.row} ${styles.back}`} type="button" onClick={() => setPage('main')}>
          <ChevronLeftIcon className={styles.icon} />
          해상도
        </button>
        <button className={styles.row} type="button" role="menuitemradio" aria-checked="true">
          {qualityLabel}
          <CheckIcon className={styles.check} />
        </button>
        <p className={styles.note}>원본 화질 하나로 송출합니다</p>
      </div>
    )
  }

  if (page === 'report') {
    return (
      <div className={styles.menu} role="menu" aria-label="재생 문제 제보">
        <button className={`${styles.row} ${styles.back}`} type="button" onClick={() => setPage('main')}>
          <ChevronLeftIcon className={styles.icon} />
          재생 문제 제보
        </button>
        <p className={styles.note}>재생 문제 제보는 준비 중입니다</p>
      </div>
    )
  }

  return (
    <div className={styles.menu} role="menu" aria-label="설정">
      <button className={styles.row} type="button" role="menuitem" onClick={() => setPage('quality')}>
        해상도
        <span className={styles.value}>
          {qualityLabel}
          <ChevronRightIcon className={styles.icon} />
        </span>
      </button>
      <button
        className={styles.row}
        type="button"
        role="menuitemcheckbox"
        aria-checked={radio}
        onClick={() => onRadioChange(!radio)}
      >
        라디오 모드
        <span className={`${styles.switch} ${radio ? styles.switchOn : ''}`} aria-hidden="true" />
      </button>
      <button className={styles.row} type="button" role="menuitem" onClick={() => setPage('report')}>
        재생 문제 제보
        <ChevronRightIcon className={styles.icon} />
      </button>
    </div>
  )
}
