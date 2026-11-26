import { AnalyticsHeader, EmptyTable, StatGrid, type Stat } from './StudioAnalytics.tsx'
import styles from './StudioAnalytics.module.css'

// 아직 집계 API가 없어 모두 0이다. 진행한 라이브·라이브 시간은 broadcast 테이블(시작·종료 시각)로 셀 수 있다
const STATS: Stat[] = [
  { label: '진행한 라이브', value: '0', unit: '개' },
  { label: '라이브 시간', value: '00:00:00' },
  { label: '재생수', value: '0', unit: '회' },
  { label: '시청자수', value: '0', unit: '명' },
  { label: '시청 시간', value: '00:00:00' },
  { label: '최대 동시 시청자', value: '0', unit: '명' },
  { label: '평균 동시 시청자', value: '0', unit: '명' },
  { label: '평균 시청 지속률', value: '0', unit: '%' },
  { label: '채팅 참여자', value: '0', unit: '명 (0%)' },
  { label: '후원', sub: '총 0건', value: '0', unit: '원' },
]

const COLUMNS = [
  '영상',
  '재생수',
  '총 시청자',
  '최대 동시 시청자',
  '평균 동시 시청자',
  '총 시청 시간',
  '평균 시청 지속 시간',
  '채팅 참여',
  '후원',
]

// 스튜디오 > 분석 > 라이브 분석
function StudioLiveAnalyticsPage() {
  return (
    <div className={styles.page}>
      <AnalyticsHeader title="라이브 스트리밍 분석" />
      <div className={styles.card}>
        <StatGrid stats={STATS} />
        <EmptyTable label="라이브 방송 목록" columns={COLUMNS} emptyText="라이브 방송이 없습니다." />
      </div>
    </div>
  )
}

export default StudioLiveAnalyticsPage
