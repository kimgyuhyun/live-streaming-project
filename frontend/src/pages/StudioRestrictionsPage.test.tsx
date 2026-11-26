import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import StudioRestrictionsPage from './StudioRestrictionsPage.tsx'

afterEach(cleanup)

describe('StudioRestrictionsPage', () => {
  it('should show the restriction list first', () => {
    render(<StudioRestrictionsPage />)

    expect(screen.getByRole('tab', { name: '활동 제한' }).getAttribute('aria-selected')).toBe('true')
    expect(screen.getByText('등록된 사용자가 없습니다.')).not.toBeNull()
  })

  it('should show the release requests when that tab is chosen', () => {
    render(<StudioRestrictionsPage />)

    fireEvent.click(screen.getByRole('tab', { name: /활동 제한 해제 요청/ }))

    expect(screen.getByRole('tabpanel').textContent).toContain('해제 요청이 없습니다.')
  })

  it('should keep 추가 disabled until a nickname is typed', () => {
    render(<StudioRestrictionsPage />)
    const add = screen.getByRole('button', { name: '추가' }) as HTMLButtonElement
    expect(add.disabled).toBe(true)

    fireEvent.change(screen.getByLabelText('활동 제한 추가'), { target: { value: '시청자' } })

    expect(add.disabled).toBe(false)
  })
})
