import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchStudioTerms } from '../api/studioTerms.ts'
import MyChannelPage from './MyChannelPage.tsx'

vi.mock('../api/members.ts', () => ({
  fetchMe: vi.fn(async () => ({ id: 1, email: 's@example.test', nickname: '하늘', role: 'STREAMER' })),
}))
vi.mock('../api/studioTerms.ts', () => ({
  fetchStudioTerms: vi.fn(async () => ({ agreed: false })),
  agreeStudioTerms: vi.fn(),
}))

afterEach(cleanup)

function renderPage() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={['/me']}>
        <MyChannelPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('MyChannelPage', () => {
  it('should show the terms popup when a streamer who has not agreed clicks 방송하기', async () => {
    renderPage()

    fireEvent.click(await screen.findByText('방송하기'))

    expect(await screen.findByRole('dialog', { name: '약관 동의' })).not.toBeNull()
  })

  it('should go to the channel management page when a streamer who has agreed clicks 채널 관리', async () => {
    vi.mocked(fetchStudioTerms).mockResolvedValueOnce({ agreed: true })
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter initialEntries={['/me']}>
          <Routes>
            <Route path="/me" element={<MyChannelPage />} />
            <Route path="/me/manage" element={<p>채널 관리 화면</p>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    )

    fireEvent.click(await screen.findByText('채널 관리'))

    expect(await screen.findByText('채널 관리 화면')).not.toBeNull()
  })

  it('should show the terms popup when a streamer who has not agreed clicks 채널 관리', async () => {
    renderPage()

    fireEvent.click(await screen.findByText('채널 관리'))

    expect(await screen.findByRole('dialog', { name: '약관 동의' })).not.toBeNull()
  })
})
