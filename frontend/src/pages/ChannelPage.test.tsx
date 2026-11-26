import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchChannel, type ChannelResponse } from '../api/channels.ts'
import { ApiError } from '../api/client.ts'
import { fetchFollowStatus, follow, unfollow } from '../api/follows.ts'
import { LIVE_POLL_INTERVAL_MS } from './broadcastView.ts'
import ChannelPage from './ChannelPage.tsx'

vi.mock('../api/channels.ts', () => ({ fetchChannel: vi.fn() }))
vi.mock('../api/follows.ts', () => ({ fetchFollowStatus: vi.fn(), follow: vi.fn(), unfollow: vi.fn() }))

// jsdom에는 MediaSource가 없다. 플레이어는 받은 호출만 기록하는 가짜 hls.js로 그린다
const hls = vi.hoisted(() => ({ instances: [] as Array<{ loadSource: (src: string) => void }> }))

vi.mock('hls.js', () => ({
  default: class FakeHls {
    static isSupported = () => true
    loadSource = vi.fn()
    attachMedia = vi.fn()
    destroy = vi.fn()
    constructor() {
      hls.instances.push(this)
    }
  },
}))

const fetchChannelMock = vi.mocked(fetchChannel)
const fetchFollowStatusMock = vi.mocked(fetchFollowStatus)
const followMock = vi.mocked(follow)
const unfollowMock = vi.mocked(unfollow)

function channel(live: boolean, demo = false, followerCount = 0): ChannelResponse {
  return { id: 7, name: '하늘', description: '', live, title: '오늘은 노래', demo, followerCount }
}

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={['/channels/7']}>
        <Routes>
          <Route path="/channels/:channelId" element={<ChannelPage />} />
          <Route path="/login" element={<p>로그인 화면</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  // 폴링(5초)을 기다리지 않고 시계를 넘긴다. 테스트 도구의 대기(waitFor)는 그대로 흐르게 둔다
  vi.useFakeTimers({ shouldAdvanceTime: true })
  fetchChannelMock.mockReset()
  // 따로 정하지 않으면 팔로우하지 않은 회원으로 본다
  fetchFollowStatusMock.mockReset().mockResolvedValue({ following: false })
  followMock.mockReset().mockResolvedValue(undefined)
  unfollowMock.mockReset().mockResolvedValue(undefined)
  hls.instances.length = 0
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('ChannelPage', () => {
  it('should show the player and the broadcast title when a visitor opens a live channel', async () => {
    fetchChannelMock.mockResolvedValue(channel(true))

    const { container } = renderPage()

    expect(await screen.findByText('오늘은 노래')).not.toBeNull()
    expect(container.querySelector('video')).not.toBeNull()
  })

  it('should load /hls/live/7/index.m3u8 into the player when a visitor opens live channel 7', async () => {
    fetchChannelMock.mockResolvedValue(channel(true))

    renderPage()

    await waitFor(() => expect(hls.instances[0]?.loadSource).toHaveBeenCalledWith('/hls/live/7/index.m3u8'))
  })

  it('should not ask for the channel again before 5 seconds pass while the channel is live', async () => {
    fetchChannelMock.mockResolvedValue(channel(true))
    const { container } = renderPage()
    await waitFor(() => expect(container.querySelector('video')).not.toBeNull())

    // shouldAdvanceTime이라 실제로 흐른 몇 ms도 시계에 더해진다. 5초 직전은 여유를 두고 4.5초로 본다
    await vi.advanceTimersByTimeAsync(4500)
    const callsBefore = fetchChannelMock.mock.calls.length
    await vi.advanceTimersByTimeAsync(500)

    expect([callsBefore, fetchChannelMock.mock.calls.length]).toEqual([1, 2])
  })

  it('should ask for the channel again exactly when 5 seconds pass while the channel is live', async () => {
    // 실제 시간이 시계에 더해지지 않게 순수 가짜 시계로 바꾼다. 대기는 0ms씩 흘려 처리한다
    vi.useRealTimers()
    vi.useFakeTimers()
    fetchChannelMock.mockResolvedValue(channel(true))
    const { container } = renderPage()
    for (let i = 0; i < 10 && container.querySelector('video') === null; i++) {
      await vi.advanceTimersByTimeAsync(0)
    }

    await vi.advanceTimersByTimeAsync(4999)
    const callsBefore = fetchChannelMock.mock.calls.length
    await vi.advanceTimersByTimeAsync(1)

    expect([callsBefore, fetchChannelMock.mock.calls.length]).toEqual([1, 2])
  })

  it('should show the offline notice without a player when a visitor opens an offline channel', async () => {
    fetchChannelMock.mockResolvedValue(channel(false))

    const { container } = renderPage()

    expect(await screen.findByText('오프라인')).not.toBeNull()
    expect(container.querySelector('video')).toBeNull()
  })

  it('should replace the player with 방송이 종료되었습니다 when the next poll says the channel is no longer live', async () => {
    fetchChannelMock.mockResolvedValueOnce(channel(true)).mockResolvedValue(channel(false))
    const { container } = renderPage()
    await waitFor(() => expect(container.querySelector('video')).not.toBeNull())

    await vi.advanceTimersByTimeAsync(LIVE_POLL_INTERVAL_MS)

    expect(await screen.findByText('방송이 종료되었습니다')).not.toBeNull()
    expect(container.querySelector('video')).toBeNull()
  })

  it('should keep the player when one poll fails while the channel is live', async () => {
    fetchChannelMock.mockResolvedValueOnce(channel(true)).mockRejectedValueOnce(new Error('network'))
    const { container } = renderPage()
    await waitFor(() => expect(container.querySelector('video')).not.toBeNull())

    await vi.advanceTimersByTimeAsync(LIVE_POLL_INTERVAL_MS)
    await waitFor(() => expect(fetchChannelMock).toHaveBeenCalledTimes(2))
    await vi.advanceTimersByTimeAsync(0)

    expect(container.querySelector('video')).not.toBeNull()
  })

  it('should show the demo channel label when a visitor opens a demo channel', async () => {
    fetchChannelMock.mockResolvedValue(channel(true, true))

    renderPage()

    expect(await screen.findByText('데모 채널')).not.toBeNull()
  })

  it('should not show the demo channel label when a visitor opens a channel that is not a demo', async () => {
    fetchChannelMock.mockResolvedValue(channel(true, false))

    renderPage()
    await screen.findByText('오늘은 노래')

    expect(screen.queryByText('데모 채널')).toBeNull()
  })

  it('should show the demo channel label when the demo channel is offline', async () => {
    fetchChannelMock.mockResolvedValue(channel(false, true))

    renderPage()
    await screen.findByText('오프라인')

    expect(screen.queryByText('데모 채널')).not.toBeNull()
  })

  it('should show the message from the error body when the channel request is rejected with 429', async () => {
    // nginx가 IP당 요청 수를 넘기면 이 본문으로 429를 준다(#31)
    fetchChannelMock.mockRejectedValue(
      new ApiError(429, { code: 'RATE_LIMITED', message: '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요' }),
    )

    renderPage()

    expect(await screen.findByText('요청이 너무 많습니다. 잠시 후 다시 시도해 주세요')).not.toBeNull()
  })

  it('should show 팔로워 3명 when the channel has 3 followers', async () => {
    fetchChannelMock.mockResolvedValue(channel(false, false, 3))

    renderPage()

    expect(await screen.findByText('팔로워 3명')).not.toBeNull()
  })

  it('should show 팔로잉 when the member already follows the channel', async () => {
    fetchChannelMock.mockResolvedValue(channel(false, false, 3))
    fetchFollowStatusMock.mockResolvedValue({ following: true })

    renderPage()

    expect(await screen.findByRole('button', { name: '팔로잉' })).not.toBeNull()
  })

  it('should change the button to 팔로잉 when a member clicks 팔로우', async () => {
    fetchChannelMock.mockResolvedValue(channel(false, false, 3))
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: '팔로우' }))

    expect(await screen.findByRole('button', { name: '팔로잉' })).not.toBeNull()
  })

  it('should show 팔로워 4명 when a member clicks 팔로우 on a channel with 3 followers', async () => {
    fetchChannelMock.mockResolvedValue(channel(false, false, 3))
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: '팔로우' }))

    expect(await screen.findByText('팔로워 4명')).not.toBeNull()
  })

  it('should change the button back to 팔로우 when the member clicks 팔로잉', async () => {
    fetchChannelMock.mockResolvedValue(channel(false, false, 4))
    fetchFollowStatusMock.mockResolvedValue({ following: true })
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: '팔로잉' }))

    expect(await screen.findByRole('button', { name: '팔로우' })).not.toBeNull()
  })

  it('should show 팔로워 3명 when the member clicks 팔로잉 on a channel with 4 followers', async () => {
    fetchChannelMock.mockResolvedValue(channel(false, false, 4))
    fetchFollowStatusMock.mockResolvedValue({ following: true })
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: '팔로잉' }))

    expect(await screen.findByText('팔로워 3명')).not.toBeNull()
  })

  it('should send follow for channel 7 when a member clicks 팔로우', async () => {
    fetchChannelMock.mockResolvedValue(channel(false, false, 3))
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: '팔로우' }))
    await screen.findByRole('button', { name: '팔로잉' })

    expect([followMock.mock.calls, unfollowMock.mock.calls]).toEqual([[[7]], []])
  })

  it('should send unfollow for channel 7 when the member clicks 팔로잉', async () => {
    fetchChannelMock.mockResolvedValue(channel(false, false, 4))
    fetchFollowStatusMock.mockResolvedValue({ following: true })
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: '팔로잉' }))
    await screen.findByRole('button', { name: '팔로우' })

    expect([unfollowMock.mock.calls, followMock.mock.calls]).toEqual([[[7]], []])
  })

  it('should show 팔로우 and 팔로워 3명 again when a member clicks 팔로우 and then 팔로잉 on a channel with 3 followers', async () => {
    fetchChannelMock.mockResolvedValue(channel(false, false, 3))
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: '팔로우' }))
    fireEvent.click(await screen.findByRole('button', { name: '팔로잉' }))
    await screen.findByRole('button', { name: '팔로우' })

    expect(screen.queryByText('팔로워 3명')).not.toBeNull()
  })

  it('should not show the follow button when the streamer opens their own channel', async () => {
    fetchChannelMock.mockResolvedValue(channel(false, false, 3))
    fetchFollowStatusMock.mockRejectedValue(
      new ApiError(400, { code: 'CANNOT_FOLLOW_OWN_CHANNEL', message: '내 채널은 팔로우할 수 없습니다' }),
    )
    renderPage()
    await screen.findByText('오늘은 노래')
    await waitFor(() => expect(fetchFollowStatusMock).toHaveBeenCalled())
    await vi.advanceTimersByTimeAsync(0)

    expect(screen.queryByRole('button', { name: '팔로우' })).toBeNull()
  })

  it('should go to the login page when a visitor who is not logged in clicks 팔로우', async () => {
    fetchChannelMock.mockResolvedValue(channel(false, false, 3))
    fetchFollowStatusMock.mockRejectedValue(new ApiError(401, { code: 'UNAUTHENTICATED', message: '로그인이 필요합니다' }))
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: '팔로우' }))

    expect(await screen.findByText('로그인 화면')).not.toBeNull()
  })

  it('should keep 팔로우 and the follower count when the follow request fails', async () => {
    fetchChannelMock.mockResolvedValue(channel(false, false, 3))
    followMock.mockRejectedValue(new ApiError(500, null))
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: '팔로우' }))
    await waitFor(() => expect(followMock).toHaveBeenCalled())
    await vi.advanceTimersByTimeAsync(0)

    expect([screen.queryByText('팔로워 3명'), screen.queryByRole('button', { name: '팔로우' })]).not.toContain(null)
  })
})
