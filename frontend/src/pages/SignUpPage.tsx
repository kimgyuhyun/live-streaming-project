import { useMutation } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { errorMessage } from '../api/client.ts'
import { signUp } from '../api/members.ts'

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
    <form onSubmit={handleSubmit}>
      <h1>가입</h1>
      <label>
        이메일
        <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
      </label>
      <label>
        비밀번호
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
      </label>
      <label>
        닉네임
        <input value={nickname} onChange={(e) => setNickname(e.target.value)} required />
      </label>
      {mutation.isError && <p role="alert">{errorMessage(mutation.error)}</p>}
      <button type="submit" disabled={mutation.isPending}>
        가입
      </button>
    </form>
  )
}

export default SignUpPage
