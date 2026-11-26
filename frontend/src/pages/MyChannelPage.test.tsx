import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import MyChannelPage from './MyChannelPage.tsx'

vi.mock('../api/members.ts', () => ({
  fetchMe: vi.fn(async () => ({ id: 1, email: 's@example.test', nickname: '하늘', role: 'STREAMER' })),
}))
vi.mock('../api/studioTerms.ts', () => ({
  fetchStudioTerms: vi.fn(async () => ({ agreed: false })),
  agreeStudioTerms: vi.fn(),
}))

afterEach(cleanup)

describe('MyChannelPage', () => {
  it('should show the terms popup when a streamer who has not agreed clicks 방송하기', async () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter initialEntries={['/me']}>
          <MyChannelPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    fireEvent.click(await screen.findByText('방송하기'))

    expect(await screen.findByRole('dialog', { name: '약관 동의' })).not.toBeNull()
  })
})
