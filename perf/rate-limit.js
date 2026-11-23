// IP당 요청 수 제한 측정(#31). 정상 시청자와 남용자를 함께 건다.
// k6는 edge 대역 안의 컨테이너에서 돌며(perf/compose.perf.yml) X-Forwarded-For로 사용자 IP를 정한다.
//
// 정상 시청자: 시청자 1,000명 = 200 req/s(채널당 5초 폴링, #12). 50명씩 IP 20개(학교·회사 NAT) — IP마다 10 req/s를 고르게.
//             채널은 #29와 같은 5개(첫 번째가 인기 채널 60%, 나머지 10%씩)
// 남용자:     IP 하나(203.0.113.7)가 없는 채널 id를 매번 바꿔 가며. 404라 캐시에 담기지 않고 Spring·DB까지 간다
//
// 실행(저장소 루트):
//   docker compose -f compose.yml -f perf/compose.perf.yml run --rm k6 run \
//     -e MODE=judge -e ABUSE_RATE=1200 -e CHANNELS=21,1,41,42,43 -e OUT=/out/issue-31/after/judge-summary.json /perf/rate-limit.js
// MODE=judge: 워밍업 30초(시청자만) → 본 측정 60초(시청자 + 남용 ABUSE_RATE, 0이면 시청자만). 합격 기준을 thresholds로 박았다
// MODE=steps: 워밍업 30초 → 남용 400·800·1600·3200 req/s 단계마다 30초(시청자는 계속). 판정하지 않고 기록만
import http from 'k6/http'
import { Counter } from 'k6/metrics'

const BASE_URL = __ENV.BASE_URL || 'http://nginx'
const MODE = __ENV.MODE || 'judge'
const ABUSE_RATE = Number(__ENV.ABUSE_RATE ?? 1200)
const CHANNELS = (__ENV.CHANNELS || '').split(',').filter(Boolean).map(Number)
if (CHANNELS.length === 0) throw new Error('CHANNELS가 필요하다 (예: -e CHANNELS=21,1,41,42,43)')

const VIEWER_IPS = 20
const RATE_PER_VIEWER_IP = 10   // 50명 × 5초에 한 번
const ABUSER_IP = '203.0.113.7'

const PHASES = MODE === 'judge'
  ? [{ name: 'warmup', seconds: 30, abuse: 0 }, { name: 'main', seconds: 60, abuse: ABUSE_RATE }]
  : [{ name: 'warmup', seconds: 30, abuse: 0 }, ...[400, 800, 1600, 3200].map((r) => ({ name: `a${r}`, seconds: 30, abuse: r }))]

// k6 기본 지표(http_req_failed)는 429도 실패로 센다. 그와 따로 429만 센다
const rateLimited = new Counter('responses_429')

function constantRate(rate, seconds, startSeconds, exec, tags, env) {
  return {
    executor: 'constant-arrival-rate',
    exec,
    rate,
    timeUnit: '1s',
    duration: `${seconds}s`,
    startTime: `${startSeconds}s`,
    // 응답이 1초까지 늘어도 목표 rate를 낼 만큼. 모자라면 dropped_iterations로 드러난다
    preAllocatedVUs: Math.min(Math.max(5, rate), 1000),
    maxVUs: Math.max(20, rate * 2),
    tags,
    env,
  }
}

function scenarios() {
  const result = {}
  let start = 0
  for (const phase of PHASES) {
    for (let i = 1; i <= VIEWER_IPS; i++) {
      result[`${phase.name}_viewers${i}`] = constantRate(RATE_PER_VIEWER_IP, phase.seconds, start, 'viewer',
        { role: 'viewer', phase: phase.name }, { VIEWER_IP: `198.51.100.${i}` })
    }
    if (phase.abuse > 0) {
      result[`${phase.name}_abuser`] = constantRate(phase.abuse, phase.seconds, start, 'abuser',
        { role: 'abuser', phase: phase.name }, {})
    }
    start += phase.seconds
  }
  return result
}

// k6는 기준을 건 하위 지표만 요약에 남긴다. 단계·역할별 수치를 남기려고 항상 참인 기준을 건다
function record(t, role, phase) {
  const tag = `{role:${role},phase:${phase}}`
  t[`http_req_duration${tag}`] = ['p(99)>=0']
  t[`http_req_failed${tag}`] = ['rate>=0']
  t[`http_reqs${tag}`] = ['count>=0']
  t[`responses_429${tag}`] = ['count>=0']
  t[`dropped_iterations${tag}`] = ['count>=0']
}

function thresholds() {
  const t = {}
  for (const phase of PHASES) {
    record(t, 'viewer', phase.name)
    if (phase.abuse > 0) record(t, 'abuser', phase.name)
  }
  if (MODE === 'judge') {
    // 합격 기준(#31, 측정 전에 정해 이슈 코멘트로 남김): 정상 시청자 오류 0건(429 포함)·p95 ≤ 50ms
    t['http_req_duration{role:viewer,phase:main}'] = ['p(95)<=50']
    t['http_req_failed{role:viewer,phase:main}'] = ['rate==0']
  }
  return t
}

export const options = {
  scenarios: scenarios(),
  thresholds: thresholds(),
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
}

function pickChannel() {
  if (CHANNELS.length === 1 || Math.random() < 0.6) return CHANNELS[0]
  return CHANNELS[1 + Math.floor(Math.random() * (CHANNELS.length - 1))]
}

function get(path, ip, name) {
  const res = http.get(`${BASE_URL}${path}`, { headers: { 'X-Forwarded-For': ip }, tags: { name } })
  if (res.status === 429) rateLimited.add(1)
}

export function viewer() {
  get(`/api/channels/${pickChannel()}`, __ENV.VIEWER_IP, 'GET /api/channels/{id}')
}

// 없는 채널 id(9억대)를 매번 바꾼다. 캐시 키가 매번 달라 nginx 캐시로는 막을 수 없다
export function abuser() {
  get(`/api/channels/${900000000 + Math.floor(Math.random() * 99999999)}`, ABUSER_IP, 'GET /api/channels/{missing}')
}

export function handleSummary(data) {
  const out = {}
  if (__ENV.OUT) out[__ENV.OUT] = JSON.stringify(data, null, 2)
  out.stdout = textSummary(data)
  return out
}

function textSummary(data) {
  const m = data.metrics
  const lines = ['phase  role  reqs  rps  p50  p95  p99  max  failed  429  dropped']
  for (const phase of PHASES) {
    for (const role of ['viewer', 'abuser']) {
      const tag = `{role:${role},phase:${phase.name}}`
      const d = m[`http_req_duration${tag}`]
      if (!d) continue
      const reqs = m[`http_reqs${tag}`]?.values.count ?? 0
      const f = m[`http_req_failed${tag}`]?.values.rate ?? 0
      const limited = m[`responses_429${tag}`]?.values.count ?? 0
      const dropped = m[`dropped_iterations${tag}`]?.values.count ?? 0
      const v = d.values
      lines.push([phase.name, role, reqs, (reqs / phase.seconds).toFixed(1), v.med.toFixed(1), v['p(95)'].toFixed(1),
        v['p(99)'].toFixed(1), v.max.toFixed(1), (f * 100).toFixed(3) + '%', limited, dropped].join('  '))
    }
  }
  const failed = Object.entries(m).filter(([, v]) => v.thresholds && Object.values(v.thresholds).some((t) => !t.ok))
  lines.push(failed.length ? `thresholds FAILED: ${failed.map(([k]) => k).join(', ')}` : 'thresholds ok')
  return lines.join('\n') + '\n'
}
