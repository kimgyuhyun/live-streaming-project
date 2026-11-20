package com.livestream.member;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class MemberService {

    private final MemberMapper memberMapper;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptLimiter loginAttemptLimiter;
    // 없는 이메일로 로그인할 때 비교할 해시. 어떤 비밀번호와도 맞지 않는다
    private final String dummyHash;

    public MemberService(MemberMapper memberMapper, PasswordEncoder passwordEncoder,
                         LoginAttemptLimiter loginAttemptLimiter) {
        this.memberMapper = memberMapper;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptLimiter = loginAttemptLimiter;
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
