import { UploadIcon } from '../components/icons.tsx'
import styles from './StudioVideosPage.module.css'

// 스튜디오 > 콘텐츠 관리 > 동영상. 다시보기(VOD)·업로드는 기본 범위 밖이라(확장으로 만든다)
// 업로드 버튼·목록은 모양만 있다
function StudioVideosPage() {
  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>동영상</h1>
        <button className={styles.uploadButton} type="button">
          <UploadIcon className={styles.uploadIcon} />
          동영상 업로드
        </button>
      </div>
      <div className={styles.card}>
        <div className={styles.tableWrap}>
          <table className={styles.table} aria-label="동영상 목록">
            <thead>
              <tr>
                <th className={styles.videoColumn} scope="col">
                  영상
                </th>
                <th scope="col">액션</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td className={styles.emptyCell} colSpan={2}>
                  동영상이 없습니다.
                  <br />
                  지금{' '}
                  <button className={styles.inlineLink} type="button">
                    동영상 업로드
                  </button>
                  를 시작해보세요.
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}

export default StudioVideosPage
