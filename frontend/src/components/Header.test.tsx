import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
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
})
