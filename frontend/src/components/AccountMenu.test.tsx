import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AccountMenu from './AccountMenu.tsx'

vi.mock('../api/studioTerms.ts', () => ({ fetchStudioTerms: vi.fn(), agreeStudioTerms: vi.fn() }))

afterEach(cleanup)

function renderMenu(path: string) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[path]}>
        <AccountMenu />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('AccountMenu', () => {
  it('should link 내 프로필, 내 채널 and 내 포인트', () => {
    renderMenu('/me/points')

    expect(screen.getByRole('link', { name: '내 프로필' }).getAttribute('href')).toBe('/me/profile')
    expect(screen.getByRole('link', { name: '내 채널' }).getAttribute('href')).toBe('/me')
    expect(screen.getByRole('link', { name: '내 포인트' }).getAttribute('href')).toBe('/me/points')
  })

  it('should keep only the account menus', () => {
    renderMenu('/me/points')

    const labels = screen.getAllByRole('listitem').map((item) => item.textContent)
    expect(labels).toEqual(['내 프로필', '조타 스튜디오', '내 채널', '내 포인트', '내 구독', '최근 시청 영상', '팔로잉 채널'])
  })

  it('should mark the current page', () => {
    renderMenu('/me/points')

    expect(screen.getByRole('link', { name: '내 포인트' }).getAttribute('aria-current')).toBe('page')
    expect(screen.getByRole('link', { name: '내 채널' }).getAttribute('aria-current')).toBeNull()
  })
})
