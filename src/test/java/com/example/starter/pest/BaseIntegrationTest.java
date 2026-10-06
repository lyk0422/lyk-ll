package com.example.starter.pest;

import java.time.Instant;

import com.example.starter.pest.api.Requests;
import com.example.starter.pest.api.Results;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

/**
 * 集成测试基类：H2 内存库（MySQL 兼容模式）、每用例前清理并重置合成数据、可控时钟。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
@Sql(scripts = {"classpath:cleanup.sql", "classpath:testdata.sql"})
abstract class BaseIntegrationTest {

    /**
     * 测试基准时间。
     */
    protected static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    protected PestReportService pestReportService;

    @Autowired
    protected TreatmentService treatmentService;

    @Autowired
    protected OutboundService outboundService;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.setInstant(T0);
    }

    /**
     * 上报并确认指定库区虫情，返回确认结果（含处置链 ID）。
     */
    protected Results.ConfirmResult reportAndConfirm(String zoneCode, String keyPrefix) {
        Results.ReportResult report = pestReportService.report(
                new Requests.ReportRequest(keyPrefix + "-r", zoneCode, "发现虫迹", "张三"));
        return pestReportService.confirm(report.id(),
                new Requests.ConfirmRequest(keyPrefix + "-c", "李四"));
    }

    /**
     * 统计表行数。
     */
    protected long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
