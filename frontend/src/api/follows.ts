import { api } from './client.ts'

// 내가 이 채널을 팔로우했는지. 채널 조회(nginx 1초 캐시)에 넣지 않고 따로 묻는다(PRD viewer 작은 결정)
export interface FollowStatusResponse {
  following: boolean
}

// 비로그인이면 401, 내 채널이면 400(CANNOT_FOLLOW_OWN_CHANNEL), 없는 채널이면 404(ApiError)
export function fetchFollowStatus(channelId: number): Promise<FollowStatusResponse> {
  return api<FollowStatusResponse>(`/channels/${channelId}/follow`)
}

// 이미 팔로우했어도 204
export async function follow(channelId: number): Promise<void> {
  await api<null>(`/channels/${channelId}/follow`, { method: 'PUT' })
}

// 팔로우하지 않았어도 204
export async function unfollow(channelId: number): Promise<void> {
  await api<null>(`/channels/${channelId}/follow`, { method: 'DELETE' })
}

// 팔로잉 목록 한 줄(#38). title은 방송 제목(정한 적 없으면 "{name}의 방송")
export interface FollowingChannel {
  channelId: number
  name: string
  live: boolean
  title: string
}

export interface FollowingResponse {
  items: FollowingChannel[]
  hasMore: boolean
}

// 내가 팔로우한 채널을 방송 중 먼저, 최근 팔로우 순으로 offset부터 20개. 비로그인이면 401(ApiError)
export function fetchFollowing(offset: number): Promise<FollowingResponse> {
  return api<FollowingResponse>(`/me/following?offset=${offset}`)
}
