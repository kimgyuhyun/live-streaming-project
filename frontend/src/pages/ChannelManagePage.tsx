import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent, type ReactNode } from 'react'
import { fetchMyChannel, updateChannel, type ChannelResponse } from '../api/channels.ts'
import { errorMessage } from '../api/client.ts'
import { UserIcon } from '../components/icons.tsx'
import styles from './ChannelManagePage.module.css'

// 서버 규칙과 같은 길이(이름 2~20자, 소개 0~200자)
const NAME_MAX = 20
const DESCRIPTION_MAX = 200

// 내 채널 > 채널 관리. 채널 이름·소개만 저장한다.
// 프로필 이미지·소셜 링크·배너·영상 상단 고정은 아직 기능이 없어 모양만 있다.
// 내 채널 정보는 스튜디오 API로 읽어서, 스튜디오 약관에 동의해야 열린다(내 채널의 "채널 관리"가 먼저 묻는다)
function ChannelManagePage() {
  const channel = useQuery({ queryKey: ['myChannel'], queryFn: fetchMyChannel })

  if (channel.isPending) return null
  if (channel.isError) return <p role="alert">{errorMessage(channel.error)}</p>

  return <ChannelManageForm key={channel.data.id} channel={channel.data} />
}

// 입력란은 처음 읽은 값으로 채운다. 바꾼 것이 있을 때만 취소·저장이 눌린다
function ChannelManageForm({ channel }: { channel: ChannelResponse }) {
  const queryClient = useQueryClient()
  const [saved, setSaved] = useState({ name: channel.name, description: channel.description })
  const [name, setName] = useState(saved.name)
  const [description, setDescription] = useState(saved.description)
  const mutation = useMutation({
    mutationFn: () => updateChannel(channel.id, { name, description }),
    onSuccess: (updated) => {
      setSaved({ name: updated.name, description: updated.description })
      return Promise.all([
        queryClient.invalidateQueries({ queryKey: ['myChannel'] }),
        queryClient.invalidateQueries({ queryKey: ['channel', channel.id] }),
      ])
    },
  })
  const dirty = name !== saved.name || description !== saved.description

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    mutation.mutate()
  }

  function cancel() {
    setName(saved.name)
    setDescription(saved.description)
    mutation.reset()
  }

  const buttons = (
    <div className={styles.actions}>
      <button className={styles.cancelButton} type="button" disabled={!dirty} onClick={cancel}>
        취소
      </button>
      <button className={styles.saveButton} type="submit" disabled={!dirty || mutation.isPending}>
        저장
      </button>
    </div>
  )

  return (
    <form className={styles.page} onSubmit={handleSubmit}>
      <div className={styles.header}>
        <h1 className={styles.title}>채널 관리</h1>
        {buttons}
      </div>

      <Section title="기본 정보" text="설정한 프로필은 내 채널 페이지와 방송 화면에 보입니다.">
        <Row label="프로필 이미지">
          <div className={styles.profileImage}>
            <UserIcon className={styles.avatar} />
            {/* 모양만 있다: 이미지 업로드가 아직 없다 */}
            <button className={styles.outlineButton} type="button">
              이미지 수정
            </button>
          </div>
        </Row>
        <Row label="채널 이름" htmlFor="channel-name">
          <div className={styles.field}>
            <input
              id="channel-name"
              className={styles.input}
              value={name}
              maxLength={NAME_MAX}
              required
              onChange={(e) => setName(e.target.value)}
            />
            <Counter length={name.length} max={NAME_MAX} />
          </div>
          <ul className={styles.notes}>
            <li>채널 이름은 2~20자로 정할 수 있습니다.</li>
          </ul>
        </Row>
        <Row label="채널 소개" htmlFor="channel-description">
          <div className={styles.field}>
            <textarea
              id="channel-description"
              className={styles.textarea}
              placeholder="내 채널을 소개하는 글을 적어보세요!"
              value={description}
              maxLength={DESCRIPTION_MAX}
              onChange={(e) => setDescription(e.target.value)}
            />
            <Counter length={description.length} max={DESCRIPTION_MAX} bottom />
          </div>
        </Row>
      </Section>

      <Section title="채널 정보">
        <Row label="소셜 링크">
          {/* 모양만 있다: 소셜 링크 저장이 아직 없다 */}
          <button className={styles.addButton} type="button">
            링크 추가
          </button>
          <ul className={styles.notes}>
            <li>내 채널에 소셜 링크를 소개할 수 있습니다.</li>
            <li>최대 5개까지 등록할 수 있습니다.</li>
          </ul>
        </Row>
      </Section>

      <Section title="채널 홈 관리">
        <Row label="배너 설정">
          {/* 모양만 있다: 배너 업로드가 아직 없다 */}
          <button className={styles.addButton} type="button">
            배너 추가
          </button>
          <ul className={styles.notes}>
            <li>내 채널에 배너를 등록할 수 있습니다. (최대 10개)</li>
            <li>파일 형식: jpeg, png, gif, webp 형식의 파일만 첨부하실 수 있습니다. (1MB 이하)</li>
          </ul>
        </Row>
        <Row label="영상 상단 고정">
          {/* 모양만 있다: 다시보기(VOD)가 아직 없다 */}
          <button className={styles.addButton} type="button">
            영상 선택
          </button>
          <ul className={styles.notes}>
            <li>방송하지 않을 때 내 채널 상단에 고정 노출할 영상을 선택할 수 있습니다.</li>
            <li>영상을 지정하지 않을 경우 가장 최근 공개 영상을 기본으로 노출합니다.</li>
          </ul>
        </Row>
      </Section>

      <div className={styles.footer}>
        {buttons}
        {mutation.isError && (
          <p className={styles.error} role="alert">
            {errorMessage(mutation.error)}
          </p>
        )}
        {mutation.isSuccess && !dirty && (
          <p className={styles.status} role="status">
            저장했습니다
          </p>
        )}
      </div>
    </form>
  )
}

function Section({ title, text, children }: { title: string; text?: string; children: ReactNode }) {
  return (
    <section className={styles.section} aria-label={title}>
      <h2 className={styles.sectionTitle}>{title}</h2>
      {text && <p className={styles.sectionText}>{text}</p>}
      <div className={styles.card}>{children}</div>
    </section>
  )
}

function Row({ label, htmlFor, children }: { label: string; htmlFor?: string; children: ReactNode }) {
  return (
    <div className={styles.row}>
      {htmlFor ? (
        <label className={styles.label} htmlFor={htmlFor}>
          {label}
        </label>
      ) : (
        <span className={styles.label}>{label}</span>
      )}
      <div className={styles.control}>{children}</div>
    </div>
  )
}

function Counter({ length, max, bottom = false }: { length: number; max: number; bottom?: boolean }) {
  return (
    <span className={`${styles.counter} ${bottom ? styles.counterBottom : ''}`} aria-hidden="true">
      <span className={styles.count}>{length}</span>/{max}
    </span>
  )
}

export default ChannelManagePage
