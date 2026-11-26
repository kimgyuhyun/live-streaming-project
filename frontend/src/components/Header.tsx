import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { logout } from '../api/auth.ts'
import { fetchMe } from '../api/members.ts'
import tip from '../styles/tooltip.module.css'
import styles from './Header.module.css'
import { useStudioEntry } from './useStudioEntry.tsx'
import {
  BellIcon,
  KeyIcon,
  MenuIcon,
  SearchIcon,
  SunIcon,
  UserIcon,
  VideoIcon,
  WalletIcon,
} from './icons.tsx'

// 검색·지갑·알림·키·테마는 아직 기능이 없어 모양만 있다

type Props = {
  // 사이드바가 펼쳐져(좁은 화면에서는 열려) 있는지. ☰의 안내 문구를 바꾼다
  menuExpanded: boolean
  onMenuClick: () => void
}

function Header({ menuExpanded, onMenuClick }: Props) {
  const queryClient = useQueryClient()
  // 401이면 비로그인으로 본다. 다시 시도해도 결과가 같다
  const me = useQuery({ queryKey: ['me'], queryFn: fetchMe, retry: false })
  const [menuOpen, setMenuOpen] = useState(false)
  const { enterStudio, termsModal } = useStudioEntry()
  const profileRef = useRef<HTMLDivElement>(null)

  const logoutMutation = useMutation({
    mutationFn: logout,
    onSuccess: () => {
      setMenuOpen(false)
      queryClient.resetQueries({ queryKey: ['me'] })
    },
  })

  // 프로필 메뉴 밖을 누르면 닫는다
  useEffect(() => {
    if (!menuOpen) return
    function closeOnOutside(e: MouseEvent) {
      if (!profileRef.current?.contains(e.target as Node)) setMenuOpen(false)
    }
    document.addEventListener('mousedown', closeOnOutside)
    return () => document.removeEventListener('mousedown', closeOnOutside)
  }, [menuOpen])

  function handleSearch(e: FormEvent) {
    e.preventDefault()
  }

  return (
    <header className={styles.header}>
      <div className={styles.left}>
        <button
          className={`${styles.iconButton} ${tip.tooltip} ${tip.alignStart}`}
          type="button"
          aria-label={menuExpanded ? '메뉴 접기' : '메뉴 펼치기'}
          data-tooltip={menuExpanded ? '메뉴 접기' : '메뉴 펼치기'}
          aria-expanded={menuExpanded}
          onClick={onMenuClick}
        >
          <MenuIcon className={styles.icon} />
        </button>
        <Link className={styles.logo} to="/">
          조타
        </Link>
      </div>

      <form className={styles.search} role="search" onSubmit={handleSearch}>
        <input
          className={styles.searchInput}
          type="search"
          placeholder="채널, 라이브, 동영상 검색"
          aria-label="검색"
        />
        <button className={styles.searchButton} type="submit" aria-label="검색">
          <SearchIcon className={styles.icon} />
        </button>
      </form>

      <div className={styles.right}>
        {me.data ? (
          <>
            {/* 운영자에게도 보인다(편의상). 스튜디오 화면과 API는 여전히 스트리머만 쓸 수 있다 */}
            {(me.data.role === 'STREAMER' || me.data.role === 'OPERATOR') && (
              // 약관에 동의하지 않았으면 이 화면에 동의 팝업을 띄운다(#35)
              <button className={styles.pillButton} type="button" onClick={enterStudio}>
                <VideoIcon className={styles.pillIcon} />
                스튜디오
              </button>
            )}
            {termsModal}
            <button className={`${styles.iconButton} ${styles.wide}`} type="button" aria-label="지갑">
              <WalletIcon className={styles.icon} />
            </button>
            <button className={`${styles.iconButton} ${styles.wide}`} type="button" aria-label="알림">
              <BellIcon className={styles.icon} />
            </button>
            <button className={`${styles.iconButton} ${styles.wide}`} type="button" aria-label="키">
              <KeyIcon className={styles.icon} />
            </button>
            <button className={`${styles.iconButton} ${styles.wide}`} type="button" aria-label="화면 테마">
              <SunIcon className={styles.icon} />
            </button>
            <div className={styles.profile} ref={profileRef}>
              <button
                className={styles.avatar}
                type="button"
                aria-label="내 메뉴"
                aria-expanded={menuOpen}
                onClick={() => setMenuOpen((open) => !open)}
              >
                <UserIcon className={styles.icon} />
              </button>
              {menuOpen && (
                <div className={styles.menu}>
                  <p className={styles.menuName}>{me.data.nickname}</p>
                  <Link className={styles.menuItem} to="/me" onClick={() => setMenuOpen(false)}>
                    내 채널
                  </Link>
                  <button
                    className={styles.menuItem}
                    type="button"
                    onClick={() => logoutMutation.mutate()}
                    disabled={logoutMutation.isPending}
                  >
                    로그아웃
                  </button>
                </div>
              )}
            </div>
          </>
        ) : (
          <>
            <button className={`${styles.iconButton} ${styles.wide}`} type="button" aria-label="화면 테마">
              <SunIcon className={styles.icon} />
            </button>
            <Link className={styles.pillButton} to="/login">
              로그인
            </Link>
          </>
        )}
      </div>
    </header>
  )
}

export default Header
