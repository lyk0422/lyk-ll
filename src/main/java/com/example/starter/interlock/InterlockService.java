package com.example.starter.interlock;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 防误闭锁评估：基于合成闭锁规则库与设备实时状态，对操作步骤做五防校验。
 * 规则：带负荷拉合刀闸、带电挂接地线、带地线送电直接闭锁；
 * 母线停电须先断开全部出线开关，顺序违例整步拒绝。
 */
@Service
public class InterlockService {

    /** 带负荷拉合刀闸 */
    public static final String LOAD_DISCONNECTOR = "LOAD_DISCONNECTOR";
    /** 带电挂接地线 */
    public static final String LIVE_GROUND = "LIVE_GROUND";
    /** 带地线送电 */
    public static final String GROUNDED_ENERGIZE = "GROUNDED_ENERGIZE";
    /** 母线停电顺序违例 */
    public static final String BUS_OUTAGE_SEQUENCE = "BUS_OUTAGE_SEQUENCE";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public InterlockService(JdbcTemplate jdbc, Clock businessClock) {
        this.jdbc = jdbc;
        this.clock = businessClock;
    }

    /**
     * 记录闭锁命中，独立事务提交：即使业务操作被拒绝回滚，命中轨迹仍保留可复算。
     *
     * @param ticketId 关联操作票主键，可为空
     * @param stepSeq  关联步序，可为空
     * @param ruleCode 命中的规则编码
     * @param detail   命中详情
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordHit(Long ticketId, Integer stepSeq, String ruleCode, String detail) {
        jdbc.update(
                "INSERT INTO interlock_hit (ticket_id, step_seq, rule_code, detail, created_at) VALUES (?, ?, ?, ?, ?)",
                ticketId, stepSeq, ruleCode, detail, Timestamp.from(Instant.now(clock)));
    }

    /**
     * 评估一步操作是否被闭锁。
     *
     * @param operationType 操作类型
     * @param deviceId      操作设备编号
     * @return 评估结果，blocked=true 表示闭锁拒绝
     */
    public InterlockResult evaluate(String operationType, String deviceId) {
        String intervalId = jdbc.query(
                        "SELECT interval_id FROM device_state WHERE device_id = ?",
                        (rs, rowNum) -> rs.getString(1), deviceId)
                .stream().findFirst().orElse(null);
        if (intervalId == null) {
            return InterlockResult.pass();
        }
        return evaluateInInterval(operationType, intervalId);
    }

    /**
     * 按间隔评估一步操作是否被闭锁（实物接地线挂接时按借用绑定间隔评估，不依赖设备台账行）。
     */
    public InterlockResult evaluateInInterval(String operationType, String intervalId) {
        return switch (operationType) {
            case "OPEN_DISCONNECTOR", "CLOSE_DISCONNECTOR" -> checkLoadDisconnector(intervalId);
            case "APPLY_GROUND" -> checkLiveGround(intervalId);
            case "CLOSE_BREAKER" -> checkGroundedEnergize(intervalId);
            case "BUS_OUTAGE" -> checkBusOutageSequence(intervalId);
            default -> InterlockResult.pass();
        };
    }

    /** 带负荷拉合刀闸：间隔内开关合位时禁止拉合刀闸。 */
    private InterlockResult checkLoadDisconnector(String intervalId) {
        List<String> closedBreakers = closedBreakers(intervalId);
        if (ruleEnabled(LOAD_DISCONNECTOR) && !closedBreakers.isEmpty()) {
            return InterlockResult.block(LOAD_DISCONNECTOR,
                    "带负荷拉合刀闸：间隔 " + intervalId + " 开关 " + closedBreakers + " 仍在合位");
        }
        return InterlockResult.pass();
    }

    /** 带电挂接地线：间隔内开关合位（设备带电）时禁止挂接地线。 */
    private InterlockResult checkLiveGround(String intervalId) {
        List<String> closedBreakers = closedBreakers(intervalId);
        if (ruleEnabled(LIVE_GROUND) && !closedBreakers.isEmpty()) {
            return InterlockResult.block(LIVE_GROUND,
                    "带电挂接地线：间隔 " + intervalId + " 开关 " + closedBreakers + " 仍在合位");
        }
        return InterlockResult.pass();
    }

    /** 带地线送电：间隔内接地线未拆时禁止合开关。 */
    private InterlockResult checkGroundedEnergize(String intervalId) {
        List<String> appliedGrounds = jdbc.query(
                "SELECT device_id FROM device_state WHERE interval_id = ? AND device_type = 'GROUND' AND state = 'APPLIED'",
                (rs, rowNum) -> rs.getString(1), intervalId);
        if (ruleEnabled(GROUNDED_ENERGIZE) && !appliedGrounds.isEmpty()) {
            return InterlockResult.block(GROUNDED_ENERGIZE,
                    "带地线送电：间隔 " + intervalId + " 接地线 " + appliedGrounds + " 未拆除");
        }
        return InterlockResult.pass();
    }

    /** 母线停电顺序：须先断开全部出线开关，否则整步拒绝。 */
    private InterlockResult checkBusOutageSequence(String intervalId) {
        List<String> closedBreakers = closedBreakers(intervalId);
        if (ruleEnabled(BUS_OUTAGE_SEQUENCE) && !closedBreakers.isEmpty()) {
            return InterlockResult.block(BUS_OUTAGE_SEQUENCE,
                    "母线停电顺序违例：母线 " + intervalId + " 出线开关 " + closedBreakers + " 未全部断开");
        }
        return InterlockResult.pass();
    }

    private List<String> closedBreakers(String intervalId) {
        return jdbc.query(
                "SELECT device_id FROM device_state WHERE interval_id = ? AND device_type = 'BREAKER' AND state = 'CLOSED'",
                (rs, rowNum) -> rs.getString(1), intervalId);
    }

    private boolean ruleEnabled(String ruleCode) {
        Boolean enabled = jdbc.query(
                        "SELECT enabled FROM interlock_rule WHERE rule_code = ?",
                        (rs, rowNum) -> rs.getBoolean(1), ruleCode)
                .stream().findFirst().orElse(null);
        return enabled == null || enabled;
    }
}
