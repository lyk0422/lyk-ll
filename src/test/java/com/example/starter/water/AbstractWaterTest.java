package com.example.starter.water;

import com.example.starter.water.service.WaterService;
import com.example.starter.water.web.ApiDtos;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试基类：每个用例前清空全部业务表，避免顺序依赖。
 * 使用独立命名内存库 water_test（非延迟关闭），上下文关闭后数据库随之释放。
 */
@SpringBootTest
public abstract class AbstractWaterTest {

    @Autowired
    protected WaterService service;

    @Autowired
    protected JdbcTemplate jdbc;

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM meter_reading");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM monthly_declaration");
        jdbc.update("DELETE FROM annual_plan_seal");
        jdbc.update("DELETE FROM quota_ledger");
        jdbc.update("DELETE FROM meter_point");
        jdbc.update("DELETE FROM water_user");
        jdbc.update("DELETE FROM reach_restriction");
    }

    /** 创建合成取水户，返回唯一编码。 */
    protected String newUser(String reachCode, String annualQuota) {
        String code = "U" + SEQUENCE.incrementAndGet();
        service.createUser(new ApiDtos.CreateUserRequest(code, "合成取水户" + code,
                reachCode, new BigDecimal(annualQuota)));
        return code;
    }

    /** 创建合成计量点，返回唯一编码。 */
    protected String newMeterPoint(String userCode) {
        String code = "P" + SEQUENCE.incrementAndGet();
        service.createMeterPoint(new ApiDtos.CreateMeterPointRequest(code, userCode));
        return code;
    }

    protected ApiDtos.DeclarationResponse declare(String requestId, String userCode,
                                                  String month, String plannedAmount) {
        return service.declare(new ApiDtos.DeclarationRequest(requestId, userCode, month,
                new BigDecimal(plannedAmount)));
    }

    protected ApiDtos.ReadingResponse report(String pointCode, String readAt, String cumulative) {
        return service.reportReading(new ApiDtos.ReadingRequest(pointCode,
                java.time.Instant.parse(readAt), new BigDecimal(cumulative)));
    }

    protected static void assertAmount(String expected, BigDecimal actual) {
        org.assertj.core.api.Assertions.assertThat(actual)
                .as("amount").isEqualByComparingTo(new BigDecimal(expected));
    }
}
