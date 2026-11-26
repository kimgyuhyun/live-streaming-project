import { AnalyticsHeader, EmptyTable, StatGrid, type Stat } from './StudioAnalytics.tsx'
import styles from './StudioAnalytics.module.css'

// 동영상 업로드 기능이 아직 없어 모두 0이다
const STATS: Stat[] = [
  { label: '업로드한 동영상', value: '0', unit: '개' },
  { label: '재생수', value: '0', unit: '회' },
  { label: '시청자수', value: '0', unit: '명' },
  { label: '시청 시간', value: '00:00:00' },
  { label: '평균 시청 지속률', value: '0', unit: '%' },
]

const COLUMNS = ['영상', '재생수', '총 시청자', '총 시청 시간', '평균 시청 지속 시간']

// 스튜디오 > 분석 > 동영상 분석
function StudioVideoAnalyticsPage() {
  return (
    <div className={styles.page}>
      <AnalyticsHeader title="동영상 분석" />
      <div className={styles.card}>
        <StatGrid stats={STATS} />
        <EmptyTable label="동영상 목록" columns={COLUMNS} emptyText="동영상이 없습니다." />
      </div>
    </div>
  )
}

export default StudioVideoAnalyticsPage
