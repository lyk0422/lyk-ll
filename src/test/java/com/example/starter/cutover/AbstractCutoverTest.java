package com.example.starter.cutover;

import com.example.starter.cutover.api.ApplyRequest;
import com.example.starter.cutover.support.TimeSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * H2 事务/并发测试基类：独立内存库 cutover_test，每例清理业务表（保留合成主数据），
 * 并以可调时钟固定在基准时间 2026-09-25 10:00:00+08:00。
 */
@SpringBootTest
@Import(ClockTestConfiguration.class)
public abstract class AbstractCutoverTest {

    protected static final LocalDateTime BASE_TIME = ClockTestConfiguration.BASE_TIME;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetContext() {
        clock.setInstant(BASE_TIME.atZone(TimeSource.ZONE).toInstant());
        jdbcTemplate.update("DELETE FROM step_report_event");
        jdbcTemplate.update("DELETE FROM rollback_record");
        jdbcTemplate.update("DELETE FROM cutover_step");
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("DELETE FROM cutover_application");
    }

    protected void setClock(LocalDateTime time) {
        clock.setInstant(time.atZone(TimeSource.ZONE).toInstant());
    }

    protected void advanceSeconds(long seconds) {
        clock.advanceSeconds(seconds);
    }

    protected void advanceMinutes(long minutes) {
        clock.advanceMinutes(minutes);
    }

    protected static ApplyRequest.StepSpec step(int seq, String name, String type) {
        return new ApplyRequest.StepSpec(seq, name, type);
    }

    protected static List<ApplyRequest.StepSpec> defaultSteps() {
        List<ApplyRequest.StepSpec> steps = new ArrayList<>();
        steps.add(step(1, "备份配置", "NORMAL"));
        steps.add(step(2, "切换路由", "NORMAL"));
        steps.add(step(3, "业务拨测验证", "VERIFY"));
        return steps;
    }

    protected static ApplyRequest applyRequest(String requestId, String device, String priority,
                                               LocalDateTime start, LocalDateTime end,
                                               List<ApplyRequest.StepSpec> steps) {
        return new ApplyRequest(requestId, device, "割接-" + requestId, priority,
                start, end, steps);
    }
}
