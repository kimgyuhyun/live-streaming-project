import { createBrowserRouter } from 'react-router'
import RootLayout from './layouts/RootLayout.tsx'
import ChannelPage from './pages/ChannelPage.tsx'
import HomePage from './pages/HomePage.tsx'
import LoginPage from './pages/LoginPage.tsx'
import MyPage from './pages/MyPage.tsx'
import NotFoundPage from './pages/NotFoundPage.tsx'
import SignUpPage from './pages/SignUpPage.tsx'
import StudioPage from './pages/StudioPage.tsx'

export const router = createBrowserRouter([
  {
    element: <RootLayout />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'signup', element: <SignUpPage /> },
      { path: 'login', element: <LoginPage /> },
      { path: 'me', element: <MyPage /> },
      { path: 'channels/:channelId', element: <ChannelPage /> },
      { path: 'studio', element: <StudioPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])
