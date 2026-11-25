import type { ReactNode } from 'react'
import styles from './AuthCard.module.css'

type Props = {
  title: string
  children: ReactNode
  footer: ReactNode
}

// 로그인·가입 화면의 가운데 카드 틀
function AuthCard({ title, children, footer }: Props) {
  return (
    <div className={styles.page}>
      <section className={styles.card}>
        <h1 className={styles.title}>{title}</h1>
        {children}
        <p className={styles.footer}>{footer}</p>
      </section>
    </div>
  )
}

export default AuthCard
