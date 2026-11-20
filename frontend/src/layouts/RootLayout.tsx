import { Link, Outlet } from 'react-router'

function RootLayout() {
  return (
    <>
      <header>
        <Link to="/">Live Streaming</Link>
      </header>
      <main>
        <Outlet />
      </main>
    </>
  )
}

export default RootLayout
