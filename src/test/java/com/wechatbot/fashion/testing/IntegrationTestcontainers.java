package com.wechatbot.fashion.testing;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试基础设施：用 Testcontainers 起 MySQL + Redis，并把连接信息以动态属性注入 Spring 上下文，
 * 使 `-Pintegration` 自包含（不依赖本机 MySQL/Redis，也不需要环境变量/application-local.properties）。
 *
 * <p>用法：集成测试类 `extends IntegrationTestcontainers` 并加 `@ActiveProfiles("test")`
 * （避免加载被 gitignore 的 `application-local.properties`）。
 */
@Testcontainers
public abstract class IntegrationTestcontainers {

    @Container
    protected static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("wechatbot")
                    .withUsername("root")
                    .withPassword("root");

    @Container
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.persistence.enabled", () -> "true");
        registry.add("app.persistence.jdbc-url", () -> withParams(MYSQL.getJdbcUrl()));
        registry.add("app.persistence.username", MYSQL::getUsername);
        registry.add("app.persistence.password", MYSQL::getPassword);
        registry.add("app.persistence.redis.host", REDIS::getHost);
        registry.add("app.persistence.redis.port", () -> REDIS.getMappedPort(6379));
    }

    private static String withParams(String jdbcUrl) {
        String params = "useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&useSSL=false&allowPublicKeyRetrieval=true";
        return jdbcUrl.contains("?") ? jdbcUrl + "&" + params : jdbcUrl + "?" + params;
    }
}
