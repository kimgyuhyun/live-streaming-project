import { useEffect, useRef, useState } from 'react'
import { ChevronDownIcon, DownloadIcon } from '../components/icons.tsx'
import { defaultRange, formatRange, toDateInput } from './analyticsRange.ts'
import styles from './StudioAnalytics.module.css'

// 라이브 분석·동영상 분석이 같이 쓰는 부품.
// 아직 집계 API가 없어 숫자는 모두 0이고, 조회·다운로드는 모양만 있다

// 제목 줄: 왼쪽 제목, 오른쪽 기간 고르기·조회·다운로드
export function AnalyticsHeader({ title }: { title: string }) {
  const [range, setRange] = useState(() => defaultRange(new Date()))
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  // 종료일 상한. 화면을 열 때 한 번 정한다
  const [today] = useState(() => toDateInput(new Date()))

  useEffect(() => {
    if (!open) return
    function closeOnOutside(e: MouseEvent) {
      if (!ref.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', closeOnOutside)
    return () => document.removeEventListener('mousedown', closeOnOutside)
  }, [open])

  return (
    <div className={styles.header}>
      <h1 className={styles.title}>{title}</h1>
      <div className={styles.controls}>
        <div className={styles.rangeWrap} ref={ref}>
          <button
            className={styles.rangeButton}
            type="button"
            aria-label={`조회 기간 ${formatRange(range)}`}
            aria-expanded={open}
            onClick={() => setOpen((o) => !o)}
          >
            {formatRange(range)}
            <ChevronDownIcon className={styles.chevron} />
          </button>
          {open && (
            <div className={styles.rangePopover}>
              <label className={styles.dateField}>
                시작일
                <input
                  className={styles.dateInput}
                  type="date"
                  value={range.from}
                  max={range.to}
                  onChange={(e) => e.target.value && setRange({ ...range, from: e.target.value })}
                />
              </label>
              <label className={styles.dateField}>
                종료일
                <input
                  className={styles.dateInput}
                  type="date"
                  value={range.to}
                  min={range.from}
                  max={today}
                  onChange={(e) => e.target.value && setRange({ ...range, to: e.target.value })}
                />
              </label>
            </div>
          )}
        </div>
        {/* 모양만 있다: 집계 API가 아직 없다 */}
        <button className={styles.primaryButton} type="button">
          조회
        </button>
        <button className={styles.secondaryButton} type="button">
          <DownloadIcon className={styles.buttonIcon} />
          다운로드
        </button>
      </div>
    </div>
  )
}

export type Stat = {
  label: string
  // 이름 옆 작은 글자(예: 후원 "총 0건")
  sub?: string
  value: string
  unit?: string
}

// 숫자 칸 표. 넓으면 한 줄에 다섯 칸
export function StatGrid({ stats }: { stats: Stat[] }) {
  return (
    <dl className={styles.stats}>
      {stats.map(({ label, sub, value, unit }) => (
        <div key={label} className={styles.stat}>
          <dt className={styles.statLabel}>
            {label}
            {sub && <span className={styles.statSub}>{sub}</span>}
          </dt>
          <dd className={styles.statValue}>
            {value}
            {unit && <span className={styles.statUnit}>{unit}</span>}
          </dd>
        </div>
      ))}
    </dl>
  )
}

// 목록 표. 아직 행 데이터가 없어 빈 안내만 보인다. 첫 칸(영상)이 넓다
export function EmptyTable({ label, columns, emptyText }: { label: string; columns: string[]; emptyText: string }) {
  return (
    <div className={styles.tableWrap}>
      <table className={styles.table} aria-label={label}>
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column} scope="col">
                {column}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          <tr>
            <td className={styles.emptyCell} colSpan={columns.length}>
              {emptyText}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  )
}
