import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { errorMessage } from '../api/client.ts'
import { changeNickname, changePassword, fetchMe, type Role } from '../api/members.ts'
import { applyForStreamer, fetchMyApplication, type ApplicationStatus } from '../api/streamerApplications.ts'

const ROLE_LABEL: Record<Role, string> = { MEMBER: '회원', STREAMER: '스트리머', OPERATOR: '운영자' }
const STATUS_LABEL: Record<ApplicationStatus, string> = {
  NONE: '없음',
  PENDING: '대기',
  APPROVED: '승인',
  REJECTED: '거절',
}

function MyPage() {
  const queryClient = useQueryClient()
  // RootLayout과 같은 쿼리를 쓴다. 401이면 비로그인으로 본다
  const me = useQuery({ queryKey: ['me'], queryFn: fetchMe, retry: false })
  const application = useQuery({
    queryKey: ['myApplication'],
    queryFn: fetchMyApplication,
    enabled: me.isSuccess,
  })

  const mutation = useMutation({
    mutationFn: applyForStreamer,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['myApplication'] }),
  })

  if (me.isPending) return null
  if (me.isError) {
    return (
      <p>
        <Link to="/login">로그인</Link>이 필요합니다
      </p>
    )
  }

  const status = application.data?.status
  const canApply = me.data.role === 'MEMBER' && (status === 'NONE' || status === 'REJECTED')

  return (
    <section>
      <h1>내 정보</h1>
      <dl>
        <dt>이메일</dt>
        <dd>{me.data.email}</dd>
        <dt>닉네임</dt>
        <dd>{me.data.nickname}</dd>
        <dt>역할</dt>
        <dd>{ROLE_LABEL[me.data.role]}</dd>
        <dt>스트리머 신청</dt>
        <dd>{status ? STATUS_LABEL[status] : ''}</dd>
      </dl>
      {canApply && (
        <button type="button" onClick={() => mutation.mutate()} disabled={mutation.isPending}>
          스트리머 신청
        </button>
      )}
      {mutation.isError && <p role="alert">{errorMessage(mutation.error)}</p>}
      <NicknameForm />
      <PasswordForm />
    </section>
  )
}

// 성공하면 ['me']를 다시 불러 이 화면과 머리글의 닉네임이 함께 바뀐다
function NicknameForm() {
  const queryClient = useQueryClient()
  const [nickname, setNickname] = useState('')
  const mutation = useMutation({
    mutationFn: changeNickname,
    onSuccess: () => {
      setNickname('')
      return queryClient.invalidateQueries({ queryKey: ['me'] })
    },
  })

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    mutation.mutate(nickname)
  }

  return (
    <form onSubmit={handleSubmit}>
      <h2>닉네임 변경</h2>
      <label>
        새 닉네임
        <input value={nickname} onChange={(e) => setNickname(e.target.value)} required />
      </label>
      {mutation.isError && <p role="alert">{errorMessage(mutation.error)}</p>}
      <button type="submit" disabled={mutation.isPending}>
        닉네임 변경
      </button>
    </form>
  )
}

function PasswordForm() {
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const mutation = useMutation({
    mutationFn: changePassword,
    onSuccess: () => {
      setCurrentPassword('')
      setNewPassword('')
    },
  })

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    mutation.mutate({ currentPassword, newPassword })
  }

  return (
    <form onSubmit={handleSubmit}>
      <h2>비밀번호 변경</h2>
      <label>
        현재 비밀번호
        <input
          type="password"
          value={currentPassword}
          onChange={(e) => setCurrentPassword(e.target.value)}
          required
        />
      </label>
      <label>
        새 비밀번호
        <input type="password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} required />
      </label>
      {mutation.isError && <p role="alert">{errorMessage(mutation.error)}</p>}
      {mutation.isSuccess && <p role="status">비밀번호를 바꿨습니다. 다른 기기에서는 로그아웃됩니다</p>}
      <button type="submit" disabled={mutation.isPending}>
        비밀번호 변경
      </button>
    </form>
  )
}

export default MyPage
