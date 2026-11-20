import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { login } from '../api/auth.ts'
import { errorMessage } from '../api/client.ts'

function LoginPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')

  const mutation = useMutation({
    mutationFn: login,
    onSuccess: async () => {
      // 머리글이 새 로그인 상태로 내 정보를 다시 불러오게 한다
      await queryClient.invalidateQueries({ queryKey: ['me'] })
      navigate('/')
    },
  })

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    mutation.mutate({ email, password })
  }

  return (
    <form onSubmit={handleSubmit}>
      <h1>로그인</h1>
      <label>
        이메일
        <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
      </label>
      <label>
        비밀번호
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
      </label>
      {mutation.isError && <p role="alert">{errorMessage(mutation.error)}</p>}
      <button type="submit" disabled={mutation.isPending}>
        로그인
      </button>
    </form>
  )
}

export default LoginPage
