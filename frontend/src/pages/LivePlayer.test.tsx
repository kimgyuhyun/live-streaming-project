import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { HlsPlayer, PlayerArea } from './LivePlayer.tsx'

// jsdom에는 MediaSource가 없어 진짜 hls.js는 돌지 않는다. 받은 호출만 기록하는 가짜로 바꾼다
const hls = vi.hoisted(() => ({
  supported: true,
  instances: [] as Array<{ loadSource: (src: string) => void; attachMedia: (video: HTMLVideoElement) => void; destroy: () => void }>,
}))

vi.mock('hls.js', () => ({
  default: class FakeHls {
    static isSupported = () => hls.supported
    loadSource = vi.fn()
    attachMedia = vi.fn()
    destroy = vi.fn()
    constructor() {
      hls.instances.push(this)
    }
  },
}))

beforeEach(() => {
  hls.supported = true
  hls.instances.length = 0
})

afterEach(cleanup)

const SRC = '/hls/live/7/index.m3u8'

describe('HlsPlayer', () => {
  it('should load the HLS address into hls.js and attach it to the video when hls.js is supported', () => {
    const { container } = render(<HlsPlayer src={SRC} />)

    expect(hls.instances[0].loadSource).toHaveBeenCalledWith(SRC)
    expect(hls.instances[0].attachMedia).toHaveBeenCalledWith(container.querySelector('video'))
  })

  it('should set the HLS address as the video source when the browser plays HLS without hls.js', () => {
    hls.supported = false

    const { container } = render(<HlsPlayer src={SRC} />)

    expect(container.querySelector('video')?.getAttribute('src')).toBe(SRC)
  })

  it('should destroy the hls.js instance when the player is removed', () => {
    const { unmount } = render(<HlsPlayer src={SRC} />)

    unmount()

    expect(hls.instances[0].destroy).toHaveBeenCalled()
  })
})

describe('PlayerArea', () => {
  it('should show the player for /hls/live/7/index.m3u8 when the view is live', () => {
    render(<PlayerArea view="live" channelId={7} />)

    expect(hls.instances[0].loadSource).toHaveBeenCalledWith(SRC)
  })

  it('should show the offline notice instead of the player when the view is offline', () => {
    const { container } = render(<PlayerArea view="offline" channelId={7} />)

    expect(screen.queryByText('오프라인')).not.toBeNull()
    expect(container.querySelector('video')).toBeNull()
  })

  it('should show 방송이 종료되었습니다 instead of the player when the view is ended', () => {
    const { container } = render(<PlayerArea view="ended" channelId={7} />)

    expect(screen.queryByText('방송이 종료되었습니다')).not.toBeNull()
    expect(container.querySelector('video')).toBeNull()
  })
})
