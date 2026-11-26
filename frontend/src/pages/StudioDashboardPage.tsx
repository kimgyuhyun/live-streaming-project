import type { ComponentType } from 'react'
import { Link } from 'react-router'
import { DownloadIcon, FolderIcon, ImageIcon, PencilIcon, SettingsIcon } from '../components/icons.tsx'
import styles from './StudioDashboardPage.module.css'

const SETTINGS_PATH = '/studio/broadcast/settings'
const BROADCAST_PATH = '/studio/broadcast/live'

// 방송 소프트웨어는 각 회사의 공식 내려받기 페이지로 보낸다. 로고는 쓰지 않고 글자로 둔다
const SOFTWARE = [
  { name: 'Open Broadcaster Software', badge: 'OBS', href: 'https://obsproject.com/download' },
]

const BROADCAST_GUIDE: { title: string; description: string; Icon: ComponentType<{ className?: string }> }[] = [
  {
    title: '방송 제목',
    description: '매력적인 제목으로 시청자의 관심을 유도해보세요. 시청자가 방송을 찾을 때 사용할 만한 키워드를 넣는 것이 좋습니다.',
    Icon: PencilIcon,
  },
  {
    title: '카테고리',
    description: '시청자가 쉽게 찾을 수 있도록 진행중인 방송 카테고리를 추가하세요.',
    Icon: FolderIcon,
  },
  {
    title: '미리보기 이미지',
    description: '진행 중인 방송을 설명할 수 있는 사진을 업로드하세요. 시청자의 관심을 끄는 이미지가 좋습니다.',
    Icon: ImageIcon,
  },
]

// 스튜디오 첫 화면. 방송하기·방송 관리 > 설정만 이동한다.
// 동영상 업로드·기념일·채팅 설정·금칙어 설정은 아직 기능이 없어 모양만 있다. 공지사항은 아직 데이터(API)가 없다
function StudioDashboardPage() {
  return (
    <div className={styles.dashboard}>
      <div className={styles.grid}>
        <div className={styles.column}>
          <section className={styles.card}>
            <h2 className={styles.cardTitle}>
              <span className={styles.accent}>방송</span> 시작하기
            </h2>
            <ol className={styles.steps}>
              <li className={styles.step}>
                <p className={styles.stepTitle}>스트리밍 소프트웨어를 다운로드 하세요.</p>
                <div className={styles.software}>
                  {SOFTWARE.map((app) => (
                    <a key={app.name} className={styles.softwareLink} href={app.href} target="_blank" rel="noreferrer">
                      <span className={styles.softwareBadge} aria-hidden="true">
                        {app.badge}
                        <DownloadIcon className={styles.softwareDownload} />
                      </span>
                      {app.name}
                    </a>
                  ))}
                </div>
              </li>
              <li className={styles.step}>
                <p className={styles.stepTitle}>스트림 키를 소프트웨어에 붙여 넣어주세요.</p>
                <p className={styles.stepText}>
                  스트림 키는{' '}
                  <Link className={styles.link} to={SETTINGS_PATH}>
                    방송 관리 &gt; 설정
                  </Link>{' '}
                  에서 확인 가능합니다.
                </p>
              </li>
              <li className={styles.step}>
                <p className={styles.stepTitle}>스트리밍 소프트웨어에서 방송을 시작하면 라이브 방송이 진행됩니다.</p>
                <p className={styles.stepText}>방송 시작과 종료를 스트리밍 소프트웨어에서 진행해주세요.</p>
              </li>
            </ol>
            <Link className={styles.primaryButton} to={BROADCAST_PATH}>
              방송하기
            </Link>
          </section>

          <section className={styles.card}>
            <h2 className={styles.cardTitle}>
              <span className={styles.accent}>동영상</span> 업로드하기
            </h2>
            <p className={styles.lead}>내 동영상을 간편하게 업로드해보세요.</p>
            <p className={styles.text}>게시된 동영상이 얼마나 인기 있는지 분석해드립니다.</p>
            <button className={styles.primaryButton} type="button">
              동영상 업로드
            </button>
          </section>

          <section className={styles.card}>
            <h2 className={styles.cardTitle}>
              <span className={styles.accent}>기념일</span> 설정 안내
            </h2>
            <p className={styles.lead}>
              생일과 첫 방송일을 모두 함께 축하할 수 있도록 <span className={styles.accent}>기념일 정보</span>를
              입력해 주세요!
            </p>
            <button className={styles.primaryButton} type="button">
              기념일 등록하기
            </button>
          </section>
        </div>

        <div className={styles.column}>
          <section className={`${styles.card} ${styles.guideCard}`}>
            <h2 className={styles.cardTitle}>방송 정보 설정 가이드</h2>
            <ul className={styles.guideList}>
              {BROADCAST_GUIDE.map(({ title, description, Icon }) => (
                <li key={title} className={styles.guideItem}>
                  <span className={styles.guideIcon} aria-hidden="true">
                    <Icon className={styles.guideSvg} />
                  </span>
                  <div>
                    <p className={styles.guideTitle}>{title}</p>
                    <p className={styles.guideText}>{description}</p>
                  </div>
                </li>
              ))}
            </ul>
          </section>

          <section className={styles.card}>
            <h2 className={styles.cardTitle}>
              <span className={styles.accent}>채팅</span> 설정
            </h2>
            <p className={styles.lead}>내 방송 스타일에 맞게 채팅 기능을 설정할 수 있습니다.</p>
            <div className={styles.chatSettings}>
              <span className={styles.chatIcon} aria-hidden="true">
                <SettingsIcon className={styles.guideSvg} />
              </span>
              <div>
                <p className={styles.chatTitle}>설정 가능 항목</p>
                <ul className={styles.bullets}>
                  <li>채팅 규칙</li>
                  <li>금칙어 설정</li>
                </ul>
              </div>
            </div>
            <div className={styles.buttons}>
              <button className={styles.secondaryButton} type="button">
                채팅 설정
              </button>
              <button className={styles.secondaryButton} type="button">
                금칙어 설정
              </button>
            </div>
          </section>

          <section className={styles.card}>
            <h2 className={styles.cardTitle}>공지사항</h2>
            <p className={styles.text}>아직 공지사항이 없습니다</p>
          </section>
        </div>
      </div>
    </div>
  )
}

export default StudioDashboardPage
