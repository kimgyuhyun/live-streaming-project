import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import type { ChannelResponse } from '../api/channels.ts'
import { ApiError } from '../api/client.ts'
import { fetchFollowStatus, follow, unfollow } from '../api/follows.ts'
import { HeartIcon } from '../components/icons.tsx'
import styles from './ChannelPage.module.css'

interface FollowButtonProps {
  channelId: number
}

// 채널 페이지의 팔로우 버튼. 팔로우 여부는 채널 조회와 따로 묻는다(채널 조회는 nginx가 1초 캐시한다)
// 401이면 비로그인으로 보고 누르면 로그인 화면으로, 내 채널(400 CANNOT_FOLLOW_OWN_CHANNEL)이면 그리지 않는다
function FollowButton({ channelId }: FollowButtonProps) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const status = useQuery({
    queryKey: ['follow', channelId],
    queryFn: () => fetchFollowStatus(channelId),
    // 401·400은 다시 물어도 같다
    retry: false,
  })
  const toggle = useMutation({
    mutationFn: (next: boolean) => (next ? follow(channelId) : unfollow(channelId)),
    // 채널 조회가 1초 캐시라 다시 묻지 않고 받아 둔 값을 고친다
    onSuccess: (_, next) => {
      queryClient.setQueryData(['follow', channelId], { following: next })
      queryClient.setQueryData<ChannelResponse>(['channel', channelId], (channel) =>
        channel && { ...channel, followerCount: channel.followerCount + (next ? 1 : -1) },
      )
    },
  })

  if (status.isPending) return null
  const loggedOut = status.error instanceof ApiError && status.error.status === 401
  // 내 채널이거나 그 밖의 오류면 버튼을 그리지 않는다
  if (status.isError && !loggedOut) return null

  const following = status.data?.following ?? false
  const onClick = () => {
    if (loggedOut) navigate('/login')
    else toggle.mutate(!following)
  }

  return (
    <button
      className={`${styles.button} ${following ? '' : styles.primary}`}
      type="button"
      onClick={onClick}
      disabled={toggle.isPending}
    >
      <HeartIcon className={styles.buttonIcon} />
      {following ? '팔로잉' : '팔로우'}
    </button>
  )
}

export default FollowButton
