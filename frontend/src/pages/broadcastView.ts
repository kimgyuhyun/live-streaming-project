// 플레이어 자리에 무엇을 보일지. 'ended' = 이 페이지에서 방송 중을 본 뒤 오프라인이 됨
export type BroadcastView = 'live' | 'offline' | 'ended'

// 채널 응답의 live가 올 때마다 다음 화면을 고른다
export function nextBroadcastView(previous: BroadcastView | null, live: boolean): BroadcastView {
  void previous
  void live
  throw new Error('not implemented')
}

// 응답이 올 때마다 nextBroadcastView로 화면을 이어 간다. 아직 응답이 없으면 null
export function useBroadcastView(live: boolean | undefined): BroadcastView | null {
  void live
  throw new Error('not implemented')
}

// 방송 중일 때만 다시 묻는다(시청 중 종료 감지)
export const LIVE_POLL_INTERVAL_MS = 5000

export function pollInterval(live: boolean | undefined): number | false {
  void live
  throw new Error('not implemented')
}

export function hlsUrl(channelId: number): string {
  void channelId
  throw new Error('not implemented')
}
