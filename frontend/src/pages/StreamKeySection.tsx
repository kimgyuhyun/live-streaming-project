import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { errorMessage } from '../api/client.ts'
import { fetchStreamKey, issueStreamKey } from '../api/streamKey.ts'
import ConfirmModal from '../components/ConfirmModal.tsx'
import { EyeClosedIcon, EyeIcon } from '../components/icons.tsx'
import tip from '../styles/tooltip.module.css'
import styles from './StudioSettingsPage.module.css'

const MASK = '•'.repeat(32)

type CopyTarget = 'url' | 'key'

// 스트림 URL·스트림 키 카드. 서버는 키의 해시와 앞 4자리만 갖고 있어서(PRD C1)
// 전체 키는 발급·재발급 직후 이 화면에서만 보고 복사할 수 있다. 새로고침하면 다시 볼 수 없다
function StreamKeySection({ channelId, live }: { channelId: number; live: boolean }) {
  const queryClient = useQueryClient()
  const streamKey = useQuery({ queryKey: ['streamKey'], queryFn: fetchStreamKey })
  const mutation = useMutation({
    mutationFn: issueStreamKey,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['streamKey'] }),
  })
  const [revealed, setRevealed] = useState(false)
  const [copied, setCopied] = useState<CopyTarget | null>(null)
  const [copyFailed, setCopyFailed] = useState(false)
  // 키 보기·재발급은 팝업에서 한 번 더 확인받는다
  const [confirming, setConfirming] = useState<'reveal' | 'reissue' | null>(null)

  if (streamKey.isPending) return null
  if (streamKey.isError) return <p role="alert">{errorMessage(streamKey.error)}</p>

  const { keyPrefix: prefix, obsServer } = streamKey.data
  // OBS 스트림 키 칸에 넣을 값({채널ID}?key={스트림키}). 발급 직후에만 있다
  const obsStreamKey = mutation.data?.obsStreamKey ?? null
  const keyText = obsStreamKey && revealed ? obsStreamKey : obsStreamKey || prefix ? MASK : null

  async function copy(target: CopyTarget, text: string) {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(target)
      setCopyFailed(false)
    } catch {
      setCopied(null)
      setCopyFailed(true)
    }
  }

  function issue() {
    setConfirming(null)
    setRevealed(false)
    setCopied(null)
    mutation.mutate()
  }

  return (
    <section className={styles.section} aria-labelledby="stream-settings">
      <h2 id="stream-settings" className={styles.sectionTitle}>
        스트림 설정
      </h2>
      <div className={styles.card}>
        <div className={styles.row}>
          <span className={styles.label}>연결 상태</span>
          <p className={styles.inline}>
            <span className={live ? styles.accent : styles.muted}>{live ? '송출 중' : '송출 없음'}</span>
            <Link className={styles.link} to={`/channels/${channelId}`}>
              내 채널 페이지
            </Link>
          </p>
        </div>

        <div className={styles.row}>
          <span className={styles.label}>스트림 URL</span>
          <div className={styles.inline}>
            <code className={styles.value}>{obsServer}</code>
            <button
              className={styles.secondaryButton}
              type="button"
              aria-label="스트림 URL 복사"
              onClick={() => copy('url', obsServer)}
            >
              복사
            </button>
          </div>
        </div>

        <div className={styles.row}>
          <span className={styles.label}>스트림 키</span>
          <div className={styles.control}>
            <div className={styles.inline}>
              <div className={`${styles.keyField} ${styles.grow}`}>
                <code className={styles.keyValue} data-testid="stream-key">
                  {keyText ?? <span className={styles.muted}>아직 발급하지 않았습니다</span>}
                </code>
                <button
                  className={`${styles.eyeButton} ${tip.tooltip} ${tip.alignEnd}`}
                  type="button"
                  disabled={!obsStreamKey}
                  aria-label={revealed ? '스트림 키 숨기기' : '스트림 키 보기'}
                  data-tooltip={obsStreamKey ? (revealed ? '숨기기' : '보기') : '발급 직후에만 볼 수 있습니다'}
                  onClick={() => (revealed ? setRevealed(false) : setConfirming('reveal'))}
                >
                  {revealed ? <EyeIcon className={styles.icon} /> : <EyeClosedIcon className={styles.icon} />}
                </button>
              </div>
              <button
                className={styles.secondaryButton}
                type="button"
                aria-label="스트림 키 복사"
                disabled={!obsStreamKey}
                onClick={() => obsStreamKey && copy('key', obsStreamKey)}
              >
                복사
              </button>
              <button
                className={styles.primaryButton}
                type="button"
                disabled={mutation.isPending}
                onClick={() => (prefix ? setConfirming('reissue') : issue())}
              >
                {prefix ? '재발급' : '발급'}
              </button>
            </div>
            {copied && (
              <p className={styles.status} role="status">
                {copied === 'url' ? '스트림 URL을' : '스트림 키를'} 복사했습니다
              </p>
            )}
            {copyFailed && (
              <p className={styles.error} role="alert">
                복사하지 못했습니다. 직접 선택해서 복사해 주세요
              </p>
            )}
            {mutation.isError && (
              <p className={styles.error} role="alert">
                {errorMessage(mutation.error)}
              </p>
            )}
            <ul className={styles.notes}>
              {obsStreamKey ? (
                <li role="status">이 키는 지금 한 번만 보여 줍니다. 페이지를 떠나기 전에 복사해 두세요</li>
              ) : (
                prefix && (
                  <li>
                    현재 키는 <code>{prefix}</code>로 시작합니다. 전체 키는 발급 직후에만 보고 복사할 수 있어요. 잃어버렸다면
                    재발급하세요
                  </li>
                )
              )}
              {prefix && <li>재발급하면 이전 키는 바로 쓸 수 없고, 송출 중이면 연결이 끊깁니다</li>}
              <li>
                OBS 방송 설정의 서버 칸에 스트림 URL을, 스트림 키 칸에 스트림 키를 붙여 넣으세요. 키프레임 간격은 1~2초로
                맞춥니다(
                <Link className={styles.link} to="/studio/guide/obs">
                  OBS 가이드
                </Link>
                )
              </li>
            </ul>
          </div>
        </div>
      </div>

      {confirming === 'reveal' && (
        <ConfirmModal
          title="스트림 키 표시 안내"
          confirmLabel="스트림 키 보기"
          onClose={() => setConfirming(null)}
          onConfirm={() => {
            setConfirming(null)
            setRevealed(true)
          }}
        >
          <p className={styles.warning}>스트림 키를 다른 사람과 공유하거나 방송 중에 노출되면 안됩니다.</p>
          <p>
            스트림 키가 노출되면 다른 사람이 내 채널 방송을 시작할 수 있습니다. 위 내용을 이해한 후 스트림 키를 보려면
            '스트림 키 보기'를 클릭해주세요.
          </p>
        </ConfirmModal>
      )}
      {confirming === 'reissue' && (
        <ConfirmModal title="스트림 키 재발급 안내" confirmLabel="재발급" onClose={() => setConfirming(null)} onConfirm={issue}>
          <p>
            방송을 시작하려면 새로 발급된 스트림 키로 스트리밍 소프트웨어에 업데이트 해야 합니다. 스트림 키를
            재발급하려면 <strong>재발급</strong>을 클릭해주세요.
          </p>
        </ConfirmModal>
      )}
    </section>
  )
}

export default StreamKeySection
