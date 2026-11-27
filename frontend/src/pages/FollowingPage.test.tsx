import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import FollowingPage from './FollowingPage.tsx'

afterEach(cleanup)

describe('FollowingPage', () => {
  it('should list live channels first, then the most recently followed', () => {
    render(<FollowingPage />)

    const names = screen.getAllByRole('listitem').map((item) => item.textContent?.replace('LIVE', ''))
    expect(names).toEqual(['데모채널', '송출연구소', '도레미', '하늘', '책갈피'])
  })
})
