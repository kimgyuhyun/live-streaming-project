import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { fetchMe } from '../api/members.ts'
import { BroadcastIcon, ChevronRightIcon, SettingsIcon, UserIcon, VideoIcon } from '../components/icons.tsx'
import { useStudioEntry } from '../components/useStudioEntry.tsx'
import styles from './MyChannelPage.module.css'

// 아직 올린 콘텐츠·팔로우·배너 기능이 없어 모든 탭이 빈 화면이다. 배너 설정하기는 모양만 있다
const TABS = ['홈', '동영상', '커뮤니티', '정보']

function MyChannelPage() {
  // RootLayout과 같은 쿼리를 쓴다. 401이면 비로그인으로 본다
  const me = useQuery({ queryKey: ['me'], queryFn: fetchMe, retry: false })
  // ?tab=동영상처럼 들어오면 그 탭을 연 채로 시작한다(방송 화면의 "전체보기")
  const requestedTab = useSearchParams()[0].get('tab')
  const [tab, setTab] = useState(requestedTab && TABS.includes(requestedTab) ? requestedTab : TABS[0])
  // 약관에 동의하지 않았으면 이 화면에 동의 팝업을 띄운다(#35)
  const { enterStudio, termsModal } = useStudioEntry()

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
            <button className={styles.pillButton} type="button" onClick={enterStudio}>
              <VideoIcon className={styles.pillIcon} />
              방송하기
            </button>
            <button className={styles.pillButton} type="button" onClick={enterStudio}>
              <SettingsIcon className={styles.pillIcon} />
              채널 관리
            </button>
          </div>
        )}
        {termsModal}
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
