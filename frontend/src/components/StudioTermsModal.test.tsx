import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client.ts'
import { agreeStudioTerms } from '../api/studioTerms.ts'
import StudioTermsModal from './StudioTermsModal.tsx'

vi.mock('../api/studioTerms.ts', () => ({ agreeStudioTerms: vi.fn(), fetchStudioTerms: vi.fn() }))

const agreeMock = vi.mocked(agreeStudioTerms)

function renderModal() {
  const onClose = vi.fn()
  const onAgreed = vi.fn()
  render(
    <QueryClientProvider client={new QueryClient()}>
      <StudioTermsModal onClose={onClose} onAgreed={onAgreed} />
    </QueryClientProvider>,
  )
  return { onClose, onAgreed }
}

const allBox = () => screen.getByRole('checkbox', { name: '모두 동의하기' })
const studioBox = () => screen.getByRole('checkbox', { name: /스튜디오 이용약관/ })
const guidelineBox = () => screen.getByRole('checkbox', { name: /콘텐츠 가이드라인/ })
const joinButton = () => screen.getByRole('button', { name: '스튜디오 가입' })

beforeEach(() => {
  agreeMock.mockReset()
})

afterEach(cleanup)

describe('StudioTermsModal', () => {
  it('should check every item when 모두 동의하기 is clicked', () => {
    renderModal()

    fireEvent.click(allBox())

    expect([studioBox(), guidelineBox()].map((box) => (box as HTMLInputElement).checked)).toEqual([true, true])
  })

  it('should send both terms codes and call onAgreed when 스튜디오 가입 is clicked after checking both items', async () => {
    agreeMock.mockResolvedValue(undefined)
    const { onAgreed } = renderModal()
    fireEvent.click(studioBox())
    fireEvent.click(guidelineBox())

    fireEvent.click(joinButton())

    await waitFor(() => expect(onAgreed).toHaveBeenCalled())
    expect(agreeMock).toHaveBeenCalledWith(['STUDIO_TERMS', 'CONTENT_GUIDELINE'])
  })

  it('should show the terms text with its version when 자세히 보기 is clicked', () => {
    renderModal()

    fireEvent.click(screen.getAllByRole('button', { name: '자세히 보기' })[0])

    expect(screen.getByRole('region', { name: '스튜디오 이용약관' }).textContent).toContain('버전 1')
  })

  it('should keep 스튜디오 가입 disabled when only one of the two required items is checked', () => {
    renderModal()

    fireEvent.click(studioBox())

    expect((joinButton() as HTMLButtonElement).disabled).toBe(true)
  })

  it('should uncheck every item when 모두 동의하기 is clicked while all items are checked', () => {
    renderModal()
    fireEvent.click(allBox())

    fireEvent.click(allBox())

    expect([studioBox(), guidelineBox()].map((box) => (box as HTMLInputElement).checked)).toEqual([false, false])
  })

  it('should check 모두 동의하기 when both items are checked one by one', () => {
    renderModal()
    fireEvent.click(studioBox())

    fireEvent.click(guidelineBox())

    expect((allBox() as HTMLInputElement).checked).toBe(true)
  })

  it('should call onClose without saving when 취소 is clicked', () => {
    const { onClose } = renderModal()
    fireEvent.click(allBox())

    fireEvent.click(screen.getByRole('button', { name: '취소' }))

    expect([onClose.mock.calls.length, agreeMock.mock.calls.length]).toEqual([1, 0])
  })

  it('should call onClose without saving when the close button is clicked', () => {
    const { onClose } = renderModal()
    fireEvent.click(allBox())

    fireEvent.click(screen.getByRole('button', { name: '닫기' }))

    expect([onClose.mock.calls.length, agreeMock.mock.calls.length]).toEqual([1, 0])
  })

  it('should show the error message and stay open when saving the agreement fails', async () => {
    agreeMock.mockRejectedValue(new ApiError(500, { code: 'INTERNAL', message: '저장하지 못했습니다' }))
    const { onAgreed } = renderModal()
    fireEvent.click(allBox())

    fireEvent.click(joinButton())

    expect((await screen.findByRole('alert')).textContent).toBe('저장하지 못했습니다')
    expect(onAgreed).not.toHaveBeenCalled()
  })
})
