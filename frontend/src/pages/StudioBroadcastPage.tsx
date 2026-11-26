import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { Link } from 'react-router'
import { fetchMyChannel, updateTitle } from '../api/channels.ts'
import { errorMessage } from '../api/client.ts'
import HelpTip from '../components/HelpTip.tsx'
import {
  BotIcon,
  ChartIcon,
  ChatIcon,
  ChevronRightIcon,
  GaugeIcon,
  MoreVerticalIcon,
  PlusIcon,
  SearchIcon,
  SmileIcon,
  UserOffIcon,
} from '../components/icons.tsx'
import form from '../styles/form.module.css'
import { hlsUrl } from './broadcastView.ts'
import { HlsPlayer } from './LivePlayer.tsx'
import styles from './StudioBroadcastPage.module.css'
// 버튼·스위치·라디오·안내 글은 설정 화면과 같은 모양을 쓴다
import parts from './StudioSettingsPage.module.css'

const OBS_GUIDE_PATH = '/studio/guide/obs'

// 스튜디오 > 방송 관리 > 방송하기. 왼쪽: 미리보기·스트림 상태·방송 정보 / 가운데: 채팅 / 오른쪽: 빠른 설정.
// 실제로 동작하는 것: 미리보기(송출 중이면 내 방송 재생), 상태(송출 여부), 팔로워 수, 방송 제목 저장.
// 나머지는 아직 데이터(API)가 없어 모양만 있다. 중간 광고는 광고 기능이 없어 넣지 않았다
function StudioBroadcastPage() {
  // 송출을 켜고 끈 뒤 몇 초 안에 미리보기·상태가 바뀌도록 5초마다 다시 읽는다
  const channel = useQuery({ queryKey: ['myChannel'], queryFn: fetchMyChannel, refetchInterval: 5000 })
  const [chatSettings, setChatSettings] = useState<ChatSettings>({
    audience: 'all',
    emoticonMode: false,
    slowMode: false,
  })

  if (channel.isPending) return null
  if (channel.isError) return <p role="alert">{errorMessage(channel.error)}</p>

  const { id, live, title, followerCount } = channel.data

  return (
    <div className={styles.page}>
      <h1 className={parts.srOnly}>방송하기</h1>
      <div className={styles.layout}>
        <div className={styles.main}>
          <section className={styles.preview} aria-label="방송 미리보기">
            {live ? (
              <HlsPlayer src={hlsUrl(id)} />
            ) : (
              <div className={styles.offline}>
                <p className={styles.offlineTitle}>라이브 스트리밍을 시작하려면 스트리밍 소프트웨어를 연결하세요.</p>
                <p className={styles.offlineText}>방송 시작 및 종료는 스트리밍 소프트웨어에서 가능합니다.</p>
                <Link className={styles.guideButton} to={OBS_GUIDE_PATH}>
                  스트리밍 설정 안내
                </Link>
              </div>
            )}
          </section>
          <p className={styles.statusBar}>
            <span className={`${styles.dot} ${live ? styles.dotLive : ''}`} aria-hidden="true" />
            {live ? '라이브' : '오프라인'}
          </p>

          <div className={styles.below}>
            <div className={styles.stats}>
              <StreamStatus channelId={id} live={live} />
              <LiveStats followerCount={followerCount} />
            </div>
            <BroadcastInfo title={title} />
          </div>
        </div>

        <StudioChat settings={chatSettings} />
        <QuickSettings settings={chatSettings} onChange={setChatSettings} />
      </div>
    </div>
  )
}

function Panel({ title, help, extra, children }: { title: string; help?: string; extra?: ReactNode; children: ReactNode }) {
  return (
    <section className={styles.panel} aria-label={title}>
      <div className={styles.panelHead}>
        <h2 className={styles.panelTitle}>
          {title}
          {help && <HelpTip label={title} text={help} />}
        </h2>
        {extra}
      </div>
      {children}
    </section>
  )
}

function Cell({ label, value, wide = false }: { label: string; value: ReactNode; wide?: boolean }) {
  return (
    <div className={`${styles.cell} ${wide ? styles.cellWide : ''}`}>
      <dt className={styles.cellLabel}>{label}</dt>
      <dd className={styles.cellValue}>{value}</dd>
    </div>
  )
}

// 상태만 실제 값이다. 방송 시간·해상도·비트레이트·FPS는 아직 받아 오는 API가 없다
function StreamStatus({ channelId, live }: { channelId: number; live: boolean }) {
  return (
    <Panel
      title="스트림 상태"
      help="스트리밍 소프트웨어에서 들어오는 방송 상태입니다"
      extra={
        <Link className={styles.headLink} to={`/channels/${channelId}`}>
          내 방송 보기
          <ChevronRightIcon className={styles.headLinkIcon} />
        </Link>
      }
    >
      <dl className={styles.cells}>
        <Cell label="상태" value={live ? '라이브' : '오프라인'} />
        <Cell label="방송 시간" value="-" />
        <Cell label="해상도" value="-" />
        <Cell label="비트레이트" value="-" />
        <Cell label="FPS" value="-" wide />
      </dl>
    </Panel>
  )
}

// 방송 화면에 통계가 그대로 비치지 않게 "정보 보기"를 켰을 때만 값을 보인다.
// 팔로워 수만 실제 값이다. 시청자·채팅·구독·후원 수는 아직 집계하지 않는다
function LiveStats({ followerCount }: { followerCount: number }) {
  const [shown, setShown] = useState(false)
  const value = (v: string) => (shown ? v : '•')

  return (
    <Panel
      title="실시간 통계"
      help="정보 보기를 켜면 숫자가 보입니다. 방송 화면에 통계가 비치지 않게 기본으로 숨깁니다"
      extra={
        <label className={styles.headSwitch}>
          정보 보기
          <input className={parts.switch} type="checkbox" role="switch" checked={shown} onChange={(e) => setShown(e.target.checked)} />
        </label>
      }
    >
      <dl className={`${styles.cells} ${shown ? '' : styles.cellsHidden}`}>
        <Cell label="동시 시청자" value={value('-')} />
        <Cell label="최고 동시 시청자" value={value('-')} />
        <Cell label="평균 동시 시청자" value={value('-')} />
        <Cell label="채팅 참여자" value={value('-')} />
        <Cell label="팔로워수" value={value(followerCount.toLocaleString())} />
        <Cell label="구독수" value={value('-')} />
        <Cell label="후원수" value={value('-')} wide />
      </dl>
    </Panel>
  )
}

function Row({ label, htmlFor, help, required = false, children }: {
  label: string
  htmlFor?: string
  help?: string
  required?: boolean
  children: ReactNode
}) {
  const name = (
    <>
      {label}
      {required && <span className={styles.required}>*</span>}
      {help && <HelpTip label={label} text={help} />}
    </>
  )
  return (
    <div className={styles.row}>
      {htmlFor ? (
        <label className={styles.label} htmlFor={htmlFor}>
          {name}
        </label>
      ) : (
        <span className={styles.label}>{name}</span>
      )}
      <div className={parts.control}>{children}</div>
    </div>
  )
}

function Radios<T extends string>({ name, options, value, onChange }: {
  name: string
  options: readonly { value: T; label: string }[]
  value: T
  onChange: (value: T) => void
}) {
  return (
    <div className={parts.choices} role="radiogroup" aria-label={name}>
      {options.map((option) => (
        <label key={option.value} className={parts.choice}>
          <input type="radio" name={name} checked={value === option.value} onChange={() => onChange(option.value)} />
          {option.label}
        </label>
      ))}
    </div>
  )
}

// 방송 제목만 저장한다(1~50자). 나머지 칸은 아직 저장할 API가 없어 모양만 있다
function BroadcastInfo({ title: savedTitle }: { title: string }) {
  const queryClient = useQueryClient()
  // 입력란은 처음 읽은 값으로 채운다. 저장하면 입력한 값이 곧 저장된 값이다
  const [title, setTitle] = useState(savedTitle)
  const mutation = useMutation({
    mutationFn: () => updateTitle(title),
    onSuccess: (channel) =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['myChannel'] }),
        queryClient.invalidateQueries({ queryKey: ['channel', channel.id] }),
      ]),
  })
  const [category, setCategory] = useState('')
  const [tag, setTag] = useState('')
  const [adult, setAdult] = useState(false)
  const [country, setCountry] = useState<'all' | 'kr'>('all')
  const [paidPromotion, setPaidPromotion] = useState(false)
  const [replay, setReplay] = useState<'review' | 'auto' | 'none'>('review')

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    mutation.mutate()
  }

  return (
    <div className={styles.info}>
      <form className={styles.row} onSubmit={handleSubmit}>
        <label className={styles.label} htmlFor="broadcast-title">
          방송 제목<span className={styles.required}>*</span>
        </label>
        <div className={parts.control}>
          <div className={parts.inline}>
            <input
              id="broadcast-title"
              className={`${form.input} ${parts.grow}`}
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              maxLength={50}
              required
            />
            <button className={parts.primaryButton} type="submit" disabled={mutation.isPending}>
              저장
            </button>
          </div>
          {mutation.isError && (
            <p className={parts.error} role="alert">
              {errorMessage(mutation.error)}
            </p>
          )}
          {mutation.isSuccess && (
            <p className={parts.status} role="status">
              저장했습니다
            </p>
          )}
        </div>
      </form>

      <Row label="카테고리" htmlFor="broadcast-category" help="방송 중인 게임이나 주제를 고르면 시청자가 찾기 쉬워집니다">
        <div className={styles.searchField}>
          <SearchIcon className={styles.searchIcon} />
          <input
            id="broadcast-category"
            className={styles.searchInput}
            placeholder="카테고리 검색"
            value={category}
            onChange={(e) => setCategory(e.target.value)}
          />
        </div>
      </Row>

      <Row label="태그 (최대 5개)" htmlFor="broadcast-tag">
        <div className={parts.inline}>
          <div className={`${styles.searchField} ${parts.grow}`}>
            <SearchIcon className={styles.searchIcon} />
            <input
              id="broadcast-tag"
              className={styles.searchInput}
              placeholder="태그 입력 후 Enter 또는 추가 버튼을 클릭해주세요."
              maxLength={15}
              value={tag}
              onChange={(e) => setTag(e.target.value)}
            />
          </div>
          {/* 모양만 있다: 태그 저장 API가 아직 없다 */}
          <button className={parts.secondaryButton} type="button" disabled={tag.trim() === ''}>
            추가
          </button>
        </div>
        <ul className={parts.notes}>
          <li>공백 및 특수 문자 없이 15자까지 입력할 수 있습니다.</li>
          <li>등록한 순서대로 방송 정보에 노출됩니다.</li>
        </ul>
      </Row>

      <Row label="미리보기 이미지" help="방송 목록에 보이는 대표 이미지입니다">
        {/* 모양만 있다: 이미지 업로드 API가 아직 없다 */}
        <button className={styles.upload} type="button">
          <PlusIcon className={styles.uploadIcon} />
          <span className={styles.uploadLabel}>업로드</span>
          <span className={styles.uploadSize}>(1280x720)</span>
        </button>
        <ul className={parts.notes}>
          <li>등록하지 않으면 방송 중인 화면으로 썸네일이 노출됩니다.</li>
        </ul>
      </Row>

      <Row label="연령 제한" help="19세 미만 시청자는 방송을 볼 수 없게 됩니다">
        <label className={parts.choice}>
          <input type="checkbox" checked={adult} onChange={(e) => setAdult(e.target.checked)} />
          시청자를 19세로 제한하겠습니다.
        </label>
        <ul className={parts.notes}>
          <li>일부 시청자에게 적합하지 않은 콘텐츠가 있다면 설정이 필요합니다.</li>
          <li>미성년자 회원은 19세 이상 방송을 진행할 수 없습니다.</li>
        </ul>
      </Row>

      <Row label="시청 국가 설정" help="방송을 볼 수 있는 국가를 정합니다">
        <Radios name="시청 국가" options={COUNTRIES} value={country} onChange={setCountry} />
      </Row>

      <Row label="유료 프로모션" help="광고·협찬을 받은 방송이면 시청자에게 알립니다">
        <label className={parts.choice}>
          <input type="checkbox" checked={paidPromotion} onChange={(e) => setPaidPromotion(e.target.checked)} />
          이 방송에 간접 광고, 협찬 등 제3자로부터 어떤 형태로든 대가를 받은 유료 프로모션이 포함되어 있습니다.
        </label>
      </Row>

      <Row label="다시보기 설정" help="방송이 끝난 뒤 다시보기 영상을 어떻게 올릴지 정합니다">
        <Radios name="다시보기 설정" options={REPLAYS} value={replay} onChange={setReplay} />
        <ul className={parts.notes}>
          <li>다시보기 영상이 만들어지면 확인 후 게시 여부를 선택할 수 있습니다.</li>
          <li>게시되지 않은 영상은 2일간 보관되고 이후 삭제됩니다.</li>
        </ul>
      </Row>
    </div>
  )
}

const COUNTRIES = [
  { value: 'all', label: '모든 국가 허용' },
  { value: 'kr', label: '한국만 허용' },
] as const

const REPLAYS = [
  { value: 'review', label: '확인 후 게시' },
  { value: 'auto', label: '자동 게시' },
  { value: 'none', label: '다시보기 없음' },
] as const

type Audience = 'all' | 'followers' | 'managers'

// 채팅 ⋮ 메뉴와 빠른 설정이 같이 보여 주는 값. 저장할 API가 아직 없어 이 화면에만 남는다
type ChatSettings = { audience: Audience; emoticonMode: boolean; slowMode: boolean }

const AUDIENCE_LABEL: Record<Audience, string> = { all: '모든 사용자', followers: '팔로워', managers: '운영자' }

// 채팅은 아직 기능이 없어 모양만 있다(방송 화면의 채팅 칸과 같다)
function StudioChat({ settings }: { settings: ChatSettings }) {
  return (
    <section className={`${styles.panel} ${styles.chat}`} aria-label="채팅">
      <div className={styles.panelHead}>
        <h2 className={styles.panelTitle}>채팅</h2>
        <ChatMenu settings={settings} />
      </div>
      <p className={styles.empty}>아직 채팅이 없습니다</p>
    </section>
  )
}

// ⋮를 누르면 아래로 펼치는 채팅 설정 메뉴. 밖을 누르거나 Esc로 닫는다.
// 각 줄은 아직 하위 화면이 없어 모양만 있다. 클린봇·활동 제한·후원 랭킹 값은 고정 글자다
function ChatMenu({ settings }: { settings: ChatSettings }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    function closeOnOutside(e: MouseEvent) {
      if (!ref.current?.contains(e.target as Node)) setOpen(false)
    }
    function closeOnEscape(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', closeOnOutside)
    document.addEventListener('keydown', closeOnEscape)
    return () => {
      document.removeEventListener('mousedown', closeOnOutside)
      document.removeEventListener('keydown', closeOnEscape)
    }
  }, [open])

  const items = [
    { label: '클린봇', value: '켜짐', Icon: BotIcon, more: false },
    { label: '채팅 이용 권한', value: AUDIENCE_LABEL[settings.audience], Icon: ChatIcon, more: true },
    { label: '이모티콘 모드', value: settings.emoticonMode ? '사용' : '사용 안 함', Icon: SmileIcon, more: true },
    { label: '저속 모드', value: settings.slowMode ? '사용' : '사용 안 함', Icon: GaugeIcon, more: true },
    { label: '활동 제한 해제 요청', value: '0건', Icon: UserOffIcon, more: false },
    { label: '주간 후원 랭킹', value: '노출', Icon: ChartIcon, more: true },
  ]

  return (
    <div className={styles.menuWrap} ref={ref}>
      <button
        className={styles.iconButton}
        type="button"
        aria-label="채팅 설정"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
      >
        <MoreVerticalIcon className={styles.iconSvg} />
      </button>
      {open && (
        <ul className={styles.menu} role="menu" aria-label="채팅 설정">
          {items.map(({ label, value, Icon, more }) => (
            <li key={label} role="none">
              <button className={styles.menuItem} type="button" role="menuitem">
                <Icon className={styles.menuIcon} />
                <span className={styles.menuLabel}>{label}</span>
                <span className={styles.menuValue}>{value}</span>
                {more && <ChevronRightIcon className={styles.menuChevron} />}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

// 설정 화면의 채팅 설정을 방송 중에 빨리 바꾸는 칸. 저장할 API가 아직 없어 모양만 있다
function QuickSettings({ settings, onChange }: { settings: ChatSettings; onChange: (settings: ChatSettings) => void }) {
  const [firstChatHighlight, setFirstChatHighlight] = useState(true)

  const switches = [
    {
      label: '이모티콘 모드',
      checked: settings.emoticonMode,
      onChange: (emoticonMode: boolean) => onChange({ ...settings, emoticonMode }),
    },
    { label: '저속 모드', checked: settings.slowMode, onChange: (slowMode: boolean) => onChange({ ...settings, slowMode }) },
    { label: '첫 채팅 하이라이트', checked: firstChatHighlight, onChange: setFirstChatHighlight },
  ]

  return (
    <section className={styles.panel} aria-label="빠른 설정">
      <div className={styles.panelHead}>
        <h2 className={styles.panelTitle}>빠른 설정</h2>
      </div>
      <p className={styles.quickGroup}>방송 설정</p>
      <ul className={styles.quickList}>
        <li className={styles.quickItem}>
          <label htmlFor="quick-audience">채팅 이용 권한</label>
          <select
            id="quick-audience"
            className={styles.select}
            value={settings.audience}
            onChange={(e) => onChange({ ...settings, audience: e.target.value as Audience })}
          >
            <option value="all">모두</option>
            <option value="followers">팔로워</option>
            <option value="managers">운영자</option>
          </select>
        </li>
        {switches.map(({ label, checked, onChange }) => (
          <li key={label} className={styles.quickItem}>
            <span>{label}</span>
            <input
              className={parts.switch}
              type="checkbox"
              role="switch"
              aria-label={label}
              checked={checked}
              onChange={(e) => onChange(e.target.checked)}
            />
          </li>
        ))}
        <li>
          <button className={`${styles.quickItem} ${styles.quickButton}`} type="button">
            알림 설정
            <ChevronRightIcon className={styles.iconSvg} />
          </button>
        </li>
      </ul>
    </section>
  )
}

export default StudioBroadcastPage
