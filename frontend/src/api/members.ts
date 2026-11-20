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

// 다른 회원이 쓰는 닉네임이면 409
export function changeNickname(nickname: string): Promise<MemberResponse> {
  return api<MemberResponse>('/members/me/nickname', { method: 'PUT', body: JSON.stringify({ nickname }) })
}

export interface ChangePasswordRequest {
  currentPassword: string
  newPassword: string
}

// 현재 비밀번호가 틀리면 400(MEMBER_PASSWORD_MISMATCH). 성공하면 다른 기기의 로그인이 풀린다
export function changePassword(req: ChangePasswordRequest): Promise<void> {
  return api<void>('/members/me/password', { method: 'PUT', body: JSON.stringify(req) })
}
