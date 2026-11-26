import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMyChannel, updateTitle, type ChannelResponse } from '../api/channels.ts'
import StudioBroadcastPage from './StudioBroadcastPage.tsx'

vi.mock('../api/channels.ts', () => ({ fetchMyChannel: vi.fn(), updateTitle: vi.fn() }))
// jsdom에는 MediaSource가 없다. 미리보기 플레이어는 이름만 남긴다
vi.mock('./LivePlayer.tsx', () => ({ HlsPlayer: ({ src }: { src: string }) => <p>재생 {src}</p> }))

const fetchMyChannelMock = vi.mocked(fetchMyChannel)
const updateTitleMock = vi.mocked(updateTitle)

const CHANNEL: ChannelResponse = {
  id: 7,
  name: '하늘',
  description: '',
  live: false,
  title: '하늘의 방송',
  demo: false,
  followerCount: 1234,
}

function renderPage() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <StudioBroadcastPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  fetchMyChannelMock.mockReset().mockResolvedValue(CHANNEL)
  updateTitleMock.mockReset().mockResolvedValue({ ...CHANNEL, title: '새 제목' })
})

afterEach(cleanup)

describe('StudioBroadcastPage', () => {
  it('should guide to the streaming setup when offline', async () => {
    renderPage()

    expect((await screen.findByRole('link', { name: '스트리밍 설정 안내' })).getAttribute('href')).toBe('/studio/guide/obs')
  })

  it('should play my broadcast in the preview when live', async () => {
    fetchMyChannelMock.mockResolvedValue({ ...CHANNEL, live: true })

    renderPage()

    expect(await screen.findByText('재생 /hls/live/7/index.m3u8')).not.toBeNull()
  })

  it('should save the broadcast title', async () => {
    renderPage()

    fireEvent.change(await screen.findByLabelText(/방송 제목/), { target: { value: '새 제목' } })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByText('저장했습니다')).not.toBeNull()
    expect(updateTitleMock).toHaveBeenCalledWith('새 제목')
  })

  it('should show the follower count only after turning on 정보 보기', async () => {
    renderPage()
    await screen.findByRole('switch', { name: '정보 보기' })
    expect(screen.queryByText('1,234')).toBeNull()

    fireEvent.click(screen.getByRole('switch', { name: '정보 보기' }))

    expect(screen.getByText('1,234')).not.toBeNull()
  })
})
