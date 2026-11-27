import { UserIcon } from '../components/icons.tsx'
import { SAMPLE_FOLLOWING, sortFollowing } from './sampleAccount.ts'
import styles from './AccountPages.module.css'

// 내 계정 > 팔로잉 채널. 방송 중인 채널 먼저(LIVE 테두리), 그다음 오프라인, 각 묶음은 최근 팔로우 순(확정 사양 7절).
// 팔로잉 목록 API가 아직 없어 예시 데이터를 보인다(sampleAccount.ts). 눌러도 이동하지 않는다
function FollowingPage() {
  const channels = sortFollowing(SAMPLE_FOLLOWING)

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>팔로잉 채널</h1>
      <div className={styles.panel}>
        {channels.length > 0 ? (
          <ul className={styles.followGrid}>
            {channels.map((channel) => (
              <li key={channel.channelId} className={styles.follow}>
                <span className={`${styles.followAvatar} ${channel.live ? styles.followLive : ''}`}>
                  <UserIcon className={styles.followIcon} />
                  {channel.live && <span className={styles.liveTag}>LIVE</span>}
                </span>
                <span className={styles.followName}>{channel.name}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className={styles.empty}>팔로우한 채널이 없습니다.</p>
        )}
      </div>
    </div>
  )
}

export default FollowingPage
