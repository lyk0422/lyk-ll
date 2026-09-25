package com.example.starter.epidemic;

import com.example.starter.epidemic.api.SubmitCardRequest;
import com.example.starter.epidemic.service.ClockService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 集成测试基类：每个用例前清理业务表（保留合成目录数据），避免顺序依赖；使用固定时钟。
 */
@SpringBootTest
abstract class AbstractIntegrationTest {

    protected static final Instant BASE_TIME = Instant.parse("2026-09-20T00:00:00Z");

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ClockService clock;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM special_correction");
        jdbc.update("DELETE FROM audit_trail");
        jdbc.update("DELETE FROM card_version");
        jdbc.update("DELETE FROM card");
        clock.setFixed(BASE_TIME);
    }

    /** 构造一份合法初报请求，调用方可覆盖字段。 */
    protected SubmitCardRequest submitRequest(String requestId, String patientId, String diseaseCode,
                                              Instant diagnosisTime) {
        return new SubmitCardRequest(
                requestId,
                patientId,
                "合成患者-" + patientId,
                35,
                "M",
                diseaseCode,
                "CONFIRMED",
                LocalDate.of(2026, 9, 19),
                diagnosisTime,
                "H001",
                "张报告",
                "示范市合成路 1 号",
                "13800000000",
                null);
    }

    protected String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
