import { Outlet } from 'react-router'
import Header from '../components/Header.tsx'

function RootLayout() {
  return (
    <>
      <Header />
      <main>
        <Outlet />
      </main>
    </>
  )
}

export default RootLayout
