// 메인·전체 방송 화면 예시 데이터. 방송 목록·시청자 수·썸네일·카테고리 API가 아직 없어 모양을 보이려고 넣었다.
// 방송 목록 API(확정 사양 9절)가 생기면 이 파일의 예시를 지우고 API 응답으로 바꾼다(정렬은 서버가 한다)
export interface LiveSummary {
  channelId: number
  title: string
  channelName: string
  viewers: number
  // 방송을 시작한 시각(ISO). 최신순·같은 시청자 수의 순서에 쓴다
  startedAt: string
  tags: string[]
}

export interface CategorySummary {
  name: string
  viewers: number
  liveCount: number
}

export const SAMPLE_LIVES: LiveSummary[] = [
  { channelId: 101, title: '저지연 HLS로 3초 안에 보이나? 지연 시간 재 보기', channelName: '데모채널', viewers: 10378, startedAt: '2026-11-27T09:10:00+09:00', tags: ['데모', 'HLS'] },
  { channelId: 102, title: '오늘은 OBS 설정 같이 해 봐요 · 키프레임 1초', channelName: '송출연구소', viewers: 9088, startedAt: '2026-11-27T11:40:00+09:00', tags: ['OBS', '세팅'] },
  { channelId: 103, title: '시청자가 몰려도 버티나? nginx 캐시 켜고 부하 걸기', channelName: '부하테스터', viewers: 3705, startedAt: '2026-11-27T08:00:00+09:00', tags: ['부하 테스트', 'k6'] },
  { channelId: 104, title: '새벽 잡담 · 다음 주에 만들 기능 이야기', channelName: '하늘', viewers: 2879, startedAt: '2026-11-27T02:30:00+09:00', tags: ['잡담', 'talk'] },
  { channelId: 105, title: '주말 장시간 송출 안정성 테스트 8시간 도전', channelName: '오래켜기', viewers: 941, startedAt: '2026-11-27T06:00:00+09:00', tags: ['송출 테스트'] },
  { channelId: 106, title: '그림 그리면서 수다 떨기', channelName: '연필깎이', viewers: 627, startedAt: '2026-11-27T12:05:00+09:00', tags: ['그림', 'talk'] },
  { channelId: 107, title: '피아노 연습 방송 · 신청곡 받아요', channelName: '도레미', viewers: 393, startedAt: '2026-11-27T10:20:00+09:00', tags: ['음악', '피아노'] },
  { channelId: 108, title: '아침 운동 같이 해요 30분', channelName: '스쿼트왕', viewers: 274, startedAt: '2026-11-27T07:00:00+09:00', tags: ['운동'] },
  { channelId: 109, title: '자격증 공부 같이 해요 · 뽀모도로 25분', channelName: '공부하는곰', viewers: 269, startedAt: '2026-11-27T09:00:00+09:00', tags: ['공부', '캠스터디'] },
  { channelId: 110, title: '오늘 저녁은 김치찌개 끓여 봅니다', channelName: '주방장', viewers: 158, startedAt: '2026-11-27T12:30:00+09:00', tags: ['요리'] },
  { channelId: 111, title: '처음 해 보는 퍼즐 게임 · 막히면 도와주세요', channelName: '퍼즐러', viewers: 137, startedAt: '2026-11-27T11:00:00+09:00', tags: ['게임', '퍼즐'] },
  { channelId: 112, title: '기타 코드 연습 · 초보 환영', channelName: '여섯줄', viewers: 121, startedAt: '2026-11-27T10:45:00+09:00', tags: ['음악', '기타'] },
  { channelId: 113, title: '오늘 읽은 책 이야기', channelName: '책갈피', viewers: 117, startedAt: '2026-11-27T08:30:00+09:00', tags: ['talk', '책'] },
  { channelId: 114, title: '사이드 프로젝트 코딩 방송 · 스프링 부트', channelName: '개발일지', viewers: 113, startedAt: '2026-11-27T12:15:00+09:00', tags: ['코딩', '공부'] },
  { channelId: 115, title: '산책하면서 동네 구경', channelName: '걷는사람', viewers: 87, startedAt: '2026-11-27T12:40:00+09:00', tags: ['야외', 'talk'] },
]

export const SAMPLE_CATEGORIES: CategorySummary[] = [
  { name: 'talk', viewers: 10240, liveCount: 240 },
  { name: '게임', viewers: 28310, liveCount: 171 },
  { name: '음악', viewers: 5147, liveCount: 124 },
  { name: '그림', viewers: 1075, liveCount: 68 },
  { name: '운동', viewers: 5849, liveCount: 75 },
  { name: '공부', viewers: 15120, liveCount: 131 },
  { name: '요리', viewers: 1, liveCount: 1 },
]

// 시청자순 · 시청자역순 · 최신순(추천순은 하지 않는다)
export type LiveOrder = 'viewers' | 'viewersAsc' | 'latest'

// 시청자 수가 같으면 먼저 시작한 방송이 앞(확정 사양 9절). 원본 배열은 바꾸지 않는다
export function sortLives(lives: LiveSummary[], order: LiveOrder): LiveSummary[] {
  const started = (live: LiveSummary) => Date.parse(live.startedAt)
  return [...lives].sort((a, b) => {
    if (order === 'latest') return started(b) - started(a)
    const byViewers = order === 'viewers' ? b.viewers - a.viewers : a.viewers - b.viewers
    return byViewers !== 0 ? byViewers : started(a) - started(b)
  })
}

// 태그 검색: 태그 중 하나에 검색어가 들어가면 남긴다(대소문자 무시, 앞뒤 공백 무시). 검색어가 비면 모두
export function filterByTag(lives: LiveSummary[], keyword: string): LiveSummary[] {
  const word = keyword.trim().toLowerCase()
  if (word === '') return lives
  return lives.filter((live) => live.tags.some((tag) => tag.toLowerCase().includes(word)))
}

// 9088 → "9,088명", 10240 → "1만명", 28310 → "2.8만명"
export function formatViewers(n: number): string {
  if (n >= 10000) return `${Number((n / 10000).toFixed(1))}만명`
  return `${n.toLocaleString('ko-KR')}명`
}
