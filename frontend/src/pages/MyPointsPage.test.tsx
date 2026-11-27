import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchMe } from '../api/members.ts'
import { ApiError } from '../api/client.ts'
import MyPointsPage from './MyPointsPage.tsx'

vi.mock('../api/members.ts', () => ({ fetchMe: vi.fn() }))

const fetchMeMock = vi.mocked(fetchMe)

function renderPage() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <MyPointsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(cleanup)

describe('MyPointsPage', () => {
  it('should show the usage history first', async () => {
    fetchMeMock.mockResolvedValue({ id: 1, email: 'v@example.test', nickname: '시청자', role: 'MEMBER' })
    renderPage()

    expect((await screen.findByRole('tab', { name: '사용 내역' })).getAttribute('aria-selected')).toBe('true')
    expect(screen.getByText('사용 내역이 없습니다.')).not.toBeNull()
  })

  it('should show the charge history when that tab is chosen', async () => {
    fetchMeMock.mockResolvedValue({ id: 1, email: 'v@example.test', nickname: '시청자', role: 'MEMBER' })
    renderPage()

    fireEvent.click(await screen.findByRole('tab', { name: '충전 내역' }))

    expect(screen.getByText('충전 내역이 없습니다.')).not.toBeNull()
  })

  it('should open the charge popup from 충전하기', async () => {
    fetchMeMock.mockResolvedValue({ id: 1, email: 'v@example.test', nickname: '시청자', role: 'MEMBER' })
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: '충전하기' }))

    expect(screen.getByRole('dialog', { name: '포인트 충전하기' })).not.toBeNull()
  })

  it('should ask to log in when not logged in', async () => {
    fetchMeMock.mockRejectedValue(new ApiError(401, null))
    renderPage()

    expect((await screen.findByRole('link', { name: '로그인' })).getAttribute('href')).toBe('/login')
  })
})
