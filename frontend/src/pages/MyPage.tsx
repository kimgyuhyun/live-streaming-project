import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router'
import { errorMessage } from '../api/client.ts'
import { fetchMe, type Role } from '../api/members.ts'
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
    </section>
  )
}

export default MyPage
