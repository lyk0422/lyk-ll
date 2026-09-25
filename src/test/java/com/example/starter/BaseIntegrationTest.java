package com.example.starter;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.example.starter.dto.FilingRequest;
import com.example.starter.dto.PermitRequest;
import com.example.starter.dto.PermitResponse;
import com.example.starter.dto.ReadingRequest;
import com.example.starter.dto.ReadingResponse;
import com.example.starter.service.EvaluationService;
import com.example.starter.service.FilingService;
import com.example.starter.service.FreezeService;
import com.example.starter.service.PermitService;
import com.example.starter.service.VibrationService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类：独立 H2 内存库，逐用例清理业务数据，使用固定 UTC 时钟。
 */
@SpringBootTest
@Import(BaseIntegrationTest.FixedClockConfig.class)
public abstract class BaseIntegrationTest {

    /** 种子爆区：东一爆区（上限 120kg，测点 P1/P2）。 */
    protected static final long ZONE_EAST = 1L;
    /** 种子爆区：西二爆区（上限 80kg，测点 P1）。 */
    protected static final long ZONE_WEST = 2L;
    /** 种子矿区：青石矿区（阈值 1.000 cm/s）。 */
    protected static final long MINE_ID = 1L;

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected PermitService permitService;
    @Autowired
    protected FilingService filingService;
    @Autowired
    protected VibrationService vibrationService;
    @Autowired
    protected FreezeService freezeService;
    @Autowired
    protected EvaluationService evaluationService;

    @BeforeEach
    void cleanBusinessData() {
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM evaluation");
        jdbc.update("DELETE FROM freeze_review");
        jdbc.update("DELETE FROM vibration_reading");
        jdbc.update("DELETE FROM blast_filing");
        jdbc.update("DELETE FROM approved_slot");
        jdbc.update("DELETE FROM blast_permit");
        jdbc.update("UPDATE mine SET frozen = FALSE, frozen_at = NULL, freeze_reason = NULL");
    }

    /** 提交申请并返回响应。 */
    protected PermitResponse apply(String requestId, long zoneId, String plannedTime, String charge) {
        return permitService.apply(new PermitRequest(requestId, zoneId, "张三",
                Instant.parse(plannedTime), new BigDecimal(charge)));
    }

    /** 申请并审批通过，返回申请单 id。 */
    protected long applyAndApprove(String requestId, long zoneId, String plannedTime, String charge) {
        PermitResponse response = apply(requestId, zoneId, plannedTime, charge);
        permitService.approve(response.id());
        return response.id();
    }

    /** 申请、审批并备案，返回申请单 id。 */
    protected long applyApproveFile(String requestId, long zoneId, String plannedTime, String charge) {
        long permitId = applyAndApprove(requestId, zoneId, plannedTime, charge);
        filingService.file(new FilingRequest(requestId + "-F", permitId));
        return permitId;
    }

    /** 回传一条振动读数。 */
    protected ReadingResponse submitReading(long permitId, String point,
            String sampledAt, String velocity) {
        return vibrationService.submit(new ReadingRequest(permitId, point,
                Instant.parse(sampledAt), new BigDecimal(velocity)));
    }

    /** 查询申请单状态。 */
    protected String statusOf(long permitId) {
        return jdbc.queryForObject("SELECT status FROM blast_permit WHERE id = ?",
                String.class, permitId);
    }

    /** 查询矿区冻结标记。 */
    protected boolean mineFrozen(long mineId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT frozen FROM mine WHERE id = ?", Boolean.class, mineId));
    }

    /**
     * 固定 UTC 时钟，保证时间相关断言可重复。
     */
    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-09-25T08:00:00Z"), ZoneOffset.UTC);
        }
    }
}
