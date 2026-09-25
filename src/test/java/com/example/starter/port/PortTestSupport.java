package com.example.starter.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import com.example.starter.MutableClock;
import com.example.starter.TestTimeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 携转流程测试基类：场景间清理流程数据，提供合成用户与流程推进的辅助方法。
 * 测试号码统一使用 139 开头，避免与演示数据冲突。
 */
public abstract class PortTestSupport {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected PortService service;

    private final AtomicLong sequence = new AtomicLong();

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM port_event");
        jdbc.update("DELETE FROM effectiveness_record");
        jdbc.update("DELETE FROM port_inflight");
        jdbc.update("DELETE FROM port_request");
        jdbc.update("DELETE FROM subscriber WHERE msisdn LIKE '139%'");
        clock.setInstant(TestTimeConfig.BASE_TIME);
    }

    /** 生成唯一幂等键。 */
    protected String nextRequestId() {
        return "req-" + sequence.incrementAndGet();
    }

    /** 生成唯一测试号码。 */
    protected String nextMsisdn() {
        return String.format("139%08d", sequence.incrementAndGet());
    }

    /** 写入合成用户状态。 */
    protected void insertSubscriber(String msisdn, String operator, BigDecimal arrears,
                                    LocalDate contractEnd, Instant lastPort) {
        jdbc.update("INSERT INTO subscriber"
                        + " (msisdn, current_operator, arrears_amount, contract_end_date, last_port_time)"
                        + " VALUES (?, ?, ?, ?, ?)",
                msisdn, operator, arrears,
                contractEnd == null ? null : java.sql.Date.valueOf(contractEnd),
                lastPort == null ? null : java.time.LocalDateTime.ofInstant(lastPort, java.time.ZoneOffset.UTC));
    }

    /** 写入一个资格满足的合成用户（无欠费、无在网约定、从未携转）。 */
    protected void insertEligibleSubscriber(String msisdn) {
        insertSubscriber(msisdn, "OP_A", BigDecimal.ZERO, null, null);
    }

    /** 推进一个流程到 CONFIRMED（待生效）状态。 */
    protected PortFlow newConfirmedFlow(String msisdn) {
        insertEligibleSubscriber(msisdn);
        PortFlow flow = service.apply(nextRequestId(), msisdn, "OP_A", "OP_B");
        flow = service.issueAuthCode(flow.id());
        return service.confirm(flow.id(), flow.authCode());
    }

    /** 统计指定号码的流程数。 */
    protected int countFlows(String msisdn) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM port_request WHERE msisdn = ?", Integer.class, msisdn);
    }

    /** 统计指定号码的在途守卫记录数。 */
    protected int countInflight(String msisdn) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM port_inflight WHERE msisdn = ?", Integer.class, msisdn);
    }

    /** 统计指定流程的生效记录数。 */
    protected int countEffectiveness(long flowId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM effectiveness_record WHERE port_request_id = ?",
                Integer.class, flowId);
    }
}
