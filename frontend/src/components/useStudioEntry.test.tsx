import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client.ts'
import { agreeStudioTerms, fetchStudioTerms } from '../api/studioTerms.ts'
import { useStudioEntry } from './useStudioEntry.tsx'

vi.mock('../api/studioTerms.ts', () => ({ agreeStudioTerms: vi.fn(), fetchStudioTerms: vi.fn() }))

const fetchMock = vi.mocked(fetchStudioTerms)
const agreeMock = vi.mocked(agreeStudioTerms)

// 훅을 쓰는 버튼 하나. 헤더·내 채널 버튼과 같은 모양
function EntryButton() {
  const { enterStudio, termsModal } = useStudioEntry()
  return (
    <>
      <p>지금 화면</p>
      <button type="button" onClick={enterStudio}>
        스튜디오로
      </button>
      {termsModal}
    </>
  )
}

function renderEntry() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<EntryButton />} />
          <Route path="/studio" element={<p>스튜디오 화면</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  fetchMock.mockReset()
  agreeMock.mockReset()
})

afterEach(cleanup)

describe('useStudioEntry', () => {
  it('should go to /studio without the popup when the streamer has agreed', async () => {
    fetchMock.mockResolvedValue({ agreed: true })
    renderEntry()

    fireEvent.click(screen.getByRole('button', { name: '스튜디오로' }))

    expect(await screen.findByText('스튜디오 화면')).not.toBeNull()
  })

  it('should show the terms popup and stay on the page when the streamer has not agreed', async () => {
    fetchMock.mockResolvedValue({ agreed: false })
    renderEntry()

    fireEvent.click(screen.getByRole('button', { name: '스튜디오로' }))

    expect(await screen.findByRole('dialog', { name: '약관 동의' })).not.toBeNull()
    expect(screen.queryByText('지금 화면')).not.toBeNull()
  })

  it('should go to /studio after the streamer agrees in the popup', async () => {
    fetchMock.mockResolvedValue({ agreed: false })
    agreeMock.mockResolvedValue(undefined)
    renderEntry()
    fireEvent.click(screen.getByRole('button', { name: '스튜디오로' }))
    fireEvent.click(await screen.findByRole('checkbox', { name: '모두 동의하기' }))

    fireEvent.click(screen.getByRole('button', { name: '스튜디오 가입' }))

    expect(await screen.findByText('스튜디오 화면')).not.toBeNull()
  })

  it('should close the popup and stay on the page when 취소 is clicked', async () => {
    fetchMock.mockResolvedValue({ agreed: false })
    renderEntry()
    fireEvent.click(screen.getByRole('button', { name: '스튜디오로' }))

    fireEvent.click(await screen.findByRole('button', { name: '취소' }))

    expect([screen.queryByRole('dialog'), screen.queryByText('지금 화면') !== null]).toEqual([null, true])
  })

  it('should close the popup and stay on the page when the close button is clicked', async () => {
    fetchMock.mockResolvedValue({ agreed: false })
    renderEntry()
    fireEvent.click(screen.getByRole('button', { name: '스튜디오로' }))

    fireEvent.click(await screen.findByRole('button', { name: '닫기' }))

    expect([screen.queryByRole('dialog'), screen.queryByText('지금 화면') !== null]).toEqual([null, true])
  })

  it('should go to /studio without the popup when the terms check is rejected with 403', async () => {
    fetchMock.mockRejectedValue(new ApiError(403, { code: 'FORBIDDEN', message: '권한이 없습니다' }))
    renderEntry()

    fireEvent.click(screen.getByRole('button', { name: '스튜디오로' }))

    expect(await screen.findByText('스튜디오 화면')).not.toBeNull()
  })
})
