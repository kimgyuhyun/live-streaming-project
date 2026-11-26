import { describe, expect, it } from 'vitest'
import { filterByTag, formatViewers, sortLives, type LiveSummary } from './sampleHome.ts'

function live(channelId: number, viewers: number, startedAt: string, tags: string[] = []): LiveSummary {
  return { channelId, title: '', channelName: '', viewers, startedAt, tags }
}

const EARLY = live(1, 100, '2026-11-27T08:00:00+09:00')
const LATE_SAME_VIEWERS = live(2, 100, '2026-11-27T12:00:00+09:00')
const POPULAR = live(3, 500, '2026-11-27T10:00:00+09:00')

describe('sortLives', () => {
  it('should put the most viewers first for 시청자순', () => {
    expect(sortLives([EARLY, POPULAR], 'viewers').map((l) => l.channelId)).toEqual([3, 1])
  })

  it('should put the earlier start first when viewers are equal', () => {
    expect(sortLives([LATE_SAME_VIEWERS, EARLY], 'viewers').map((l) => l.channelId)).toEqual([1, 2])
  })

  it('should put the fewest viewers first for 시청자역순', () => {
    expect(sortLives([POPULAR, EARLY], 'viewersAsc').map((l) => l.channelId)).toEqual([1, 3])
  })

  it('should put the latest start first for 최신순', () => {
    expect(sortLives([EARLY, POPULAR, LATE_SAME_VIEWERS], 'latest').map((l) => l.channelId)).toEqual([2, 3, 1])
  })

  it('should not change the original list', () => {
    const lives = [EARLY, POPULAR]

    sortLives(lives, 'viewers')

    expect(lives.map((l) => l.channelId)).toEqual([1, 3])
  })
})

describe('filterByTag', () => {
  const lives = [live(1, 1, EARLY.startedAt, ['OBS', '세팅']), live(2, 1, EARLY.startedAt, ['talk'])]

  it('should keep lives whose tag contains the keyword ignoring case', () => {
    expect(filterByTag(lives, ' obs ').map((l) => l.channelId)).toEqual([1])
  })

  it('should keep every live when the keyword is blank', () => {
    expect(filterByTag(lives, '  ')).toHaveLength(2)
  })
})

describe('formatViewers', () => {
  it('should show the full number with commas under 10,000', () => {
    expect(formatViewers(9088)).toBe('9,088명')
  })

  it('should show 만 from 10,000', () => {
    expect(formatViewers(10240)).toBe('1만명')
  })

  it('should keep one decimal place in 만', () => {
    expect(formatViewers(28310)).toBe('2.8만명')
  })
})
