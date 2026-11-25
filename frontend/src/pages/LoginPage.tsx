import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { login } from '../api/auth.ts'
import { errorMessage } from '../api/client.ts'
import AuthCard from '../components/AuthCard.tsx'
import SocialLoginButtons from '../components/SocialLoginButtons.tsx'
import form from '../styles/form.module.css'

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
    <AuthCard
      title="로그인"
      footer={<Link to="/signup">계정이 없으신가요? 회원가입</Link>}
    >
      <form className={form.form} onSubmit={handleSubmit}>
        <label className={form.field}>
          이메일
          <input
            className={form.input}
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
        </label>
        <label className={form.field}>
          비밀번호
          <input
            className={form.input}
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </label>
        {mutation.isError && (
          <p className={form.error} role="alert">
            {errorMessage(mutation.error)}
          </p>
        )}
        <button className={form.button} type="submit" disabled={mutation.isPending}>
          로그인
        </button>
      </form>
      <SocialLoginButtons />
    </AuthCard>
  )
}

export default LoginPage
