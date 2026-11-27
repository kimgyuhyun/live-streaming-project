import { describe, expect, it } from 'vitest'
import { chargeError, parseAmount } from './pointCharge.ts'

describe('chargeError', () => {
  it('should accept 1,000 to 100,000 in 1,000 units', () => {
    expect(chargeError(1_000, 0)).toBeNull()
    expect(chargeError(100_000, 0)).toBeNull()
  })

  it('should reject less than 1,000', () => {
    expect(chargeError(999, 0)).toBe('1,000포인트 이상 충전할 수 있습니다.')
  })

  it('should reject an amount that is not a 1,000 unit', () => {
    expect(chargeError(1_500, 0)).toBe('1,000포인트 단위로 충전할 수 있습니다.')
  })

  it('should reject more than 100,000 at once', () => {
    expect(chargeError(101_000, 0)).toBe('한 번에 100,000포인트까지 충전할 수 있습니다.')
  })

  it('should reject a charge that makes the balance exceed 1,000,000', () => {
    expect(chargeError(2_000, 999_000)).toBe('보유 포인트는 1,000,000포인트를 넘을 수 없습니다.')
  })

  it('should accept a charge that makes the balance exactly 1,000,000', () => {
    expect(chargeError(1_000, 999_000)).toBeNull()
  })
})

describe('parseAmount', () => {
  it('should ignore commas and other characters', () => {
    expect(parseAmount('12,000P')).toBe(12_000)
  })

  it('should be 0 when empty', () => {
    expect(parseAmount('')).toBe(0)
  })
})
