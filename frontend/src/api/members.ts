import { api } from './client.ts'

export type Role = 'MEMBER' | 'STREAMER' | 'OPERATOR'

export interface SignUpRequest {
  email: string
  password: string
  nickname: string
}

export interface MemberResponse {
  id: number
  email: string
  nickname: string
  role: Role
}

export function signUp(req: SignUpRequest): Promise<MemberResponse> {
  return api<MemberResponse>('/members', { method: 'POST', body: JSON.stringify(req) })
}

// 로그인하지 않았으면 401(ApiError)
export function fetchMe(): Promise<MemberResponse> {
  return api<MemberResponse>('/members/me')
}
