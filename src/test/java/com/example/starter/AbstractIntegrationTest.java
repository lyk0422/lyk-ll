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

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected SiloService siloService;

    @Autowired
    protected VentilationService ventilationService;

    @BeforeEach
    void cleanDatabase() {
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
}
