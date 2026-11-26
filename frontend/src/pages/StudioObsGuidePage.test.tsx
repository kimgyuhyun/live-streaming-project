import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it } from 'vitest'
import StudioObsGuidePage from './StudioObsGuidePage.tsx'

function renderGuide() {
  render(
    <MemoryRouter>
      <StudioObsGuidePage />
    </MemoryRouter>,
  )
}

afterEach(cleanup)

describe('StudioObsGuidePage', () => {
  it('should show the NVIDIA NVENC settings first', () => {
    renderGuide()

    expect(screen.getByRole('tab', { name: 'NVIDIA NVENC' }).getAttribute('aria-selected')).toBe('true')
    expect(screen.getByRole('tabpanel', { name: 'NVIDIA NVENC' }).textContent).toContain('NVIDIA NVENC H.264')
  })

  it('should show the x264 options when the x264 tab is chosen', () => {
    renderGuide()

    fireEvent.click(screen.getByRole('tab', { name: 'x264' }))

    expect(screen.getByRole('tabpanel', { name: 'x264' }).textContent).toContain('scenecut=0 min-keyint=60')
  })

  it('should link the server and stream key step to 방송 관리 > 설정', () => {
    renderGuide()

    const links = screen.getAllByRole('link', { name: '방송 관리 > 설정' })
    expect(links[0].getAttribute('href')).toBe('/studio/broadcast/settings')
  })
})
