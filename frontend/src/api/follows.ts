// 내가 이 채널을 팔로우했는지. 채널 조회(nginx 1초 캐시)에 넣지 않고 따로 묻는다(PRD viewer 작은 결정)
export interface FollowStatusResponse {
  following: boolean
}

// 비로그인이면 401, 내 채널이면 400(CANNOT_FOLLOW_OWN_CHANNEL), 없는 채널이면 404(ApiError)
export function fetchFollowStatus(_channelId: number): Promise<FollowStatusResponse> {
  throw new Error('not implemented')
}

// 이미 팔로우했어도 204
export function follow(_channelId: number): Promise<void> {
  throw new Error('not implemented')
}

// 팔로우하지 않았어도 204
export function unfollow(_channelId: number): Promise<void> {
  throw new Error('not implemented')
}
