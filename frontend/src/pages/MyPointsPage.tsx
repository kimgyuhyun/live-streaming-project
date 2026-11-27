import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { fetchMe } from '../api/members.ts'
import { ChevronDownIcon, CoinIcon } from '../components/icons.tsx'
import PointChargeModal from '../components/PointChargeModal.tsx'
import styles from './MyPointsPage.module.css'

type Tab = 'use' | 'charge'

const TABS: { value: Tab; label: string; columns: string[]; empty: string }[] = [
  {
    value: 'use',
    label: '사용 내역',
    columns: ['사용일시', '사용 포인트', '사용내용', '사용채널', '후원 메시지'],
    empty: '사용 내역이 없습니다.',
  },
  {
    value: 'charge',
    label: '충전 내역',
    columns: ['충전일시', '충전 포인트', '충전 후 보유 포인트'],
    empty: '충전 내역이 없습니다.',
  },
]

// 확정 사양 8절의 포인트 규칙을 그대로 옮긴다
const USE_NOTES = [
  '충전한 포인트로 라이브 방송 중인 스트리머에게 후원할 수 있습니다.',
  '한 번에 100포인트 이상, 100포인트 단위로 보유 포인트 안에서 후원할 수 있습니다.',
  '후원에는 메시지를 100자까지 붙일 수 있습니다.',
  '내 채널에는 후원할 수 없습니다.',
  '후원한 포인트는 같은 양이 스트리머의 적립 포인트로 들어갑니다. 수수료는 없습니다.',
  '포인트 충전·사용 내역은 이 페이지에서 최근순으로 확인할 수 있습니다.',
]

const CHARGE_NOTES = [
  '충전한 포인트는 바로 보유 포인트에 들어갑니다.',
  '한 번에 1,000~100,000포인트를 1,000포인트 단위로 충전할 수 있습니다.',
  '보유 포인트는 1,000,000포인트를 넘을 수 없으며, 넘게 되는 충전은 거부됩니다.',
  '후원에 사용한 포인트는 되돌릴 수 없습니다.',
  '스트리머의 적립 포인트를 꺼내 쓰는(출금) 기능은 없습니다.',
]

// 포인트 API(충전·후원·원장)가 아직 없어 보유 포인트는 0으로 둔다
const BALANCE = 0

// 헤더의 포인트(지갑) 버튼 → 내 포인트. 충전하기는 충전 팝업을 연다(충전 자체는 아직 안 된다). 내역은 모양만 있다
function MyPointsPage() {
  const me = useQuery({ queryKey: ['me'], queryFn: fetchMe, retry: false })
  const [tab, setTab] = useState<Tab>('use')
  const [chargeOpen, setChargeOpen] = useState(false)
  // 조회할 달. 화면을 열 때의 연·월로 시작한다
  const [period, setPeriod] = useState(() => {
    const now = new Date()
    return { year: now.getFullYear(), month: now.getMonth() + 1 }
  })

  if (me.isPending) return null
  if (me.isError) {
    return (
      <p>
        <Link to="/login">로그인</Link>이 필요합니다
      </p>
    )
  }

  const current = TABS.find((t) => t.value === tab) ?? TABS[0]
  const years = [period.year - 1, period.year]

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>내 포인트</h1>
      <div className={styles.panel}>
        <section className={styles.card} aria-label="보유 포인트">
          <div className={styles.balanceRow}>
            <span className={styles.coin} aria-hidden="true">
              <CoinIcon className={styles.coinIcon} />
            </span>
            <p className={styles.balanceLabel}>
              보유 중인 포인트 <strong className={styles.balance}>{BALANCE.toLocaleString('ko-KR')}P</strong>
            </p>
            <button className={styles.primaryButton} type="button" onClick={() => setChargeOpen(true)}>
              충전하기
            </button>
          </div>
        </section>
        {chargeOpen && <PointChargeModal balance={BALANCE} onClose={() => setChargeOpen(false)} />}

        <div className={styles.tabs} role="tablist" aria-label="포인트 내역">
          {TABS.map(({ value, label }) => (
            <button
              key={value}
              className={`${styles.tab} ${tab === value ? styles.tabActive : ''}`}
              type="button"
              role="tab"
              aria-selected={tab === value}
              onClick={() => setTab(value)}
            >
              {label}
            </button>
          ))}
        </div>

        <div className={styles.history} role="tabpanel" aria-label={current.label}>
          <div className={styles.filters}>
            <label className={styles.selectWrap}>
              <span className={styles.srOnly}>연도</span>
              <select
                className={styles.select}
                value={period.year}
                onChange={(e) => setPeriod({ ...period, year: Number(e.target.value) })}
              >
                {years.map((year) => (
                  <option key={year} value={year}>
                    {year}년
                  </option>
                ))}
              </select>
              <ChevronDownIcon className={styles.selectIcon} />
            </label>
            <label className={styles.selectWrap}>
              <span className={styles.srOnly}>월</span>
              <select
                className={styles.select}
                value={period.month}
                onChange={(e) => setPeriod({ ...period, month: Number(e.target.value) })}
              >
                {Array.from({ length: 12 }, (_, i) => i + 1).map((month) => (
                  <option key={month} value={month}>
                    {month}월
                  </option>
                ))}
              </select>
              <ChevronDownIcon className={styles.selectIcon} />
            </label>
          </div>
          <div className={styles.tableWrap}>
            <table className={styles.table} aria-label={current.label}>
              <thead>
                <tr>
                  {current.columns.map((column) => (
                    <th key={column} scope="col">
                      {column}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td className={styles.emptyCell} colSpan={current.columns.length}>
                    {current.empty}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div className={styles.guides}>
          <Guide title="포인트 이용안내" notes={USE_NOTES} />
          <Guide title="포인트 충전안내" notes={CHARGE_NOTES} />
        </div>
      </div>
    </div>
  )
}

function Guide({ title, notes }: { title: string; notes: string[] }) {
  return (
    <section className={styles.guide} aria-label={title}>
      <h2 className={styles.guideTitle}>{title}</h2>
      <ul className={styles.notes}>
        {notes.map((note) => (
          <li key={note}>{note}</li>
        ))}
      </ul>
    </section>
  )
}

export default MyPointsPage
