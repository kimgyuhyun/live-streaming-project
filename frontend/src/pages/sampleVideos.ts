// 예시 데이터. 다시보기(녹화)와 그 목록 API가 아직 없어 방송 화면의 "이 채널의 동영상" 모양을 보이려고 넣었다.
// 다시보기 API가 생기면 이 파일을 지우고 API 응답으로 바꾼다
export interface VideoSummary {
  id: number
  title: string
  // 다시보기 조회수
  views: number
  // 라이브였을 때 시청한 사람 수
  liveViewers: number
  durationSeconds: number
  publishedAt: string
  tags: string[]
}

export const SAMPLE_VIDEOS: VideoSummary[] = [
  { id: 1, title: '데모채널 첫 방송 다시보기 · 카운터 화면으로 송출 시작', views: 147, liveViewers: 3800, durationSeconds: 6070, publishedAt: '2026-11-24', tags: ['데모', '송출 테스트'] },
  { id: 2, title: 'OBS 키프레임 1초 vs 2초, 지연이 얼마나 다를까', views: 159, liveViewers: 1700, durationSeconds: 4691, publishedAt: '2026-11-23', tags: ['데모', 'OBS'] },
  { id: 3, title: '저지연 HLS 지연 시간 재 보기 (12:10 방송)', views: 298, liveViewers: 9000, durationSeconds: 11524, publishedAt: '2026-11-20', tags: ['데모', 'HLS'] },
  { id: 4, title: '시청자가 몰려도 버티나? nginx 1초 캐시 켜고 부하 걸기', views: 122, liveViewers: 1800, durationSeconds: 4582, publishedAt: '2026-11-19', tags: ['데모', '부하 테스트'] },
  { id: 5, title: '스트림 키 재발급하면 이전 송출이 끊기는지 시연', views: 86, liveViewers: 640, durationSeconds: 2410, publishedAt: '2026-11-18', tags: ['데모', '스트림 키'] },
  { id: 6, title: '새벽 잡담 방송 · 다음 주에 만들 기능 이야기', views: 412, liveViewers: 2300, durationSeconds: 7385, publishedAt: '2026-11-16', tags: ['데모', '잡담'] },
  { id: 7, title: '주말 장시간 송출 안정성 테스트 8시간', views: 233, liveViewers: 5100, durationSeconds: 28800, publishedAt: '2026-11-15', tags: ['데모', '송출 테스트'] },
  { id: 8, title: '채팅 기능 미리보기 방송', views: 64, liveViewers: 420, durationSeconds: 1830, publishedAt: '2026-11-13', tags: ['데모', '채팅'] },
  { id: 9, title: '데모 방송 하이라이트 모음', views: 978, liveViewers: 12400, durationSeconds: 3305, publishedAt: '2026-11-11', tags: ['데모', '하이라이트'] },
  { id: 10, title: '방송 화면 디자인 바꾼 날 · 치지직 벤치마킹', views: 51, liveViewers: 310, durationSeconds: 2741, publishedAt: '2026-11-10', tags: ['데모', '디자인'] },
]

// 3800 → "3.8천회", 9000 → "9천회", 12400 → "1.2만회", 640 → "640회"
export function formatCount(n: number): string {
  const short = (value: number, unit: string) => `${Number(value.toFixed(1))}${unit}회`
  if (n >= 10000) return short(n / 10000, '만')
  if (n >= 1000) return short(n / 1000, '천')
  return `${n}회`
}

// 6070 → "01:41:10", 1830 → "30:30"
export function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  const pad = (v: number) => String(v).padStart(2, '0')
  return h > 0 ? `${pad(h)}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`
}

// "2026-11-24" → "11.24"
export function formatDate(isoDate: string): string {
  const [, month, day] = isoDate.split('-')
  return `${month}.${day}`
}
