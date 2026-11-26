import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { Link, MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it } from 'vitest'
import StudioSidebar from './StudioSidebar.tsx'

function renderSidebar(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <StudioSidebar toggled={false} onClose={() => {}} />
    </MemoryRouter>,
  )
}

afterEach(cleanup)

describe('StudioSidebar', () => {
  it('should show the 설정 link when 방송 관리 is opened', () => {
    renderSidebar('/studio')

    fireEvent.click(screen.getByRole('button', { name: '방송 관리' }))

    expect(screen.getByRole('link', { name: '설정' }).getAttribute('href')).toBe('/studio/broadcast/settings')
  })

  it('should start with 방송 관리 open when the current page is 방송 관리 > 설정', () => {
    renderSidebar('/studio/broadcast/settings')

    expect(screen.getByRole('button', { name: '방송 관리' }).getAttribute('aria-expanded')).toBe('true')
  })

  it('should open 방송 관리 when moving to 방송 관리 > 설정 from another studio page', () => {
    render(
      <MemoryRouter initialEntries={['/studio']}>
        <StudioSidebar toggled={false} onClose={() => {}} />
        <Link to="/studio/broadcast/settings">방송하기</Link>
      </MemoryRouter>,
    )

    fireEvent.click(screen.getByRole('link', { name: '방송하기' }))

    expect(screen.getByRole('button', { name: '방송 관리' }).getAttribute('aria-expanded')).toBe('true')
  })
})
