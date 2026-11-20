package com.livestream.member;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

@Service
public class MemberService {

    private final MemberMapper memberMapper;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
    // 없는 이메일로 로그인할 때 비교할 해시. 어떤 비밀번호와도 맞지 않는다
    private final String dummyHash;

    public MemberService(MemberMapper memberMapper, PasswordEncoder passwordEncoder,
                         LoginAttemptLimiter loginAttemptLimiter,
                         FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
        this.memberMapper = memberMapper;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.sessionRepository = sessionRepository;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    // 미리 조회해서 중복을 막고, 동시에 들어와 조회를 함께 통과한 경우는 DB UNIQUE 제약이 막는다
    public MemberResponse signUp(SignUpRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT);
        if (memberMapper.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.MEMBER_EMAIL_DUPLICATED);
        }
        if (memberMapper.existsByNickname(request.nickname())) {
            throw new BusinessException(ErrorCode.MEMBER_NICKNAME_DUPLICATED);
        }

        Member member = new Member(email, passwordEncoder.encode(request.password()), request.nickname(), Role.MEMBER);
        try {
            memberMapper.insert(member);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(duplicateCodeOf(e));
        }
        return toResponse(member);
    }

    // 이메일을 소문자로 바꿔 찾는다. 없는 이메일이면 가짜 해시와 한 번 비교한 뒤 MEMBER_LOGIN_FAILED
    // 비교를 건너뛰면 응답이 빨라져 "이 이메일은 없다"가 응답 시간으로 드러나므로, 없을 때도 같은 비용을 낸다
    // 시도는 비교 전에 센다. 막혔으면 비교하지 않고 RetryLaterException이 그대로 나간다 (PRD member M3)
    public MemberResponse login(LoginRequest request, String clientIp) {
        String email = request.email().toLowerCase(Locale.ROOT);
        loginAttemptLimiter.acquire(email, clientIp);

        Optional<Member> member = memberMapper.findByEmail(email);
        String hash = member.map(Member::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (member.isEmpty() || !matches) {
            throw new BusinessException(ErrorCode.MEMBER_LOGIN_FAILED);
        }
        loginAttemptLimiter.succeeded(email, clientIp);
        return toResponse(member.get());
    }

    public MemberResponse findMe(long memberId) {
        return toResponse(memberMapper.findById(memberId).orElseThrow());
    }

    // 지금 닉네임과 같으면 그대로 돌려준다. 미리 조회만 하면 자기 닉네임도 "사용 중"으로 걸린다
    // 다른 회원과 동시에 바꿔 조회를 함께 통과한 경우는 DB UNIQUE 제약이 막는다 (가입과 같은 방식)
    public MemberResponse changeNickname(long memberId, ChangeNicknameRequest request) {
        Member member = memberMapper.findById(memberId).orElseThrow();
        String nickname = request.nickname();
        if (nickname.equals(member.getNickname())) {
            return toResponse(member);
        }
        if (memberMapper.existsByNickname(nickname)) {
            throw new BusinessException(ErrorCode.MEMBER_NICKNAME_DUPLICATED);
        }

        try {
            memberMapper.updateNickname(memberId, nickname);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(duplicateCodeOf(e));
        }
        member.setNickname(nickname);
        return toResponse(member);
    }

    // 현재 비밀번호 확인 → 새 해시 저장 → 이 회원의 다른 세션 삭제 (PRD member M2). 지금 세션은 남긴다.
    // DB와 Redis는 한 트랜잭션으로 묶을 수 없다. 세션 삭제가 실패해도 비밀번호는 이미 바뀌었고, 다시 바꾸면 그때 지워진다
    // 현재 비밀번호 확인에도 로그인과 같은 시도 제한을 건다. 안 걸면 로그인된 세션으로 비밀번호를 대입할 수 있다
    public void changePassword(long memberId, ChangePasswordRequest request, String currentSessionId,
                               String clientIp) {
        Member member = memberMapper.findById(memberId).orElseThrow();
        loginAttemptLimiter.acquire(member.getEmail(), clientIp);
        if (!passwordEncoder.matches(request.currentPassword(), member.getPasswordHash())) {
            throw new BusinessException(ErrorCode.MEMBER_PASSWORD_MISMATCH);
        }
        loginAttemptLimiter.succeeded(member.getEmail(), clientIp);

        memberMapper.updatePasswordHash(memberId, passwordEncoder.encode(request.newPassword()));
        sessionRepository.findByPrincipalName(String.valueOf(memberId)).keySet().stream()
                .filter(sessionId -> !sessionId.equals(currentSessionId))
                .forEach(sessionRepository::deleteById);
    }

    private static MemberResponse toResponse(Member member) {
        return new MemberResponse(member.getId(), member.getEmail(), member.getNickname(), member.getRole());
    }

    // 제약 이름은 V1__create_member.sql 에서 정한다
    private static ErrorCode duplicateCodeOf(DuplicateKeyException e) {
        String message = String.valueOf(e.getMessage()).toUpperCase(Locale.ROOT);
        if (message.contains("UK_MEMBER_EMAIL")) {
            return ErrorCode.MEMBER_EMAIL_DUPLICATED;
        }
        if (message.contains("UK_MEMBER_NICKNAME")) {
            return ErrorCode.MEMBER_NICKNAME_DUPLICATED;
        }
        throw e;
    }
}
