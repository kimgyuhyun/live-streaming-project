package com.livestream.broadcast;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

// 키 확인 엔드포인트(/internal/)는 인증 없이 받는다. 바깥 입구인 nginx가 이 경로를 Spring에 넘기지 않는 것을 설정 파일로 박는다
// (PRD channel C2 결과). 실제 응답은 로컬 compose에서 curl로 확인한다(issue-10.md 수동 확인)
class NginxInternalPathTest {

    // gradle은 backend/에서 테스트를 돌린다
    private static final Path NGINX_CONF = Path.of("..", "infra", "nginx", "nginx.conf");

    @Nested
    @DisplayName("nginx.conf")
    class NginxConf {

        @Test
        @DisplayName("should answer /internal/ with 404 without passing it to Spring")
        void blocksInternalPath() throws Exception {
            String conf = Files.readString(NGINX_CONF, StandardCharsets.UTF_8);

            Matcher block = Pattern.compile("location\\s+/internal/\\s*\\{([^}]*)}").matcher(conf);

            assertThat(block.find()).as("location /internal/ 블록").isTrue();
            assertThat(block.group(1).strip()).isEqualTo("return 404;");
        }
    }
}
