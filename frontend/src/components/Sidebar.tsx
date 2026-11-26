import type { ComponentType } from 'react'
import { NavLink } from 'react-router'
import { BroadcastIcon, GridIcon, HeartIcon } from './icons.tsx'
import styles from './Sidebar.module.css'

type Item = {
  label: string
  shortLabel: string
  Icon: ComponentType<{ className?: string }>
  to?: string
}

// 전체 방송만 이동한다. 나머지는 아직 기능이 없어 모양만 있다
const ITEMS: Item[] = [
  { label: '전체 방송', shortLabel: '방송', Icon: BroadcastIcon, to: '/lives' },
  { label: '카테고리', shortLabel: '카테고리', Icon: GridIcon },
  { label: '팔로잉', shortLabel: '팔로잉', Icon: HeartIcon },
]

type Props = {
  // 1024 이상에서 접힘
  collapsed: boolean
  // 1024 미만에서 열림
  open: boolean
  onClose: () => void
}

function Sidebar({ collapsed, open, onClose }: Props) {
  const className = [styles.sidebar, collapsed && styles.collapsed, open && styles.open].filter(Boolean).join(' ')

  return (
    <>
      {open && <button className={styles.backdrop} type="button" aria-label="메뉴 닫기" onClick={onClose} />}
      <aside className={className} aria-label="사이드 메뉴">
        <nav className={styles.list}>
          {ITEMS.map(({ label, shortLabel, Icon, to }) => {
            const content = (
              <>
                <Icon className={styles.icon} />
                <span className={styles.label}>{label}</span>
                <span className={styles.shortLabel}>{shortLabel}</span>
              </>
            )
            return to ? (
              <NavLink
                key={label}
                to={to}
                end
                className={({ isActive }) => `${styles.item} ${isActive ? styles.active : ''}`}
                onClick={onClose}
              >
                {content}
              </NavLink>
            ) : (
              <button key={label} className={styles.item} type="button">
                {content}
              </button>
            )
          })}
        </nav>
        {/* 아직 데이터(API)가 없다 */}
        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>인기 카테고리</h2>
          <p className={styles.empty}>아직 없습니다</p>
        </section>
      </aside>
    </>
  )
}

export default Sidebar
