package com.example.starter.testsupport;

import com.example.starter.repo.AuditRepository;
import com.example.starter.repo.DeviceMessageRepository;
import com.example.starter.repo.TopicConfigRepository;
import com.example.starter.service.ForwarderService;
import com.example.starter.upstream.SyntheticUpstreamClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

/**
 * 测试基类：每个测试类使用独立临时目录下的 H2 文件库（MODE=MySQL），
 * 用例之间清空三张表，避免顺序依赖。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfig.class)
public abstract class ForwarderTestBase {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void dataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:" + tempDir.resolve("forwarder-test")
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
    }

    @Autowired
    protected ForwarderService service;

    @Autowired
    protected DeviceMessageRepository messageRepository;

    @Autowired
    protected AuditRepository auditRepository;

    @Autowired
    protected TopicConfigRepository topicConfigRepository;

    @Autowired
    protected SyntheticUpstreamClient upstream;

    @Autowired
    protected MutableTestClock clock;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM device_message");
        jdbcTemplate.update("DELETE FROM audit_record");
        jdbcTemplate.update("DELETE FROM topic_config");
        upstream.setOnline(true);
        upstream.setFailureRate(0);
        upstream.resetStats();
    }
}
