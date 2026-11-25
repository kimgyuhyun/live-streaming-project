import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { fetchMe } from '../api/members.ts'
import { BroadcastIcon, ChevronRightIcon, SettingsIcon, UserIcon, VideoIcon } from '../components/icons.tsx'
import styles from './MyChannelPage.module.css'

// 아직 올린 콘텐츠·팔로우·배너 기능이 없어 모든 탭이 빈 화면이다. 배너 설정하기는 모양만 있다
const TABS = ['홈', '동영상', '클립', '커뮤니티', '정보']

function MyChannelPage() {
  // RootLayout과 같은 쿼리를 쓴다. 401이면 비로그인으로 본다
  const me = useQuery({ queryKey: ['me'], queryFn: fetchMe, retry: false })
  const [tab, setTab] = useState(TABS[0])

  if (me.isPending) return null
  if (me.isError) {
    return (
      <p>
        <Link to="/login">로그인</Link>이 필요합니다
      </p>
    )
  }

  // 헤더의 스튜디오 버튼과 같은 조건(운영자도 편의상 보인다)
  const canStream = me.data.role === 'STREAMER' || me.data.role === 'OPERATOR'

  return (
    <div className={styles.page}>
      <section className={styles.profile}>
        <UserIcon className={styles.avatar} />
        <div className={styles.identity}>
          <h1 className={styles.nickname}>{me.data.nickname}</h1>
          <p className={styles.meta}>
            <span>팔로워 0명</span>
            <span aria-hidden="true">·</span>
            <Link className={styles.metaLink} to="/me/profile">
              프로필 보기
              <ChevronRightIcon className={styles.metaIcon} />
            </Link>
          </p>
        </div>
        {canStream && (
          <div className={styles.actions}>
            <Link className={styles.pillButton} to="/studio">
              <VideoIcon className={styles.pillIcon} />
              방송하기
            </Link>
            <Link className={styles.pillButton} to="/studio">
              <SettingsIcon className={styles.pillIcon} />
              채널 관리
            </Link>
          </div>
        )}
      </section>

      <div className={styles.tabs} role="tablist">
        {TABS.map((name) => (
          <button
            key={name}
            className={`${styles.tab} ${name === tab ? styles.tabActive : ''}`}
            type="button"
            role="tab"
            aria-selected={name === tab}
            onClick={() => setTab(name)}
          >
            {name}
          </button>
        ))}
      </div>

      <div className={styles.empty}>
        <BroadcastIcon className={styles.emptyIcon} />
        <p className={styles.emptyTitle}>등록된 콘텐츠가 없습니다.</p>
        <p className={styles.emptyText}>언젠간 {me.data.nickname}님을 만날 수 있겠죠?</p>
      </div>

      {tab === '홈' && (
        <div className={styles.banner}>
          <p>내 채널을 알리는 배너를 등록해보세요.</p>
          <button className={styles.bannerButton} type="button">
            배너 설정하기
          </button>
        </div>
      )}
    </div>
  )
}

export default MyChannelPage
