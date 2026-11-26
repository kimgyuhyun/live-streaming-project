import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { fetchMyChannel, updateChannel, type ChannelResponse } from '../api/channels.ts'
import { errorMessage } from '../api/client.ts'
import form from '../styles/form.module.css'
import ChatSettingsSections from './ChatSettingsSections.tsx'
import StreamKeySection from './StreamKeySection.tsx'
import styles from './StudioSettingsPage.module.css'

// 스튜디오 > 방송 관리 > 설정. 로그인·역할·약관 확인은 StudioLayout이 먼저 한다
function StudioSettingsPage() {
  // 연결 상태(live)가 송출을 켜고 끈 뒤 몇 초 안에 바뀌도록 5초마다 다시 읽는다
  const channel = useQuery({ queryKey: ['myChannel'], queryFn: fetchMyChannel, refetchInterval: 5000 })

  if (channel.isPending) return null
  if (channel.isError) return <p role="alert">{errorMessage(channel.error)}</p>

  return (
    <div className={styles.page}>
      <h1 className={styles.srOnly}>방송 설정</h1>
      {/* 진행 중 방송이 있으면 송출 중(#11) */}
      <StreamKeySection channelId={channel.data.id} live={channel.data.live} />
      <ChatSettingsSections />
      {/* 입력란은 처음 읽은 값으로 채운다. 저장하면 입력한 값이 곧 저장된 값이다. 방송 제목은 방송하기 화면에 있다 */}
      <ChannelForm channel={channel.data} />
    </div>
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
    <section className={styles.section} aria-labelledby="channel-settings">
      <h2 id="channel-settings" className={styles.sectionTitle}>
        채널 정보 설정
      </h2>
      <form className={styles.card} onSubmit={handleSubmit}>
        <div className={styles.row}>
          <label className={styles.label} htmlFor="channel-name">
            채널 이름
          </label>
          <input
            id="channel-name"
            className={form.input}
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
          />
        </div>
        <div className={`${styles.row} ${styles.rowTop}`}>
          <label className={styles.label} htmlFor="channel-description">
            소개
          </label>
          <div className={styles.control}>
            <div className={styles.inline}>
              <textarea
                id="channel-description"
                className={`${form.input} ${styles.textarea} ${styles.grow}`}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
              />
              <button className={styles.secondaryButton} type="submit" disabled={mutation.isPending}>
                저장
              </button>
            </div>
            <SaveResult mutation={mutation} />
          </div>
        </div>
      </form>
    </section>
  )
}

function SaveResult({ mutation }: { mutation: { isError: boolean; isSuccess: boolean; error: unknown } }) {
  if (mutation.isError)
    return (
      <p className={styles.error} role="alert">
        {errorMessage(mutation.error)}
      </p>
    )
  if (mutation.isSuccess)
    return (
      <p className={styles.status} role="status">
        저장했습니다
      </p>
    )
  return null
}

export default StudioSettingsPage
