# 화면 디자인 규칙

화면(프론트) 작업 전에 읽는다. CSS 규약 스킬(css-standards)도 이 문서를 먼저 따른다.

## 1. 화면은 칸을 조립해서 만든다

- 바탕 칸 안에 자식 칸을, 자식 칸 안에 또 칸을 넣는다. 칸끼리는 flex·grid로 가로나 세로로 나란히 놓고, 각 칸은 부모가 준 자리 안에서만 채운다.
- 한 칸의 크기가 바뀌면 옆 칸이 저절로 맞춰지게 한다. 다른 칸의 크기를 숫자로 계산해서 여백(margin·padding)으로 비켜 가지 않는다.
  - 하지 않는 것: 헤더를 `position: fixed`로 얹고 페이지마다 `padding-top: 4rem`으로 밀어내기. 헤더 높이가 바뀌면 모든 페이지를 고쳐야 하고, 하나라도 틀리면 내용이 헤더에 가려진다.
  - 하지 않는 것: 사이드바를 `fixed`로 얹고 본문에 `margin-left: 240px`.
- flex 자식에는 `min-width: 0`을 준다. 안의 콘텐츠(영상, 긴 글)가 넓어도 칸을 밀어내지 않는다.

겹쳐 띄우는 것은 아래뿐이다.

| 무엇 | 방법 |
| --- | --- |
| 스크롤해도 따라오는 헤더·사이드바 | 조립된 자리에 두고 `position: sticky` |
| 잠깐 위에 덮이는 것: 드롭다운, 모달, 모바일 서랍 메뉴, 툴팁 | `position: absolute` / `fixed` |
| 영상 위에 덮이는 것: 플레이어 컨트롤, LIVE 표시, 채팅 펼치기 버튼 | 영상 칸 안에서 `position: absolute` |

## 2. 화면 틀

```
<Header>             sticky, 높이 --header-height
<div .shell>         가로 flex
  <Sidebar>          1024 이상: 왼쪽 칸, sticky. 펼침 --sidebar-width / 접힘 --sidebar-width-collapsed
                     1024 미만: 숨김. 헤더 ☰로 본문 위에 덮어 연다
  <main .main>       남은 너비 전부, 안쪽 여백
```

- 스튜디오(`/studio` 아래)는 따로 `frontend/src/layouts/StudioLayout.tsx`를 쓴다. 틀은 같고 헤더(`StudioHeader`)·사이드바(`StudioSidebar`, 펼치는 하위 메뉴)만 다르다. 로그인·역할·약관 확인도 이 틀이 한 번 한다. 1024 이상에서 ☰는 사이드바를 숨긴다(접힘 없음).
- 헤더·사이드바(푸터를 넣는다면 푸터도)는 `frontend/src/layouts/RootLayout.tsx`에 한 번만 둔다. 페이지는 메인 칸 안의 내용만 만든다. 페이지가 헤더·사이드바를 직접 넣거나 그 크기를 알 필요가 없어야 한다.
- 메인 칸은 기본으로 안쪽 여백이 있다. 방송 화면처럼 여백 없이 칸을 다 써야 하는 페이지는 `router.tsx`의 그 경로에 `handle: { flush: true }`를 붙인다. 그런 페이지는 자기 안쪽 여백을 직접 정한다.
- 방송 화면(`/channels/:id`)은 메인 칸 안에서 다시 왼쪽 칸(영상 + 방송 정보)과 오른쪽 채팅 칸(`--chat-width`)으로 나뉜다. 채팅을 접으면 왼쪽 칸이 너비를 다 쓴다.
- 넓은 화면(플레이어 버튼 또는 `t`)은 사이드바 칸을 빼서 메인 칸이 그 자리까지 넓어지게 한다. 상태는 `RootLayout`이 갖고 `<Outlet context>`(`LayoutContext`)로 페이지에 넘긴다. 방송 화면을 떠나면 꺼진다.
- 푸터는 넣는다면 한두 줄(서비스 이름, 포트폴리오 안내, 출처 표기). 방송 보기 화면에는 두지 않는다.

## 3. 색·크기는 변수로

- `frontend/src/styles/global.css`의 `:root` 변수만 쓴다.
  - 색 `--color-*`, 글자 `--font-size-*`, 간격 `--space-*`, 모서리 `--radius-*`
  - 높이·너비 `--control-height`, `--header-height`, `--sidebar-width`, `--sidebar-width-collapsed`
- 화면 CSS에 색·간격 값을 직접 쓰지 않는다. 필요한 값이 없으면 `global.css`에 변수를 먼저 추가한다. 전역 파일이라 추가할 때 영향 범위를 말한다.
- 예외: 각 회사가 정한 브랜드 색(카카오 노랑 등)은 그 컴포넌트 CSS에 직접 쓰고 주석으로 이유를 남긴다. 테마를 바꿔도 따라 바뀌면 안 되는 색이다.
- 어두운 테마 하나만 쓴다.

## 4. 스타일 방식

- 전역 CSS는 `reset.css`(초기화, 하나만)와 `global.css`(변수, body 기본)뿐이다. `main.tsx`에서 불러온다.
- 나머지는 CSS 모듈(`{컴포넌트}.module.css`)을 컴포넌트 옆에 둔다. 다른 화면에 번지지 않는다.
- 모바일 우선: 기본 스타일을 좁은 화면 기준으로 쓰고, `@media (min-width: …)`로 넓은 화면을 덧붙인다.
- 브레이크포인트는 768 / 1024 (필요하면 480 / 1280). 이 넷 밖의 값을 새로 만들지 않는다.
- 사이드바·채팅을 여닫으면 너비가 바뀌는 칸 안의 목록(카드 그리드 등)은 화면 너비 대신 그 칸 너비로 정한다: 칸에 `container-type: inline-size`, 목록에 `@container (min-width: …)`.
- 크기는 rem. 레이아웃 너비는 flex·grid·%·max-width로 잡고 고정 px 너비를 피한다.
- `!important`나 인라인 `style`로 덮어쓰지 않는다.
- 마우스를 올리면 뜨는 설명(툴팁)에 HTML `title` 속성을 쓰지 않는다. 브라우저·OS가 그려서 CSS가 닿지 않고 늘 흰 바탕으로 나온다. `src/styles/tooltip.module.css`(`data-tooltip`)를 쓰고 `aria-label`을 같이 둔다.
- 키보드 포커스(`:focus-visible`)에 브라우저 기본 흰 테두리가 그대로 나오지 않게, 버튼은 마우스를 올렸을 때와 같은 바탕을 준다.

## 5. 공통 부품

새 부품을 만들기 전에 이 표부터 본다.

| 무엇 | 어디 | 쓰는 곳 |
| --- | --- | --- |
| 툴팁(마우스를 올리면 뜨는 설명) | `src/styles/tooltip.module.css` | 플레이어 버튼, 헤더 ☰, 채팅 접기, 소셜 로그인, 동영상 카드 제목 |
| 폼 입력칸·라벨·버튼·에러 문구 | `src/styles/form.module.css` | 로그인, 회원가입. 마이페이지·스튜디오 폼도 이것을 쓴다 |
| 가운데 카드 틀 | `src/components/AuthCard.tsx` | 로그인, 회원가입 |
| 확인 팝업(취소 / 확인) | `src/components/ConfirmModal.tsx` | 스튜디오 설정: 스트림 키 보기·재발급 |
| 선 아이콘 | `src/components/icons.tsx` | 헤더, 사이드바, 내 채널, 방송 화면 |
| 플레이어(컨트롤, 단축키: 스페이스 재생/정지·`t` 넓은 화면·`f` 전체 화면) | `src/pages/LivePlayer.tsx` | 방송 화면 |
| 플레이어 설정 메뉴(해상도·라디오 모드·재생 문제 제보) | `src/pages/PlayerSettings.tsx` | 방송 화면 |
| 채팅 칸 | `src/components/ChatPanel.tsx` | 방송 화면 |
| 동영상 카드 목록(더보기 → 전체보기) | `src/components/ChannelVideos.tsx` | 방송 화면. 지금은 예시 데이터(`src/pages/sampleVideos.ts`) |
| 소셜 로그인 버튼 | `src/components/SocialLoginButtons.tsx` | 로그인 |
| 이미지 아이콘 파일 | `src/assets/icons/` | 소셜 로그인 |

## 6. 벤치마킹

- 참고 대상: 헤더·사이드바·방송 화면·스튜디오(대시보드)는 치지직, 로그인은 트위치. 스튜디오는 밝은 화면이지만 여기서는 어두운 테마 변수로 옮긴다.
- 배치·크기·간격·정보 구성은 따라 한다. 로고, 브랜드 색, 아이콘·이미지 파일은 가져오지 않는다. 아이콘은 직접 그리거나 각 회사가 공식 배포한 파일을 쓴다.
- 서비스 이름은 임시로 "조타". 로고 자리에 글자로 둔다.
- 기능이 없는 메뉴·버튼은 모양만 두고, 코드 주석에 "모양만 있다"고 적는다.
