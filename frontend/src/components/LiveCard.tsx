import { formatViewers, type LiveSummary } from '../pages/sampleHome.ts'
import { BroadcastIcon, UserIcon } from './icons.tsx'
import styles from './LiveCard.module.css'

// 방송 중 카드(썸네일 · LIVE·시청자 수 · 프로필 · 제목 · 채널 이름 · 태그). 메인 인기 LIVE와 전체 방송이 쓴다
function LiveCard({ live }: { live: LiveSummary }) {
  return (
    <article className={styles.card}>
      {/* 썸네일은 방송 화면에서 자동으로 만든다(확정 사양 5절). 아직 없어 빈 화면을 둔다 */}
      <div className={styles.thumbnail}>
        <BroadcastIcon className={styles.thumbnailIcon} />
        <span className={styles.badges}>
          <span className={styles.liveBadge}>LIVE</span>
          <span className={styles.viewerBadge}>{formatViewers(live.viewers)}</span>
        </span>
      </div>
      <div className={styles.info}>
        <UserIcon className={styles.avatar} />
        <div className={styles.text}>
          <h3 className={styles.title}>{live.title}</h3>
          <p className={styles.channelName}>{live.channelName}</p>
          <ul className={styles.tags}>
            {live.tags.map((tag) => (
              <li key={tag} className={styles.tag}>
                {tag}
              </li>
            ))}
          </ul>
        </div>
      </div>
    </article>
  )
}

export default LiveCard
