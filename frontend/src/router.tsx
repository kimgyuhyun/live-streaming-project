import { createBrowserRouter } from 'react-router'
import RootLayout from './layouts/RootLayout.tsx'
import HomePage from './pages/HomePage.tsx'
import LoginPage from './pages/LoginPage.tsx'
import MyPage from './pages/MyPage.tsx'
import NotFoundPage from './pages/NotFoundPage.tsx'
import SignUpPage from './pages/SignUpPage.tsx'

export const router = createBrowserRouter([
  {
    element: <RootLayout />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'signup', element: <SignUpPage /> },
      { path: 'login', element: <LoginPage /> },
      { path: 'me', element: <MyPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])
