import { Outlet } from 'react-router'
import AccountMenu from '../components/AccountMenu.tsx'
import styles from './AccountLayout.module.css'

// 내 계정 화면(내 프로필·내 포인트)의 틀: 왼쪽 메뉴 카드 | 오른쪽 본문. 사이트 사이드바 대신 이 메뉴를 쓴다
// (router.tsx의 handle.hideSidebar로 RootLayout이 넓은 화면에서 사이트 사이드바를 뺀다)
function AccountLayout() {
  return (
    <div className={styles.account}>
      <aside className={styles.menu}>
        <AccountMenu />
      </aside>
      <div className={styles.content}>
        <Outlet />
      </div>
    </div>
  )
}

export default AccountLayout
