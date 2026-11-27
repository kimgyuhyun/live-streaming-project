import { useEffect, useMemo, useState } from 'react'
import { Outlet, useMatches } from 'react-router'
import Header from '../components/Header.tsx'
import Sidebar from '../components/Sidebar.tsx'
import styles from './RootLayout.module.css'

// Sidebar.module.css의 브레이크포인트와 같아야 한다
const DESKTOP = '(min-width: 1024px)'

// 페이지가 useOutletContext<LayoutContext>()로 받는다.
// theater: 넓은 화면. 켜면 사이드바를 숨겨 메인 칸이 그 자리까지 넓어진다(방송 화면이 켜고 끈다)
export type LayoutContext = {
  theater: boolean
  setTheater: (on: boolean) => void
}

// router.tsx의 경로에 붙이는 표시
type RouteHandle = { flush?: boolean; hideSidebar?: boolean }

function RootLayout() {
  const [isDesktop, setIsDesktop] = useState(() => window.matchMedia(DESKTOP).matches)
  // 넓은 화면: 펼침/접힘. 좁은 화면: 덮는 메뉴 열림/닫힘
  const [collapsed, setCollapsed] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const handles = useMatches().map((match) => match.handle as RouteHandle | undefined)
  // 방송 화면처럼 메인 칸을 여백 없이 다 쓰는 경로(router.tsx의 handle.flush)
  const flush = handles.some((handle) => handle?.flush)
  // 자기 메뉴 칸이 있는 경로(내 계정 화면, handle.hideSidebar). 넓은 화면에서만 사이트 사이드바를 뺀다.
  // 좁은 화면은 원래 사이드바를 ☰로 덮어 여니 그대로 둔다
  const hideSidebar = isDesktop && handles.some((handle) => handle?.hideSidebar)
  const [theater, setTheater] = useState(false)
  const layoutContext = useMemo<LayoutContext>(() => ({ theater, setTheater }), [theater])

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
        {!theater && !hideSidebar && (
          <Sidebar
            collapsed={isDesktop && collapsed}
            open={!isDesktop && drawerOpen}
            onClose={() => setDrawerOpen(false)}
          />
        )}
        <main className={`${styles.main} ${flush ? styles.flush : ''}`}>
          <Outlet context={layoutContext} />
        </main>
      </div>
    </>
  )
}

export default RootLayout
