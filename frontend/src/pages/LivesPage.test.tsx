import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import LivesPage from './LivesPage.tsx'

afterEach(cleanup)

function titles() {
  return screen.getAllByRole('article').map((card) => card.querySelector('h3')?.textContent)
}

describe('LivesPage', () => {
  it('should sort by viewers first and offer no recommended order', () => {
    render(<LivesPage />)

    expect(screen.getByRole('button', { name: '시청자순' }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.queryByRole('button', { name: '추천순' })).toBeNull()
  })

  it('should put the latest broadcast first for 최신순', () => {
    render(<LivesPage />)

    fireEvent.click(screen.getByRole('button', { name: '최신순' }))

    expect(titles()[0]).toBe('산책하면서 동네 구경')
  })

  it('should keep only broadcasts with a matching tag', () => {
    render(<LivesPage />)

    fireEvent.change(screen.getByLabelText('태그 검색'), { target: { value: 'obs' } })

    expect(titles()).toEqual(['오늘은 OBS 설정 같이 해 봐요 · 키프레임 1초'])
  })

  it('should say so when no broadcast matches the tag', () => {
    render(<LivesPage />)

    fireEvent.change(screen.getByLabelText('태그 검색'), { target: { value: '없는태그' } })

    expect(screen.getByText('검색 결과가 없습니다.')).not.toBeNull()
  })
})
