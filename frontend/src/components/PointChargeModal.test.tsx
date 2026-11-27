import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PointChargeModal from './PointChargeModal.tsx'

afterEach(cleanup)

function amountInput() {
  return screen.getByLabelText('충전할 포인트') as HTMLInputElement
}

describe('PointChargeModal', () => {
  it('should start with 1,000 points', () => {
    render(<PointChargeModal balance={0} onClose={() => {}} />)

    expect(amountInput().value).toBe('1,000')
  })

  it('should add the quick amount and show the balance after charging', () => {
    render(<PointChargeModal balance={2_000} onClose={() => {}} />)

    fireEvent.click(screen.getByRole('button', { name: '+1만' }))

    expect(amountInput().value).toBe('11,000')
    expect(screen.getByText('13,000P')).not.toBeNull()
  })

  it('should block charging more than 100,000 at once', () => {
    render(<PointChargeModal balance={0} onClose={() => {}} />)

    fireEvent.click(screen.getByRole('button', { name: '+10만' }))

    expect(screen.getByRole('alert').textContent).toBe('한 번에 100,000포인트까지 충전할 수 있습니다.')
    expect((screen.getByRole('button', { name: '포인트 충전하기' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('should show the charge notice from 안내보기', () => {
    render(<PointChargeModal balance={0} onClose={() => {}} />)

    fireEvent.click(screen.getByRole('button', { name: '안내보기' }))

    expect(screen.getByRole('region', { name: '포인트 충전 안내' }).textContent).toContain('1,000포인트 단위로 충전할 수 있습니다')
  })

  it('should close from the X button', () => {
    const onClose = vi.fn()
    render(<PointChargeModal balance={0} onClose={onClose} />)

    fireEvent.click(screen.getByRole('button', { name: '닫기' }))

    expect(onClose).toHaveBeenCalled()
  })
})
