import { useState, type ComponentType } from 'react'
import { NavLink, useLocation } from 'react-router'
import {
  ChartIcon,
  ChevronDownIcon,
  CoinIcon,
  DashboardIcon,
  DocumentIcon,
  DownloadIcon,
  MegaphoneIcon,
  MonitorIcon,
  PersonIcon,
  ShieldIcon,
  UsersIcon,
} from './icons.tsx'
import styles from './StudioSidebar.module.css'

type Item = {
  label: string
  Icon: ComponentType<{ className?: string }>
  to?: string
  // 있으면 펼치는 메뉴(▾). 비어 있으면 아직 하위 메뉴가 없어 모양만 있다
  children?: { label: string; to: string }[]
}

// 대시보드·방송 관리 > 설정만 이동한다. 나머지는 아직 기능이 없어 모양만 있다
const ITEMS: Item[] = [
  { label: '대시보드', Icon: DashboardIcon, to: '/studio' },
  { label: '방송 관리', Icon: MonitorIcon, children: [{ label: '설정', to: '/studio/broadcast/settings' }] },
  { label: '분석', Icon: ChartIcon, children: [] },
  { label: '시청자 관리', Icon: UsersIcon, children: [] },
  { label: '콘텐츠 관리', Icon: DocumentIcon, children: [] },
  { label: '수익 관리', Icon: CoinIcon, children: [] },
  { label: '채널/권한 관리', Icon: ShieldIcon, children: [] },
  { label: '내정보', Icon: PersonIcon },
  // 새 공지 표시(N)는 공지사항 기능이 생기면 읽지 않은 공지가 있을 때만 붙인다
  { label: '공지사항', Icon: MegaphoneIcon },
]

// 방송 소프트웨어 안내. 아직 안내 글이 없어 모양만 있다
const GUIDES = [
  { name: 'OBS 가이드', description: '방송 기본 세팅', badge: 'OBS' },
]

function groupsContaining(pathname: string): string[] {
  return ITEMS.filter((item) => item.children?.some((child) => child.to === pathname)).map((item) => item.label)
}

type Props = {
  // 1024 미만에서 ☰로 연 메뉴를 닫는다(링크를 눌렀을 때, 뒤를 눌렀을 때)
  onClose: () => void
  // ☰를 누른 상태. 1024 이상에서는 숨김, 1024 미만에서는 열림
  toggled: boolean
}

function StudioSidebar({ onClose, toggled }: Props) {
  const { pathname } = useLocation()
  // 지금 화면이 들어 있는 메뉴는 펼친다. 처음 열 때와, 대시보드의 "방송하기"처럼 다른 곳에서 넘어왔을 때
  const [openGroups, setOpenGroups] = useState<string[]>(() => groupsContaining(pathname))
  const [prevPathname, setPrevPathname] = useState(pathname)
  if (pathname !== prevPathname) {
    setPrevPathname(pathname)
    setOpenGroups((open) => [...open, ...groupsContaining(pathname).filter((label) => !open.includes(label))])
  }

  function toggleGroup(label: string) {
    setOpenGroups((open) => (open.includes(label) ? open.filter((l) => l !== label) : [...open, label]))
  }

  return (
    <>
      {toggled && <button className={styles.backdrop} type="button" aria-label="메뉴 닫기" onClick={onClose} />}
      <aside className={`${styles.sidebar} ${toggled ? styles.toggled : ''}`} aria-label="스튜디오 메뉴">
        <nav className={styles.list}>
          {ITEMS.map(({ label, Icon, to, children }) => {
            const content = (
              <>
                <Icon className={styles.icon} />
                <span className={styles.label}>{label}</span>
              </>
            )
            if (to) {
              return (
                <NavLink
                  key={label}
                  to={to}
                  end
                  className={({ isActive }) => `${styles.item} ${isActive ? styles.active : ''}`}
                  onClick={onClose}
                >
                  {content}
                </NavLink>
              )
            }
            if (!children) {
              return (
                <button key={label} className={styles.item} type="button">
                  {content}
                </button>
              )
            }
            const open = openGroups.includes(label)
            return (
              <div key={label}>
                <button
                  className={styles.item}
                  type="button"
                  aria-expanded={open}
                  onClick={() => toggleGroup(label)}
                >
                  {content}
                  <ChevronDownIcon className={`${styles.chevron} ${open ? styles.chevronOpen : ''}`} />
                </button>
                {open && children.length > 0 && (
                  <div className={styles.children}>
                    {children.map((child) => (
                      <NavLink
                        key={child.to}
                        to={child.to}
                        className={({ isActive }) => `${styles.child} ${isActive ? styles.active : ''}`}
                        onClick={onClose}
                      >
                        {child.label}
                      </NavLink>
                    ))}
                  </div>
                )}
              </div>
            )
          })}
        </nav>
        <div className={styles.guides}>
          {GUIDES.map((guide) => (
            <button key={guide.name} className={styles.guide} type="button">
              <span className={styles.guideBadge} aria-hidden="true">
                {guide.badge}
              </span>
              <span className={styles.guideText}>
                <span className={styles.guideName}>{guide.name}</span>
                <span className={styles.guideDescription}>{guide.description}</span>
              </span>
              <DownloadIcon className={styles.guideIcon} />
            </button>
          ))}
        </div>
      </aside>
    </>
  )
}

export default StudioSidebar
