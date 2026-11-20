import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { fetchMyChannel, updateChannel, type ChannelResponse } from '../api/channels.ts'
import { errorMessage } from '../api/client.ts'
import { fetchMe } from '../api/members.ts'
import StreamKeySection from './StreamKeySection.tsx'

function StudioPage() {
  // RootLayout과 같은 쿼리를 쓴다. 401이면 비로그인으로 본다
  const me = useQuery({ queryKey: ['me'], queryFn: fetchMe, retry: false })
  const isStreamer = me.data?.role === 'STREAMER'
  const channel = useQuery({ queryKey: ['myChannel'], queryFn: fetchMyChannel, enabled: isStreamer })

  if (me.isPending) return null
  if (me.isError) {
    return (
      <p>
        <Link to="/login">로그인</Link>이 필요합니다
      </p>
    )
  }
  if (!isStreamer) return <p>스트리머만 쓸 수 있습니다</p>
  if (channel.isPending) return null
  if (channel.isError) return <p role="alert">{errorMessage(channel.error)}</p>

  return (
    <section>
      <h1>스튜디오</h1>
      <p>
        <Link to={`/channels/${channel.data.id}`}>내 채널 페이지</Link>
      </p>
      {/* 입력란은 처음 읽은 값으로 채운다. 저장하면 입력한 값이 곧 저장된 값이다 */}
      <ChannelForm channel={channel.data} />
      <StreamKeySection channelId={channel.data.id} />
    </section>
  )
}

function ChannelForm({ channel }: { channel: ChannelResponse }) {
  const queryClient = useQueryClient()
  const [name, setName] = useState(channel.name)
  const [description, setDescription] = useState(channel.description)
  const mutation = useMutation({
    mutationFn: () => updateChannel(channel.id, { name, description }),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['myChannel'] }),
        queryClient.invalidateQueries({ queryKey: ['channel', channel.id] }),
      ]),
  })

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    mutation.mutate()
  }

  return (
    <form onSubmit={handleSubmit}>
      <h2>채널 정보</h2>
      <label>
        채널 이름
        <input value={name} onChange={(e) => setName(e.target.value)} required />
      </label>
      <label>
        소개
        <textarea value={description} onChange={(e) => setDescription(e.target.value)} />
      </label>
      {mutation.isError && <p role="alert">{errorMessage(mutation.error)}</p>}
      {mutation.isSuccess && <p role="status">저장했습니다</p>}
      <button type="submit" disabled={mutation.isPending}>
        저장
      </button>
    </form>
  )
}

export default StudioPage
