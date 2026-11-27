import { useInfiniteQuery } from '@tanstack/react-query'
import { Link, Navigate } from 'react-router'
import { ApiError } from '../api/client.ts'
import { fetchFollowing } from '../api/follows.ts'
import { UserIcon } from '../components/icons.tsx'
import styles from './AccountPages.module.css'

// 팔로잉(사이드바 "팔로잉" → /following, 내 계정 > 팔로잉 채널 → /me/following). 서버가 방송 중 먼저,
// 각 묶음은 최근 팔로우 순으로 20개씩 준다(#38). "더 보기"는 받은 개수를 offset으로 다음 묶음을 이어 붙인다.
// 비로그인(401)이면 로그인 화면으로 보낸다
function FollowingPage() {
  const following = useInfiniteQuery({
    queryKey: ['following'],
    queryFn: ({ pageParam }) => fetchFollowing(pageParam),
    initialPageParam: 0,
    getNextPageParam: (last, pages) => (last.hasMore ? pages.reduce((sum, page) => sum + page.items.length, 0) : undefined),
    // 401은 다시 물어도 같다
    retry: false,
  })

  if (following.error instanceof ApiError && following.error.status === 401) return <Navigate to="/login" replace />

  const channels = following.data?.pages.flatMap((page) => page.items) ?? []

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>팔로잉 채널</h1>
      {following.isSuccess && (
        <div className={styles.panel}>
          {channels.length > 0 ? (
            <ul className={styles.followGrid}>
              {channels.map((channel) => (
                <li key={channel.channelId}>
                  <Link className={styles.follow} to={`/channels/${channel.channelId}`}>
                    <span className={`${styles.followAvatar} ${channel.live ? styles.followLive : ''}`}>
                      <UserIcon className={styles.followIcon} />
                      {channel.live && <span className={styles.liveTag}>LIVE</span>}
                    </span>
                    <span className={styles.followName}>{channel.name}</span>
                    {channel.live && <span className={styles.followTitle}>{channel.title}</span>}
                  </Link>
                </li>
              ))}
            </ul>
          ) : (
            <p className={styles.empty}>팔로우한 채널이 없습니다.</p>
          )}
          {following.hasNextPage && (
            <div className={styles.more}>
              <button
                className={styles.moreButton}
                type="button"
                onClick={() => following.fetchNextPage()}
                disabled={following.isFetchingNextPage}
              >
                더 보기
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

export default FollowingPage
