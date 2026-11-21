// 시청자 폴링 부하(#29). 방송 중 채널을 보는 시청자는 5초마다 GET /api/channels/{id}를 다시 묻는다(#12).
// 시청자 1,000명 = 200 req/s. 인기 채널 하나가 60%, 나머지 넷이 10%씩.
//
// 실행(저장소 루트, 로컬 compose가 떠 있어야 한다):
//   k6 run -e MODE=target -e CHANNELS=21,1,41,42,43 perf/channel-poll.js   합격 판정(목표 부하)
//   k6 run -e MODE=steps  -e CHANNELS=21,1,41,42,43 perf/channel-poll.js   포화 지점(계단)
//   k6 run -e MODE=steps  -e STEPS=1600,2400,3200,4800 ... 계단 바꾸기(1600 너머 포화 지점)
// CHANNELS의 첫 번째가 인기 채널이다. BASE_URL 기본값은 nginx(http://localhost:8080). OUT은 요약 JSON 경로.
import http from 'k6/http'
import { check } from 'k6'

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080'
const MODE = __ENV.MODE || 'target'
const CHANNELS = (__ENV.CHANNELS || '').split(',').filter(Boolean).map(Number)
if (CHANNELS.length === 0) throw new Error('CHANNELS가 필요하다 (예: -e CHANNELS=21,1,41,42,43)')

// 기본은 이슈가 정한 계단. 포화 지점을 더 찾을 때만 -e STEPS=1600,2400,... 로 바꾼다
const STEPS = (__ENV.STEPS || '50,100,200,400,800,1600').split(',').map(Number)
const STEP_SECONDS = 30
const WARMUP_SECONDS = 30

function constantRate(rate, seconds, startSeconds) {
  return {
    executor: 'constant-arrival-rate',
    rate,
    timeUnit: '1s',
    duration: `${seconds}s`,
    startTime: `${startSeconds}s`,
    // 응답이 1초까지 늘어도 목표 rate를 낼 만큼. 모자라면 dropped_iterations로 드러난다
    // 1,000개를 넘게 미리 만들면 k6 메모리만 든다. 모자라면 maxVUs까지 늘린다
    preAllocatedVUs: Math.min(Math.max(20, rate), 1000),
    maxVUs: rate * 2,
  }
}

function scenarios() {
  // 워밍업: JIT·커넥션 풀 예열. 판정·집계에서 뺀다(scenario 태그로 나눈다)
  const result = { warmup: constantRate(50, WARMUP_SECONDS, 0) }
  if (MODE === 'target') {
    result.target = constantRate(200, 60, WARMUP_SECONDS)
    return result
  }
  STEPS.forEach((rate, i) => {
    result[`s${rate}`] = constantRate(rate, STEP_SECONDS, WARMUP_SECONDS + i * STEP_SECONDS)
  })
  return result
}

// k6는 기준을 건 하위 지표만 요약에 남긴다. 시나리오별 수치를 남기려고 항상 참인 기준을 건다
function recordScenario(t, s) {
  t[`http_req_duration{scenario:${s}}`] = ['p(99)>=0']
  t[`http_req_failed{scenario:${s}}`] = ['rate>=0']
  t[`http_reqs{scenario:${s}}`] = ['count>=0']
  t[`dropped_iterations{scenario:${s}}`] = ['count>=0']
}

function thresholds() {
  const t = {}
  recordScenario(t, 'warmup')
  if (MODE === 'target') {
    recordScenario(t, 'target')
    // 합격 기준(#29, 측정 전에 정함): 목표 부하에서 p95 ≤ 200ms, 오류율 < 0.1%
    t['http_req_duration{scenario:target}'] = ['p(95)<=200']
    t['http_req_failed{scenario:target}'] = ['rate<0.001']
    return t
  }
  // 계단 모드는 판정하지 않는다
  for (const rate of STEPS) recordScenario(t, `s${rate}`)
  return t
}

export const options = {
  scenarios: scenarios(),
  thresholds: thresholds(),
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
}

// 인기 채널 60%, 나머지는 고르게
function pickChannel() {
  if (CHANNELS.length === 1 || Math.random() < 0.6) return CHANNELS[0]
  return CHANNELS[1 + Math.floor(Math.random() * (CHANNELS.length - 1))]
}

export default function () {
  const res = http.get(`${BASE_URL}/api/channels/${pickChannel()}`, { tags: { name: 'GET /api/channels/{id}' } })
  check(res, { 'status 200': (r) => r.status === 200 })
}

export function handleSummary(data) {
  const out = {}
  if (__ENV.OUT) out[__ENV.OUT] = JSON.stringify(data, null, 2)
  out.stdout = textSummary(data)
  return out
}

// 단계별 한 줄 요약(k6 기본 요약은 하위 지표가 길어 읽기 어렵다)
function textSummary(data) {
  const m = data.metrics
  const names = MODE === 'target' ? ['warmup', 'target'] : ['warmup', ...STEPS.map((r) => `s${r}`)]
  const lines = ['scenario  reqs  rps  p50  p95  p99  max  failed  dropped']
  for (const s of names) {
    const d = m[`http_req_duration{scenario:${s}}`]
    if (!d) continue
    const reqs = m[`http_reqs{scenario:${s}}`]?.values.count ?? 0
    const secs = s === 'warmup' ? WARMUP_SECONDS : s === 'target' ? 60 : STEP_SECONDS
    const f = m[`http_req_failed{scenario:${s}}`]?.values.rate ?? 0
    const dropped = m[`dropped_iterations{scenario:${s}}`]?.values.count ?? 0
    const v = d.values
    lines.push([s, reqs, (reqs / secs).toFixed(1), v.med.toFixed(1), v['p(95)'].toFixed(1), v['p(99)'].toFixed(1),
      v.max.toFixed(1), (f * 100).toFixed(3) + '%', dropped].join('  '))
  }
  const failed = Object.entries(m).filter(([, v]) => v.thresholds && Object.values(v.thresholds).some((t) => !t.ok))
  lines.push(failed.length ? `thresholds FAILED: ${failed.map(([k]) => k).join(', ')}` : 'thresholds ok')
  return lines.join('\n') + '\n'
}
