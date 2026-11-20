package com.livestream;

import java.time.Duration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

// 오라클 컨테이너는 시작이 느리다. 컨텍스트 캐시 덕분에 같은 설정을 쓰는 테스트끼리 하나를 같이 쓴다.
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    OracleContainer oracleContainer() {
        // 기본 대기 시간은 60초다. 다른 컨테이너와 함께 돌면 이 PC에서 준비까지 80초를 넘겨 테스트가 통째로 실패한다
        return new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23.26.4-slim-faststart"))
                .withStartupTimeout(Duration.ofMinutes(3));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:8.8.4-alpine")).withExposedPorts(6379);
    }
}
