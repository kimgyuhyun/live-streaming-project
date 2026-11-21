import Hls from 'hls.js'
import { useEffect, useRef, type ReactElement } from 'react'
import { hlsUrl, type BroadcastView } from './broadcastView.ts'

// hls.js로 재생한다(저지연 HLS. infra-validation 3절과 같은 lowLatencyMode).
// hls.js를 못 쓰는 브라우저(MSE가 없는 iOS Safari)는 HLS를 직접 재생하므로 video.src에 넣는다.
// 브라우저가 소리 있는 자동 재생을 막아 muted로 시작한다. 소리는 controls로 켠다
export function HlsPlayer({ src }: { src: string }): ReactElement {
  const videoRef = useRef<HTMLVideoElement>(null)

  useEffect(() => {
    const video = videoRef.current
    if (!video) return
    if (!Hls.isSupported()) {
      video.src = src
      return
    }
    const hls = new Hls({ lowLatencyMode: true })
    hls.loadSource(src)
    hls.attachMedia(video)
    return () => hls.destroy()
  }, [src])

  return <video ref={videoRef} autoPlay muted playsInline controls />
}

// 플레이어 자리: 방송 중이면 플레이어, 아니면 그 자리에 상태 문구
export function PlayerArea({ view, channelId }: { view: BroadcastView; channelId: number }): ReactElement {
  if (view === 'live') {
    return (
      <div>
        <p>방송 중</p>
        <HlsPlayer src={hlsUrl(channelId)} />
      </div>
    )
  }
  return <p>{view === 'ended' ? '방송이 종료되었습니다' : '오프라인'}</p>
}
