package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.api.ApplicationRequest;
import com.example.starter.api.SegmentRequest;
import com.example.starter.service.DivingService;
import com.example.starter.support.BusinessException;

/**
 * H2（MODE=MySQL）集成测试基类：每个用例前清空全部业务表，保证用例间无顺序依赖。
 */
@SpringBootTest
public abstract class AbstractDivingIntegrationTest {

    protected static final MutableClock MUTABLE_CLOCK = new MutableClock();

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return MUTABLE_CLOCK;
        }
    }

    @Autowired
    protected DivingService service;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM diver_time_ledger");
        jdbc.update("DELETE FROM segment_pair");
        jdbc.update("DELETE FROM dive_segment");
        jdbc.update("DELETE FROM pair_lock");
        jdbc.update("DELETE FROM vitals_record");
        jdbc.update("DELETE FROM dive_event");
        jdbc.update("DELETE FROM dive_plan");
        jdbc.update("DELETE FROM active_dive_group");
        jdbc.update("DELETE FROM dive_operation");
        MUTABLE_CLOCK.setEpochSeconds(1_800_000_000L);
    }

    // ---- 常用构造与断言辅助 ----

    protected ApplicationRequest application(String requestId, String a, String b, int depth, int minutes) {
        return new ApplicationRequest(requestId, a, b, depth, minutes);
    }

    protected SegmentRequest bottomSegment(String requestId, String operationNo, String diver,
                                           int depth, long start, long end) {
        return new SegmentRequest(requestId, operationNo, diver, "BOTTOM", depth, depth, start, end);
    }

    protected SegmentRequest descendSegment(String requestId, String operationNo, String diver,
                                            int fromDepth, int toDepth, long start, long end) {
        return new SegmentRequest(requestId, operationNo, diver, "DESCEND", fromDepth, toDepth, start, end);
    }

    protected BusinessException expectBusiness(Runnable action, String code) {
        BusinessException ex = catchThrowableOfType(BusinessException.class, action::run);
        assertThat(ex).as("应抛出业务异常 " + code).isNotNull();
        assertThat(ex.getCode()).isEqualTo(code);
        return ex;
    }

    protected long count(String table, String whereClause, Object... args) {
        // H2 DATABASE_TO_LOWER 模式下事务回滚后偶发无法解析未加引号的小写表名，统一用大写。
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table.toUpperCase() + " " + whereClause,
                Long.class, args);
        return value == null ? 0L : value;
    }

    protected Map<String, Object> operationById(long id) {
        return jdbc.queryForMap("SELECT * FROM dive_operation WHERE id = ?", id);
    }

    protected List<Map<String, Object>> events(long operationId) {
        return jdbc.queryForList("SELECT * FROM dive_event WHERE operation_id = ? ORDER BY id", operationId);
    }
}
