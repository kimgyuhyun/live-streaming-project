import { useState, type FormEvent } from 'react'
import { SearchIcon } from '../components/icons.tsx'
import { EmptyTable } from './StudioAnalytics.tsx'
// 화면 읽기 프로그램용 제목(srOnly)은 설정 화면 스타일을 같이 쓴다
import parts from './StudioSettingsPage.module.css'
import styles from './StudioViewers.module.css'

type Tab = 'restrictions' | 'requests'

const RESTRICTION_COLUMNS = ['활동 제한', '등록자', '등록일', '제한기간', '제한사유', '액션']
const REQUEST_COLUMNS = ['닉네임', '요청일', '요청 내용', '액션']

// 스튜디오 > 시청자 관리 > 활동 제한. 탭: 활동 제한 / 활동 제한 해제 요청.
// 채팅 기능이 아직 없어 추가·검색·목록 모두 모양만 있다(PRD 기본 범위 밖, 확장으로 만든다)
function StudioRestrictionsPage() {
  const [tab, setTab] = useState<Tab>('restrictions')

  return (
    <div className={styles.page}>
      <h1 className={parts.srOnly}>활동 제한</h1>
      <div className={styles.tabs} role="tablist" aria-label="활동 제한">
        <button
          className={`${styles.tab} ${tab === 'restrictions' ? styles.tabActive : ''}`}
          type="button"
          role="tab"
          id="tab-restrictions"
          aria-selected={tab === 'restrictions'}
          aria-controls="panel-restrictions"
          onClick={() => setTab('restrictions')}
        >
          활동 제한
        </button>
        <button
          className={`${styles.tab} ${tab === 'requests' ? styles.tabActive : ''}`}
          type="button"
          role="tab"
          id="tab-requests"
          aria-selected={tab === 'requests'}
          aria-controls="panel-requests"
          onClick={() => setTab('requests')}
        >
          활동 제한 해제 요청
          <span className={styles.badge}>0</span>
        </button>
      </div>

      {tab === 'restrictions' ? (
        <div className={styles.stack} role="tabpanel" id="panel-restrictions" aria-labelledby="tab-restrictions">
          <AddRestriction />
          <section className={styles.card} aria-label="활동 제한 목록">
            <div className={styles.listHead}>
              <h2 className={styles.listTitle}>
                활동 제한 목록<span className={styles.listCount}>0</span>
              </h2>
              <SearchForm label="활동 제한 닉네임" />
            </div>
            <EmptyTable label="활동 제한 목록" columns={RESTRICTION_COLUMNS} emptyText="등록된 사용자가 없습니다." />
          </section>
        </div>
      ) : (
        <section className={styles.card} role="tabpanel" id="panel-requests" aria-labelledby="tab-requests">
          <div className={styles.listHead}>
            <h2 className={styles.listTitle}>
              해제 요청 목록<span className={styles.listCount}>0</span>
            </h2>
            <SearchForm label="해제 요청 닉네임" />
          </div>
          <EmptyTable label="해제 요청 목록" columns={REQUEST_COLUMNS} emptyText="해제 요청이 없습니다." />
        </section>
      )}
    </div>
  )
}

// 모양만 있다: 제한 목록 API가 아직 없다
function AddRestriction() {
  const [nickname, setNickname] = useState('')

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
  }

  return (
    <form className={styles.card} onSubmit={handleSubmit}>
      <div className={styles.addRow}>
        <label className={styles.addLabel} htmlFor="restriction-nickname">
          활동 제한 추가
        </label>
        <div className={styles.addControl}>
          <div className={styles.addInline}>
            <input
              id="restriction-nickname"
              className={styles.addInput}
              placeholder="닉네임을 입력해주세요."
              value={nickname}
              onChange={(e) => setNickname(e.target.value)}
            />
            <button className={styles.secondaryButton} type="submit" disabled={nickname.trim() === ''}>
              추가
            </button>
          </div>
          <ul className={styles.notes}>
            <li>등록된 사용자는 내 채널 활동이 제한됩니다.</li>
          </ul>
        </div>
      </div>
    </form>
  )
}

// 모양만 있다: 목록 API가 아직 없다
function SearchForm({ label }: { label: string }) {
  const [keyword, setKeyword] = useState('')

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
  }

  return (
    <form className={styles.search} role="search" onSubmit={handleSubmit}>
      <div className={styles.searchField}>
        <SearchIcon className={styles.searchIcon} />
        <input
          className={styles.searchInput}
          aria-label={label}
          placeholder="닉네임을 입력해 주세요."
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
        />
      </div>
      <button className={styles.primaryButton} type="submit">
        검색
      </button>
    </form>
  )
}

export default StudioRestrictionsPage
