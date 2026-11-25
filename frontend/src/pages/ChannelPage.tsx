import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useOutletContext, useParams } from 'react-router'
import { fetchChannel } from '../api/channels.ts'
import { ApiError, errorMessage } from '../api/client.ts'
import ChannelVideos from '../components/ChannelVideos.tsx'
import ChatPanel from '../components/ChatPanel.tsx'
import type { LayoutContext } from '../layouts/RootLayout.tsx'
import { GiftIcon, HeartIcon, MoreHorizontalIcon, PlusCircleIcon, UserIcon } from '../components/icons.tsx'
import { pollInterval, useBroadcastView } from './broadcastView.ts'
import styles from './ChannelPage.module.css'
import { ChatOpenButtons, PlayerArea } from './LivePlayer.tsx'

// 팔로우·구독·구독 선물은 아직 기능이 없어 모양만 있다(팔로워 수도 늘 0)
function ChannelPage() {
  const channelId = Number(useParams().channelId)
  const channel = useQuery({
    queryKey: ['channel', channelId],
    queryFn: () => fetchChannel(channelId),
    // 없는 채널은 다시 불러도 없다
    retry: false,
    // 방송 중일 때만 5초마다 다시 물어 방송이 끝나면 종료 문구로 바꾼다
    refetchInterval: (query) => pollInterval(query.state.data?.live),
  })
  const view = useBroadcastView(channel.data?.live)
  const [chatOpen, setChatOpen] = useState(true)
  // 넓은 화면은 RootLayout이 갖고 있다(사이드바를 숨긴다). 레이아웃 없이 그릴 때(테스트)는 없다
  const layout = useOutletContext<LayoutContext | undefined>()
  const setTheater = layout?.setTheater
  // 방송 화면을 떠나면 다른 화면에 사이드바가 돌아오게 끈다
  useEffect(() => () => setTheater?.(false), [setTheater])

  if (channel.isPending) return null
  // 폴링 한 번 실패로 보던 영상을 지우지 않는다. 받은 채널이 있으면 그것으로 계속 그린다
  if (channel.isError && channel.data === undefined) {
    const notFound = channel.error instanceof ApiError && channel.error.status === 404
    // 그 밖의 오류는 본문의 message(nginx 요청 수 제한 429 등, #31). 본문이 없으면 errorMessage가 일반 문구를 준다
    return <p>{notFound ? '채널을 찾을 수 없습니다' : errorMessage(channel.error)}</p>
  }

  return (
    <div className={styles.watch}>
      <section className={styles.main}>
        {view && (
          <PlayerArea
            view={view}
            channelId={channelId}
            overlay={!chatOpen && <ChatOpenButtons onOpenChat={() => setChatOpen(true)} />}
            theater={layout?.theater}
            onToggleTheater={layout && (() => layout.setTheater(!layout.theater))}
          />
        )}
        <div className={styles.info}>
          {/* 방송 중이면 지금 방송의, 아니면 다음 방송의 제목. 텍스트로 넣어 React가 이스케이프한다 */}
          <h1 className={styles.title}>{channel.data.title}</h1>
          <p className={styles.meta}>
            <span className={styles.channelName}>{channel.data.name}</span>
            {/* 운영자가 붙인 데모 표시(확정 사양 10절). 방송 중이든 아니든 보인다 */}
            {channel.data.demo && <span className={styles.tag}>데모 채널</span>}
          </p>
          <div className={styles.streamer}>
            <UserIcon className={styles.avatar} />
            <div className={styles.streamerText}>
              <p className={styles.streamerName}>{channel.data.name}</p>
              <p className={styles.followers}>팔로워 0명</p>
            </div>
            <div className={styles.actions}>
              <button className={`${styles.button} ${styles.primary}`} type="button">
                <HeartIcon className={styles.buttonIcon} />
                팔로우
              </button>
              <button className={styles.button} type="button">
                <PlusCircleIcon className={styles.buttonIcon} />
                구독
              </button>
              <button className={styles.button} type="button">
                <GiftIcon className={styles.buttonIcon} />
                구독 선물
              </button>
              <button className={`${styles.button} ${styles.round}`} type="button" aria-label="더 보기">
                <MoreHorizontalIcon className={styles.buttonIcon} />
              </button>
            </div>
          </div>
          {channel.data.description && <p className={styles.description}>{channel.data.description}</p>}
        </div>
        <ChannelVideos />
      </section>
      {chatOpen && <ChatPanel onClose={() => setChatOpen(false)} />}
    </div>
  )
}

export default ChannelPage
