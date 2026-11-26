package com.livestream.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.livestream.TestcontainersConfiguration;
import com.livestream.member.MemberService;
import com.livestream.member.SignUpRequest;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

// 진짜 Oracle 컨테이너로 SQL까지 본다. 다른 통합 테스트와 설정을 맞춰 Spring 컨텍스트(컨테이너)를 같이 쓴다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OperatorChannelInitializerTest {

    @Autowired
    private OperatorChannelInitializer initializer;

    @Autowired
    private MemberService memberService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String unique() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
    }

    private long signUp(String nickname) {
        return memberService.signUp(new SignUpRequest("t" + unique() + "@example.com", "abcd1234", nickname)).id();
    }

    private long operator(String nickname) {
        long memberId = signUp(nickname);
        jdbcTemplate.update("UPDATE member SET role = 'OPERATOR' WHERE id = ?", memberId);
        return memberId;
    }

    private int channelsOf(long memberId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM channel WHERE member_id = ?", Integer.class, memberId);
    }

    @Nested
    @DisplayName("createMissingChannels")
    class CreateMissingChannels {

        @Test
        @DisplayName("should create a channel named after the nickname for an operator without a channel")
        void createsOperatorChannel() {
            String nickname = "운영" + unique();
            long memberId = operator(nickname);

            initializer.createMissingChannels();

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT name FROM channel WHERE member_id = ?", String.class, memberId)).isEqualTo(nickname);
        }

        @Test
        @DisplayName("should keep one channel when it runs again for an operator who already has a channel")
        void keepsOneChannel() {
            long memberId = operator("운영" + unique());
            initializer.createMissingChannels();

            initializer.createMissingChannels();

            assertThat(channelsOf(memberId)).isEqualTo(1);
        }

        @Test
        @DisplayName("should not create a channel for a member who is not an operator")
        void skipsMember() {
            long memberId = signUp("회원" + unique());

            initializer.createMissingChannels();

            assertThat(channelsOf(memberId)).isZero();
        }
    }
}
