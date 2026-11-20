import { Link, Outlet } from 'react-router'

function RootLayout() {
  return (
    <>
      <header>
        <Link to="/">Live Streaming</Link> <Link to="/signup">가입</Link>
      </header>
      <main>
        <Outlet />
      </main>
    </>
  )
}

export default RootLayout
