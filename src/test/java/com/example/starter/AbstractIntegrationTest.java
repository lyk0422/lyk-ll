package com.example.starter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.example.starter.dto.GrainDtos.CreateFanRequest;
import com.example.starter.dto.GrainDtos.CreateJobRequest;
import com.example.starter.dto.GrainDtos.CreateSiloRequest;
import com.example.starter.dto.GrainDtos.FanView;
import com.example.starter.dto.GrainDtos.ReadingRequest;
import com.example.starter.dto.GrainDtos.SiloView;
import com.example.starter.model.VentilationPurpose;
import com.example.starter.service.FumigationService;
import com.example.starter.service.SiloService;
import com.example.starter.service.VentilationService;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 集成测试基类：独立 H2 内存库，每个用例前清空全部业务表，避免顺序依赖。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    protected static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0);

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected SiloService siloService;

    @Autowired
    protected VentilationService ventilationService;

    @Autowired
    protected FumigationService fumigationService;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM fumigation_release");
        jdbc.update("DELETE FROM gas_reading");
        jdbc.update("DELETE FROM fumigation_approval");
        jdbc.update("DELETE FROM fumigation_order");
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM recheck");
        jdbc.update("DELETE FROM ventilation_job");
        jdbc.update("DELETE FROM alarm");
        jdbc.update("DELETE FROM sensor_reading");
        jdbc.update("DELETE FROM fan");
        jdbc.update("DELETE FROM silo");
    }

    /** 创建安全温度 30℃、平衡湿度 70%RH 的粮仓。 */
    protected SiloView newSilo(String code) {
        return siloService.createSilo(new CreateSiloRequest(code, "粮仓" + code,
                new BigDecimal("30.00"), new BigDecimal("70.00")));
    }

    protected FanView newFan(String code) {
        return siloService.createFan(new CreateFanRequest(code, "风机" + code));
    }

    /** 上报一次超温数据使粮仓进入告警态。 */
    protected void raiseAlarm(long siloId, String requestId) {
        siloService.ingestReading(siloId, new ReadingRequest(requestId, 1,
                new BigDecimal("31.50"), new BigDecimal("60.00"), T0));
    }

    /** 构造 10:00-11:00 降温作业请求。 */
    protected CreateJobRequest newJobRequest(String requestId, long fanId) {
        return new CreateJobRequest(requestId, fanId, VentilationPurpose.COOLING,
                T0.plusHours(2), T0.plusHours(3), new BigDecimal("65.00"));
    }

    protected long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    /** UTC 作业窗基准时刻：2026-09-26T02:00:00Z（对应北京时间 10:00）。 */
    protected static java.time.Instant U0 = T0.toInstant(java.time.ZoneOffset.ofHours(8));

    /** 申请一张 02:00Z-03:00Z、阈值 5.000ppm 的熏蒸单。 */
    protected com.example.starter.dto.GrainDtos.FumigationView applyFumigation(long siloId, String requestId) {
        return fumigationService.apply(siloId,
                new com.example.starter.dto.GrainDtos.ApplyFumigationRequest(
                        requestId, U0, U0.plusSeconds(3600), "SYN-AGENT-A",
                        new BigDecimal("5.000")));
    }

    /** 两名不同审批人按当前版本依次批准至 APPROVED。 */
    protected com.example.starter.dto.GrainDtos.FumigationView approveTwice(long orderId, String prefix) {
        var first = fumigationService.approve(orderId,
                new com.example.starter.dto.GrainDtos.ApproveFumigationRequest(
                        prefix + "-a1", 1, "alice"));
        var second = fumigationService.approve(orderId,
                new com.example.starter.dto.GrainDtos.ApproveFumigationRequest(
                        prefix + "-a2", first.version(), "bob"));
        return second;
    }

    /** 在窗口起点开始熏蒸并冻结版本。 */
    protected com.example.starter.dto.GrainDtos.FumigationView startFumigation(long orderId, int version,
                                                                               String requestId) {
        return fumigationService.start(orderId,
                new com.example.starter.dto.GrainDtos.StartFumigationRequest(
                        requestId, version, U0));
    }

    /** 绑定空闲风机转散气。 */
    protected com.example.starter.dto.GrainDtos.FumigationView beginAeration(long orderId, int version,
                                                                             long fanId, String requestId) {
        return fumigationService.beginAeration(orderId,
                new com.example.starter.dto.GrainDtos.BeginAerationRequest(
                        requestId, version, fanId, U0.plusSeconds(600)));
    }

    /** 上报连续三点安全读数（4.000ppm），首末恰间隔 30 分钟，推动版本至可解除。 */
    protected int safeThreeReadings(long orderId, int version, String prefix) {
        var r1 = fumigationService.ingestGasReading(orderId,
                new com.example.starter.dto.GrainDtos.GasReadingRequest(
                        prefix + "-g1", version, 1, new BigDecimal("4.000"),
                        U0.plusSeconds(3600)));
        var r2 = fumigationService.ingestGasReading(orderId,
                new com.example.starter.dto.GrainDtos.GasReadingRequest(
                        prefix + "-g2", version + 1, 2, new BigDecimal("4.000"),
                        U0.plusSeconds(3600 + 900)));
        var r3 = fumigationService.ingestGasReading(orderId,
                new com.example.starter.dto.GrainDtos.GasReadingRequest(
                        prefix + "-g3", version + 2, 3, new BigDecimal("4.000"),
                        U0.plusSeconds(3600 + 1800)));
        assertEquals(1, r1.seqNo());
        assertEquals(3, r3.seqNo());
        return version + 3;
    }

    /** 两名不同复核人解除。 */
    protected com.example.starter.dto.GrainDtos.ReleaseView releaseFumigation(long orderId, int version,
                                                                              String requestId) {
        return fumigationService.release(orderId,
                new com.example.starter.dto.GrainDtos.ReleaseFumigationRequest(
                        requestId, version, java.util.List.of("carol", "dave")));
    }
}
