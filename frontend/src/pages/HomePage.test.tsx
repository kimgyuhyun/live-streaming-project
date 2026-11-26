import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it } from 'vitest'
import HomePage from './HomePage.tsx'

afterEach(cleanup)

function renderPage() {
  render(
    <MemoryRouter>
      <HomePage />
    </MemoryRouter>,
  )
}

describe('HomePage', () => {
  it('should list 인기 LIVE with the most viewers first', () => {
    renderPage()

    const cards = within(screen.getByRole('region', { name: '인기 LIVE' })).getAllByRole('article')
    const viewers = cards.map((card) => card.textContent?.match(/LIVE([\d,.]+만?)명/)?.[1])
    expect(viewers.slice(0, 3)).toEqual(['1만', '9,088', '3,705'])
  })

  it('should link 인기 LIVE 전체보기 to the 전체 방송 page', () => {
    renderPage()

    const section = screen.getByRole('region', { name: '인기 LIVE' })
    expect(within(section).getByRole('link', { name: '전체보기' }).getAttribute('href')).toBe('/lives')
  })

  it('should show each category with its live count', () => {
    renderPage()

    const section = screen.getByRole('region', { name: '이런 카테고리는 어때요?' })
    expect(within(section).getByText('라이브 240개')).not.toBeNull()
  })
})
