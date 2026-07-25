package com.example.ykdsummer.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(PersistenceProperties.class)
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class PersistenceConfiguration {

    @Bean(destroyMethod = "close")
    public HikariDataSource applicationDataSource(PersistenceProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(required(properties.getJdbcUrl(), "app.persistence.jdbc-url"));
        config.setUsername(required(properties.getUsername(), "app.persistence.username"));
        config.setPassword(properties.getPassword());
        config.setMaximumPoolSize(Math.max(2, properties.getMaximumPoolSize()));
        config.setMinimumIdle(1);
        config.setConnectionTimeout(safeTimeout(properties.getConnectionTimeout()));
        config.setPoolName("ykd-mysql");
        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource applicationDataSource) {
        return new JdbcTemplate(applicationDataSource);
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource applicationDataSource) {
        return new DataSourceTransactionManager(applicationDataSource);
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    public Flyway operationalFlyway(DataSource applicationDataSource) {
        return Flyway.configure()
                .dataSource(applicationDataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public ApplicationRunner persistenceMigrationRunner(Flyway operationalFlyway) {
        return arguments -> operationalFlyway.migrate();
    }

    @Bean(destroyMethod = "destroy")
    @ConditionalOnProperty(prefix = "app.persistence.redis", name = "enabled", havingValue = "true")
    public RedisConnectionFactory redisConnectionFactory(PersistenceProperties properties) {
        PersistenceProperties.Redis redis = properties.getRedis();
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(redis.getHost(), redis.getPort());
        if (redis.getPassword() != null && !redis.getPassword().isBlank()) {
            configuration.setPassword(redis.getPassword());
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.setValidateConnection(true);
        return factory;
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.persistence.redis", name = "enabled", havingValue = "true")
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
        return new StringRedisTemplate(redisConnectionFactory);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when app.persistence.enabled=true");
        }
        return value;
    }

    private static long safeTimeout(java.time.Duration value) {
        return value == null || value.isNegative() || value.isZero() ? 5_000L : value.toMillis();
    }
}
