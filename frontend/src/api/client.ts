// 백엔드 API 호출 공통 함수. 같은 출처(/api)로만 부르므로 세션 쿠키는 브라우저가 알아서 싣는다.
// 에러 응답 본문의 모양은 #1에서 정한다. 여기서는 상태 코드와 본문을 그대로 들고 던진다.

export class ApiError extends Error {
  readonly status: number
  readonly body: unknown

  constructor(status: number, body: unknown) {
    super(`API ${status}`)
    this.status = status
    this.body = body
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const res = await fetch(`/api${path}`, { ...init, headers, credentials: 'same-origin' })
  const text = await res.text()
  // 프록시(nginx·Vite)가 낸 에러는 JSON이 아닐 수 있다. 그때는 글자 그대로 둔다.
  let body: unknown = null
  if (text) {
    try {
      body = JSON.parse(text)
    } catch {
      body = text
    }
  }

  if (!res.ok) throw new ApiError(res.status, body)
  return body as T
}

// 서버 에러 본문 {"code", "message"}의 message를 꺼낸다. 없으면 일반 문구
export function errorMessage(e: unknown): string {
  if (e instanceof ApiError && typeof e.body === 'object' && e.body !== null && 'message' in e.body) {
    const message = (e.body as { message: unknown }).message
    if (typeof message === 'string') return message
  }
  return '잠시 후 다시 시도해 주세요'
}
