import { describe, expect, it } from 'vitest'
import { formatPublished, sortFollowing, type FollowingChannel } from './sampleAccount.ts'

function channel(channelId: number, live: boolean, followedAt: string): FollowingChannel {
  return { channelId, name: '', live, followedAt }
}

describe('sortFollowing', () => {
  it('should put live channels before offline ones', () => {
    const sorted = sortFollowing([channel(1, false, '2026-11-27T00:00:00Z'), channel(2, true, '2026-01-01T00:00:00Z')])

    expect(sorted.map((c) => c.channelId)).toEqual([2, 1])
  })

  it('should put the most recently followed first within the same group', () => {
    const sorted = sortFollowing([channel(1, false, '2026-11-01T00:00:00Z'), channel(2, false, '2026-11-20T00:00:00Z')])

    expect(sorted.map((c) => c.channelId)).toEqual([2, 1])
  })
})

describe('formatPublished', () => {
  it('should show the date with dots', () => {
    expect(formatPublished('2026-11-24')).toBe('2026.11.24')
  })
})
