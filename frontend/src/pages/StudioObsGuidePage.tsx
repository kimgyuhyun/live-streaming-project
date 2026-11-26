import { useState } from 'react'
import { Link } from 'react-router'
import styles from './StudioObsGuidePage.module.css'

// 스튜디오 > OBS 설정 가이드. 설정 항목과 권장값은 치지직 OBS 설정 가이드(2025.02)를 참고했고,
// 문장·화면은 가져오지 않고 이 서비스 서버에 맞게 다시 썼다.
// 키프레임 1초: 시청 조각 길이가 키프레임 간격과 같다(docs/spikes/infra-validation.md O).
// 비트레이트 6,000Kbps: 화질 선택이 없어 시청자가 송출 그대로 받는다. 서버에서 확인한 값이 1080p 6Mbps다

const SETTINGS_PATH = '/studio/broadcast/settings'

type Row = { item: string; value: string; note?: string }

// 출력 > 방송 탭의 공통값. 인코더마다 다른 값은 ENCODERS에 둔다
const OUTPUT_COMMON: Row[] = [
  { item: '출력 방식', value: '고급' },
  { item: '출력 배율 재조정', value: '1920x1080' },
  { item: '데이터율 제어', value: 'CBR', note: '비트레이트를 일정하게 보낸다' },
  { item: '비트레이트', value: '6000 Kbps', note: '업로드 속도가 넉넉하면 8000까지. 시청자는 이 값 그대로 받는다' },
  { item: '키프레임 간격', value: '1 s', note: '0(자동)으로 두면 시청 지연이 길어진다' },
]

type Encoder = { id: string; name: string; when: string; rows: Row[] }

const ENCODERS: Encoder[] = [
  {
    id: 'nvenc',
    name: 'NVIDIA NVENC',
    when: 'NVIDIA 그래픽카드가 있을 때',
    rows: [
      { item: '비디오 인코더', value: 'NVIDIA NVENC H.264' },
      { item: '사전 설정', value: 'P4: 보통(중간 품질)' },
      { item: '조정', value: '매우 짧은 지연 시간' },
      { item: '멀티패스 모드', value: '1패스' },
      { item: '프로파일', value: 'high' },
      { item: 'Look-ahead', value: '끔' },
      { item: 'Psycho Visual Tuning', value: '켬' },
      { item: '최대 B-프레임', value: '0' },
    ],
  },
  {
    id: 'quicksync',
    name: 'Intel QuickSync',
    when: 'Intel 내장 그래픽을 쓸 때',
    rows: [
      { item: '비디오 인코더', value: 'QuickSync H.264' },
      { item: '목표 사용 수준', value: 'TU4: 균형(중간 품질)' },
      { item: '프로파일', value: 'high' },
      { item: '지연 시간', value: 'ultra-low' },
      { item: 'B 프레임', value: '0' },
    ],
  },
  {
    id: 'apple',
    name: 'Apple Hardware Encoder',
    when: 'Apple 칩(M 시리즈) Mac',
    rows: [
      { item: '비디오 인코더', value: 'Apple VT H264 Hardware Encoder' },
      { item: '데이터율 제어', value: 'CBR', note: 'Apple 인코더는 반드시 CBR' },
    ],
  },
  {
    id: 'x264',
    name: 'x264',
    when: '하드웨어 인코더가 없거나 Intel CPU Mac',
    rows: [
      { item: '비디오 인코더', value: 'x264' },
      { item: 'CPU 사용량 사전 설정', value: 'veryfast' },
      { item: '프로파일', value: '(없음)' },
      { item: '조정', value: 'zerolatency' },
      {
        item: 'x264 설정',
        value: 'scenecut=0 min-keyint=60',
        note: 'min-keyint는 FPS와 같게. 30 FPS면 min-keyint=30',
      },
    ],
  },
]

function SettingsTable({ rows }: { rows: Row[] }) {
  return (
    <div className={styles.tableWrap}>
      <table className={styles.table}>
        <thead>
          <tr>
            <th scope="col">항목</th>
            <th scope="col">값</th>
            <th scope="col">설명</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.item}>
              <th scope="row">{row.item}</th>
              <td className={styles.value}>{row.value}</td>
              <td className={styles.note}>{row.note ?? ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function StudioObsGuidePage() {
  const [encoderId, setEncoderId] = useState(ENCODERS[0].id)
  const encoder = ENCODERS.find((e) => e.id === encoderId) ?? ENCODERS[0]

  return (
    <article className={styles.guide}>
      <header className={styles.intro}>
        <h1 className={styles.title}>OBS 설정 가이드</h1>
        <p className={styles.text}>OBS를 처음 쓰는 스트리머를 위한 게임 방송 기본 설정입니다. 위에서부터 차례로 따라 하세요.</p>
      </header>

      <section className={styles.card}>
        <h2 className={styles.cardTitle}>시작하기 전에</h2>
        <ul className={styles.bullets}>
          <li>OBS를 처음 실행하면 뜨는 자동 구성 마법사는 취소합니다. 아래 값을 직접 넣습니다.</li>
          <li>
            설정은 송출 중에 바꾸면 적용되지 않습니다. 송출을 멈추고 바꾼 뒤 다시 시작하세요(스트림 키는 그대로 씁니다).
          </li>
          <li>OBS 왼쪽 아래에 인코딩 과부하 경고가 뜨면 PC가 버거운 것입니다. 해상도나 FPS를 낮추거나 하드웨어 인코더를 쓰세요.</li>
        </ul>
      </section>

      <section className={styles.card}>
        <h2 className={styles.cardTitle}>1. 방송 — 서버와 스트림 키</h2>
        <p className={styles.text}>설정 → 방송에서 서비스를 "사용자 지정"으로 고르고, 서버와 스트림 키를 넣습니다.</p>
        <p className={styles.text}>
          두 값은{' '}
          <Link className={styles.link} to={SETTINGS_PATH}>
            방송 관리 &gt; 설정
          </Link>
          에 있습니다. 서버는 <code className={styles.code}>rtmp://…/live</code>, 스트림 키는{' '}
          <code className={styles.code}>채널번호?key=…</code> 모양입니다.
        </p>
      </section>

      <section className={styles.card}>
        <h2 className={styles.cardTitle}>2. 출력 — 비디오 인코더</h2>
        <p className={styles.text}>설정 → 출력에서 출력 방식을 "고급"으로 바꾸고, 방송 탭에서 아래처럼 설정합니다.</p>
        <SettingsTable rows={OUTPUT_COMMON} />
        <p className={styles.text}>
          비디오 인코더는 내 PC에 맞는 것을 하나 고릅니다. H.264 인코더만 씁니다(HEVC·AV1은 브라우저에서 재생되지 않을 수 있습니다).
          Apple Software Encoder는 쓸 수 없습니다.
        </p>
        <div className={styles.tabs} role="tablist" aria-label="비디오 인코더">
          {ENCODERS.map((e) => (
            <button
              key={e.id}
              className={`${styles.tab} ${e.id === encoder.id ? styles.tabActive : ''}`}
              type="button"
              role="tab"
              aria-selected={e.id === encoder.id}
              onClick={() => setEncoderId(e.id)}
            >
              {e.name}
            </button>
          ))}
        </div>
        <div role="tabpanel" aria-label={encoder.name} className={styles.panel}>
          <p className={styles.when}>{encoder.when}</p>
          <SettingsTable rows={encoder.rows} />
        </div>
      </section>

      <section className={styles.card}>
        <h2 className={styles.cardTitle}>3. 오디오</h2>
        <SettingsTable
          rows={[
            { item: '오디오 인코더(출력 > 방송)', value: 'FFmpeg AAC' },
            { item: '오디오 비트레이트(출력 > 오디오)', value: '192' },
            { item: '샘플 레이트(설정 > 오디오)', value: '48 kHz' },
            { item: '채널(설정 > 오디오)', value: '스테레오' },
          ]}
        />
      </section>

      <section className={styles.card}>
        <h2 className={styles.cardTitle}>4. 비디오</h2>
        <SettingsTable
          rows={[
            { item: '기본(캔버스) 해상도', value: '1920x1080' },
            { item: '출력(조정된) 해상도', value: '1920x1080', note: '모니터가 더 커도 출력은 1920x1080' },
            { item: 'FPS', value: '60', note: 'PC가 버거우면 30' },
          ]}
        />
      </section>

      <section className={styles.card}>
        <h2 className={styles.cardTitle}>5. 화면 구성</h2>
        <p className={styles.text}>
          소스 목록의 +를 눌러 화면을 추가합니다. PC 한 대로 방송하면 "게임 캡처"(게임 창만) 또는 "디스플레이 캡처"(모니터
          전체)를 고르면 됩니다.
        </p>
      </section>

      <section className={styles.card}>
        <h2 className={styles.cardTitle}>6. 방송 시작</h2>
        <ul className={styles.bullets}>
          <li>OBS 오른쪽 아래 "방송 시작"을 누릅니다. 문제가 있으면 OBS 왼쪽 아래 상태 표시줄에 오류가 나옵니다.</li>
          <li>
            <Link className={styles.link} to={SETTINGS_PATH}>
              방송 관리 &gt; 설정
            </Link>
            의 연결 상태가 "송출 중"이 되면 시청자에게 보이기 시작합니다(2~4초 늦게).
          </li>
        </ul>
      </section>

      <p className={styles.source}>설정 항목과 권장값은 치지직 OBS 설정 가이드를 참고해 이 서비스 서버에 맞게 정리했습니다.</p>
    </article>
  )
}

export default StudioObsGuidePage
