package com.livestream.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 환경변수 이름 → application.yml 키 → @Value → 시작 때 실행으로 이어지는 연결을 본다.
// 이 클래스 하나만 띄워 자동 구성이 없다. DB·Redis 컨테이너가 뜨지 않는다.
// properties의 값은 환경변수와 같은 이름으로 넣어 yml의 ${OPERATOR_EMAIL:} 자리를 채운다
@SpringBootTest(classes = OperatorAccountInitializer.class,
        properties = {"OPERATOR_EMAIL=Boss@Example.com", "OPERATOR_PASSWORD=opPass1234"})
class OperatorAccountStartupTest {

    @MockitoBean
    private MemberMapper memberMapper;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @Nested
    @DisplayName("OperatorAccountInitializer")
    class Startup {

        @Test
        @DisplayName("should save an OPERATOR account at application startup when OPERATOR_EMAIL and OPERATOR_PASSWORD are set")
        void savesOperatorAtStartup() {
            // 컨텍스트가 뜰 때 이미 실행됐다. 테스트는 그 호출을 확인만 한다
            ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
            verify(memberMapper).insert(captor.capture());

            assertThat(captor.getValue())
                    .extracting(Member::getEmail, Member::getRole)
                    .containsExactly("boss@example.com", Role.OPERATOR);
        }
    }
}
