package com.example.starter.plan;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 告警服务：独立事务落库，保证业务异常回滚时告警仍然保留。
 */
@Service
public class AlertService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AlertService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 记录跳次执行告警（REQUIRES_NEW，避免随业务异常回滚）。
     *
     * @param planId    计划ID
     * @param patientId 患者ID
     * @param expected  期望分次号
     * @param actual    实际请求分次号
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fractionSkip(long planId, long patientId, int expected, int actual) {
        jdbc.update(
                "INSERT INTO alert_record (plan_id, patient_id, alert_type, message, created_at) VALUES (?,?,?,?,?)",
                planId, patientId, "FRACTION_SKIP",
                "跳次执行拒绝：期望分次号 " + expected + "，实际请求分次号 " + actual,
                LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC));
    }
}
