import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMyChannel } from '../api/channels.ts'
import { fetchMe } from '../api/members.ts'
import { fetchStreamKey } from '../api/streamKey.ts'
import { fetchStudioTerms } from '../api/studioTerms.ts'
import StudioPage from './StudioPage.tsx'

vi.mock('../api/members.ts', () => ({ fetchMe: vi.fn() }))
vi.mock('../api/channels.ts', () => ({ fetchMyChannel: vi.fn(), updateChannel: vi.fn(), updateTitle: vi.fn() }))
vi.mock('../api/streamKey.ts', () => ({ fetchStreamKey: vi.fn(), issueStreamKey: vi.fn() }))
vi.mock('../api/studioTerms.ts', () => ({ fetchStudioTerms: vi.fn(), agreeStudioTerms: vi.fn() }))

const fetchMeMock = vi.mocked(fetchMe)
const fetchMyChannelMock = vi.mocked(fetchMyChannel)
const fetchStreamKeyMock = vi.mocked(fetchStreamKey)
const fetchStudioTermsMock = vi.mocked(fetchStudioTerms)

function renderStudio() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={['/studio']}>
        <Routes>
          <Route path="/" element={<p>홈 화면</p>} />
          <Route path="/studio" element={<StudioPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  fetchMeMock.mockReset().mockResolvedValue({ id: 1, email: 's@example.test', nickname: '하늘', role: 'STREAMER' })
  fetchMyChannelMock
    .mockReset()
    .mockResolvedValue({ id: 7, name: '하늘', description: '', live: false, title: '하늘의 방송', demo: false, followerCount: 0 })
  fetchStreamKeyMock.mockReset().mockResolvedValue({ keyPrefix: null, obsServer: 'rtmp://localhost/live' })
  fetchStudioTermsMock.mockReset()
})

afterEach(cleanup)

describe('StudioPage', () => {
  it('should show the studio when the streamer has agreed', async () => {
    fetchStudioTermsMock.mockResolvedValue({ agreed: true })

    renderStudio()

    expect(await screen.findByRole('heading', { name: '스튜디오' })).not.toBeNull()
  })

  it('should show the studio when an operator has agreed', async () => {
    fetchMeMock.mockResolvedValue({ id: 2, email: 'o@example.test', nickname: '운영자', role: 'OPERATOR' })
    fetchStudioTermsMock.mockResolvedValue({ agreed: true })

    renderStudio()

    expect(await screen.findByRole('heading', { name: '스튜디오' })).not.toBeNull()
  })

  it('should show the terms popup and not request the studio channel when the streamer has not agreed', async () => {
    fetchStudioTermsMock.mockResolvedValue({ agreed: false })

    renderStudio()

    expect(await screen.findByRole('dialog', { name: '약관 동의' })).not.toBeNull()
    expect(fetchMyChannelMock).not.toHaveBeenCalled()
  })

  it('should go to the home page when the popup is cancelled on /studio', async () => {
    fetchStudioTermsMock.mockResolvedValue({ agreed: false })
    renderStudio()

    fireEvent.click(await screen.findByRole('button', { name: '취소' }))

    expect(await screen.findByText('홈 화면')).not.toBeNull()
  })
})
