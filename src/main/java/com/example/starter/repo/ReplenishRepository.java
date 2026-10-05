package com.example.starter.repo;

import com.example.starter.domain.DiscrepancyStatus;
import com.example.starter.domain.PlanStatus;
import com.example.starter.domain.PlanType;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 加钞服务数据访问层：全部使用参数化 SQL，时间以 UTC 时刻写入 TIMESTAMP 列。
 */
@Repository
public class ReplenishRepository {

    private final JdbcTemplate jdbc;

    public ReplenishRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- ATM ----------------

    public Optional<Atm> findAtmByCode(String code) {
        return queryOne("SELECT * FROM atm WHERE code = ?", ATM_MAPPER, code);
    }

    public Optional<Atm> findAtmById(long id) {
        return queryOne("SELECT * FROM atm WHERE id = ?", ATM_MAPPER, id);
    }

    public List<Atm> findAllAtms() {
        return jdbc.query("SELECT * FROM atm ORDER BY id", ATM_MAPPER);
    }

    @Transactional
    public void freezeAtm(long atmId) {
        jdbc.update("UPDATE atm SET frozen = 1 WHERE id = ?", atmId);
    }

    // ---------------- 押运员 ----------------

    public Optional<Custodian> findCustodianByCode(String code) {
        return queryOne("SELECT * FROM custodian WHERE code = ?", CUSTODIAN_MAPPER, code);
    }

    public Optional<Custodian> findCustodianById(long id) {
        return queryOne("SELECT * FROM custodian WHERE id = ?", CUSTODIAN_MAPPER, id);
    }

    @Transactional
    public void freezeCustodian(long custodianId) {
        jdbc.update("UPDATE custodian SET frozen = 1 WHERE id = ?", custodianId);
    }

    // ---------------- 计划单号 ----------------

    @Transactional
    public long nextPlanNoSequence() {
        jdbc.update("UPDATE seq_plan_no SET seq_value = seq_value + 1 WHERE id = 1");
        Long value = jdbc.queryForObject("SELECT seq_value FROM seq_plan_no WHERE id = 1", Long.class);
        return value == null ? 0 : value;
    }

    // ---------------- 计划 ----------------

    /** 单条插入；可能抛唯一键冲突，不加独立事务边界，便于服务层在同一事务内捕获并继续。 */
    public long insertPlan(PlanRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO replenish_plan (plan_no, atm_id, business_date, plan_type, queue_position, "
                            + "predicted_amount, planned_amount, "
                            + "status, approved_at, escort_deadline, escort_completed_at, counted_amount, counted_at, "
                            + "count_custodian_id, device_amount, device_confirmed_at, reconciled_at, discrepancy_id, "
                            + "sealed_at, created_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, row.getPlanNo());
            ps.setLong(i++, row.getAtmId());
            ps.setDate(i++, java.sql.Date.valueOf(row.getBusinessDate()));
            ps.setString(i++, row.getPlanType().name());
            ps.setInt(i++, row.getQueuePosition());
            ps.setLong(i++, row.getPredictedAmount());
            ps.setLong(i++, row.getPlannedAmount());
            ps.setString(i++, row.getStatus().name());
            setNullableInstant(ps, i++, row.getApprovedAt());
            setNullableInstant(ps, i++, row.getEscortDeadline());
            setNullableInstant(ps, i++, row.getEscortCompletedAt());
            setNullableLong(ps, i++, row.getCountedAmount());
            setNullableInstant(ps, i++, row.getCountedAt());
            setNullableLong(ps, i++, row.getCountCustodianId());
            setNullableLong(ps, i++, row.getDeviceAmount());
            setNullableInstant(ps, i++, row.getDeviceConfirmedAt());
            setNullableInstant(ps, i++, row.getReconciledAt());
            setNullableLong(ps, i++, row.getDiscrepancyId());
            setNullableInstant(ps, i++, row.getSealedAt());
            ps.setTimestamp(i, Timestamp.from(row.getCreatedAt()));
            return ps;
        }, keyHolder);
        long id = extractKey(keyHolder);
        row.setId(id);
        return id;
    }

    public Optional<PlanRow> findPlanById(long id) {
        return queryOne("SELECT * FROM replenish_plan WHERE id = ?", PLAN_MAPPER, id);
    }

    public Optional<PlanRow> findPlanByAtmAndDate(long atmId, LocalDate businessDate) {
        return queryOne("SELECT * FROM replenish_plan WHERE atm_id = ? AND business_date = ?",
                PLAN_MAPPER, atmId, java.sql.Date.valueOf(businessDate));
    }

    /** 同 ATM 当日同类型计划查询，用于临时计划当日唯一受理判定。 */
    public Optional<PlanRow> findPlanByAtmDateAndType(long atmId, LocalDate businessDate, PlanType planType) {
        return queryOne("SELECT * FROM replenish_plan WHERE atm_id = ? AND business_date = ? AND plan_type = ?",
                PLAN_MAPPER, atmId, java.sql.Date.valueOf(businessDate), planType.name());
    }

    /** 执行中计划（已审批且未完结）：同一 ATM 同时刻只允许一张。 */
    public List<PlanRow> findExecutingByAtm(long atmId) {
        return jdbc.query(
                "SELECT * FROM replenish_plan WHERE atm_id = ? AND status IN ('APPROVED','ESCORTED','COUNTED') "
                        + "ORDER BY id",
                PLAN_MAPPER, atmId);
    }

    /** 让路计划按排队位置升序，恢复执行时按队列顺序受理。 */
    public List<PlanRow> findYieldedByAtm(long atmId) {
        return jdbc.query(
                "SELECT * FROM replenish_plan WHERE atm_id = ? AND status = 'YIELDED' ORDER BY queue_position, id",
                PLAN_MAPPER, atmId);
    }

    /** 同一 ATM 当前最大排队位置；无计划时返回 0。 */
    public int maxQueuePosition(long atmId) {
        Integer n = jdbc.queryForObject(
                "SELECT MAX(queue_position) FROM replenish_plan WHERE atm_id = ?", Integer.class, atmId);
        return n == null ? 0 : n;
    }

    public List<PlanRow> findPlansByBusinessDate(LocalDate businessDate) {
        return jdbc.query("SELECT * FROM replenish_plan WHERE business_date = ? ORDER BY id",
                PLAN_MAPPER, java.sql.Date.valueOf(businessDate));
    }

    /** 行锁加载，用于状态机推进与并发串行化。 */
    public Optional<PlanRow> findPlanByIdForUpdate(long id) {
        return queryOne("SELECT * FROM replenish_plan WHERE id = ? FOR UPDATE", PLAN_MAPPER, id);
    }

    public List<PlanRow> findAllPlans() {
        return jdbc.query("SELECT * FROM replenish_plan ORDER BY id", PLAN_MAPPER);
    }

    @Transactional
    public void updatePlan(PlanRow row) {
        jdbc.update(
                "UPDATE replenish_plan SET status = ?, approved_at = ?, escort_deadline = ?, escort_completed_at = ?, "
                        + "counted_amount = ?, counted_at = ?, count_custodian_id = ?, device_amount = ?, "
                        + "device_confirmed_at = ?, reconciled_at = ?, discrepancy_id = ?, sealed_at = ? WHERE id = ?",
                row.getStatus().name(),
                row.getApprovedAt() == null ? null : Timestamp.from(row.getApprovedAt()),
                row.getEscortDeadline() == null ? null : Timestamp.from(row.getEscortDeadline()),
                row.getEscortCompletedAt() == null ? null : Timestamp.from(row.getEscortCompletedAt()),
                row.getCountedAmount(),
                row.getCountedAt() == null ? null : Timestamp.from(row.getCountedAt()),
                row.getCountCustodianId(),
                row.getDeviceAmount(),
                row.getDeviceConfirmedAt() == null ? null : Timestamp.from(row.getDeviceConfirmedAt()),
                row.getReconciledAt() == null ? null : Timestamp.from(row.getReconciledAt()),
                row.getDiscrepancyId(),
                row.getSealedAt() == null ? null : Timestamp.from(row.getSealedAt()),
                row.getId());
    }

    // ---------------- 押运打卡 ----------------

    @Transactional
    public long insertCheckin(CheckinRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO escort_checkin (plan_id, custodian_id, seq, checkin_time, created_at) "
                            + "VALUES (?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, row.planId());
            ps.setLong(2, row.custodianId());
            ps.setInt(3, row.seq());
            ps.setTimestamp(4, Timestamp.from(row.checkinTime()));
            ps.setTimestamp(5, Timestamp.from(row.createdAt()));
            return ps;
        }, keyHolder);
        return extractKey(keyHolder);
    }

    public int countCheckins(long planId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM escort_checkin WHERE plan_id = ?", Integer.class, planId);
        return n == null ? 0 : n;
    }

    public boolean existsCheckin(long planId, long custodianId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM escort_checkin WHERE plan_id = ? AND custodian_id = ?",
                Integer.class, planId, custodianId);
        return n != null && n > 0;
    }

    public List<CheckinRow> findCheckins(long planId) {
        return jdbc.query(
                "SELECT * FROM escort_checkin WHERE plan_id = ? ORDER BY checkin_time, id",
                CHECKIN_MAPPER, planId);
    }

    // ---------------- 长短款挂账 ----------------

    @Transactional
    public long insertDiscrepancy(DiscrepancyRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO plan_discrepancy (plan_id, amount, status, responsible_custodian_id, "
                            + "occurred_date, due_date, settled_at, created_at) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, row.getPlanId());
            ps.setLong(2, row.getAmount());
            ps.setString(3, row.getStatus().name());
            ps.setLong(4, row.getResponsibleCustodianId());
            ps.setDate(5, java.sql.Date.valueOf(row.getOccurredDate()));
            ps.setDate(6, java.sql.Date.valueOf(row.getDueDate()));
            setNullableInstant(ps, 7, row.getSettledAt());
            ps.setTimestamp(8, Timestamp.from(row.getCreatedAt()));
            return ps;
        }, keyHolder);
        long id = extractKey(keyHolder);
        row.setId(id);
        return id;
    }

    public Optional<DiscrepancyRow> findDiscrepancyByPlan(long planId) {
        return queryOne("SELECT * FROM plan_discrepancy WHERE plan_id = ?", DISCREPANCY_MAPPER, planId);
    }

    public Optional<DiscrepancyRow> findDiscrepancyByPlanForUpdate(long planId) {
        return queryOne("SELECT * FROM plan_discrepancy WHERE plan_id = ? FOR UPDATE",
                DISCREPANCY_MAPPER, planId);
    }

    @Transactional
    public void updateDiscrepancy(DiscrepancyRow row) {
        jdbc.update(
                "UPDATE plan_discrepancy SET amount = ?, status = ?, responsible_custodian_id = ?, "
                        + "occurred_date = ?, due_date = ?, settled_at = ? WHERE id = ?",
                row.getAmount(),
                row.getStatus().name(),
                row.getResponsibleCustodianId(),
                java.sql.Date.valueOf(row.getOccurredDate()),
                java.sql.Date.valueOf(row.getDueDate()),
                row.getSettledAt() == null ? null : Timestamp.from(row.getSettledAt()),
                row.getId());
    }

    // ---------------- 迟到事件 ----------------

    @Transactional
    public void insertLateEvent(long planId, String eventType, String payload, Instant now) {
        jdbc.update("INSERT INTO late_event (plan_id, event_type, payload, created_at) VALUES (?,?,?,?)",
                planId, eventType, payload, Timestamp.from(now));
    }

    public long countLateEvents(long planId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM late_event WHERE plan_id = ?", Long.class, planId);
        return n == null ? 0L : n;
    }

    // ---------------- 节假日日历 ----------------

    public Optional<HolidayRow> findHolidayByDate(LocalDate holidayDate) {
        return queryOne("SELECT * FROM holiday_calendar WHERE holiday_date = ?",
                HOLIDAY_MAPPER, java.sql.Date.valueOf(holidayDate));
    }

    // ---------------- 快速通道留痕 ----------------

    @Transactional
    public long insertFastTrackAudit(long planId, String decision, String detail, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO fast_track_audit (plan_id, decision, detail, created_at) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, planId);
            ps.setString(2, decision);
            ps.setString(3, detail);
            ps.setTimestamp(4, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return extractKey(keyHolder);
    }

    public List<FastTrackAuditRow> findFastTrackAuditsByPlan(long planId) {
        return jdbc.query("SELECT * FROM fast_track_audit WHERE plan_id = ? ORDER BY id",
                AUDIT_MAPPER, planId);
    }

    /** 按 UTC 日界查询留痕，纳入日结核对。 */
    public List<FastTrackAuditRow> findFastTrackAuditsByCreatedDate(LocalDate date) {
        return jdbc.query(
                "SELECT * FROM fast_track_audit WHERE created_at >= ? AND created_at < ? ORDER BY id",
                AUDIT_MAPPER,
                Timestamp.from(date.atStartOfDay(ZoneOffset.UTC).toInstant()),
                Timestamp.from(date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
    }

    // ---------------- 幂等记录 ----------------

    /** 单条幂等留痕插入；并发同键可能抛唯一键冲突，由服务层捕获裁决，不加独立事务边界。 */
    public void insertIdempotent(String requestId, String action, String fingerprint,
                                 Long planId, Integer statusCode, String responseBody, Instant now) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO idempotent_request (request_id, action, fingerprint, plan_id, status_code, "
                            + "response_body, created_at) VALUES (?,?,?,?,?,?,?)");
            ps.setString(1, requestId);
            ps.setString(2, action);
            ps.setString(3, fingerprint);
            if (planId == null) {
                ps.setNull(4, Types.BIGINT);
            } else {
                ps.setLong(4, planId);
            }
            if (statusCode == null) {
                ps.setNull(5, Types.INTEGER);
            } else {
                ps.setInt(5, statusCode);
            }
            ps.setString(6, responseBody);
            ps.setTimestamp(7, Timestamp.from(now));
            return ps;
        });
    }

    /** 业务执行完成后回写首次响应；占位记录随事务提交/回滚一并落盘或撤销。 */
    @Transactional
    public void completeIdempotent(String requestId, int statusCode, String responseBody) {
        jdbc.update("UPDATE idempotent_request SET status_code = ?, response_body = ? WHERE request_id = ?",
                statusCode, responseBody, requestId);
    }

    public Optional<IdempotentRow> findIdempotent(String requestId) {
        return queryOne("SELECT * FROM idempotent_request WHERE request_id = ?", IDEMPOTENT_MAPPER, requestId);
    }

    /** H2 MySQL 模式下自增键可能以 String 返回，统一解析。 */
    private static long extractKey(KeyHolder keyHolder) {
        if (keyHolder.getKeyList().isEmpty()) {
            return 0L;
        }
        Object value = keyHolder.getKeyList().get(0).values().iterator().next();
        return value == null ? 0L : Long.parseLong(value.toString());
    }

    // ---------------- 私有映射与工具 ----------------

    private <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
        try {
            return Optional.of(jdbc.queryForObject(sql, mapper, args));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private static void setNullableInstant(PreparedStatement ps, int index, Instant value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.TIMESTAMP);
        } else {
            ps.setTimestamp(index, Timestamp.from(value));
        }
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static final RowMapper<Atm> ATM_MAPPER = (rs, n) -> new Atm(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getLong("capacity"),
            rs.getLong("daily_avg_withdrawal"),
            rs.getInt("frozen") == 1,
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<Custodian> CUSTODIAN_MAPPER = (rs, n) -> new Custodian(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getInt("frozen") == 1,
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<PlanRow> PLAN_MAPPER = (rs, n) -> {
        PlanRow r = new PlanRow();
        r.setId(rs.getLong("id"));
        r.setPlanNo(rs.getString("plan_no"));
        r.setAtmId(rs.getLong("atm_id"));
        r.setBusinessDate(rs.getDate("business_date").toLocalDate());
        r.setPlanType(PlanType.valueOf(rs.getString("plan_type")));
        r.setQueuePosition(rs.getInt("queue_position"));
        r.setPredictedAmount(rs.getLong("predicted_amount"));
        r.setPlannedAmount(rs.getLong("planned_amount"));
        r.setStatus(PlanStatus.valueOf(rs.getString("status")));
        Timestamp approvedAt = rs.getTimestamp("approved_at");
        r.setApprovedAt(approvedAt == null ? null : approvedAt.toInstant());
        Timestamp deadline = rs.getTimestamp("escort_deadline");
        r.setEscortDeadline(deadline == null ? null : deadline.toInstant());
        Timestamp escortDone = rs.getTimestamp("escort_completed_at");
        r.setEscortCompletedAt(escortDone == null ? null : escortDone.toInstant());
        Long counted = (Long) rs.getObject("counted_amount");
        r.setCountedAmount(counted);
        Timestamp countedAt = rs.getTimestamp("counted_at");
        r.setCountedAt(countedAt == null ? null : countedAt.toInstant());
        Long countCustodianId = (Long) rs.getObject("count_custodian_id");
        r.setCountCustodianId(countCustodianId);
        Long device = (Long) rs.getObject("device_amount");
        r.setDeviceAmount(device);
        Timestamp deviceAt = rs.getTimestamp("device_confirmed_at");
        r.setDeviceConfirmedAt(deviceAt == null ? null : deviceAt.toInstant());
        Timestamp reconciledAt = rs.getTimestamp("reconciled_at");
        r.setReconciledAt(reconciledAt == null ? null : reconciledAt.toInstant());
        Long discrepancyId = (Long) rs.getObject("discrepancy_id");
        r.setDiscrepancyId(discrepancyId);
        Timestamp sealedAt = rs.getTimestamp("sealed_at");
        r.setSealedAt(sealedAt == null ? null : sealedAt.toInstant());
        r.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return r;
    };

    private static final RowMapper<CheckinRow> CHECKIN_MAPPER = (rs, n) -> new CheckinRow(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getLong("custodian_id"),
            rs.getInt("seq"),
            rs.getTimestamp("checkin_time").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<DiscrepancyRow> DISCREPANCY_MAPPER = (rs, n) -> {
        DiscrepancyRow r = new DiscrepancyRow();
        r.setId(rs.getLong("id"));
        r.setPlanId(rs.getLong("plan_id"));
        r.setAmount(rs.getLong("amount"));
        r.setStatus(DiscrepancyStatus.valueOf(rs.getString("status")));
        r.setResponsibleCustodianId(rs.getLong("responsible_custodian_id"));
        r.setOccurredDate(rs.getDate("occurred_date").toLocalDate());
        r.setDueDate(rs.getDate("due_date").toLocalDate());
        Timestamp settledAt = rs.getTimestamp("settled_at");
        r.setSettledAt(settledAt == null ? null : settledAt.toInstant());
        r.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return r;
    };

    private static final RowMapper<IdempotentRow> IDEMPOTENT_MAPPER = (rs, n) -> new IdempotentRow(
            rs.getString("request_id"),
            rs.getString("action"),
            rs.getString("fingerprint"),
            (Long) rs.getObject("plan_id"),
            (Integer) rs.getObject("status_code"),
            rs.getString("response_body"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<HolidayRow> HOLIDAY_MAPPER = (rs, n) -> new HolidayRow(
            rs.getLong("id"),
            rs.getDate("holiday_date").toLocalDate(),
            rs.getString("name"),
            rs.getInt("peak_factor_bp"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<FastTrackAuditRow> AUDIT_MAPPER = (rs, n) -> new FastTrackAuditRow(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("decision"),
            rs.getString("detail"),
            rs.getTimestamp("created_at").toInstant());
}
