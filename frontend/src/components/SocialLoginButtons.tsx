import { useState } from 'react'
import googleIcon from '../assets/icons/social/google.svg'
import kakaoIcon from '../assets/icons/social/kakao.svg'
import naverIcon from '../assets/icons/social/naver.svg'
import styles from './SocialLoginButtons.module.css'

const PROVIDERS = [
  { name: '카카오', icon: kakaoIcon, className: styles.kakao },
  { name: '네이버', icon: naverIcon, className: styles.naver },
  { name: 'Google', icon: googleIcon, className: styles.google },
]

// 화면만 있다. 실제 소셜 로그인(OAuth)은 아직 기획 전이라 누르면 안내만 띄운다
function SocialLoginButtons() {
  const [notice, setNotice] = useState(false)

  return (
    <div className={styles.wrap}>
      <p className={styles.divider}>또는</p>
      <div className={styles.buttons}>
        {PROVIDERS.map(({ name, icon, className }) => (
          <button
            key={name}
            className={`${styles.button} ${className}`}
            type="button"
            aria-label={`${name}로 로그인`}
            title={`${name}로 로그인`}
            onClick={() => setNotice(true)}
          >
            <img className={styles.icon} src={icon} alt="" />
          </button>
        ))}
      </div>
      {notice && (
        <p className={styles.notice} role="status">
          소셜 로그인은 준비 중입니다
        </p>
      )}
    </div>
  )
}

export default SocialLoginButtons
