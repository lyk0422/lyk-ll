package com.example.starter.ride;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import javax.sql.DataSource;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试配置：固定在 Asia/Shanghai 营业日内的可控时钟；上下文销毁后释放 H2 内存库。
 */
@TestConfiguration
public class RideTestConfiguration {

    @Bean
    @Primary
    public BusinessClock fixedBusinessClock() {
        return new BusinessClock(Clock.fixed(Instant.parse("2026-09-26T02:00:00Z"),
                ZoneId.of("Asia/Shanghai")));
    }

    /**
     * 测试上下文关闭时执行 SHUTDOWN，释放带 DB_CLOSE_DELAY=-1 的命名内存库。
     */
    @Bean(destroyMethod = "release")
    public H2DatabaseReleaser h2DatabaseReleaser(DataSource dataSource) {
        return new H2DatabaseReleaser(dataSource);
    }

    public static class H2DatabaseReleaser {

        private final DataSource dataSource;

        H2DatabaseReleaser(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        public void release() {
            try (java.sql.Connection conn = dataSource.getConnection();
                 java.sql.Statement stmt = conn.createStatement()) {
                stmt.execute("SHUTDOWN");
            } catch (Exception ignored) {
                // 池随上下文一并关闭，释放失败不影响测试结论
            }
        }
    }
}
