package com.example.starter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.File;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地启动入口冒烟测试：直接加载主资源 {@code src/main/resources/application.yaml}
 * （不使用 src/test 的测试配置），验证默认嵌入式 H2（MODE=MySQL）可独立引导并自动建表，
 * 无需外部 MySQL、Docker 或环境变量。
 */
class LocalEntryBootTest {

    @Test
    void applicationBootsWithEmbeddedH2UsingMainConfig() throws Exception {
        File mainConfig = new File("src/main/resources/application.yaml");
        assertThat(mainConfig).exists();
        // 主配置中必须声明 H2 默认值，保证未设置 DB_URL 时也能本地启动
        String yaml = Files.readString(mainConfig.toPath());
        assertThat(yaml).contains("jdbc:h2:mem:calib_demo;MODE=MySQL");

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(StarterApplication.class)
                .properties(
                        "spring.config.location=file:" + mainConfig.getAbsolutePath().replace('\\', '/'),
                        "server.port=0",
                        "spring.main.web-application-type=none")
                .run()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Integer tables = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                    WHERE UPPER(TABLE_NAME) IN
                    ('CALIBRATION_VERSION','CANARY_RELEASE','CANARY_VEHICLE','CANARY_VEHICLE_LOCK',
                     'RESOLUTION_RECORD','STATUS_TRANSITION')
                    """, Integer.class);
            assertThat(tables).isEqualTo(6);
        }
    }
}
