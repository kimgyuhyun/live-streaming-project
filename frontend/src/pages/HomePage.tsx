import type { ComponentType, ReactNode } from 'react'
import { Link } from 'react-router'
import {
  ChatIcon,
  DocumentIcon,
  GridIcon,
  HeartIcon,
  MonitorIcon,
  PencilIcon,
  SmileIcon,
  VolumeIcon,
} from '../components/icons.tsx'
import LiveCard from '../components/LiveCard.tsx'
import { SAMPLE_CATEGORIES, SAMPLE_LIVES, formatViewers, sortLives, type CategorySummary } from './sampleHome.ts'
import styles from './HomePage.module.css'

// 인기 LIVE에 보이는 개수(두 줄)
const POPULAR_COUNT = 8

// 메인 화면: 인기 LIVE(시청자 수가 많은 순, 확정 사양 9절) → 이런 카테고리는 어때요?
// 아직 예시 데이터라 카드는 눌러도 이동하지 않는다(예시 채널은 실제로 없다)
function HomePage() {
  const popular = sortLives(SAMPLE_LIVES, 'viewers').slice(0, POPULAR_COUNT)

  return (
    <div className={styles.page}>
      <h1 className={styles.srOnly}>메인</h1>
      <Section
        title="인기 LIVE"
        more={
          <Link className={styles.moreButton} to="/lives">
            전체보기
          </Link>
        }
      >
        <ul className={styles.liveGrid}>
          {popular.map((live) => (
            <li key={live.channelId}>
              <LiveCard live={live} />
            </li>
          ))}
        </ul>
      </Section>
      <Section
        title="이런 카테고리는 어때요?"
        more={
          // 모양만 있다: 카테고리 목록 화면이 아직 없다
          <button className={styles.moreButton} type="button">
            전체보기
          </button>
        }
      >
        <ul className={styles.categoryRow}>
          {SAMPLE_CATEGORIES.map((category) => (
            <li key={category.name}>
              <CategoryCard category={category} />
            </li>
          ))}
        </ul>
      </Section>
    </div>
  )
}

function Section({ title, more, children }: { title: string; more: ReactNode; children: ReactNode }) {
  return (
    <section className={styles.section} aria-label={title}>
      <div className={styles.sectionHead}>
        <h2 className={styles.sectionTitle}>{title}</h2>
        {more}
      </div>
      {children}
    </section>
  )
}

// 카테고리 그림 대신 선 아이콘을 둔다(게임 표지 같은 다른 회사 그림은 쓰지 않는다)
const CATEGORY_ICONS: Record<string, ComponentType<{ className?: string }>> = {
  talk: ChatIcon,
  게임: MonitorIcon,
  음악: VolumeIcon,
  그림: PencilIcon,
  운동: HeartIcon,
  공부: DocumentIcon,
  요리: SmileIcon,
}

function CategoryCard({ category }: { category: CategorySummary }) {
  const Icon = CATEGORY_ICONS[category.name] ?? GridIcon
  return (
    <article className={styles.categoryCard}>
      <div className={styles.poster}>
        <Icon className={styles.posterIcon} />
        <span className={styles.categoryBadge}>
          <span className={styles.liveDot} aria-hidden="true" />
          {formatViewers(category.viewers)}
        </span>
      </div>
      <h3 className={styles.categoryName}>{category.name}</h3>
      <p className={styles.liveCount}>라이브 {category.liveCount.toLocaleString('ko-KR')}개</p>
    </article>
  )
}

export default HomePage
