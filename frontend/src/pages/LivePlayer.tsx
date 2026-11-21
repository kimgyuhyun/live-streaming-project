import type { ReactElement } from 'react'
import type { BroadcastView } from './broadcastView.ts'

// hls.js로 재생한다. hls.js를 못 쓰는 브라우저(Safari)는 video.src에 직접 넣는다
export function HlsPlayer({ src }: { src: string }): ReactElement {
  void src
  throw new Error('not implemented')
}

// 플레이어 자리: live → 플레이어, offline → 오프라인, ended → 방송이 종료되었습니다
export function PlayerArea({ view, channelId }: { view: BroadcastView; channelId: number }): ReactElement {
  void view
  void channelId
  throw new Error('not implemented')
}
