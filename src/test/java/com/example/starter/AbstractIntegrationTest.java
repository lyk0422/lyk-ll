package com.example.starter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

import com.example.starter.dto.FumigationDtos.ApproveRequest;
import com.example.starter.dto.FumigationDtos.CreateFumigationRequest;
import com.example.starter.dto.FumigationDtos.FinishRequest;
import com.example.starter.dto.FumigationDtos.FumigationView;
import com.example.starter.dto.FumigationDtos.SampleRequest;
import com.example.starter.dto.FumigationDtos.StartRequest;
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

/**
 * 集成测试基类：独立 H2 内存库，每个用例前清空全部业务表，避免顺序依赖。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    protected static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0);

    /** 熏蒸作业窗基准时刻（UTC） */
    protected static final Instant I0 = Instant.parse("2026-09-26T00:00:00Z");

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
        jdbc.update("DELETE FROM fumigation_idempotency");
        jdbc.update("DELETE FROM fumigation_reading");
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

    /** 申请熏蒸单：UTC 窗 02:00-04:00，合成安全阈值 0.5。 */
    protected FumigationView newFumigation(long siloId, String requestId) {
        return fumigationService.create(siloId, new CreateFumigationRequest(requestId,
                I0.plusSeconds(2 * 3600), I0.plusSeconds(4 * 3600), new BigDecimal("0.5000")));
    }

    /** 按当前版本批准一次。 */
    protected FumigationView approveNow(long fumigationId, String requestId, String approver) {
        int version = fumigationService.getFumigation(fumigationId).version();
        return fumigationService.approve(fumigationId,
                new ApproveRequest(requestId, version, approver, I0.plusSeconds(3600)));
    }

    /** 走完整流程到散气中：双人批准→开始→结束转散气绑定风机。 */
    protected FumigationView toAerating(long fumigationId, long fanId) {
        approveNow(fumigationId, "ap-" + fumigationId + "-1", "approver-a");
        approveNow(fumigationId, "ap-" + fumigationId + "-2", "approver-b");
        int version = fumigationService.getFumigation(fumigationId).version();
        fumigationService.start(fumigationId,
                new StartRequest("st-" + fumigationId, version, I0.plusSeconds(2 * 3600)));
        version = fumigationService.getFumigation(fumigationId).version();
        return fumigationService.finish(fumigationId,
                new FinishRequest("fi-" + fumigationId, version, fanId, I0.plusSeconds(3 * 3600)));
    }

    /** 上报一次散气读数。 */
    protected void sample(long fumigationId, String requestId, int seqNo, String concentration,
                          Instant measuredAt) {
        fumigationService.sample(fumigationId, new SampleRequest(requestId, seqNo,
                new BigDecimal(concentration), measuredAt));
    }

    protected long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }
}
