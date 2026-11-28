import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMyChannel, updateChannel, type ChannelResponse } from '../api/channels.ts'
import ChannelManagePage from './ChannelManagePage.tsx'

vi.mock('../api/channels.ts', () => ({ fetchMyChannel: vi.fn(), updateChannel: vi.fn() }))

const fetchMyChannelMock = vi.mocked(fetchMyChannel)
const updateChannelMock = vi.mocked(updateChannel)

const CHANNEL: ChannelResponse = {
  id: 7,
  name: '하늘',
  description: '안녕하세요',
  live: false,
  title: '하늘의 방송',
  demo: false,
  followerCount: 0,
  viewerCount: 0,
}

function renderPage() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ChannelManagePage />
    </QueryClientProvider>,
  )
}

// 위·아래에 같은 버튼이 있다. 위쪽 것을 누른다
function button(name: string) {
  return screen.getAllByRole('button', { name })[0] as HTMLButtonElement
}

beforeEach(() => {
  fetchMyChannelMock.mockReset().mockResolvedValue(CHANNEL)
  updateChannelMock.mockReset().mockResolvedValue({ ...CHANNEL, name: '새 이름' })
})

afterEach(cleanup)

describe('ChannelManagePage', () => {
  it('should keep 저장 disabled until something changes', async () => {
    renderPage()
    await screen.findByLabelText('채널 이름')

    expect(button('저장').disabled).toBe(true)
  })

  it('should save the channel name and description', async () => {
    renderPage()

    fireEvent.change(await screen.findByLabelText('채널 이름'), { target: { value: '새 이름' } })
    fireEvent.click(button('저장'))

    expect(await screen.findByText('저장했습니다')).not.toBeNull()
    expect(updateChannelMock).toHaveBeenCalledWith(7, { name: '새 이름', description: '안녕하세요' })
  })

  it('should restore the saved values on 취소', async () => {
    renderPage()
    const name = (await screen.findByLabelText('채널 이름')) as HTMLInputElement
    fireEvent.change(name, { target: { value: '바꾸는 중' } })

    fireEvent.click(button('취소'))

    expect(name.value).toBe('하늘')
  })
})
