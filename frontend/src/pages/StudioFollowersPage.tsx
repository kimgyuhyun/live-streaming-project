import { useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { fetchMyChannel } from '../api/channels.ts'
import { errorMessage } from '../api/client.ts'
import { SearchIcon, UsersIcon } from '../components/icons.tsx'
import { EmptyTable } from './StudioAnalytics.tsx'
import styles from './StudioViewers.module.css'

const COLUMNS = ['닉네임', '팔로우 등록일', '기간', '액션']

// 스튜디오 > 시청자 관리 > 팔로워. 팔로워 수만 실제 값이다(채널 응답의 followerCount).
// 팔로워 목록·닉네임 검색은 아직 목록 API가 없어 모양만 있다
function StudioFollowersPage() {
  const channel = useQuery({ queryKey: ['myChannel'], queryFn: fetchMyChannel })
  const [keyword, setKeyword] = useState('')

  if (channel.isPending) return null
  if (channel.isError) return <p role="alert">{errorMessage(channel.error)}</p>

  const { followerCount } = channel.data

  function handleSearch(e: FormEvent) {
    e.preventDefault()
  }

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>팔로워</h1>
      <div className={styles.card}>
        <p className={styles.summary}>
          <UsersIcon className={styles.summaryIcon} />
          내 채널 팔로워
          <span className={styles.summarySlash}>/</span>
          <span className={styles.summaryCount}>{followerCount.toLocaleString()}</span>
        </p>
        <form className={styles.search} role="search" onSubmit={handleSearch}>
          <div className={styles.searchField}>
            <SearchIcon className={styles.searchIcon} />
            <input
              className={styles.searchInput}
              aria-label="팔로워 닉네임"
              placeholder="닉네임을 입력해 주세요."
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
          </div>
          <button className={styles.secondaryButton} type="submit">
            검색
          </button>
        </form>
        <EmptyTable
          label="팔로워 목록"
          columns={COLUMNS}
          // 팔로워가 있는데 빈 목록을 "고요하다"고 하면 틀린 말이라 문구를 나눈다
          emptyText={followerCount === 0 ? '아직은 고요합니다..' : '팔로워 목록은 준비 중입니다.'}
        />
      </div>
    </div>
  )
}

export default StudioFollowersPage
