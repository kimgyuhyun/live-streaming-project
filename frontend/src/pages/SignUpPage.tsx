import { useMutation } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { errorMessage } from '../api/client.ts'
import { signUp } from '../api/members.ts'
import AuthCard from '../components/AuthCard.tsx'
import form from '../styles/form.module.css'

function SignUpPage() {
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [nickname, setNickname] = useState('')

  const mutation = useMutation({
    mutationFn: signUp,
    onSuccess: () => navigate('/login'),
  })

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    mutation.mutate({ email, password, nickname })
  }

  return (
    <AuthCard
      title="회원가입"
      footer={<Link to="/login">이미 계정이 있으신가요? 로그인</Link>}
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
        <label className={form.field}>
          닉네임
          <input className={form.input} value={nickname} onChange={(e) => setNickname(e.target.value)} required />
        </label>
        {mutation.isError && (
          <p className={form.error} role="alert">
            {errorMessage(mutation.error)}
          </p>
        )}
        <button className={form.button} type="submit" disabled={mutation.isPending}>
          회원가입
        </button>
      </form>
    </AuthCard>
  )
}

export default SignUpPage
