import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client.ts'
import { fetchFollowing } from '../api/follows.ts'
import FollowingPage from '../pages/FollowingPage.tsx'
import Sidebar from './Sidebar.tsx'

vi.mock('../api/follows.ts', () => ({ fetchFollowing: vi.fn() }))

const fetchFollowingMock = vi.mocked(fetchFollowing)

// 사이드바 + 메인 칸. 팔로잉 화면은 진짜 컴포넌트, 로그인 화면은 표시만
function renderWithSidebar() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter>
        <Routes>
          <Route
            element={
              <>
                <Sidebar collapsed={false} open={false} onClose={() => {}} />
                <Outlet />
              </>
            }
          >
            <Route index element={<p>홈 화면</p>} />
            <Route path="following" element={<FollowingPage />} />
            <Route path="login" element={<p>로그인 화면</p>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function followingMenu(): HTMLElement {
  return screen.getByText('팔로잉', { selector: `span:first-of-type` }).closest('a, button') as HTMLElement
}

afterEach(cleanup)

describe('Sidebar', () => {
  it('should open the following page when 팔로잉 is clicked', async () => {
    fetchFollowingMock.mockResolvedValue({
      items: [{ channelId: 12, name: '연필깎이', live: false, title: '연필깎이의 방송' }],
      hasMore: false,
    })
    renderWithSidebar()

    fireEvent.click(followingMenu())

    expect(await screen.findByText('연필깎이')).not.toBeNull()
  })

  it('should go to the login page when a visitor who is not logged in clicks 팔로잉', async () => {
    fetchFollowingMock.mockRejectedValue(new ApiError(401, null))
    renderWithSidebar()

    fireEvent.click(followingMenu())

    expect(await screen.findByText('로그인 화면')).not.toBeNull()
  })
})
