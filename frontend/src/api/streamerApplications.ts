import { api } from './client.ts'

// NONE은 신청한 적 없음
export type ApplicationStatus = 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED'

export interface StreamerApplicationResponse {
  status: ApplicationStatus
}

// 대기 신청이 있거나 회원이 아니면 409(ApiError)
export function applyForStreamer(): Promise<StreamerApplicationResponse> {
  return api<StreamerApplicationResponse>('/streamer-applications', { method: 'POST' })
}

export function fetchMyApplication(): Promise<StreamerApplicationResponse> {
  return api<StreamerApplicationResponse>('/streamer-applications/me')
}
