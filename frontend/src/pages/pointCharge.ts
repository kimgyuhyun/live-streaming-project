// 포인트 충전 규칙(확정 사양 8절). 서버도 같은 규칙으로 거부하지만, 화면에서 먼저 알려 준다

export const CHARGE_MIN = 1_000
export const CHARGE_MAX = 100_000
export const CHARGE_UNIT = 1_000
export const BALANCE_MAX = 1_000_000

// 문제가 없으면 null, 있으면 화면에 보일 문구
export function chargeError(amount: number, balance: number): string | null {
  if (amount < CHARGE_MIN) return '1,000포인트 이상 충전할 수 있습니다.'
  if (amount % CHARGE_UNIT !== 0) return '1,000포인트 단위로 충전할 수 있습니다.'
  if (amount > CHARGE_MAX) return '한 번에 100,000포인트까지 충전할 수 있습니다.'
  if (balance + amount > BALANCE_MAX) return '보유 포인트는 1,000,000포인트를 넘을 수 없습니다.'
  return null
}

// 입력칸 글자 → 숫자. 숫자가 아닌 글자(쉼표 등)는 버린다. 비면 0
export function parseAmount(text: string): number {
  const digits = text.replace(/\D/g, '')
  return digits === '' ? 0 : Number(digits)
}
