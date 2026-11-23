import { useQuery } from '@tanstack/react-query'
import { useParams } from 'react-router'
import { fetchChannel } from '../api/channels.ts'
import { ApiError } from '../api/client.ts'
import { pollInterval, useBroadcastView } from './broadcastView.ts'
import { PlayerArea } from './LivePlayer.tsx'

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

  if (channel.isPending) return null
  // 폴링 한 번 실패로 보던 영상을 지우지 않는다. 받은 채널이 있으면 그것으로 계속 그린다
  if (channel.isError && channel.data === undefined) {
    const notFound = channel.error instanceof ApiError && channel.error.status === 404
    return <p>{notFound ? '채널을 찾을 수 없습니다' : '잠시 후 다시 시도해 주세요'}</p>
  }

  return (
    <section>
      <h1>{channel.data.name}</h1>
      {/* 운영자가 붙인 데모 표시(확정 사양 10절). 방송 중이든 아니든 보인다 */}
      {channel.data.demo && <p>데모 채널</p>}
      {/* 방송 중이면 지금 방송의, 아니면 다음 방송의 제목. 텍스트로 넣어 React가 이스케이프한다 */}
      <h2>{channel.data.title}</h2>
      {view && <PlayerArea view={view} channelId={channelId} />}
      {channel.data.description && <p>{channel.data.description}</p>}
    </section>
  )
}

export default ChannelPage
