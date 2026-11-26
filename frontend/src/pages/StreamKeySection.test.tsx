import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchStreamKey, issueStreamKey } from '../api/streamKey.ts'
import StreamKeySection from './StreamKeySection.tsx'

vi.mock('../api/streamKey.ts', () => ({ fetchStreamKey: vi.fn(), issueStreamKey: vi.fn() }))

const fetchStreamKeyMock = vi.mocked(fetchStreamKey)
const issueStreamKeyMock = vi.mocked(issueStreamKey)
const writeText = vi.fn()

function renderSection() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <StreamKeySection channelId={7} live={false} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  fetchStreamKeyMock.mockReset().mockResolvedValue({ keyPrefix: 'abcd', obsServer: 'rtmp://localhost/live' })
  issueStreamKeyMock.mockReset().mockResolvedValue({
    streamKey: 'abcdNEWKEY',
    keyPrefix: 'abcd',
    obsServer: 'rtmp://localhost/live',
    obsStreamKey: '7?key=abcdNEWKEY',
  })
  writeText.mockReset().mockResolvedValue(undefined)
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
})

afterEach(cleanup)

describe('StreamKeySection', () => {
  it('should not let the key be revealed or copied when it was issued before this visit', async () => {
    renderSection()

    const copy = await screen.findByRole('button', { name: '스트림 키 복사' })

    expect((copy as HTMLButtonElement).disabled).toBe(true)
    expect((screen.getByRole('button', { name: '스트림 키 보기' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('should reveal the OBS stream key after reissuing', async () => {
    renderSection()

    fireEvent.click(await screen.findByRole('button', { name: '재발급' }))
    await screen.findByText(/한 번만 보여 줍니다/)
    fireEvent.click(screen.getByRole('button', { name: '스트림 키 보기' }))

    expect(screen.getByTestId('stream-key').textContent).toBe('7?key=abcdNEWKEY')
  })

  it('should copy the OBS stream key after reissuing', async () => {
    renderSection()

    fireEvent.click(await screen.findByRole('button', { name: '재발급' }))
    await screen.findByText(/한 번만 보여 줍니다/)
    fireEvent.click(screen.getByRole('button', { name: '스트림 키 복사' }))

    expect(writeText).toHaveBeenCalledWith('7?key=abcdNEWKEY')
  })

  it('should copy the stream URL', async () => {
    renderSection()

    fireEvent.click(await screen.findByRole('button', { name: '스트림 URL 복사' }))

    expect(writeText).toHaveBeenCalledWith('rtmp://localhost/live')
  })
})
