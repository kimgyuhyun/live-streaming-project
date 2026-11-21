import { api } from './client.ts'

// live는 방송 중 여부(진행 중 방송이 있으면 true)
export interface ChannelResponse {
  id: number
  name: string
  description: string
  live: boolean
}

export interface UpdateChannelRequest {
  name: string
  description: string
}

// 없는 채널이면 404(ApiError). 로그인하지 않아도 볼 수 있다
export function fetchChannel(channelId: number): Promise<ChannelResponse> {
  return api<ChannelResponse>(`/channels/${channelId}`)
}

// 스튜디오용 내 채널. 스트리머가 아니면 403
export function fetchMyChannel(): Promise<ChannelResponse> {
  return api<ChannelResponse>('/studio/channel')
}

// 이름 2~20자·소개 0~200자가 아니면 400, 내 채널이 아니면 403
export function updateChannel(channelId: number, req: UpdateChannelRequest): Promise<ChannelResponse> {
  return api<ChannelResponse>(`/channels/${channelId}`, { method: 'PUT', body: JSON.stringify(req) })
}
