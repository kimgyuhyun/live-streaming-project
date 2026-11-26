import { describe, expect, it } from 'vitest'
import { defaultRange, formatRange } from './analyticsRange.ts'

describe('defaultRange', () => {
  it('should start 30 days before today and end today', () => {
    expect(defaultRange(new Date(2026, 10, 27))).toEqual({ from: '2026-10-28', to: '2026-11-27' })
  })

  it('should cross the year boundary', () => {
    expect(defaultRange(new Date(2027, 0, 10))).toEqual({ from: '2026-12-11', to: '2027-01-10' })
  })
})

describe('formatRange', () => {
  it('should show the range with dots', () => {
    expect(formatRange({ from: '2026-10-28', to: '2026-11-27' })).toBe('2026.10.28. ~ 2026.11.27.')
  })
})
