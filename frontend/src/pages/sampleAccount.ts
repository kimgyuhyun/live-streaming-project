// 내 계정 화면(최근 시청 영상·팔로잉 채널) 예시 데이터.
// 시청 기록·팔로잉 목록 API가 아직 없어 모양을 보이려고 넣었다. API가 생기면 이 파일을 지우고 응답으로 바꾼다
export interface WatchedVideo {
  id: number
  title: string
  channelName: string
  views: number
  durationSeconds: number
  publishedAt: string
}

export interface FollowingChannel {
  channelId: number
  name: string
  live: boolean
  // 팔로우한 시각(ISO). 같은 묶음 안에서 최근에 팔로우한 순으로 놓는다
  followedAt: string
}

export const SAMPLE_WATCHED: WatchedVideo[] = [
  { id: 1, title: '저지연 HLS 지연 시간 재 보기 · 3초 안에 보이나', channelName: '데모채널', views: 1265, durationSeconds: 34202, publishedAt: '2026-11-27' },
  { id: 2, title: '오늘은 OBS 설정 같이 해 봐요 · 키프레임 1초', channelName: '송출연구소', views: 118, durationSeconds: 25846, publishedAt: '2026-11-27' },
  { id: 3, title: '시청자가 몰려도 버티나? nginx 캐시 켜고 부하 걸기', channelName: '부하테스터', views: 181, durationSeconds: 61200, publishedAt: '2026-11-26' },
  { id: 4, title: '새벽 잡담 · 다음 주에 만들 기능 이야기', channelName: '하늘', views: 9624, durationSeconds: 29362, publishedAt: '2026-06-14' },
  { id: 5, title: '그림 그리면서 수다 떨기', channelName: '연필깎이', views: 28823, durationSeconds: 10635, publishedAt: '2026-06-15' },
  { id: 6, title: '피아노 연습 방송 · 신청곡 받아요', channelName: '도레미', views: 6220, durationSeconds: 23764, publishedAt: '2026-06-17' },
  { id: 7, title: '아침 운동 같이 해요 30분', channelName: '스쿼트왕', views: 21454, durationSeconds: 10318, publishedAt: '2026-05-15' },
  { id: 8, title: '자격증 공부 같이 해요 · 뽀모도로 25분', channelName: '공부하는곰', views: 31634, durationSeconds: 203, publishedAt: '2026-01-05' },
]

export const SAMPLE_FOLLOWING: FollowingChannel[] = [
  { channelId: 104, name: '하늘', live: false, followedAt: '2026-11-20T10:00:00+09:00' },
  { channelId: 102, name: '송출연구소', live: true, followedAt: '2026-11-02T10:00:00+09:00' },
  { channelId: 107, name: '도레미', live: false, followedAt: '2026-11-25T10:00:00+09:00' },
  { channelId: 101, name: '데모채널', live: true, followedAt: '2026-11-26T10:00:00+09:00' },
  { channelId: 113, name: '책갈피', live: false, followedAt: '2026-10-11T10:00:00+09:00' },
]

// 팔로잉 순서(확정 사양 7절): 방송 중인 채널 먼저, 그다음 오프라인. 각 묶음 안에서는 최근에 팔로우한 순
export function sortFollowing(channels: FollowingChannel[]): FollowingChannel[] {
  return [...channels].sort((a, b) => {
    if (a.live !== b.live) return a.live ? -1 : 1
    return Date.parse(b.followedAt) - Date.parse(a.followedAt)
  })
}

// "2026-11-24" → "2026.11.24"
export function formatPublished(isoDate: string): string {
  return isoDate.replaceAll('-', '.')
}
