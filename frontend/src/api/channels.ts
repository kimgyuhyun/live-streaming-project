import { api } from './client.ts'

// live는 방송 중 여부(진행 중 방송이 있으면 true). title은 방송 제목, 정한 적 없으면 "{name}의 방송".
// demo는 운영자가 데모 표시를 붙인 채널이면 true. followerCount는 그 채널을 팔로우한 회원 수(#37).
// viewerCount는 지금 시청자 수(#39). 방송 중이 아니면 0
export interface ChannelResponse {
  id: number
  name: string
  description: string
  live: boolean
  title: string
  demo: boolean
  followerCount: number
  viewerCount: number
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

// 스튜디오에서 내 채널의 방송 제목을 바꾼다. 1~50자(공백만은 안 됨)가 아니면 400
export function updateTitle(title: string): Promise<ChannelResponse> {
  return api<ChannelResponse>('/studio/title', { method: 'PUT', body: JSON.stringify({ title }) })
}
