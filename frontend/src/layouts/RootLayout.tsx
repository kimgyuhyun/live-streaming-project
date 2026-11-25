import { useEffect, useState } from 'react'
import { Outlet } from 'react-router'
import Header from '../components/Header.tsx'
import Sidebar from '../components/Sidebar.tsx'
import styles from './RootLayout.module.css'

// Sidebar.module.css의 브레이크포인트와 같아야 한다
const DESKTOP = '(min-width: 1024px)'

function RootLayout() {
  const [isDesktop, setIsDesktop] = useState(() => window.matchMedia(DESKTOP).matches)
  // 넓은 화면: 펼침/접힘. 좁은 화면: 덮는 메뉴 열림/닫힘
  const [collapsed, setCollapsed] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)

  useEffect(() => {
    const query = window.matchMedia(DESKTOP)
    const onChange = () => setIsDesktop(query.matches)
    query.addEventListener('change', onChange)
    return () => query.removeEventListener('change', onChange)
  }, [])

  function toggleMenu() {
    if (isDesktop) setCollapsed((c) => !c)
    else setDrawerOpen((o) => !o)
  }

  return (
    <>
      <Header menuExpanded={isDesktop ? !collapsed : drawerOpen} onMenuClick={toggleMenu} />
      <div className={styles.shell}>
        <Sidebar
          collapsed={isDesktop && collapsed}
          open={!isDesktop && drawerOpen}
          onClose={() => setDrawerOpen(false)}
        />
        <main className={styles.main}>
          <Outlet />
        </main>
      </div>
    </>
  )
}

export default RootLayout
