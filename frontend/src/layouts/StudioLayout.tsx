import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, Outlet, useNavigate } from 'react-router'
import { errorMessage } from '../api/client.ts'
import { fetchMe } from '../api/members.ts'
import { fetchStudioTerms } from '../api/studioTerms.ts'
import StudioHeader from '../components/StudioHeader.tsx'
import StudioSidebar from '../components/StudioSidebar.tsx'
import StudioTermsModal from '../components/StudioTermsModal.tsx'
import { STUDIO_TERMS_KEY } from '../components/useStudioEntry.tsx'
import styles from './StudioLayout.module.css'

// 스튜디오(/studio 아래)의 화면 틀. 사이트와 다른 헤더·사이드바를 쓴다
function StudioLayout() {
  // ☰를 누른 상태. 1024 이상에서는 사이드바를 숨기고, 1024 미만에서는 덮어 연다(StudioSidebar.module.css)
  const [toggled, setToggled] = useState(false)

  return (
    <>
      <StudioHeader onMenuClick={() => setToggled((t) => !t)} />
      <div className={styles.shell}>
        <StudioSidebar toggled={toggled} onClose={() => setToggled(false)} />
        <main className={styles.main}>
          <StudioGate />
        </main>
      </div>
    </>
  )
}

// 로그인한 스트리머(개발 편의상 운영자도)가 약관에 동의했을 때만 스튜디오 화면을 보인다
function StudioGate() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  // 사이트 헤더와 같은 쿼리를 쓴다. 401이면 비로그인으로 본다
  const me = useQuery({ queryKey: ['me'], queryFn: fetchMe, retry: false })
  const canUseStudio = me.data?.role === 'STREAMER' || me.data?.role === 'OPERATOR'
  // 약관에 동의하기 전에는 스튜디오 API가 403이다(#35). 동의 여부를 먼저 묻는다
  const terms = useQuery({ queryKey: STUDIO_TERMS_KEY, queryFn: fetchStudioTerms, enabled: canUseStudio })

  if (me.isPending) return null
  if (me.isError) {
    return (
      <p>
        <Link to="/login">로그인</Link>이 필요합니다
      </p>
    )
  }
  if (!canUseStudio) return <p>스트리머만 쓸 수 있습니다</p>
  if (terms.isPending) return null
  if (terms.isError) return <p role="alert">{errorMessage(terms.error)}</p>
  // 주소로 바로 들어와도 팝업을 띄운다. 취소하면 스튜디오에 남지 않고 홈으로 간다
  if (!terms.data.agreed) {
    return (
      <StudioTermsModal
        onClose={() => navigate('/')}
        onAgreed={() => queryClient.setQueryData(STUDIO_TERMS_KEY, { agreed: true })}
      />
    )
  }
  return <Outlet />
}

export default StudioLayout
