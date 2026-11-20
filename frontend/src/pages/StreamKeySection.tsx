import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { errorMessage } from '../api/client.ts'
import { fetchStreamKey, issueStreamKey } from '../api/streamKey.ts'

const MASK = '••••'

// 스튜디오의 스트림 키·OBS 설정. 전체 키는 발급·재발급 직후 이 화면에서만 보인다(새로고침하면 앞 4자리만)
function StreamKeySection({ channelId }: { channelId: number }) {
  const queryClient = useQueryClient()
  const streamKey = useQuery({ queryKey: ['streamKey'], queryFn: fetchStreamKey })
  const mutation = useMutation({
    mutationFn: issueStreamKey,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['streamKey'] }),
  })

  if (streamKey.isPending) return null
  if (streamKey.isError) return <p role="alert">{errorMessage(streamKey.error)}</p>

  const issued = mutation.data
  const prefix = streamKey.data.keyPrefix
  const obsStreamKey = issued ? issued.obsStreamKey : prefix ? `${channelId}?key=${prefix}${MASK}` : null

  return (
    <section>
      <h2>스트림 키·OBS 설정</h2>
      {issued ? (
        <>
          <p>
            스트림 키 <code>{issued.streamKey}</code>
          </p>
          <p role="status">이 키는 지금 한 번만 보여 줍니다. 잃어버리면 재발급하세요</p>
        </>
      ) : (
        <p>
          스트림 키{' '}
          {prefix ? (
            <code>
              {prefix}
              {MASK}
            </code>
          ) : (
            '아직 발급하지 않았습니다'
          )}
        </p>
      )}
      {prefix && <p>재발급하면 이전 키는 바로 쓸 수 없습니다</p>}
      <button type="button" onClick={() => mutation.mutate()} disabled={mutation.isPending}>
        {prefix ? '재발급' : '발급'}
      </button>
      {mutation.isError && <p role="alert">{errorMessage(mutation.error)}</p>}
      <h3>OBS 설정</h3>
      <dl>
        <dt>서버</dt>
        <dd>
          <code>{streamKey.data.obsServer}</code>
        </dd>
        <dt>스트림 키</dt>
        <dd>{obsStreamKey ? <code>{obsStreamKey}</code> : '키를 발급하면 보입니다'}</dd>
      </dl>
      <p>OBS 출력 설정에서 키프레임 간격을 1~2초로 설정하세요</p>
    </section>
  )
}

export default StreamKeySection
