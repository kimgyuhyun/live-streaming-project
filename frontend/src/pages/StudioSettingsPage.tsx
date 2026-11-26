import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { fetchMyChannel, updateChannel, updateTitle, type ChannelResponse } from '../api/channels.ts'
import { errorMessage } from '../api/client.ts'
import StreamKeySection from './StreamKeySection.tsx'

// 스튜디오 > 방송 관리 > 설정. 로그인·역할·약관 확인은 StudioLayout이 먼저 한다
function StudioSettingsPage() {
  // 연결 상태(live)가 송출을 켜고 끈 뒤 몇 초 안에 바뀌도록 5초마다 다시 읽는다
  const channel = useQuery({ queryKey: ['myChannel'], queryFn: fetchMyChannel, refetchInterval: 5000 })

  if (channel.isPending) return null
  if (channel.isError) return <p role="alert">{errorMessage(channel.error)}</p>

  return (
    <section>
      <h1>방송 설정</h1>
      <p>
        <Link to={`/channels/${channel.data.id}`}>내 채널 페이지</Link>
      </p>
      {/* 진행 중 방송이 있으면 송출 중(#11) */}
      <p>연결 상태: {channel.data.live ? '송출 중' : '송출 없음'}</p>
      {/* 입력란은 처음 읽은 값으로 채운다. 저장하면 입력한 값이 곧 저장된 값이다 */}
      <TitleForm title={channel.data.title} />
      <ChannelForm channel={channel.data} />
      <StreamKeySection channelId={channel.data.id} />
    </section>
  )
}

// 방송 제목 1~50자. 방송 중에 바꾸면 채널 페이지의 다음 조회부터 새 제목이다
function TitleForm({ title: savedTitle }: { title: string }) {
  const queryClient = useQueryClient()
  const [title, setTitle] = useState(savedTitle)
  const mutation = useMutation({
    mutationFn: () => updateTitle(title),
    onSuccess: (channel) =>
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
      <h2>방송 제목</h2>
      <label>
        제목
        <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={50} required />
      </label>
      {mutation.isError && <p role="alert">{errorMessage(mutation.error)}</p>}
      {mutation.isSuccess && <p role="status">저장했습니다</p>}
      <button type="submit" disabled={mutation.isPending}>
        저장
      </button>
    </form>
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

export default StudioSettingsPage
