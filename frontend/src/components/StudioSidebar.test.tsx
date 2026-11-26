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

  it('should show the 방송하기 link above 설정 when 방송 관리 is opened', () => {
    renderSidebar('/studio')

    fireEvent.click(screen.getByRole('button', { name: '방송 관리' }))

    const links = screen.getAllByRole('link').map((link) => link.textContent)
    expect(screen.getByRole('link', { name: '방송하기' }).getAttribute('href')).toBe('/studio/broadcast/live')
    expect(links.indexOf('방송하기')).toBe(links.indexOf('설정') - 1)
  })

  it('should show the 라이브 분석 and 동영상 분석 links when 분석 is opened', () => {
    renderSidebar('/studio')

    fireEvent.click(screen.getByRole('button', { name: '분석' }))

    expect(screen.getByRole('link', { name: '라이브 분석' }).getAttribute('href')).toBe('/studio/analytics/live')
    expect(screen.getByRole('link', { name: '동영상 분석' }).getAttribute('href')).toBe('/studio/analytics/video')
  })

  it('should show the 팔로워 and 활동 제한 links when 시청자 관리 is opened', () => {
    renderSidebar('/studio')

    fireEvent.click(screen.getByRole('button', { name: '시청자 관리' }))

    expect(screen.getByRole('link', { name: '팔로워' }).getAttribute('href')).toBe('/studio/viewers/followers')
    expect(screen.getByRole('link', { name: '활동 제한' }).getAttribute('href')).toBe('/studio/viewers/restrictions')
  })

  it('should start with 방송 관리 open when the current page is 방송 관리 > 설정', () => {
    renderSidebar('/studio/broadcast/settings')

    expect(screen.getByRole('button', { name: '방송 관리' }).getAttribute('aria-expanded')).toBe('true')
  })

  it('should link the OBS 가이드 card to the OBS guide page', () => {
    renderSidebar('/studio')

    expect(screen.getByRole('link', { name: /OBS 가이드/ }).getAttribute('href')).toBe('/studio/guide/obs')
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
