import { useState } from 'react'

// 플레이어 자리에 무엇을 보일지. 'ended' = 이 페이지에서 방송 중을 본 뒤 오프라인이 됨
export type BroadcastView = 'live' | 'offline' | 'ended'

// 채널 응답의 live가 올 때마다 다음 화면을 고른다.
// 방송 중을 본 적이 있을 때만 '종료됨'이다 — 처음부터 오프라인이면 '오프라인'
export function nextBroadcastView(previous: BroadcastView | null, live: boolean): BroadcastView {
  if (live) return 'live'
  return previous === 'live' || previous === 'ended' ? 'ended' : 'offline'
}

// 응답이 올 때마다 nextBroadcastView로 화면을 이어 간다. 아직 응답이 없으면 null.
// 'ended'는 이전 화면을 기억해야 나오므로 서버 값 하나로는 고를 수 없다
export function useBroadcastView(live: boolean | undefined): BroadcastView | null {
  const [view, setView] = useState<BroadcastView | null>(null)
  const [seenLive, setSeenLive] = useState<boolean | undefined>(undefined)
  // 렌더 중에 이전 값과 비교해 상태를 고친다(React 문서의 "prop이 바뀔 때 상태 조정" 방식)
  if (live !== seenLive) {
    setSeenLive(live)
    if (live !== undefined) setView(nextBroadcastView(view, live))
  }
  return view
}

// 방송 중일 때만 다시 묻는다(시청 중 종료 감지). 오프라인 채널을 시청자 모두가 계속 묻지 않게 한다
export const LIVE_POLL_INTERVAL_MS = 5000

export function pollInterval(live: boolean | undefined): number | false {
  return live ? LIVE_POLL_INTERVAL_MS : false
}

// 방송 중이면 "N명 시청 중", 아니면 null(보이지 않는다)(#39)
export function viewerCountLabel(live: boolean, viewerCount: number): string | null {
  return live ? `${viewerCount}명 시청 중` : null
}

// nginx /hls/ → MediaMTX. 원본 화질 하나(화질 자동 전환은 하지 않는다)
export function hlsUrl(channelId: number): string {
  return `/hls/live/${channelId}/index.m3u8`
}
