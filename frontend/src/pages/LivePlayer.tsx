import Hls from 'hls.js'
import { useCallback, useEffect, useRef, useState, type ReactElement, type ReactNode } from 'react'
import {
  ChatIcon,
  CoinIcon,
  FullscreenIcon,
  PauseIcon,
  PipIcon,
  PlayIcon,
  SettingsIcon,
  TheaterIcon,
  VolumeIcon,
  VolumeOffIcon,
} from '../components/icons.tsx'
import { hlsUrl, type BroadcastView } from './broadcastView.ts'
import styles from './LivePlayer.module.css'

// 입력칸·버튼에 포커스가 있을 때의 스페이스는 그 요소의 것이다(글자 입력, 버튼 누르기)
function isTypingTarget(target: EventTarget | null): boolean {
  return target instanceof Element && target.closest('input, textarea, select, button, [contenteditable="true"]') !== null
}

// hls.js로 재생한다(저지연 HLS. infra-validation 3절과 같은 lowLatencyMode).
// hls.js를 못 쓰는 브라우저(MSE가 없는 iOS Safari)는 HLS를 직접 재생하므로 video.src에 넣는다.
// 브라우저가 소리 있는 자동 재생을 막아 muted로 시작한다. 소리는 컨트롤의 소리 버튼으로 켠다.
// 설정·넓은 화면 버튼은 아직 기능이 없어 모양만 있다
export function HlsPlayer({ src }: { src: string }): ReactElement {
  const boxRef = useRef<HTMLDivElement>(null)
  const videoRef = useRef<HTMLVideoElement>(null)
  const hlsRef = useRef<Hls | null>(null)
  const [paused, setPaused] = useState(false)
  const [muted, setMuted] = useState(true)
  const [volume, setVolume] = useState(1)

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
    hlsRef.current = hls
    return () => {
      hls.destroy()
      hlsRef.current = null
    }
  }, [src])

  // 영상의 실제 상태를 버튼 모양에 옮긴다(자동 재생 실패, 다른 경로로 멈춤 포함)
  useEffect(() => {
    const video = videoRef.current
    if (!video) return
    const sync = () => {
      setPaused(video.paused)
      setMuted(video.muted)
      setVolume(video.volume)
    }
    const events = ['play', 'pause', 'volumechange']
    events.forEach((name) => video.addEventListener(name, sync))
    return () => events.forEach((name) => video.removeEventListener(name, sync))
  }, [])

  const togglePlay = useCallback(() => {
    const video = videoRef.current
    if (!video) return
    if (video.paused) {
      // 재생이 막히면(브라우저 정책) 멈춘 채로 둔다
      video.play()?.catch(() => {})
    } else {
      video.pause()
    }
  }, [])

  // 스페이스바로 재생·정지. 페이지가 아래로 스크롤되지 않게 막는다
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.code !== 'Space' || isTypingTarget(e.target)) return
      e.preventDefault()
      togglePlay()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [togglePlay])

  function toggleMute() {
    const video = videoRef.current
    if (!video) return
    video.muted = !video.muted
    if (!video.muted && video.volume === 0) video.volume = 0.5
  }

  function changeVolume(value: number) {
    const video = videoRef.current
    if (!video) return
    video.volume = value
    video.muted = value === 0
  }

  // 멈췄다 다시 보면 늦어진 만큼 실시간 위치로 건너뛴다
  function goLive() {
    const video = videoRef.current
    const livePosition = hlsRef.current?.liveSyncPosition
    if (video && livePosition) video.currentTime = livePosition
    if (video?.paused) togglePlay()
  }

  function togglePip() {
    const video = videoRef.current
    if (!video) return
    if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {})
    else video.requestPictureInPicture().catch(() => {})
  }

  function toggleFullscreen() {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
    else boxRef.current?.requestFullscreen().catch(() => {})
  }

  const soundOff = muted || volume === 0

  return (
    <div ref={boxRef} className={`${styles.box} ${paused ? styles.paused : ''}`}>
      <video ref={videoRef} className={styles.video} autoPlay muted playsInline onClick={togglePlay} />
      <span className={styles.liveBadge}>LIVE</span>
      <div className={styles.controls}>
        <div className={styles.progress} />
        <div className={styles.bar}>
          <button className={styles.button} type="button" aria-label={paused ? '재생' : '정지'} onClick={togglePlay}>
            {paused ? <PlayIcon className={styles.icon} /> : <PauseIcon className={styles.icon} />}
          </button>
          <div className={styles.volume}>
            <button
              className={styles.button}
              type="button"
              aria-label={soundOff ? '소리 켜기' : '소리 끄기'}
              onClick={toggleMute}
            >
              {soundOff ? <VolumeOffIcon className={styles.icon} /> : <VolumeIcon className={styles.icon} />}
            </button>
            <input
              className={styles.volumeSlider}
              type="range"
              min={0}
              max={1}
              step={0.05}
              value={soundOff ? 0 : volume}
              onChange={(e) => changeVolume(Number(e.target.value))}
              aria-label="소리 크기"
            />
          </div>
          <button className={styles.liveButton} type="button" onClick={goLive}>
            <span className={styles.liveDot} />
            실시간
          </button>
          <span className={styles.spacer} />
          {document.pictureInPictureEnabled && (
            <button className={styles.button} type="button" aria-label="PIP 모드" onClick={togglePip}>
              <PipIcon className={styles.icon} />
            </button>
          )}
          <button className={styles.button} type="button" aria-label="설정">
            <SettingsIcon className={styles.icon} />
          </button>
          <button className={styles.button} type="button" aria-label="넓은 화면">
            <TheaterIcon className={styles.icon} />
          </button>
          <button className={styles.button} type="button" aria-label="전체 화면" onClick={toggleFullscreen}>
            <FullscreenIcon className={styles.icon} />
          </button>
        </div>
      </div>
    </div>
  )
}

// 채팅을 접었을 때 영상 오른쪽에 뜨는 버튼. 후원은 아직 기능이 없어 모양만 있다
export function ChatOpenButtons({ onOpenChat }: { onOpenChat: () => void }): ReactElement {
  return (
    <div className={styles.overlay}>
      <button className={styles.overlayButton} type="button" aria-label="채팅 펼치기" title="채팅 펼치기" onClick={onOpenChat}>
        <ChatIcon className={styles.icon} />
      </button>
      <button className={styles.overlayButton} type="button" aria-label="후원하기" title="후원하기">
        <CoinIcon className={styles.icon} />
      </button>
    </div>
  )
}

// 플레이어 자리: 방송 중이면 플레이어, 아니면 그 자리에 상태 문구. overlay는 영상 위에 덮어 그린다
export function PlayerArea({
  view,
  channelId,
  overlay,
}: {
  view: BroadcastView
  channelId: number
  overlay?: ReactNode
}): ReactElement {
  return (
    <div className={styles.player}>
      {view === 'live' ? (
        <HlsPlayer src={hlsUrl(channelId)} />
      ) : (
        <p className={styles.status}>{view === 'ended' ? '방송이 종료되었습니다' : '오프라인'}</p>
      )}
      {overlay}
    </div>
  )
}
