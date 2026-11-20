import { api } from './client.ts'

// 발급·재발급 응답. 전체 키는 여기에서만 한 번 받는다. obsStreamKey는 OBS 스트림 키 칸에 넣을 값
export interface IssuedStreamKeyResponse {
  streamKey: string
  keyPrefix: string
  obsServer: string
  obsStreamKey: string
}

// keyPrefix는 키의 앞 4자리, 키가 없으면 null
export interface StreamKeyResponse {
  keyPrefix: string | null
  obsServer: string
}

// 키가 있어도 새 키로 바꾼다(재발급). 스트리머가 아니면 403
export function issueStreamKey(): Promise<IssuedStreamKeyResponse> {
  return api<IssuedStreamKeyResponse>('/studio/stream-key', { method: 'POST' })
}

export function fetchStreamKey(): Promise<StreamKeyResponse> {
  return api<StreamKeyResponse>('/studio/stream-key')
}
