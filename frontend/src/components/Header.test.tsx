import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchStudioTerms } from '../api/studioTerms.ts'
import Header from './Header.tsx'

vi.mock('../api/members.ts', () => ({
  fetchMe: vi.fn(async () => ({ id: 1, email: 's@example.test', nickname: '하늘', role: 'STREAMER' })),
}))
vi.mock('../api/auth.ts', () => ({ logout: vi.fn() }))
vi.mock('../api/studioTerms.ts', () => ({
  fetchStudioTerms: vi.fn(async () => ({ agreed: false })),
  agreeStudioTerms: vi.fn(),
}))

afterEach(cleanup)

describe('Header', () => {
  it('should show the terms popup when a streamer who has not agreed clicks 스튜디오', async () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter>
          <Header menuExpanded onMenuClick={() => {}} />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    fireEvent.click(await screen.findByText('스튜디오'))

    expect(await screen.findByRole('dialog', { name: '약관 동의' })).not.toBeNull()
  })

  it('should link the wallet icon to my points page', async () => {
    renderWithRoutes()

    expect((await screen.findByRole('link', { name: '내 포인트' })).getAttribute('href')).toBe('/me/points')
  })

  it('should show 스튜디오 above 내 채널 in the profile menu', async () => {
    renderWithRoutes()

    fireEvent.click(await screen.findByRole('button', { name: '내 메뉴' }))

    const items = Array.from(menuOf(screen.getByText('내 채널')).querySelectorAll('a, button'))
    expect(items.map((item) => item.textContent)).toEqual(['스튜디오', '내 채널', '로그아웃'])
  })

  it('should go to the studio from the profile menu when a streamer has agreed', async () => {
    vi.mocked(fetchStudioTerms).mockResolvedValueOnce({ agreed: true })
    renderWithRoutes()
    fireEvent.click(await screen.findByRole('button', { name: '내 메뉴' }))

    fireEvent.click(within(menuOf(screen.getByText('내 채널'))).getByRole('button', { name: '스튜디오' }))

    expect(await screen.findByText('스튜디오 화면')).not.toBeNull()
  })
})

function renderWithRoutes() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={['/']}>
        <Header menuExpanded onMenuClick={() => {}} />
        <Routes>
          <Route path="/" element={null} />
          <Route path="/studio" element={<p>스튜디오 화면</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

// 프로필 메뉴 칸(내 채널 링크의 부모)
function menuOf(item: HTMLElement): HTMLElement {
  return item.parentElement as HTMLElement
}
