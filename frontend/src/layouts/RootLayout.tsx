import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, Outlet } from 'react-router'
import { logout } from '../api/auth.ts'
import { fetchMe } from '../api/members.ts'

function RootLayout() {
  const queryClient = useQueryClient()
  // 401이면 비로그인으로 본다. 다시 시도해도 결과가 같다
  const me = useQuery({ queryKey: ['me'], queryFn: fetchMe, retry: false })

  const logoutMutation = useMutation({
    mutationFn: logout,
    onSuccess: () => queryClient.resetQueries({ queryKey: ['me'] }),
  })

  return (
    <>
      <header>
        <Link to="/">Live Streaming</Link>{' '}
        {me.data ? (
          <>
            <Link to="/me">{me.data.nickname}</Link>{' '}
            {me.data.role === 'STREAMER' && (
              <>
                <Link to="/studio">스튜디오</Link>{' '}
              </>
            )}
            <button type="button" onClick={() => logoutMutation.mutate()} disabled={logoutMutation.isPending}>
              로그아웃
            </button>
          </>
        ) : (
          <>
            <Link to="/login">로그인</Link> <Link to="/signup">가입</Link>
          </>
        )}
      </header>
      <main>
        <Outlet />
      </main>
    </>
  )
}

export default RootLayout
