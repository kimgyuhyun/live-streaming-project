package com.livestream.broadcast;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

// MediaMTX API 호출도 Spring 키 확인 엔드포인트가 판단하는 것을 설정 파일로 박는다(#26).
// 내부망 다른 컨테이너에서 자격 없이 부르면 401인지는 로컬 compose에서 확인한다(issue-26.md 수동 확인)
class MediaMtxConfigTest {

    // gradle은 backend/에서 테스트를 돌린다
    private static final Path MEDIAMTX_YML = Path.of("..", "infra", "mediamtx", "mediamtx.yml");

    @Nested
    @DisplayName("mediamtx.yml")
    class MediaMtxYml {

        @Test
        @DisplayName("should not exclude the api action from the http auth")
        @SuppressWarnings("unchecked")
        void keepsApiUnderHttpAuth() throws Exception {
            Map<String, Object> conf;
            try (Reader reader = Files.newBufferedReader(MEDIAMTX_YML, StandardCharsets.UTF_8)) {
                conf = new Yaml().load(reader);
            }
            List<Map<String, Object>> excluded = (List<Map<String, Object>>) conf.get("authHTTPExclude");

            assertThat(excluded).extracting(entry -> entry.get("action")).doesNotContain("api");
        }
    }
}
