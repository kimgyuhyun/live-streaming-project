import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client.ts'
import { fetchFollowing, type FollowingChannel } from '../api/follows.ts'
import FollowingPage from './FollowingPage.tsx'

vi.mock('../api/follows.ts', () => ({ fetchFollowing: vi.fn() }))

const fetchFollowingMock = vi.mocked(fetchFollowing)

function channel(channelId: number, name: string, live = false, title = `${name}의 방송`): FollowingChannel {
  return { channelId, name, live, title }
}

function renderPage() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/following']}>
        <Routes>
          <Route path="/following" element={<FollowingPage />} />
          <Route path="/channels/:channelId" element={<p>채널 화면</p>} />
          <Route path="/login" element={<p>로그인 화면</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

// 이름이 든 목록 칸
function itemOf(name: string): HTMLElement {
  return screen.getByText(name).closest('li') as HTMLElement
}

beforeEach(() => {
  fetchFollowingMock.mockReset()
})

afterEach(cleanup)

describe('FollowingPage', () => {
  it('should list the channels in the order the server returns', async () => {
    fetchFollowingMock.mockResolvedValue({
      items: [channel(12, '연필깎이', true), channel(11, '스쿼트왕'), channel(13, '공부하는곰')],
      hasMore: false,
    })
    renderPage()
    await screen.findByText('연필깎이')

    const links = screen.getAllByRole('listitem').map((item) => within(item).getByRole('link').getAttribute('href'))
    expect(links).toEqual(['/channels/12', '/channels/11', '/channels/13'])
  })

  it('should show LIVE and the broadcast title for a live channel', async () => {
    fetchFollowingMock.mockResolvedValue({ items: [channel(12, '연필깎이', true, '그림 그리면서 수다')], hasMore: false })
    renderPage()
    await screen.findByText('연필깎이')

    expect(within(itemOf('연필깎이')).getAllByText(/^(LIVE|그림 그리면서 수다)$/).map((el) => el.textContent)).toEqual([
      'LIVE',
      '그림 그리면서 수다',
    ])
  })

  it('should not show LIVE or the title for an offline channel', async () => {
    fetchFollowingMock.mockResolvedValue({ items: [channel(11, '스쿼트왕', false, '아침 운동')], hasMore: false })
    renderPage()
    await screen.findByText('스쿼트왕')

    expect(within(itemOf('스쿼트왕')).queryAllByText(/^(LIVE|아침 운동)$/)).toEqual([])
  })

  it('should go to the channel page when a channel is clicked', async () => {
    fetchFollowingMock.mockResolvedValue({ items: [channel(12, '연필깎이', true)], hasMore: false })
    renderPage()

    fireEvent.click(await screen.findByText('연필깎이'))

    expect(await screen.findByText('채널 화면')).not.toBeNull()
  })

  it('should append the next channels fetched with offset 20 when 더 보기 is clicked', async () => {
    const first = Array.from({ length: 20 }, (_, i) => channel(100 + i, `첫묶음${i}`))
    fetchFollowingMock.mockImplementation(async (offset) =>
      offset === 20 ? { items: [channel(200, '스물한번째')], hasMore: false } : { items: first, hasMore: true },
    )
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: '더 보기' }))
    await screen.findByText('스물한번째')

    expect(screen.getAllByRole('listitem')).toHaveLength(21)
  })

  it('should show 팔로우한 채널이 없습니다 when the member follows no channel', async () => {
    fetchFollowingMock.mockResolvedValue({ items: [], hasMore: false })
    renderPage()

    expect(await screen.findByText('팔로우한 채널이 없습니다.')).not.toBeNull()
  })

  it('should not show 더 보기 when hasMore is false', async () => {
    fetchFollowingMock.mockResolvedValue({ items: [channel(13, '공부하는곰')], hasMore: false })
    renderPage()
    await screen.findByText('공부하는곰')

    expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull()
  })

  it('should go to the login page when the following list returns 401', async () => {
    fetchFollowingMock.mockRejectedValue(new ApiError(401, null))
    renderPage()

    expect(await screen.findByText('로그인 화면')).not.toBeNull()
  })
})
