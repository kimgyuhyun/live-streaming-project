import { api } from './client.ts'
import type { MemberResponse } from './members.ts'

export interface LoginRequest {
  email: string
  password: string
}

export function login(req: LoginRequest): Promise<MemberResponse> {
  return api<MemberResponse>('/auth/login', { method: 'POST', body: JSON.stringify(req) })
}

export async function logout(): Promise<void> {
  await api<null>('/auth/logout', { method: 'POST' })
}
