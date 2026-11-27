import { BroadcastIcon } from '../components/icons.tsx'
import { SAMPLE_WATCHED, formatPublished } from './sampleAccount.ts'
import { formatDuration } from './sampleVideos.ts'
import styles from './AccountPages.module.css'

// 내 계정 > 최근 시청 영상. 시청 기록 API가 아직 없어 예시 데이터를 보인다(sampleAccount.ts). 카드는 눌러도 이동하지 않는다
function WatchHistoryPage() {
  return (
    <div className={styles.page}>
      <h1 className={styles.title}>최근 시청 영상</h1>
      <div className={styles.panel}>
        {SAMPLE_WATCHED.length > 0 ? (
          <ul className={styles.videoGrid}>
            {SAMPLE_WATCHED.map((video) => (
              <li key={video.id}>
                <article className={styles.videoCard}>
                  <div className={styles.thumbnail}>
                    <BroadcastIcon className={styles.thumbnailIcon} />
                    <span className={styles.duration}>{formatDuration(video.durationSeconds)}</span>
                  </div>
                  <h2 className={styles.videoTitle}>{video.title}</h2>
                  <p className={styles.channelName}>{video.channelName}</p>
                  <p className={styles.meta}>
                    조회수 {video.views.toLocaleString('ko-KR')}회 · {formatPublished(video.publishedAt)}
                  </p>
                </article>
              </li>
            ))}
          </ul>
        ) : (
          <p className={styles.empty}>최근 시청한 영상이 없습니다.</p>
        )}
      </div>
    </div>
  )
}

export default WatchHistoryPage
