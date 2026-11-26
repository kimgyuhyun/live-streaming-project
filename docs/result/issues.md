# 이슈 목록 — 계정, 채널·송출

> 상태: **확정, GitHub 등록 완료** (2026-11-20) — #1~#14(#26은 2026-11-21에 더함), 라벨 `member`·`channel`.
> 근거: `docs/result/spec-fixed.md`, `docs/result/prd/member.md`, `docs/result/prd/channel.md`
> 번호는 GitHub 번호와 같다(이 저장소의 이슈·PR이 0개라 #1부터 이어진다).

각 이슈는 **사용자가 볼 수 있는 동작** 하나다(수직 슬라이스). 테이블·API·화면을 이슈 안에서 함께 만든다. 화면이 없는 이슈는 "통합 테스트 결과가 데모"라고 적었다.

## 이슈 전 셋업 (이슈가 아님)

- [ ] 프론트: React Router, React Query, API 호출 공통 함수(쿠키 포함, 에러 응답 읽기) 뼈대 — #1 전에
- [ ] 프론트 테스트(Vitest): 화면 상태 로직이 처음 생기는 #12에서 넣는다 (CLAUDE.md)
- [ ] 의존성: Spring Security·Spring Session·`spring-security-test`는 #1, `mybatis-spring-boot-starter-test`는 첫 매퍼 테스트에서 추가한다

## 한눈에

| # | 제목 | 도메인 | 선행 | 크기 |
| --- | --- | --- | --- | --- |
| 1 | 이메일로 가입한다 | member | 없음 | 1일 |
| 2 | 로그인·로그아웃하고 내 정보를 본다 | member | #1 | 1일 |
| 3 | 로그인을 연속으로 틀리면 잠시 막힌다 | member | #2 | 1일 |
| 4 | 닉네임과 비밀번호를 바꾼다 | member | #2 | 반나절 |
| 5 | 운영자 계정이 앱 시작 때 준비된다 | member | #2 | 반나절 |
| 6 | 회원이 스트리머를 신청하고 상태를 본다 | channel | #2 | 반나절 |
| 7 | 운영자가 신청을 승인하면 채널이 생긴다 | channel | #5, #6 | 1일 |
| 8 | 채널 페이지를 보고 스트리머가 채널 정보를 고친다 | channel | #7 | 반나절 |
| 9 | 스튜디오에서 스트림 키를 발급하고 OBS 설정을 본다 | channel | #7 | 반나절 |
| 10 | 키가 맞을 때만 송출되고, 재발급하면 이전 송출이 끊긴다 | channel | #9 | 1일 |
| 11 | 송출이 시작·종료되면 채널이 방송 중·오프라인이 된다 | channel | #10 | 1일 |
| 12 | 채널 페이지에서 방송을 본다 | channel | #8, #11 | 1일 |
| 13 | 스튜디오에서 방송 제목을 정하고 연결 상태를 본다 | channel | #11 | 반나절 |
| 14 | 데모 방송 3개가 뜬다 | channel | #9, #12 | 1일 |
| 26 | MediaMTX API는 Spring만 부를 수 있다 | channel | #10 | 반나절 |
| 29 | 시청자가 몰려도 채널 조회는 nginx에서 1초 캐시로 답한다 | channel | #12 | 반나절 |
| 31 | 공개 API는 IP당 요청 수를 제한한다 | channel | #29 | 1일 |


---

## #1 이메일로 가입한다

비로그인 시청자가 이메일·비밀번호·닉네임으로 가입한다. 회원 테이블 첫 마이그레이션, Spring Security 기본 설정(가입 경로 허용, 출처 검증 필터), 공통 에러 응답 형식, 가입 화면을 만든다. 1일.

**선행**: 없음

## Acceptance Criteria
- [ ] Given 가입된 적 없는 이메일, When 이메일·비밀번호 `abcd1234`·닉네임 `시청자1`로 가입하면, Then 201이고 역할 `MEMBER`인 회원이 생기며 비밀번호는 BCrypt 해시로만 저장된다
- [ ] Given `A@b.com`으로 가입된 회원, When `a@b.com`으로 가입하면, Then 409(이메일 중복)이다
- [ ] Given 가입 요청, When 비밀번호가 7자이거나 65자이거나 영문만·숫자만이면, Then 400이고, 8자·64자의 영문+숫자면 가입된다
- [ ] Given 가입 요청, When 닉네임이 1자·13자이거나 한글·영문·숫자 외 문자를 포함하면, Then 400이고, 2자·12자면 가입된다
- [ ] Given 닉네임 `시청자1`인 회원, When 같은 닉네임으로 가입하면, Then 409(닉네임 중복)이다
- [ ] Given 같은 닉네임으로 가입 요청 2개가 동시에 오면, When 둘 다 처리되면, Then 회원은 하나만 생기고 다른 하나는 409다
- [ ] Given `Origin` 헤더가 우리 도메인이 아닌 가입 요청, When 보내면, Then 403이다. `Origin`·`Referer`가 둘 다 없어도 403이다
- [ ] Given 가입 화면, When 가입에 성공하면, Then 로그인 화면으로 가고, 실패하면 서버가 준 메시지를 보여 준다

PRD: docs/result/prd/member.md

---

## #2 로그인·로그아웃하고 내 정보를 본다

회원이 로그인하면 세션(Redis, 색인 저장소)이 생기고, 로그아웃하면 사라진다. 로그인 화면과, 모든 화면 위에 닉네임을 보여 주는 머리글을 만든다. 1일.

**선행**: #1

## Acceptance Criteria
- [ ] Given 가입된 회원, When 맞는 비밀번호로 로그인하면, Then 200이고 `HttpOnly`·`SameSite=Lax` 세션 쿠키가 오며, 그 쿠키로 내 정보를 조회하면 이메일·닉네임·역할이 나온다
- [ ] Given 로그인 전에 받은 세션 ID, When 로그인하면, Then 로그인 후의 세션 ID는 그것과 다르다
- [ ] Given 가입된 회원, When 비밀번호를 틀리거나 없는 이메일로 로그인하면, Then 둘 다 401이고 메시지는 똑같이 "이메일 또는 비밀번호가 맞지 않습니다"다
- [ ] Given 없는 이메일로 로그인, When 처리하면, Then 비밀번호 비교(가짜 해시)가 한 번 일어난다
- [ ] Given `A@b.com`으로 가입한 회원, When `a@B.COM`으로 로그인하면, Then 로그인된다
- [ ] Given 로그인한 회원, When 로그아웃하면, Then 같은 쿠키로 내 정보를 조회할 때 401이다
- [ ] Given 로그인한 회원, When 세션을 보면, Then Redis에 저장돼 있고 유휴 만료 시간이 7일이다
- [ ] Given 로그인하지 않은 요청, When 내 정보를 조회하면, Then 401이다

PRD: docs/result/prd/member.md

---

## #3 로그인을 연속으로 틀리면 잠시 막힌다

이메일 기준(5번 연속 → 15분)과 IP 기준(15분 안에 20번 → 15분) 로그인 시도 제한. 비밀번호를 비교하기 전에 Redis 카운터를 먼저 올린다(계정 M3). 로그인 화면에 남은 시간을 보여 준다. 1일.

**선행**: #2

## Acceptance Criteria
- [ ] Given 같은 이메일로 5번 실패한 상태, When 맞는 비밀번호로 6번째 로그인하면, Then 429이고 남은 시간(초)이 응답에 있다
- [ ] Given 같은 이메일로 4번 실패한 상태, When 맞는 비밀번호로 로그인하면, Then 성공하고, 그 뒤 다시 5번을 틀려야 막힌다
- [ ] Given 5번째 실패 직후, When 차단 응답을 받으면, Then 남은 시간은 900초 이하이고 5번째 실패 시각 기준이다
- [ ] Given 4번 실패, When 각 실패가 일어날 때마다, Then 이메일 카운터의 만료가 15분으로 다시 잡힌다(마지막 실패 기준)
- [ ] Given 같은 IP에서 서로 다른 이메일로 20번 실패, When 21번째로 로그인하면, Then 429다. IP 카운터의 만료는 첫 실패 기준 15분이다
- [ ] Given 다른 IP에서 온 요청, When 실패를 세면, Then IP 카운터가 따로 센다(nginx가 넘긴 실제 IP 기준)
- [ ] Given 같은 이메일로 틀린 비밀번호 로그인 요청 20개가 동시에 오면, When 모두 처리되면, Then 비밀번호 비교는 5번까지만 일어난다
- [ ] Given 로그인 화면, When 429를 받으면, Then "로그인 시도가 너무 많습니다. N분 후 다시 시도해 주세요"를 보여 준다

PRD: docs/result/prd/member.md

---

## #4 닉네임과 비밀번호를 바꾼다

내 정보 화면에서 닉네임과 비밀번호를 바꾼다. 비밀번호를 바꾸면 다른 기기의 세션이 지워진다(계정 M2). 반나절.

**선행**: #2

## Acceptance Criteria
- [ ] Given 로그인한 회원, When 닉네임을 2~12자 규칙에 맞게 바꾸면, Then 바뀌고, 다른 회원이 쓰는 닉네임이면 409다
- [ ] Given 로그인한 회원, When 현재 비밀번호를 틀린 채 비밀번호 변경을 요청하면, Then 400이고 비밀번호는 그대로다
- [ ] Given 기기 A·B에 로그인한 회원, When A에서 비밀번호를 바꾸면, Then B의 쿠키로 내 정보를 조회할 때 401이고 A는 여전히 200이다
- [ ] Given 비밀번호를 바꾼 회원, When 새 비밀번호로 로그인하면, Then 성공하고, 이전 비밀번호로는 401이다
- [ ] Given 새 비밀번호가 규칙(8~64자, 영문+숫자)에 맞지 않으면, When 바꾸면, Then 400이다

PRD: docs/result/prd/member.md

---

## #5 운영자 계정이 앱 시작 때 준비된다

`OPERATOR_EMAIL`·`OPERATOR_PASSWORD`가 있으면 앱 시작 때 운영자 계정을 만든다(계정 M4). 화면이 없다 — **통합 테스트 결과가 데모다.** 반나절.

**선행**: #2

## Acceptance Criteria
- [ ] Given 두 환경변수가 있고 그 이메일의 계정이 없음, When 앱이 시작하면, Then 역할 `OPERATOR` 계정이 생기고 그 비밀번호로 로그인하면 내 정보의 역할이 `OPERATOR`다
- [ ] Given 그 이메일의 계정이 이미 있음, When 다른 비밀번호로 앱이 시작하면, Then 계정은 바뀌지 않는다(이전 비밀번호로 로그인된다)
- [ ] Given 환경변수가 없거나 둘 중 하나만 있음, When 앱이 시작하면, Then 운영자 계정을 만들지 않고 앱은 정상으로 뜬다
- [ ] Given 계정이 없음, When 운영자 생성이 두 번 동시에 실행되면, Then 계정은 하나이고 둘 다 예외 없이 끝난다
- [ ] `.env.example`에 두 키가 값 없이 적혀 있다

PRD: docs/result/prd/member.md

---

## #6 회원이 스트리머를 신청하고 상태를 본다

회원이 스트리머 신청을 하고 내 신청 상태(없음·대기·승인·거절)를 본다. 내 정보 화면에 신청 버튼과 상태를 둔다. 반나절.

**선행**: #2

## Acceptance Criteria
- [ ] Given 신청한 적 없는 회원, When 신청하면, Then 201이고 내 신청 상태가 `대기`다
- [ ] Given 대기 중인 신청이 있는 회원, When 다시 신청하면, Then 409다
- [ ] Given 신청한 적 없는 회원, When 신청 요청 2개가 동시에 오면, Then 대기 신청은 하나만 생긴다
- [ ] Given 신청이 거절된 회원, When 다시 신청하면, Then 새 대기 신청이 생긴다
- [ ] Given 스트리머나 운영자, When 신청하면, Then 409다
- [ ] Given 로그인하지 않은 요청, When 신청하면, Then 401이다

PRD: docs/result/prd/channel.md

---

## #7 운영자가 신청을 승인하면 채널이 생긴다

운영자가 대기 목록을 보고 승인·거절한다(화면 없는 API). 승인하면 역할 변경·채널 생성이 한 트랜잭션으로 일어나고, 그 회원의 세션이 지워진다. 1일.

**선행**: #5, #6

## Acceptance Criteria
- [ ] Given 대기 신청 3개, When 운영자가 대기 목록을 조회하면, Then 신청 시각이 오래된 순으로 닉네임·이메일·신청 시각이 나온다
- [ ] Given 대기 신청, When 운영자가 승인하면, Then 신청은 `승인`, 회원 역할은 `STREAMER`가 되고, 이름이 닉네임이고 소개가 빈 채널이 하나 생긴다
- [ ] Given 로그인해 있는 신청 회원, When 승인되면, Then 그 회원의 기존 쿠키로 내 정보를 조회할 때 401이고, 다시 로그인하면 역할이 `STREAMER`다
- [ ] Given 대기 신청, When 운영자가 거절하면, Then 신청은 `거절`이고 회원 역할은 `MEMBER` 그대로다
- [ ] Given 이미 승인·거절된 신청, When 다시 승인·거절하면, Then 409다
- [ ] Given 대기 신청, When 승인 요청 2개가 동시에 오면, Then 채널은 하나만 생긴다
- [ ] Given 승인 중 채널 생성이 실패하면, When 트랜잭션이 끝나면, Then 신청은 `대기`, 역할은 `MEMBER` 그대로다
- [ ] Given 운영자가 아닌 회원, When 대기 목록·승인·거절을 요청하면, Then 403이다

PRD: docs/result/prd/channel.md

---

## #8 채널 페이지를 보고 스트리머가 채널 정보를 고친다

누구나 채널 페이지(이름·소개·방송 상태)를 본다. 스트리머는 스튜디오에서 채널 이름·소개를 고친다. 이 이슈에서는 방송 상태가 늘 `오프라인`이다(#11에서 바뀐다). 반나절.

**선행**: #7

## Acceptance Criteria
- [ ] Given 채널, When 비로그인 시청자가 채널 페이지를 보면, Then 채널 이름·소개·`오프라인`이 보인다
- [ ] Given 없는 채널 ID, When 조회하면, Then 404다
- [ ] Given 채널 주인인 스트리머, When 이름을 2자·20자로, 소개를 0자·200자로 바꾸면, Then 바뀌고, 이름 1자·21자나 소개 201자면 400이다
- [ ] Given 다른 스트리머의 채널, When 고치려 하면, Then 403이다

PRD: docs/result/prd/channel.md

---

## #9 스튜디오에서 스트림 키를 발급하고 OBS 설정을 본다

스트리머가 스튜디오에서 스트림 키를 발급·재발급한다. 키는 해시와 앞 4자리만 저장한다(채널 C1). OBS에 넣을 값과 키프레임 안내를 보여 준다. 이 이슈는 송출 연결 끊기를 하지 않는다(#10). 반나절.

**선행**: #7

## Acceptance Criteria
- [ ] Given 키가 없는 스트리머, When 발급하면, Then 응답에 전체 키가 있고, DB에는 키의 SHA-256 해시와 앞 4자리만 있다
- [ ] Given 키가 있는 스트리머, When 스튜디오에서 키를 조회하면, Then 앞 4자리만 오고 전체 키는 응답 어디에도 없다
- [ ] Given 키가 있는 스트리머, When 재발급하면, Then 새 전체 키가 응답에 있고, 저장된 해시가 새 키의 해시로 바뀐다
- [ ] Given 발급된 키, When 길이와 문자를 보면, Then 32바이트 난수를 URL에 안전한 문자로 만든 것이다
- [ ] Given 스튜디오, When OBS 안내를 보면, Then 서버 `rtmp://{서버}/live`, 스트림 키 칸에 넣을 값 `{채널ID}?key={키}`, 키프레임 간격 1~2초 안내가 있다
- [ ] Given 스트리머가 아닌 회원·비로그인, When 키를 발급·조회하면, Then 403·401이다

PRD: docs/result/prd/channel.md

---

## #10 키가 맞을 때만 송출되고, 재발급하면 이전 송출이 끊긴다

MediaMTX가 송출 시작 때 Spring 내부 엔드포인트로 키를 확인한다. 같은 채널 두 번째 송출을 거부하고(`overridePublisher: false`), 재발급하면 MediaMTX API로 그 채널 송출을 끊는다. nginx는 `/internal/`을 넘기지 않는다. 1일.

**선행**: #9

## Acceptance Criteria
- [ ] Given 채널 7과 그 키, When MediaMTX가 `action: publish`, `path: live/7`, `query: key={키}`로 확인을 요청하면, Then 200이다
- [ ] Given 같은 요청, When 키가 다르거나·없거나·채널이 없거나·경로가 `live/{숫자}`가 아니면, Then 401이다
- [ ] Given 키를 재발급한 스트리머, When 저장을 마치면, Then 그 다음에 MediaMTX API로 `live/{채널ID}`의 송출 연결을 끊는다(순서: 저장 → 끊기)
- [ ] Given 송출 중인 연결이 없거나 MediaMTX API가 실패하면, When 재발급하면, Then 재발급은 성공한다
- [ ] Given 로컬 compose, When ffmpeg로 맞는 키 송출 → 키 재발급하면, Then 송출이 끊기고 이전 키로 다시 붙으면 거부된다(확인 결과를 PR에 남긴다)
- [ ] Given 로컬 compose에서 채널이 송출 중, When 같은 채널로 두 번째 송출을 하면, Then 거부되고 첫 송출은 그대로다(확인 결과를 PR에 남긴다)
- [ ] Given nginx, When 바깥에서 `/internal/` 경로를 요청하면, Then Spring에 닿지 않는다(404)

PRD: docs/result/prd/channel.md

---

## #11 송출이 시작·종료되면 채널이 방송 중·오프라인이 된다

MediaMTX 훅(`runOnAvailable`·`runOnUnavailable`)으로 방송 시작·종료를 받고, 1분마다 MediaMTX API와 대조한다(채널 C2). MediaMTX 이미지를 `-ffmpeg`로 바꾼다. 채널 페이지 상태가 실제로 바뀐다. 1일.

**선행**: #10

## Acceptance Criteria
- [ ] Given 오프라인 채널 7, When `live/7` 시작 알림이 오면, Then 시작 시각이 있는 진행 중 방송이 생기고 채널 페이지 상태가 `방송 중`이다
- [ ] Given 같은 시작 알림, When 두 번 오면, Then 진행 중 방송은 하나다
- [ ] Given 진행 중 방송, When `live/7` 종료 알림이 오면, Then 방송에 종료 시각이 찍히고 채널은 `오프라인`이다
- [ ] Given `live/`가 아닌 경로(`720/7` 등)의 알림, When 오면, Then 아무것도 바뀌지 않는다
- [ ] Given MediaMTX에는 송출 중인데 DB에 진행 중 방송이 없음, When 대조하면, Then 방송이 시작된다
- [ ] Given DB에는 진행 중인데 MediaMTX에 송출이 없음, When 대조하면, Then 방송이 종료된다
- [ ] Given 대조가 두 서버에서 동시에 돌면, When 같은 채널을 시작 처리하면, Then 진행 중 방송은 하나다(DB 제약)
- [ ] Given 로컬 compose, When ffmpeg로 송출을 시작했다 끊으면, Then 채널 페이지 상태가 몇 초 안에 `방송 중` → `오프라인`으로 바뀐다(확인 결과를 PR에 남긴다)

PRD: docs/result/prd/channel.md

---

## #12 채널 페이지에서 방송을 본다

채널 페이지에 hls.js 플레이어를 붙인다. 방송 중이면 재생하고, 오프라인이면 오프라인 표시, 보는 중에 끝나면 종료 문구를 보여 준다. 프론트 화면 상태 로직이 처음 생기므로 Vitest를 넣는다. 1일.

**선행**: #8, #11

## Acceptance Criteria
- [ ] Given 방송 중인 채널, When 비로그인 시청자가 채널 페이지를 열면, Then 플레이어가 `/hls/live/{채널ID}/index.m3u8`을 재생하고 방송 제목이 보인다
- [ ] Given 오프라인 채널, When 채널 페이지를 열면, Then 플레이어 대신 오프라인 표시가 보인다
- [ ] Given 시청 중, When 방송이 끝나면, Then 플레이어 자리에 "방송이 종료되었습니다"가 보인다
- [ ] Given 채널 페이지 상태 로직, When 방송 상태가 `방송 중`·`오프라인`·`종료됨`으로 바뀌면, Then 각각 맞는 화면을 고른다(Vitest)

PRD: docs/result/prd/channel.md

---

## #13 스튜디오에서 방송 제목을 정하고 연결 상태를 본다

스트리머가 방송 제목을 미리 정하거나 방송 중에 바꾸고, 지금 송출이 들어오는지 본다. 반나절.

**선행**: #11

## Acceptance Criteria
- [ ] Given 스트리머, When 제목을 1자·50자로 정하면, Then 저장되고, 0자(공백만 포함)·51자면 400이다
- [ ] Given 제목을 정한 적 없는 채널 `하늘`, When 채널 페이지를 보면, Then 방송 제목은 `하늘의 방송`이다
- [ ] Given 방송 중인 채널, When 스트리머가 제목을 바꾸면, Then 채널 페이지를 다시 조회할 때 새 제목이 보인다
- [ ] Given 진행 중 방송이 있는/없는 채널, When 스튜디오 연결 상태를 보면, Then `송출 중`/`송출 없음`이다

PRD: docs/result/prd/channel.md

---

## #14 데모 방송 3개가 뜬다

준비 스크립트가 공개 API로 가입 → 신청 → 운영자 승인 → 키 발급을 밟아 데모 스트리머를 만들고, 운영자 API로 데모 표시를 붙인다(채널 C3). 시뮬레이터(FFmpeg 컨테이너)가 영상 파일을 반복 송출한다. 1일.

**선행**: #9, #12

## Acceptance Criteria
- [ ] Given 비어 있는 DB와 운영자 계정, When 준비 스크립트를 3개로 돌리면, Then 데모 스트리머·채널 3개가 생기고 키가 git 제외된 시뮬레이터 설정 파일에 적힌다
- [ ] Given 준비가 끝난 상태, When 시뮬레이터를 띄우면, Then 데모 채널 3개 모두 `방송 중`이고 채널 페이지에서 재생된다
- [ ] Given 데모 채널, When 채널 페이지를 보면, Then "데모 채널" 표시가 보인다
- [ ] Given 운영자가 아닌 회원, When 데모 표시를 붙이려 하면, Then 403이다
- [ ] Given 돌고 있는 데모, When 준비 스크립트를 다시 돌리면, Then 키가 재발급되고 시뮬레이터가 새 키로 다시 송출해 3개 모두 다시 `방송 중`이 된다
- [ ] Given 데모 영상 파일, When 출처를 보면, Then 직접 만들었거나 녹화한 것이거나 CC BY 영상이며 README에 출처가 적혀 있다

PRD: docs/result/prd/channel.md


---

## #26 MediaMTX API는 Spring만 부를 수 있다

> #1~#14 뒤에 더한 이슈(2026-11-21). #10 보안 점검 🟡 1을 사용자가 별도 이슈로 정했다. GitHub 번호는 PR과 같은 순번을 써서 #26이다.

MediaMTX API(내부 :9997)가 인증 없이 도커 내부망 전체에 열려 있는 것(#10 보안 점검 🟡 1)을 막는다. `authHTTPExclude`에서 `api`를 빼 API 호출도 Spring 키 확인 엔드포인트가 판단하게 하고, Spring의 MediaMTX 클라이언트는 환경변수의 내부 비밀번호를 Basic 인증으로 싣는다. 채널 PRD "키 확인 연동"(API 호출은 인증에서 뺀다)을 바꾸므로 PRD 표도 고친다. 네트워크 분리로는 막을 수 없다 — nginx가 HLS(:8888) 때문에 mediamtx와 같은 망에 있어야 한다. 반나절.

**선행**: #10

## Acceptance Criteria
- [ ] Given 설정된 내부 비밀번호, When MediaMTX가 `action: api`와 그 사용자·비밀번호로 확인을 요청하면, Then 200이다
- [ ] Given 같은 요청, When 비밀번호가 다르거나·비었거나·내부 비밀번호가 설정되지 않았으면, Then 401이다
- [ ] Given 재발급(끊기)·1분 대조, When Spring이 MediaMTX API를 부르면, Then 내부 비밀번호를 Basic 인증으로 싣는다
- [ ] Given 로컬 compose, When 내부망의 다른 컨테이너에서 자격 없이 `/v3/rtmpconns/list`를 부르면, Then 401이다(확인 결과를 PR에 남긴다)
- [ ] Given 로컬 compose, When 송출 중 키를 재발급하고 1분 대조가 돌면, Then 송출 끊기와 대조가 그대로 동작한다(확인 결과를 PR에 남긴다)
- [ ] Given 키 확인 요청 처리, When 로그·응답을 보면, Then 내부 비밀번호가 남지 않는다

PRD: docs/result/prd/channel.md

---

## #29 시청자가 몰려도 채널 조회는 nginx에서 1초 캐시로 답한다

> #12 보안 점검 🟡(비로그인 시청자의 폴링 부하)를 사용자가 별도 이슈로 정했다(2026-11-21). 규모가 작아도 미리 대비한다. 본문·AC는 GitHub 이슈와 같다.

#12에서 방송 중인 채널을 보는 시청자는 5초마다 채널 API(`GET /api/channels/{id}`)를 다시 묻는다. 지금은 이 요청이 모두 Spring·Oracle까지 간다. 공개 응답이고 5초 폴링이라 1초 늦게 반영돼도 되므로, nginx에서 채널 조회를 1초 캐시한다. k6로 캐시 전 기준선을 먼저 재고, 같은 조건으로 다시 재서 비교한다. 반나절.

Redis 캐시는 넣지 않는다. nginx 뒤에서는 채널당 초당 1건 이하만 받아 효과가 없다. 사용자마다 다른 응답이나 무거운 목록 조회처럼 nginx가 캐시할 수 없는 곳이 측정으로 드러나면 그때 다룬다.

**선행**: #12

## 합격 기준 (측정 전에 정함, 바꾸지 않는다)

- 목표 부하: 방송 중 채널 5개를 시청자 1,000명이 보는 상황 = 채널 조회 200 req/s(시청자당 5초에 1번). 인기 채널 하나가 60%, 나머지 넷이 10%씩
- 로컬 compose(서버와 같은 컨테이너 자원 제한), nginx(`localhost:8080`)를 거쳐서
- 목표 부하에서 **p95 ≤ 200ms, 오류율 < 0.1%** (k6 `thresholds`로 박는다)
- 포화 지점: 50 → 100 → 200 → 400 → 800 → 1600 req/s 계단(단계당 30초)에서 처리량·p50/p95/p99·오류율과 컨테이너 CPU를 남긴다

## Acceptance Criteria
- [ ] Given 같은 채널 조회가 1초 안에 다시 오면, When nginx를 거치면, Then Spring에 다시 묻지 않고 캐시에서 답한다
- [ ] Given 캐시된 지 1초가 지나면, When 다시 조회하면, Then Spring에 다시 물어 새 값(제목·방송 상태)을 준다
- [ ] Given 캐시가 빈 채널에 동시에 여러 요청이 오면, When nginx를 거치면, Then Spring에는 한 번만 간다
- [ ] Given query string만 다른 같은 채널 조회, When nginx를 거치면, Then 같은 캐시로 답한다(query를 바꿔 캐시를 우회할 수 없다)
- [ ] Given 채널 조회가 아닌 API(`/api/studio/**`, `/api/members/me` 등)·쓰기 요청·오류 응답, When nginx를 거치면, Then 캐시하지 않는다
- [ ] Given k6 시청자 폴링 시나리오, When 캐시 전후를 같은 조건으로 재면, Then 시나리오·도구 버전·대상 커밋·데이터 규모·원시 결과·자원 지표가 저장소에 남고, 캐시 후 목표 부하에서 합격 기준을 넘는다

PRD: docs/result/prd/channel.md

---

## #31 공개 API는 IP당 요청 수를 제한한다

> #29 보안 점검 🟡(캐시 우회)를 사용자가 정리했다(2026-11-21): 캐시 오염은 #29 뒤 정규식으로 막고, 우회 전체는 이 이슈의 요청 수 제한으로 막는다. 본문·AC는 GitHub 이슈와 같다.

#29의 nginx 캐시는 **같은 채널을 반복해 묻는 요청**만 줄인다. 키가 매번 다른 요청은 그대로 Spring·Oracle까지 간다 — 없는 채널 id(404는 담지 않는다), `/api/channels/021` 같은 앞자리 0 변형(#29 보안 점검 🟡, 캐시 오염은 정규식으로 막았지만 Spring까지는 간다), 캐시 밖의 공개 API. 클라이언트 하나가 이런 요청을 몰아 보내면 #29에서 잰 포화 지점(캐시 없이 약 1,400~1,500 req/s, api CPU 한도) 근처에서 정상 시청자까지 느려진다. nginx `limit_req`로 IP당 요청 수를 제한한다. #29처럼 기준선을 먼저 재고 같은 조건으로 다시 잰다. 1일.

**선행**: #29

## 이 이슈에서 정할 것 (측정 전에 이슈에 적고 바꾸지 않는다)

- 한도 값(초당 요청 수·버스트)과 근거 — 정상 시청자는 채널당 5초에 한 번 묻고(#12), 학교·회사처럼 한 IP(NAT) 뒤에 시청자 여럿이 있을 수 있다
- 셀 범위 — 공개 API 전체인지 캐시를 지나친 요청만인지, 캐시 HIT를 세는지
- 로그인 시도 제한(#3, `MEMBER_LOGIN_BLOCKED` 429)과 겹치지 않게 하는 방법
- 합격 기준 — 남용 시나리오(한 IP가 키를 바꿔 가며 초당 수천 건)와 정상 시청자 부하를 함께 걸었을 때 정상 시청자의 p95·오류율 상한

## Acceptance Criteria
- [ ] Given 한 IP가 한도를 넘게 공개 API를 부르면, When nginx를 거치면, Then 넘친 요청은 429이고 Spring에는 가지 않는다
- [ ] Given 한도 안의 요청(한 IP 뒤 시청자 여럿의 5초 폴링 포함), When nginx를 거치면, Then 모두 정상 응답이다
- [ ] Given 서로 다른 IP, When 한 IP가 한도를 넘으면, Then 다른 IP의 요청은 영향받지 않는다
- [ ] Given 서버의 edge(Caddy)를 거친 요청, When IP를 셀 때, Then edge 대역에서 온 `X-Forwarded-For`의 실제 사용자 IP로 센다(그 밖의 출처가 보낸 `X-Forwarded-For`는 믿지 않는다)
- [ ] Given 429 응답, When 프론트가 받으면, Then 오류 본문(`code`·`message`)을 읽어 "잠시 후 다시 시도" 문구를 보인다
- [ ] Given k6 남용 시나리오와 정상 시청자 부하, When 제한 전후를 같은 조건으로 재면, Then 시나리오·도구 버전·대상 커밋·원시 결과·자원 지표가 저장소에 남고, 제한 뒤 정상 시청자가 합격 기준을 넘는다

PRD: docs/result/prd/channel.md

---

## #35 스튜디오에 처음 들어갈 때 약관에 동의한다

> 2026-11-26 사용자 요청으로 추가했다(화면 벤치마킹 중 스튜디오 약관 동의 팝업). 본문·AC는 GitHub 이슈와 같다.

스트리머가 스튜디오를 처음 열 때 약관 동의 팝업을 띄운다. 동의 기록은 서버(DB)에 남기고, 동의하기 전에는 서버가 스튜디오 API를 막는다. 1일.

**선행**: #13

## 이 이슈에서 정한 것

- 약관 항목: [필수] 스튜디오 이용약관(`STUDIO_TERMS`, 버전 1), [필수] 콘텐츠 가이드라인(`CONTENT_GUIDELINE`, 버전 1). 클립 제작허용 안내는 넣지 않는다(클립 기능을 하지 않는다)
- 저장: 새 테이블 `STUDIO_TERMS_AGREEMENT`(회원·약관 코드·약관 버전·동의 시각), 마이그레이션 V8. 버전을 같이 남겨 약관을 바꾸면 버전만 올려 다시 동의받을 수 있게 한다
- API: `GET /api/studio/terms`(동의 여부), `POST /api/studio/terms-agreement`(동의). 나머지 `/api/studio/**`는 동의 전 403(`STUDIO_TERMS_NOT_AGREED`)
- 영향: 기존 스튜디오 통합 테스트와 `demo/setup.mjs`에 "먼저 동의" 단계가 들어간다. MediaMTX 키 검증(내부 API)은 그대로
- "자세히 보기": 약관 전문을 보인다. 다른 서비스 약관을 옮기지 않고 이 서비스 기능으로 직접 썼다(포트폴리오용 예시, 법적 효력 없음을 밝힌다. 2026-11-26 사용자 결정)
- 운영자는 헤더에 스튜디오 버튼이 보이지만(편의상) 동의 여부 조회부터 403이라, 팝업 없이 기존 "스트리머만 쓸 수 있습니다" 화면으로 간다

## Acceptance Criteria
- [ ] Given 약관에 동의하지 않은 스트리머, When 동의 여부를 물으면, Then `agreed: false`다
- [ ] Given 스트리머, When 필수 약관 두 개에 동의하면, Then 204이고 이후 동의 여부는 `agreed: true`다
- [ ] Given 필수 약관이 하나라도 빠진 요청, When 동의하면, Then 400이고 아무것도 저장되지 않는다
- [ ] Given 이미 동의한 스트리머, When 다시 동의하면, Then 204이고 기록은 늘지 않는다
- [ ] Given 동의하지 않은 스트리머, When 스튜디오 API(내 채널 조회·제목 변경·키 발급·키 조회)를 부르면, Then 403(`STUDIO_TERMS_NOT_AGREED`)이고, 동의한 뒤에는 지금처럼 동작한다
- [ ] Given 회원·운영자, When 동의 API를 부르면, Then 403이다(스튜디오는 스트리머만)
- [ ] Given 동의하지 않은 스트리머, When 헤더 "스튜디오"·내 채널 "방송하기·채널 관리"를 누르거나 `/studio`로 바로 들어오면, Then 약관 동의 팝업이 뜨고, 필수 두 개를 체크해야 "스튜디오 가입"이 눌린다
- [ ] Given 팝업, When "모두 동의하기"를 누르면 전부 체크·해제되고, "취소"·X는 스튜디오로 가지 않고 닫는다
- [ ] Given 동의한 스트리머, When 스튜디오 버튼을 누르면, Then 팝업 없이 스튜디오로 간다
- [ ] Given 데모 설정 스크립트, When 데모 채널의 키를 발급하기 전에, Then 약관에 먼저 동의한다

PRD: docs/result/prd/channel.md

---

# 이슈 목록 — 시청자 활동·방송 목록 (viewer)

> 상태: **확정, GitHub 등록 완료** (2026-11-26) — #37~#42, 라벨 `viewer`.
> 근거: `docs/result/spec-fixed.md` 7절·9절(방송 목록), `docs/result/prd/viewer.md`(V1·V2), `docs/spikes/infra-validation.md` 4절
> 번호는 GitHub 번호와 같다(마지막 이슈·PR이 #36).

| # | 제목 | 도메인 | 선행 | 크기 |
| --- | --- | --- | --- | --- |
| 37 | 채널을 팔로우하고 채널 페이지에서 팔로워 수를 본다 | viewer | #12 | 1일 |
| 38 | 팔로잉 화면에서 팔로우한 채널을 방송 중 먼저 본다 | viewer | #37 | 반나절 |
| 39 | 채널 페이지에서 동시 시청자 수를 본다 | viewer | #11, #12 | 1일 |
| 40 | 한 곳(IP)에서 시청을 많이 열어도 5개까지만 센다 | viewer | #39 | 반나절 |
| 41 | 메인 화면에서 방송 중 목록을 시청자 수 순으로 본다 | viewer | #39 | 1일 |
| 42 | 시청자 수 집계와 방송 목록을 규모별로 잰다 | viewer | #40, #41 | 1일 |

---

## #37 채널을 팔로우하고 채널 페이지에서 팔로워 수를 본다

회원이 채널 페이지에서 팔로우·팔로우 취소를 하고, 채널의 팔로워 수를 본다. 지금 모양만 있는 팔로우 버튼과 "팔로워 0명"을 실제로 동작하게 한다. 1일.

**선행**: #12

## 이 이슈에서 정한 것

- 저장: 새 테이블 `follow(member_id, channel_id, created_at)`, 기본 키(member_id, channel_id), `channel_id` 색인, 회원·채널 외래 키. `channel.follower_count`(기본 0) 열 추가. 마이그레이션 V9 하나로
- 팔로워 수: 팔로우가 실제로 새로 들어갔을 때만 `follower_count = follower_count + 1`, 취소가 실제로 지웠을 때만 `- 1`을 같은 트랜잭션에서 한다(읽고 더해 쓰지 않는다)
- API: `PUT /api/channels/{id}/follow`(팔로우), `DELETE` 같은 주소(취소) — 둘 다 멱등, 204. `GET /api/channels/{id}/follow` → `{following}`. 자기 채널 400(`CANNOT_FOLLOW_OWN_CHANNEL`), 없는 채널 404(`CHANNEL_NOT_FOUND`), 비로그인 401
- 채널 조회(`GET /api/channels/{id}`) 응답에 `followerCount`. 내가 팔로우했는지는 넣지 않는다(nginx가 `$uri`로 1초 캐시한다 — 넣으면 남의 값이 보인다)
- 화면: 팔로우 버튼은 "팔로우"/"팔로잉"으로 바뀐다. 내 채널에서는 버튼이 없다. 비로그인이 누르면 로그인 화면. 채널 조회가 1초 캐시라, 응답 뒤 팔로워 수는 화면에서 바로 고쳐 보인다
- 동시성: 숫자 열 방어를 넣기 전에 동시 팔로우로 숫자가 어긋나는 것을 먼저 재현해 기록한다(`defect-repro`)

## Acceptance Criteria
- [ ] Given 다른 채널을 팔로우하지 않은 회원, When 팔로우하면, Then 204이고 팔로우 여부는 `following: true`, 채널 조회의 `followerCount`가 1 늘어난다
- [ ] Given 이미 팔로우한 회원, When 다시 팔로우하면, Then 204이고 `followerCount`는 그대로, `follow` 행은 하나다
- [ ] Given 팔로우한 회원, When 팔로우를 취소하면, Then 204이고 `following: false`, `followerCount`가 1 줄어든다
- [ ] Given 팔로우하지 않은 회원, When 취소하면, Then 204이고 `followerCount`는 그대로다(0 아래로 내려가지 않는다)
- [ ] Given 스트리머, When 자기 채널을 팔로우하면, Then 400(`CANNOT_FOLLOW_OWN_CHANNEL`)이고 아무것도 저장되지 않는다
- [ ] Given 없는 채널, When 팔로우하면, Then 404(`CHANNEL_NOT_FOUND`)다
- [ ] Given 비로그인, When 팔로우·취소·팔로우 여부 API를 부르면, Then 401이다
- [ ] Given 회원 20명이 같은 채널을 동시에 팔로우하고 그중 10명이 동시에 취소하면, Then `followerCount`는 `follow` 행 수(10)와 같다
- [ ] Given 한 회원이 같은 채널에 팔로우·취소를 동시에 여러 번 보내면, Then 끝난 뒤 `followerCount`는 그 채널의 `follow` 행 수와 같다
- [ ] Given 채널 페이지, When 회원이 "팔로우"를 누르면, Then 버튼이 "팔로잉"이 되고 "팔로워 N명"이 1 늘어나며, 다시 누르면 되돌아간다
- [ ] Given 내 채널 페이지, Then 팔로우 버튼이 없다
- [ ] Given 비로그인, When 채널 페이지에서 "팔로우"를 누르면, Then 로그인 화면으로 간다

PRD: docs/result/prd/viewer.md

---

## #38 팔로잉 화면에서 팔로우한 채널을 방송 중 먼저 본다

사이드바 "팔로잉"을 누르면 내가 팔로우한 채널 목록 화면이 열린다. 방송 중인 채널이 먼저, 그다음 오프라인 채널. 반나절.

**선행**: #37

## 이 이슈에서 정한 것

- API: `GET /api/me/following?offset=` → `{items: [{channelId, name, live, title}], hasMore}`. 20개씩. 내 것만 본다(다른 회원을 고르는 값이 없다)
- 순서: 방송 중 먼저, 각 묶음 안에서는 팔로우한 시각 최근 순. SQL 한 번(진행 중 여부는 `ChannelMapper.xml`의 `EXISTS` 식 그대로)
- 화면: `/following`. 사이드바 "팔로잉"(지금 모양만)이 이 화면으로 간다. 비로그인은 로그인 화면으로. "더 보기"로 20개씩 이어 붙인다

## Acceptance Criteria
- [ ] Given X·Y·Z 순서로 팔로우했고 Y만 방송 중, When 팔로잉을 보면, Then Y, Z, X 순이다
- [ ] Given 21개 채널을 팔로우, When 처음 보면, Then 20개와 `hasMore: true`, 다음 요청(`offset=20`)에서 1개와 `hasMore: false`다
- [ ] Given 팔로우한 채널이 없음, When 팔로잉 화면을 열면, Then "팔로우한 채널이 없습니다"가 보인다
- [ ] Given 비로그인, When 팔로잉 API를 부르면, Then 401이고, 사이드바 "팔로잉"을 누르면 로그인 화면으로 간다
- [ ] Given 팔로잉 화면, When 채널을 누르면, Then 그 채널 페이지로 간다. 방송 중인 채널에는 방송 중 표시와 방송 제목이 보인다

PRD: docs/result/prd/viewer.md

---

## #39 채널 페이지에서 동시 시청자 수를 본다

MediaMTX 시청 세션을 5초마다 모아 채널별 동시 시청자 수를 세고, 채널 페이지(방송 화면)에 보여 준다. 1일.

**선행**: #11, #12

## 이 이슈에서 정한 것

- 집계: Spring이 5초마다 `GET /v3/hlssessions/list`를 마지막 페이지까지 읽어 `live/{채널ID}`별 세션 수를 센다. 진행 중 방송이 없는 경로는 버린다. 세션에서는 `path`·`remoteAddr`만 읽고, 주소는 저장·로그하지 않는다
- 서버 2대: Redis 잠금(`SET NX`, 4초 만료)을 잡은 서버만 그 회차를 센다
- 판(스냅샷): 회차마다 `viewers:{판}:count`(방송ID → 수)를 쓰고, 다 쓴 다음 `viewers:current`를 새 판 번호로 바꾼다. 판은 2분 뒤 만료. 진행 중 방송은 0명이어도 넣는다
- 채널 조회 응답에 `viewerCount`: 지금 판의 값. 방송 중이 아니거나 판이 없으면 0
- MediaMTX 호출이 실패하면 그 회차는 건너뛰고(이전 판 유지) 실패만 남긴다. 회차마다 걸린 시간을 로그로 남긴다
- 화면: 방송 중이면 "N명 시청 중". 채널 조회를 이미 폴링하므로 그 값으로 갱신한다
- 테스트 설정은 주기 작업을 끈다(`app.scheduling.enabled=false`) — 집계 함수를 직접 부른다

## Acceptance Criteria
- [ ] Given 방송 중 채널 A에 세션 3개, B에 1개, 방송 중이 아닌 경로 C에 2개, When 집계하면, Then 판에 A=3, B=1이고 C는 없다
- [ ] Given 방송 중이고 세션이 없는 채널 D, When 집계하면, Then 판에 D=0이다
- [ ] Given 세션 목록이 여러 페이지, When 집계하면, Then 모든 페이지의 세션을 센다
- [ ] Given 집계가 끝남, When 채널 A를 조회하면, Then `viewerCount`가 3이다. 방송 중이 아닌 채널은 0이다
- [ ] Given 집계 객체 두 개(서버 2대)가 같은 회차에 동시에 돌면, Then MediaMTX 세션 목록은 한 번만 읽힌다
- [ ] Given 잠금을 잡은 쪽이 멈춰 잠금이 만료됨, When 다음 회차, Then 다른 쪽이 집계한다
- [ ] Given MediaMTX 호출이 실패, When 집계하면, Then 예외가 밖으로 나가지 않고 이전 판의 값이 그대로 보인다
- [ ] Given 판이 2분 넘게 새로 만들어지지 않음, When 채널을 조회하면, Then `viewerCount`는 0이다
- [ ] Given 방송 중인 채널 페이지, Then "N명 시청 중"이 보이고, 오프라인이면 보이지 않는다
- [ ] Given 로컬 도커에서 실제 시청, When 방송 화면을 열면 10초 안에 1 늘고, 닫으면 1분 안에 1 준다(수동 확인, 시각을 기록한다)

PRD: docs/result/prd/viewer.md

---

## #40 한 곳(IP)에서 시청을 많이 열어도 5개까지만 센다

시청자 수 부풀리기를 막는다. 같은 IP에서 같은 방송을 보는 세션은 5개까지만 센다. 그러려면 MediaMTX가 nginx 뒤에서도 진짜 시청자 주소를 봐야 한다. 반나절.

**선행**: #39

## 이 이슈에서 정한 것

- nginx `/hls/`가 `X-Forwarded-For`를 넘긴다(앞단 `real_ip` 설정으로 고친 진짜 주소)
- MediaMTX `hlsTrustedProxies`에 nginx가 있는 도커 대역을 넣는다(1.21.4에 있는 설정 — 바이너리에서 확인했다)
- 집계: (IP, 경로)마다 최대 5세션. 한도는 설정값(`app.viewer.max-sessions-per-ip`, 기본 5)
- 지금(설정 전)은 모든 세션이 nginx 내부 주소로 보인다(스파이크 4절). 설정 뒤 진짜 주소가 보이는지를 먼저 확인하고, 안 보이면 이 이슈를 멈추고 이유를 남긴다

## Acceptance Criteria
- [ ] Given 같은 IP의 세션 8개와 다른 IP의 세션 2개가 채널 A에, When 집계하면, Then A=7이다
- [ ] Given 같은 IP가 A에 5개, B에 5개, When 집계하면, Then A=5, B=5다(경로마다 센다)
- [ ] Given 로컬 도커에서 nginx를 거쳐 시청, When 세션 목록을 보면, Then `remoteAddr`가 nginx 내부 주소가 아니라 요청한 쪽 주소다(수동 확인, 결과를 기록한다)
- [ ] Given 집계, Then 로그·Redis 어디에도 시청자 IP가 남지 않는다

PRD: docs/result/prd/viewer.md

---

## #41 메인 화면에서 방송 중 목록을 시청자 수 순으로 본다

메인 화면에 지금 방송 중인 방송을 시청자 수가 많은 순으로 20개씩 보여 준다. 순위는 #39의 집계가 판마다 만든다. 1일.

**선행**: #39

## 이 이슈에서 정한 것

- 판에 순위를 더한다: `viewers:{판}:rank`(순위 집합 — 값 `{역순 시작 시각}:{방송ID}`, 점수 시청자 수). `count`와 `rank`를 다 쓴 다음 `viewers:current`를 바꾼다
- API: `GET /api/broadcasts?snapshot=&offset=` → `{snapshot, items: [{broadcastId, channelId, channelName, title, viewerCount, startedAt}], hasMore}`. 첫 요청은 지금 판, "더 보기"는 같은 판. 20개의 제목·채널 이름은 DB에서 한 번에 읽고 끝난 방송은 거른다
- 판이 만료된 `snapshot`으로 물으면 지금 판의 첫 묶음을 준다(화면은 목록을 새로 그린다)
- 집계가 멈춤(지금 판이 없거나 2분 넘음): DB의 진행 중 방송을 시작 순으로, `viewerCount` 없이, `snapshot: null`
- nginx: `snapshot` 없는 첫 묶음 요청만 1초 캐시(회원마다 다른 값이 없다)
- 화면: 메인(`/`)에 카드 목록 — 썸네일 자리(기본 이미지, 썸네일은 아직 없다), 방송 제목, 채널 이름, 시청자 수. 카드를 누르면 채널 페이지. "더 보기"로 이어 붙인다

## Acceptance Criteria
- [ ] Given 방송 세 개의 시청자 9, 5, 5(5명인 X가 Y보다 먼저 시작), When 목록을 보면, Then 9명, X, Y 순이다
- [ ] Given 시청자 0명인 방송, When 목록을 보면, Then 목록에 있다(맨 뒤)
- [ ] Given 방송 25개, When 처음 보면, Then 20개와 `hasMore: true`와 판 번호, 같은 판으로 다음 묶음에서 5개와 `hasMore: false`다
- [ ] Given 첫 묶음을 받은 뒤 새 판에서 순위가 바뀜, When 같은 판으로 다음 묶음을 받으면, Then 두 묶음 사이에 겹치거나 빠진 방송이 없다
- [ ] Given 판에는 있지만 그 뒤 끝난 방송, When 목록을 보면, Then 그 방송은 빠진다
- [ ] Given 만료된 판 번호, When 다음 묶음을 물으면, Then 지금 판의 첫 묶음과 새 판 번호가 온다
- [ ] Given 집계가 멈춰 판이 2분 넘음, When 목록을 보면, Then 진행 중 방송이 시작 순으로 오고 `viewerCount`가 없다
- [ ] Given 메인 화면, Then 카드마다 기본 썸네일·방송 제목·채널 이름·시청자 수가 보이고, 누르면 채널 페이지로 간다
- [ ] Given 방송 중인 방송이 없음, When 메인 화면을 열면, Then "지금 방송 중인 채널이 없습니다"가 보인다
- [ ] Given nginx, When `snapshot` 없는 목록을 1초 안에 두 번 요청하면, Then 두 번째는 캐시에서 온다(`X-Cache-Status: HIT`). `snapshot`이 있는 요청은 캐시하지 않는다

PRD: docs/result/prd/viewer.md

---

## #42 시청자 수 집계와 방송 목록을 규모별로 잰다

PRD viewer V1·V2의 측정 계획을 실행한다. 화면은 없고, 측정 결과가 데모다(#29·#31과 같은 방식). 1일.

**선행**: #40, #41

## 합격 기준 (측정 전에 정함, 바꾸지 않는다)

- 집계 한 번: 시청 세션 10,000개에서 5초 안
- 목록 API p95: 진행 중 방송 1,000개에서 100ms 안
- Redis 메모리: 판 전체(최대 약 24판)가 진행 중 방송 1,000개에서 10MB 안

## Acceptance Criteria
- [ ] Given 진행 중 방송 100·1,000개와 시청 세션 1,000·10,000개(가짜 MediaMTX 응답), When 집계를 돌리면, Then 회차마다 걸린 시간이 기록되고 합격 기준과 비교된다
- [ ] Given 같은 조건, When k6로 목록 API(첫 묶음·같은 판 다음 묶음)를 부르면, Then p95가 기록되고 합격 기준과 비교된다
- [ ] Given 같은 조건, When 판이 쌓인 뒤 Redis 메모리를 재면, Then 기록되고 합격 기준과 비교된다
- [ ] Given 측정, Then 시나리오·도구 버전·대상 커밋·원시 결과가 저장소(`perf/`)에 남고, 기준을 넘으면 원인과 다음 조치를 적는다

PRD: docs/result/prd/viewer.md
