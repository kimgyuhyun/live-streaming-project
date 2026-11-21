import { renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { hlsUrl, LIVE_POLL_INTERVAL_MS, nextBroadcastView, pollInterval, useBroadcastView } from './broadcastView.ts'

describe('nextBroadcastView', () => {
  it('should choose live when the first channel response is live', () => {
    expect(nextBroadcastView(null, true)).toBe('live')
  })

  it('should choose offline when the first channel response is offline', () => {
    expect(nextBroadcastView(null, false)).toBe('offline')
  })

  it('should choose ended when a live channel turns offline', () => {
    expect(nextBroadcastView('live', false)).toBe('ended')
  })

  it('should stay ended when the channel is still offline after the broadcast ended', () => {
    expect(nextBroadcastView('ended', false)).toBe('ended')
  })

  it('should choose live when an offline channel turns live', () => {
    expect(nextBroadcastView('offline', true)).toBe('live')
  })

  it('should choose live when a new broadcast starts after the previous one ended', () => {
    expect(nextBroadcastView('ended', true)).toBe('live')
  })
})

describe('useBroadcastView', () => {
  it('should switch from live to ended when a later response says the channel is offline', () => {
    const { result, rerender } = renderHook(({ live }) => useBroadcastView(live), {
      initialProps: { live: true as boolean | undefined },
    })

    rerender({ live: false })

    expect(result.current).toBe('ended')
  })

  it('should return null before the first channel response arrives', () => {
    const { result } = renderHook(() => useBroadcastView(undefined))

    expect(result.current).toBeNull()
  })

  it('should stay offline when a channel that was offline from the start is fetched again', () => {
    const { result, rerender } = renderHook(({ live }) => useBroadcastView(live), {
      initialProps: { live: false as boolean | undefined },
    })

    rerender({ live: false })

    expect(result.current).toBe('offline')
  })
})

describe('pollInterval', () => {
  it('should poll every 5 seconds while the channel is live', () => {
    expect(pollInterval(true)).toBe(LIVE_POLL_INTERVAL_MS)
  })

  it('should not poll before the first channel response arrives', () => {
    expect(pollInterval(undefined)).toBe(false)
  })

  it('should not poll while the channel is offline', () => {
    expect(pollInterval(false)).toBe(false)
  })
})

describe('hlsUrl', () => {
  it('should build /hls/live/{channelId}/index.m3u8 for the channel', () => {
    expect(hlsUrl(7)).toBe('/hls/live/7/index.m3u8')
  })
})
