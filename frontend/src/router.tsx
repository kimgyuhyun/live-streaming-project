import { createBrowserRouter } from 'react-router'
import AccountLayout from './layouts/AccountLayout.tsx'
import RootLayout from './layouts/RootLayout.tsx'
import StudioLayout from './layouts/StudioLayout.tsx'
import ChannelManagePage from './pages/ChannelManagePage.tsx'
import ChannelPage from './pages/ChannelPage.tsx'
import FollowingPage from './pages/FollowingPage.tsx'
import HomePage from './pages/HomePage.tsx'
import LivesPage from './pages/LivesPage.tsx'
import LoginPage from './pages/LoginPage.tsx'
import MyChannelPage from './pages/MyChannelPage.tsx'
import MyPage from './pages/MyPage.tsx'
import MyPointsPage from './pages/MyPointsPage.tsx'
import NotFoundPage from './pages/NotFoundPage.tsx'
import SignUpPage from './pages/SignUpPage.tsx'
import StudioBroadcastPage from './pages/StudioBroadcastPage.tsx'
import StudioDashboardPage from './pages/StudioDashboardPage.tsx'
import StudioFollowersPage from './pages/StudioFollowersPage.tsx'
import StudioLiveAnalyticsPage from './pages/StudioLiveAnalyticsPage.tsx'
import StudioObsGuidePage from './pages/StudioObsGuidePage.tsx'
import StudioRestrictionsPage from './pages/StudioRestrictionsPage.tsx'
import StudioSettingsPage from './pages/StudioSettingsPage.tsx'
import StudioVideoAnalyticsPage from './pages/StudioVideoAnalyticsPage.tsx'
import StudioVideosPage from './pages/StudioVideosPage.tsx'
import WatchHistoryPage from './pages/WatchHistoryPage.tsx'

export const router = createBrowserRouter([
  {
    element: <RootLayout />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'lives', element: <LivesPage /> },
      { path: 'signup', element: <SignUpPage /> },
      { path: 'login', element: <LoginPage /> },
      { path: 'me', element: <MyChannelPage /> },
      { path: 'me/manage', element: <ChannelManagePage /> },
      // 내 계정 화면: 사이트 사이드바 대신 왼쪽 내 계정 메뉴(AccountLayout)
      {
        element: <AccountLayout />,
        handle: { hideSidebar: true },
        children: [
          { path: 'me/profile', element: <MyPage /> },
          { path: 'me/points', element: <MyPointsPage /> },
          { path: 'me/history', element: <WatchHistoryPage /> },
          { path: 'me/following', element: <FollowingPage /> },
        ],
      },
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
      { path: 'broadcast/live', element: <StudioBroadcastPage /> },
      { path: 'broadcast/settings', element: <StudioSettingsPage /> },
      { path: 'analytics/live', element: <StudioLiveAnalyticsPage /> },
      { path: 'analytics/video', element: <StudioVideoAnalyticsPage /> },
      { path: 'viewers/followers', element: <StudioFollowersPage /> },
      { path: 'viewers/restrictions', element: <StudioRestrictionsPage /> },
      { path: 'content/videos', element: <StudioVideosPage /> },
      { path: 'guide/obs', element: <StudioObsGuidePage /> },
    ],
  },
])
