import { useState } from 'react'
import { SearchIcon } from '../components/icons.tsx'
import LiveCard from '../components/LiveCard.tsx'
import { SAMPLE_LIVES, filterByTag, sortLives, type LiveOrder } from './sampleHome.ts'
import styles from './LivesPage.module.css'

type Tab = 'live' | 'video'

const ORDERS: { value: LiveOrder; label: string }[] = [
  { value: 'viewers', label: '시청자순' },
  { value: 'viewersAsc', label: '시청자역순' },
  { value: 'latest', label: '최신순' },
]

// 전체 방송(메인 인기 LIVE의 전체보기, 사이드바 "전체 방송"). 탭: 라이브 / 동영상.
// 라이브는 태그 검색과 정렬(시청자순·시청자역순·최신순)을 이 화면 안에서 한다. 아직 예시 데이터다(sampleHome.ts).
// 동영상(다시보기)은 확장으로 만들 기능이라 빈 화면이다
function LivesPage() {
  const [tab, setTab] = useState<Tab>('live')
  const [keyword, setKeyword] = useState('')
  const [order, setOrder] = useState<LiveOrder>('viewers')
  const lives = sortLives(filterByTag(SAMPLE_LIVES, keyword), order)

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>전체 방송</h1>
      <div className={styles.tabs} role="tablist" aria-label="전체 방송">
        {(['live', 'video'] as const).map((value) => (
          <button
            key={value}
            className={`${styles.tab} ${tab === value ? styles.tabActive : ''}`}
            type="button"
            role="tab"
            aria-selected={tab === value}
            onClick={() => setTab(value)}
          >
            {value === 'live' ? '라이브' : '동영상'}
          </button>
        ))}
      </div>

      {tab === 'live' ? (
        <div className={styles.panel} role="tabpanel" aria-label="라이브">
          <div className={styles.toolbar}>
            <label className={styles.search}>
              <input
                className={styles.searchInput}
                aria-label="태그 검색"
                placeholder="태그 검색"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
              />
              <SearchIcon className={styles.searchIcon} />
            </label>
            <div className={styles.orders} role="group" aria-label="정렬">
              {ORDERS.map(({ value, label }) => (
                <button
                  key={value}
                  className={`${styles.order} ${order === value ? styles.orderActive : ''}`}
                  type="button"
                  aria-pressed={order === value}
                  onClick={() => setOrder(value)}
                >
                  {label}
                </button>
              ))}
            </div>
          </div>
          {lives.length > 0 ? (
            <ul className={styles.grid}>
              {lives.map((live) => (
                <li key={live.channelId}>
                  <LiveCard live={live} />
                </li>
              ))}
            </ul>
          ) : (
            <p className={styles.empty}>검색 결과가 없습니다.</p>
          )}
        </div>
      ) : (
        <div className={styles.panel} role="tabpanel" aria-label="동영상">
          <p className={styles.empty}>아직 동영상이 없습니다.</p>
        </div>
      )}
    </div>
  )
}

export default LivesPage
