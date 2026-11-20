package com.livestream.member;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class MemberService {

    private final MemberMapper memberMapper;
    private final PasswordEncoder passwordEncoder;

    public MemberService(MemberMapper memberMapper, PasswordEncoder passwordEncoder) {
        this.memberMapper = memberMapper;
        this.passwordEncoder = passwordEncoder;
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
        return new MemberResponse(member.getId(), member.getEmail(), member.getNickname(), member.getRole());
    }

    // 이메일을 소문자로 바꿔 찾는다. 없는 이메일이면 가짜 해시와 한 번 비교한 뒤 MEMBER_LOGIN_FAILED
    public MemberResponse login(LoginRequest request) {
        throw new UnsupportedOperationException("not implemented");
    }

    public MemberResponse findMe(long memberId) {
        throw new UnsupportedOperationException("not implemented");
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
