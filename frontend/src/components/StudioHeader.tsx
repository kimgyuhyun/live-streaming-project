import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { fetchMe } from '../api/members.ts'
// 둥근 버튼·아이콘 버튼·프로필 메뉴는 사이트 헤더와 모양이 같다
import common from './Header.module.css'
import { BellIcon, HelpIcon, MenuIcon, PlusIcon, UserIcon } from './icons.tsx'
import styles from './StudioHeader.module.css'

// 스튜디오 헤더. 만들기·도움말·알림은 아직 기능이 없어 모양만 있다
function StudioHeader({ onMenuClick }: { onMenuClick: () => void }) {
  const me = useQuery({ queryKey: ['me'], queryFn: fetchMe, retry: false })
  const [menuOpen, setMenuOpen] = useState(false)
  const profileRef = useRef<HTMLDivElement>(null)

  // 프로필 메뉴 밖을 누르면 닫는다
  useEffect(() => {
    if (!menuOpen) return
    function closeOnOutside(e: MouseEvent) {
      if (!profileRef.current?.contains(e.target as Node)) setMenuOpen(false)
    }
    document.addEventListener('mousedown', closeOnOutside)
    return () => document.removeEventListener('mousedown', closeOnOutside)
  }, [menuOpen])

  return (
    <header className={styles.header}>
      <div className={styles.left}>
        <button className={common.iconButton} type="button" aria-label="메뉴" onClick={onMenuClick}>
          <MenuIcon className={common.icon} />
        </button>
        <Link className={styles.logo} to="/studio">
          조타 <span className={styles.studio}>STUDIO</span>
        </Link>
      </div>
      <div className={styles.right}>
        <button className={`${common.pillButton} ${styles.wide}`} type="button">
          <PlusIcon className={common.pillIcon} />
          만들기
        </button>
        <span className={`${styles.divider} ${styles.wide}`} aria-hidden="true" />
        <button className={common.iconButton} type="button" aria-label="도움말">
          <HelpIcon className={common.icon} />
        </button>
        <button className={common.iconButton} type="button" aria-label="알림">
          <BellIcon className={common.icon} />
        </button>
        {me.data && (
          <div className={common.profile} ref={profileRef}>
            <button
              className={common.avatar}
              type="button"
              aria-label="내 메뉴"
              aria-expanded={menuOpen}
              onClick={() => setMenuOpen((open) => !open)}
            >
              <UserIcon className={common.icon} />
            </button>
            {menuOpen && (
              <div className={common.menu}>
                <p className={common.menuName}>{me.data.nickname}</p>
                <Link className={common.menuItem} to="/">
                  조타 홈
                </Link>
                <Link className={common.menuItem} to="/me">
                  내 채널
                </Link>
              </div>
            )}
          </div>
        )}
      </div>
    </header>
  )
}

export default StudioHeader
