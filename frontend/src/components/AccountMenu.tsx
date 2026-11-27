import type { ComponentType } from 'react'
import { NavLink } from 'react-router'
import {
  ExternalLinkIcon,
  HeartIcon,
  MonitorIcon,
  PersonIcon,
  RefreshIcon,
  VideoIcon,
  WalletIcon,
} from './icons.tsx'
import styles from './AccountMenu.module.css'
import { useStudioEntry } from './useStudioEntry.tsx'

type Item = {
  label: string
  Icon: ComponentType<{ className?: string }>
  // 있으면 이동한다. 없으면 아직 기능이 없어 모양만 있다
  to?: string
  // 스튜디오(약관 확인을 거쳐 연다)
  studio?: boolean
  // 다른 화면으로 나가는 메뉴(오른쪽 화살표 아이콘)
  external?: boolean
}

// 내 계정 화면의 왼쪽 메뉴. 내 프로필·스튜디오·내 채널·내 포인트만 이동한다.
// 최근 시청 영상·팔로잉 채널은 모양만 있다
const GROUPS: { title?: string; items: Item[] }[] = [
  { items: [{ label: '내 프로필', Icon: PersonIcon, to: '/me/profile' }] },
  {
    title: '조타',
    items: [
      { label: '조타 스튜디오', Icon: MonitorIcon, studio: true },
      { label: '내 채널', Icon: VideoIcon, to: '/me', external: true },
      { label: '내 포인트', Icon: WalletIcon, to: '/me/points' },
      { label: '최근 시청 영상', Icon: RefreshIcon },
      { label: '팔로잉 채널', Icon: HeartIcon },
    ],
  },
]

function AccountMenu() {
  // 약관에 동의하지 않았으면 이 화면에 동의 팝업을 띄운다(#35)
  const { enterStudio, termsModal } = useStudioEntry()

  return (
    <nav className={styles.menu} aria-label="내 계정 메뉴">
      {GROUPS.map((group, index) => (
        <div key={group.title ?? index} className={styles.group}>
          {group.title && <p className={styles.groupTitle}>{group.title}</p>}
          <ul className={styles.list}>
            {group.items.map((item) => (
              <li key={item.label}>
                <MenuItem item={item} onStudio={enterStudio} />
              </li>
            ))}
          </ul>
        </div>
      ))}
      {termsModal}
    </nav>
  )
}

function MenuItem({ item, onStudio }: { item: Item; onStudio: () => void }) {
  const { label, Icon, to, studio, external } = item
  const content = (
    <>
      <Icon className={styles.icon} />
      <span className={styles.label}>{label}</span>
      {external && <ExternalLinkIcon className={styles.externalIcon} />}
    </>
  )

  if (to) {
    return (
      <NavLink to={to} end className={({ isActive }) => `${styles.item} ${isActive ? styles.active : ''}`}>
        {content}
      </NavLink>
    )
  }
  return (
    <button className={styles.item} type="button" onClick={studio ? onStudio : undefined}>
      {content}
    </button>
  )
}

export default AccountMenu
