import { useQuery } from '@tanstack/react-query'
import { useParams } from 'react-router'
import { fetchChannel } from '../api/channels.ts'
import { ApiError } from '../api/client.ts'

function ChannelPage() {
  const channelId = Number(useParams().channelId)
  const channel = useQuery({
    queryKey: ['channel', channelId],
    queryFn: () => fetchChannel(channelId),
    // 없는 채널은 다시 불러도 없다
    retry: false,
  })

  if (channel.isPending) return null
  if (channel.isError) {
    const notFound = channel.error instanceof ApiError && channel.error.status === 404
    return <p>{notFound ? '채널을 찾을 수 없습니다' : '잠시 후 다시 시도해 주세요'}</p>
  }

  return (
    <section>
      <h1>{channel.data.name}</h1>
      <p>{channel.data.live ? '방송 중' : '오프라인'}</p>
      {channel.data.description && <p>{channel.data.description}</p>}
    </section>
  )
}

export default ChannelPage
