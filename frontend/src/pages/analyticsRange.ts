// 분석 화면의 조회 기간. 날짜는 <input type="date">와 같은 "YYYY-MM-DD"(내 컴퓨터 시간 기준)로 다룬다

export interface DateRange {
  from: string
  to: string
}

const DEFAULT_DAYS = 30

export function toDateInput(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

// 처음 보이는 기간: 오늘을 포함해 30일 전부터 오늘까지
export function defaultRange(today: Date): DateRange {
  const from = new Date(today)
  from.setDate(from.getDate() - DEFAULT_DAYS)
  return { from: toDateInput(from), to: toDateInput(today) }
}

// "2026-10-28" ~ "2026-11-27" → "2026.10.28. ~ 2026.11.27."
export function formatRange({ from, to }: DateRange): string {
  const dotted = (date: string) => `${date.replaceAll('-', '.')}.`
  return `${dotted(from)} ~ ${dotted(to)}`
}
