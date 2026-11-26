import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchMyChannel, type ChannelResponse } from '../api/channels.ts'
import StudioFollowersPage from './StudioFollowersPage.tsx'

vi.mock('../api/channels.ts', () => ({ fetchMyChannel: vi.fn() }))

const fetchMyChannelMock = vi.mocked(fetchMyChannel)

const CHANNEL: ChannelResponse = {
  id: 7,
  name: '하늘',
  description: '',
  live: false,
  title: '하늘의 방송',
  demo: false,
  followerCount: 0,
}

function renderPage(followerCount: number) {
  fetchMyChannelMock.mockReset().mockResolvedValue({ ...CHANNEL, followerCount })
  render(
    <QueryClientProvider client={new QueryClient()}>
      <StudioFollowersPage />
    </QueryClientProvider>,
  )
}

afterEach(cleanup)

describe('StudioFollowersPage', () => {
  it('should show my channel follower count', async () => {
    renderPage(1234)

    expect(await screen.findByText('1,234')).not.toBeNull()
  })

  it('should say it is quiet when there are no followers', async () => {
    renderPage(0)

    expect(await screen.findByText('아직은 고요합니다..')).not.toBeNull()
  })

  it('should not say it is quiet when there are followers', async () => {
    renderPage(3)

    expect(await screen.findByText('팔로워 목록은 준비 중입니다.')).not.toBeNull()
    expect(screen.queryByText('아직은 고요합니다..')).toBeNull()
  })
})
