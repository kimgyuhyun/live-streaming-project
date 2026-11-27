import { describe, expect, it } from 'vitest'
import { formatPublished } from './sampleAccount.ts'

describe('formatPublished', () => {
  it('should show the date with dots', () => {
    expect(formatPublished('2026-11-24')).toBe('2026.11.24')
  })
})
