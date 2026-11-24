// 데모 방송 준비 스크립트 (#14, PRD channel C3)
// 공개 API로 가입 → 스트리머 신청 → 운영자 승인 → 데모 표시 → 키 발급(이미 있으면 재발급)을 실제로 밟고,
// 받은 키를 demo/streams.txt(git 제외)에 "{채널ID} {키}" 한 줄씩 적는다. 시뮬레이터(demo/simulator.sh)가 이 파일을 읽는다.
//
// 실행: node --env-file=.env demo/setup.mjs [개수=3]
// 필요한 환경변수: OPERATOR_EMAIL, OPERATOR_PASSWORD, DEMO_PASSWORD. 선택: DEMO_BASE_URL(기본 http://localhost:8000)
// 다시 돌려도 된다. 이미 가입·승인된 데모 계정은 그 단계를 건너뛰고, 키는 항상 재발급한다.
// 키·비밀번호는 출력하지 않는다(확정 사양 11절).

import { writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const BASE_URL = process.env.DEMO_BASE_URL ?? 'http://localhost:8000'
const STREAMS_FILE = fileURLToPath(new URL('./streams.txt', import.meta.url))

function required(name) {
  const value = process.env[name]
  if (!value) throw new Error(`.env에 ${name}가 필요합니다`)
  return value
}

// 상태를 바꾸는 요청은 허용된 Origin이 있어야 한다(계정 PRD "위조 요청 방어")
async function call(method, path, { cookie, body } = {}) {
  const headers = { Origin: BASE_URL }
  if (cookie) headers.Cookie = cookie
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  return fetch(`${BASE_URL}/api${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

// 오류 본문의 code만 읽는다. 다른 응답 본문(키가 들어 있을 수 있다)은 출력하지 않는다
async function errorCode(res) {
  try {
    return (await res.json()).code ?? ''
  } catch {
    return ''
  }
}

async function expectStatus(res, statuses, step) {
  if (statuses.includes(res.status)) return
  throw new Error(`${step} 실패: HTTP ${res.status} ${await errorCode(res)}`)
}

async function login(email, password, step) {
  const res = await call('POST', '/auth/login', { body: { email, password } })
  await expectStatus(res, [200], step)
  const session = res.headers.getSetCookie().find((c) => c.startsWith('SESSION='))
  if (!session) throw new Error(`${step} 실패: 세션 쿠키가 없습니다`)
  return { cookie: session.split(';')[0], member: await res.json() }
}

async function prepare(i, operator, demoPassword) {
  const email = `demo${i}@demo.local`
  const label = `데모 ${i}(${email})`

  const signUp = await call('POST', '/members', { body: { email, password: demoPassword, nickname: `데모채널${i}` } })
  if (signUp.status === 409) {
    const code = await errorCode(signUp)
    if (code !== 'MEMBER_EMAIL_DUPLICATED') throw new Error(`${label} 가입 실패: HTTP 409 ${code}`)
  } else {
    await expectStatus(signUp, [201], `${label} 가입`)
  }

  let demo = await login(email, demoPassword, `${label} 로그인`)
  if (demo.member.role !== 'STREAMER') {
    // 201 = 새 신청, 409 = 이미 대기 중(CHANNEL_APPLICATION_PENDING)
    const apply = await call('POST', '/streamer-applications', { cookie: demo.cookie })
    await expectStatus(apply, [201, 409], `${label} 스트리머 신청`)

    const pending = await call('GET', '/operator/streamer-applications', { cookie: operator.cookie })
    await expectStatus(pending, [200], '대기 목록 조회')
    const application = (await pending.json()).find((a) => a.email === email)
    if (!application) throw new Error(`${label} 승인 실패: 대기 중인 신청이 없습니다`)
    const approve = await call('POST', `/operator/streamer-applications/${application.id}/approve`, {
      cookie: operator.cookie,
    })
    await expectStatus(approve, [200], `${label} 승인`)

    // 승인하면 그 회원의 세션이 모두 지워진다(PRD channel 작은 결정). 다시 로그인해 스트리머로 들어온다
    demo = await login(email, demoPassword, `${label} 다시 로그인`)
  }

  const channelRes = await call('GET', '/studio/channel', { cookie: demo.cookie })
  await expectStatus(channelRes, [200], `${label} 채널 조회`)
  const channelId = (await channelRes.json()).id

  const mark = await call('PUT', `/operator/channels/${channelId}/demo`, { cookie: operator.cookie })
  await expectStatus(mark, [200], `${label} 데모 표시`)

  const keyRes = await call('POST', '/studio/stream-key', { cookie: demo.cookie })
  await expectStatus(keyRes, [200], `${label} 키 발급`)
  const { streamKey } = await keyRes.json()

  console.log(`${label}: 채널 ${channelId} 준비됨`)
  return `${channelId} ${streamKey}`
}

async function main() {
  const count = Number(process.argv[2] ?? 3)
  if (!Number.isInteger(count) || count < 1) throw new Error('개수는 1 이상의 정수입니다')

  const operator = await login(required('OPERATOR_EMAIL'), required('OPERATOR_PASSWORD'), '운영자 로그인')
  const demoPassword = required('DEMO_PASSWORD')

  const lines = []
  for (let i = 1; i <= count; i++) {
    lines.push(await prepare(i, operator, demoPassword))
  }
  // 다 끝난 뒤 한 번에 쓴다. 중간에 실패하면 이전 파일을 그대로 둔다
  writeFileSync(STREAMS_FILE, lines.join('\n') + '\n', { mode: 0o600 })
  console.log(`데모 채널 ${count}개의 키를 demo/streams.txt에 적었습니다`)
}

main().catch((e) => {
  console.error(e.message)
  process.exit(1)
})
