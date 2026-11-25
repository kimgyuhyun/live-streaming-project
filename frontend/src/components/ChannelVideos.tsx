import { useState } from 'react'
import { Link } from 'react-router'
import { formatCount, formatDate, formatDuration, SAMPLE_VIDEOS } from '../pages/sampleVideos.ts'
import styles from './ChannelVideos.module.css'
import { BroadcastIcon, ChevronDownIcon, ChevronRightIcon, RefreshIcon } from './icons.tsx'

type Sort = 'latest' | 'popular'

// 처음 보이는 수, 더보기를 한 번 누른 뒤 보이는 수. 그다음은 전체보기(내 채널 동영상 탭)로 넘긴다
const FIRST = 4
const MORE = 8
const THUMBS = [styles.thumb0, styles.thumb1, styles.thumb2, styles.thumb3]

// 방송 화면 아래 "이 채널의 동영상". 지금은 예시 데이터(sampleVideos.ts)다. 새로고침은 모양만 있다
function ChannelVideos() {
  const [sort, setSort] = useState<Sort>('latest')
  const [expanded, setExpanded] = useState(false)

  const sorted = [...SAMPLE_VIDEOS].sort((a, b) =>
    sort === 'latest' ? b.publishedAt.localeCompare(a.publishedAt) : b.views - a.views,
  )
  const shown = sorted.slice(0, expanded ? MORE : FIRST)

  return (
    <section className={styles.section}>
      <div className={styles.head}>
        <h2 className={styles.title}>이 채널의 동영상</h2>
        <button className={styles.refresh} type="button" aria-label="새로고침">
          <RefreshIcon className={styles.refreshIcon} />
        </button>
        <div className={styles.sort}>
          <button
            className={`${styles.sortButton} ${sort === 'latest' ? styles.sortActive : ''}`}
            type="button"
            aria-pressed={sort === 'latest'}
            onClick={() => setSort('latest')}
          >
            최신순
          </button>
          <span aria-hidden="true">·</span>
          <button
            className={`${styles.sortButton} ${sort === 'popular' ? styles.sortActive : ''}`}
            type="button"
            aria-pressed={sort === 'popular'}
            onClick={() => setSort('popular')}
          >
            인기순
          </button>
        </div>
      </div>

      <ul className={styles.grid}>
        {shown.map((video) => (
          <li key={video.id} className={styles.card}>
            <div className={`${styles.thumb} ${THUMBS[video.id % THUMBS.length]}`}>
              <BroadcastIcon className={styles.thumbIcon} />
              <div className={styles.badges}>
                <span className={`${styles.badge} ${styles.replay}`}>다시보기</span>
                <span className={styles.badge}>{formatCount(video.liveViewers)} 시청된 라이브</span>
              </div>
              <span className={styles.duration}>{formatDuration(video.durationSeconds)}</span>
            </div>
            <p className={styles.cardTitle} title={video.title}>
              {video.title}
            </p>
            <p className={styles.meta}>
              조회수 {video.views}회 · {formatDate(video.publishedAt)}
            </p>
            <div className={styles.tags}>
              {video.tags.map((tag) => (
                <span key={tag} className={styles.tag}>
                  {tag}
                </span>
              ))}
            </div>
          </li>
        ))}
      </ul>

      <div className={styles.more}>
        {expanded ? (
          <Link className={styles.moreButton} to="/me?tab=동영상">
            전체보기
            <ChevronRightIcon className={styles.moreIcon} />
          </Link>
        ) : (
          <button className={styles.moreButton} type="button" onClick={() => setExpanded(true)}>
            더보기
            <ChevronDownIcon className={styles.moreIcon} />
          </button>
        )}
      </div>
    </section>
  )
}

export default ChannelVideos
