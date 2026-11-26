import { createBrowserRouter } from 'react-router'
import RootLayout from './layouts/RootLayout.tsx'
import StudioLayout from './layouts/StudioLayout.tsx'
import ChannelPage from './pages/ChannelPage.tsx'
import HomePage from './pages/HomePage.tsx'
import LoginPage from './pages/LoginPage.tsx'
import MyChannelPage from './pages/MyChannelPage.tsx'
import MyPage from './pages/MyPage.tsx'
import NotFoundPage from './pages/NotFoundPage.tsx'
import SignUpPage from './pages/SignUpPage.tsx'
import StudioDashboardPage from './pages/StudioDashboardPage.tsx'
import StudioObsGuidePage from './pages/StudioObsGuidePage.tsx'
import StudioSettingsPage from './pages/StudioSettingsPage.tsx'

export const router = createBrowserRouter([
  {
    element: <RootLayout />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'signup', element: <SignUpPage /> },
      { path: 'login', element: <LoginPage /> },
      { path: 'me', element: <MyChannelPage /> },
      { path: 'me/profile', element: <MyPage /> },
      // flush: 메인 칸의 안쪽 여백 없이 너비·높이를 다 쓰는 화면(RootLayout이 읽는다)
      { path: 'channels/:channelId', element: <ChannelPage />, handle: { flush: true } },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
  // 스튜디오는 사이트와 다른 헤더·사이드바를 쓴다
  {
    path: 'studio',
    element: <StudioLayout />,
    children: [
      { index: true, element: <StudioDashboardPage /> },
      { path: 'broadcast/settings', element: <StudioSettingsPage /> },
      { path: 'guide/obs', element: <StudioObsGuidePage /> },
    ],
  },
])
