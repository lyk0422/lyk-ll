package com.example.starter.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.repo.IdempotentRow;
import com.example.starter.repo.OperationRow;
import com.example.starter.repo.PlanRow;
import com.example.starter.repo.SegmentRow;

/**
 * 潜水作业全部持久化操作。所有 SQL 参数化；时刻为 UTC 纪元秒。
 */
@Repository
public class DivingRepository {

    private final JdbcTemplate jdbc;

    public DivingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<IdempotentRow> IDEMPOTENT_MAPPER = (rs, n) -> new IdempotentRow(
            rs.getString("request_id"), rs.getString("request_kind"), rs.getString("fingerprint"),
            rs.getInt("http_status"), rs.getString("response_json"), rs.getLong("created_at"));

    private static final RowMapper<OperationRow> OPERATION_MAPPER = (rs, n) -> new OperationRow(
            rs.getLong("id"), rs.getString("operation_no"), rs.getString("application_request"),
            rs.getString("diver_a"), rs.getString("diver_b"), rs.getString("group_key"),
            rs.getInt("depth_m"), rs.getInt("bottom_minutes"), rs.getString("status"),
            rs.getInt("plan_version"),
            (Long) rs.getObject("exit_at"), (Long) rs.getObject("observation_due_at"),
            (Long) rs.getObject("chamber_at"), (Long) rs.getObject("closed_at"),
            rs.getLong("created_at"));

    private static final RowMapper<PlanRow> PLAN_MAPPER = (rs, n) -> new PlanRow(
            rs.getLong("id"), rs.getLong("operation_id"), rs.getInt("version"),
            rs.getInt("depth_m"), rs.getInt("bottom_minutes"), rs.getInt("ndl_minutes"),
            rs.getBoolean("at_limit"), rs.getString("profile_json"),
            rs.getBoolean("active"), rs.getLong("created_at"));

    private static final RowMapper<SegmentRow> SEGMENT_MAPPER = (rs, n) -> new SegmentRow(
            rs.getLong("id"), rs.getLong("operation_id"), rs.getInt("plan_version"),
            rs.getString("request_id"), rs.getString("diver_id"), rs.getString("phase"),
            rs.getInt("start_depth_m"), rs.getInt("end_depth_m"),
            rs.getLong("started_at"), rs.getLong("ended_at"), rs.getLong("duration_seconds"),
            rs.getBigDecimal("rate_m_per_min"), rs.getBoolean("rate_alarm"),
            rs.getString("pair_state"), rs.getString("pair_key"),
            (Long) rs.getObject("pair_group_id"), rs.getBoolean("frozen"),
            rs.getLong("created_at"));

    // ---- 幂等台账 ----

    public Optional<IdempotentRow> findIdempotent(String requestId) {
        return jdbc.query("SELECT * FROM idempotent_request WHERE request_id = ?", IDEMPOTENT_MAPPER, requestId)
                .stream().findFirst();
    }

    /** @return false 表示 requestId 已被并发事务占用（唯一键冲突） */
    public boolean tryInsertIdempotent(IdempotentRow row) {
        try {
            jdbc.update("INSERT INTO idempotent_request "
                            + "(request_id, request_kind, fingerprint, http_status, response_json, created_at) "
                            + "VALUES (?,?,?,?,?,?)",
                    row.requestId(), row.requestKind(), row.fingerprint(), row.httpStatus(),
                    row.responseJson(), row.createdAt());
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /** 业务成功后在同一事务内把首次响应快照写回幂等台账。 */
    public void updateIdempotentResponse(String requestId, int httpStatus, String responseJson) {
        jdbc.update("UPDATE idempotent_request SET http_status = ?, response_json = ? WHERE request_id = ?",
                httpStatus, responseJson, requestId);
    }

    // ---- 减压表 ----

    /** 返回 {免减压极限分钟, 是否建议安全停留 0/1}。 */
    public Optional<int[]> findDecoEntry(int depthM) {
        List<int[]> rows = jdbc.query(
                "SELECT ndl_minutes, safety_stop FROM deco_table WHERE depth_m = ?",
                (rs, n) -> new int[] {rs.getInt("ndl_minutes"), rs.getBoolean("safety_stop") ? 1 : 0},
                depthM);
        return rows.stream().findFirst();
    }

    // ---- 潜次 ----

    public Optional<OperationRow> findOperationByNo(String operationNo) {
        return jdbc.query("SELECT * FROM dive_operation WHERE operation_no = ?", OPERATION_MAPPER, operationNo)
                .stream().findFirst();
    }

    public Optional<OperationRow> findOperationByApplicationRequest(String requestId) {
        return jdbc.query("SELECT * FROM dive_operation WHERE application_request = ?", OPERATION_MAPPER, requestId)
                .stream().findFirst();
    }

    public long insertOperation(OperationRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dive_operation (operation_no, application_request, diver_a, diver_b, "
                            + "group_key, depth_m, bottom_minutes, status, plan_version, "
                            + "exit_at, observation_due_at, chamber_at, closed_at, created_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.operationNo());
            ps.setString(2, row.applicationRequest());
            ps.setString(3, row.diverA());
            ps.setString(4, row.diverB());
            ps.setString(5, row.groupKey());
            ps.setInt(6, row.depthM());
            ps.setInt(7, row.bottomMinutes());
            ps.setString(8, row.status());
            ps.setInt(9, row.planVersion());
            ps.setObject(10, row.exitAt());
            ps.setObject(11, row.observationDueAt());
            ps.setObject(12, row.chamberAt());
            ps.setObject(13, row.closedAt());
            ps.setLong(14, row.createdAt());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public int updateOperationPlan(long operationId, int depthM, int bottomMinutes, int planVersion) {
        return jdbc.update(
                "UPDATE dive_operation SET depth_m = ?, bottom_minutes = ?, plan_version = ? WHERE id = ?",
                depthM, bottomMinutes, planVersion, operationId);
    }

    public int updateStatusExpected(long operationId, String expectedStatus, String newStatus) {
        return jdbc.update(
                "UPDATE dive_operation SET status = ? WHERE id = ? AND status = ?",
                newStatus, operationId, expectedStatus);
    }

    public int markExit(long operationId, long exitAt, long observationDueAt) {
        return jdbc.update(
                "UPDATE dive_operation SET status = 'OBSERVING', exit_at = ?, observation_due_at = ? "
                        + "WHERE id = ? AND status = 'EXECUTING'",
                exitAt, observationDueAt, operationId);
    }

    /** 仅观察期可启动加压舱；幂等于已是 CHAMBER 的潜次（返回 0 行）。 */
    public int activateChamber(long operationId, long chamberAt) {
        return jdbc.update(
                "UPDATE dive_operation SET status = 'CHAMBER', chamber_at = ? "
                        + "WHERE id = ? AND status = 'OBSERVING' AND chamber_at IS NULL",
                chamberAt, operationId);
    }

    public int markClosed(long operationId, long closedAt) {
        return jdbc.update(
                "UPDATE dive_operation SET status = 'CLOSED', closed_at = ? "
                        + "WHERE id = ? AND status = 'OBSERVING'",
                closedAt, operationId);
    }

    /** 占组：成功返回 true；同组已有进行中潜次返回 false（唯一键冲突）。 */
    public boolean occupyGroup(String groupKey, long operationId, String operationNo, long now) {
        try {
            jdbc.update(
                    "INSERT INTO active_dive_group (group_key, operation_id, operation_no, occupied_at) "
                            + "VALUES (?,?,?,?)",
                    groupKey, operationId, operationNo, now);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    public void releaseGroup(String groupKey) {
        jdbc.update("DELETE FROM active_dive_group WHERE group_key = ?", groupKey);
    }

    // ---- 方案 ----

    public void insertPlan(PlanRow row) {
        jdbc.update(
                "INSERT INTO dive_plan (operation_id, version, depth_m, bottom_minutes, ndl_minutes, "
                        + "at_limit, profile_json, active, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                row.operationId(), row.version(), row.depthM(), row.bottomMinutes(), row.ndlMinutes(),
                row.atLimit(), row.profileJson(), row.active(), row.createdAt());
    }

    public void deactivatePlans(long operationId) {
        jdbc.update("UPDATE dive_plan SET active = FALSE WHERE operation_id = ?", operationId);
    }

    public Optional<PlanRow> findActivePlan(long operationId) {
        return jdbc.query("SELECT * FROM dive_plan WHERE operation_id = ? AND active = TRUE",
                PLAN_MAPPER, operationId).stream().findFirst();
    }

    // ---- 配对锁与水下记录 ----

    /** 串行化同一配对槽的并发提交：先确保锁行存在，再 SELECT FOR UPDATE 取行锁。 */
    public void lockPairKey(String pairKey) {
        jdbc.update("MERGE INTO pair_lock (pair_key) KEY (pair_key) VALUES (?)", pairKey);
        jdbc.queryForObject("SELECT pair_key FROM pair_lock WHERE pair_key = ? FOR UPDATE",
                String.class, pairKey);
    }

    public long insertSegment(SegmentRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dive_segment (operation_id, plan_version, request_id, diver_id, phase, "
                            + "start_depth_m, end_depth_m, started_at, ended_at, duration_seconds, "
                            + "rate_m_per_min, rate_alarm, pair_state, pair_key, pair_group_id, frozen, created_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, row.operationId());
            ps.setInt(2, row.planVersion());
            ps.setString(3, row.requestId());
            ps.setString(4, row.diverId());
            ps.setString(5, row.phase());
            ps.setInt(6, row.startDepthM());
            ps.setInt(7, row.endDepthM());
            ps.setLong(8, row.startedAt());
            ps.setLong(9, row.endedAt());
            ps.setLong(10, row.durationSeconds());
            ps.setBigDecimal(11, row.rateMPerMin());
            ps.setBoolean(12, row.rateAlarm());
            ps.setString(13, row.pairState());
            ps.setString(14, row.pairKey());
            ps.setObject(15, row.pairGroupId());
            ps.setBoolean(16, row.frozen());
            ps.setLong(17, row.createdAt());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<SegmentRow> findSegmentByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM dive_segment WHERE request_id = ?", SEGMENT_MAPPER, requestId)
                .stream().findFirst();
    }

    public List<SegmentRow> findPendingSegments(long operationId, int planVersion, String pairKey) {
        return jdbc.query(
                "SELECT * FROM dive_segment WHERE operation_id = ? AND plan_version = ? AND pair_key = ? "
                        + "AND pair_state = 'PENDING' ORDER BY id",
                SEGMENT_MAPPER, operationId, planVersion, pairKey);
    }

    public int countPendingSegments(long operationId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dive_segment WHERE operation_id = ? AND pair_state = 'PENDING'",
                Integer.class, operationId);
        return count == null ? 0 : count;
    }

    public long insertPairGroup(long operationId, String pairKey, int planVersion,
                                long durationSeconds, long now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO segment_pair (operation_id, pair_key, plan_version, duration_seconds, created_at) "
                            + "VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, operationId);
            ps.setString(2, pairKey);
            ps.setInt(3, planVersion);
            ps.setLong(4, durationSeconds);
            ps.setLong(5, now);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void markSegmentEffective(long segmentId, long pairGroupId) {
        jdbc.update(
                "UPDATE dive_segment SET pair_state = 'EFFECTIVE', pair_group_id = ? WHERE id = ?",
                pairGroupId, segmentId);
    }

    public void insertLedger(String diverId, long operationId, long pairGroupId,
                             long startedAt, long endedAt, long durationSeconds, long now) {
        jdbc.update(
                "INSERT INTO diver_time_ledger (diver_id, operation_id, pair_group_id, started_at, ended_at, "
                        + "duration_seconds, created_at) VALUES (?,?,?,?,?,?,?)",
                diverId, operationId, pairGroupId, startedAt, endedAt, durationSeconds, now);
    }

    /**
     * 方案变更：冻结指定版本全部未冻结记录（PENDING/EFFECTIVE → FROZEN），返回冻结条数。
     */
    public int freezeSegments(long operationId, int oldPlanVersion) {
        return jdbc.update(
                "UPDATE dive_segment SET pair_state = 'FROZEN', frozen = TRUE "
                        + "WHERE operation_id = ? AND plan_version = ? AND frozen = FALSE",
                operationId, oldPlanVersion);
    }

    /** 删除被冻结记录对应的配对组与台账，保证冻结后不沿用旧方案记录。 */
    public int deleteLedgerByFrozenSegments(long operationId, int oldPlanVersion) {
        int pairs = jdbc.update(
                "DELETE FROM segment_pair WHERE operation_id = ? AND plan_version = ?",
                operationId, oldPlanVersion);
        return jdbc.update(
                "DELETE FROM diver_time_ledger WHERE operation_id = ? AND pair_group_id IN ("
                        + "SELECT pair_group_id FROM dive_segment "
                        + "WHERE operation_id = ? AND plan_version = ? AND pair_group_id IS NOT NULL)",
                operationId, operationId, oldPlanVersion) + pairs;
    }

    /**
     * 复算潜水员在 [windowStart, windowEnd) 内与台账区间重叠的累计水下秒数。
     */
    public long sumLedgerOverlapSeconds(String diverId, long windowStart, long windowEnd) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN ended_at > ? AND started_at < ? "
                        + "THEN LEAST(ended_at, ?) - GREATEST(started_at, ?) ELSE 0 END), 0) "
                        + "FROM diver_time_ledger WHERE diver_id = ?",
                Long.class, windowStart, windowEnd, windowEnd, windowStart, diverId);
        return sum == null ? 0L : sum;
    }

    public List<SegmentRow> findSegmentsByOperation(long operationId) {
        return jdbc.query("SELECT * FROM dive_segment WHERE operation_id = ? ORDER BY id",
                SEGMENT_MAPPER, operationId);
    }

    /** 查询某潜次全部台账行（用于累计时长复算）。 */
    public List<LedgerRow> findLedgerByOperation(long operationId) {
        return jdbc.query(
                "SELECT diver_id, started_at, ended_at, duration_seconds FROM diver_time_ledger "
                        + "WHERE operation_id = ? ORDER BY id",
                (rs, n) -> new LedgerRow(rs.getString("diver_id"), rs.getLong("started_at"),
                        rs.getLong("ended_at"), rs.getLong("duration_seconds")),
                operationId);
    }

    // ---- 体征 ----

    public long insertVitals(long operationId, String requestId, String diverId, long observedAt,
                             boolean symptomatic, String symptoms, boolean late, long now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO vitals_record (operation_id, request_id, diver_id, observed_at, symptomatic, "
                            + "symptoms, late, created_at) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, operationId);
            ps.setString(2, requestId);
            ps.setString(3, diverId);
            ps.setLong(4, observedAt);
            ps.setBoolean(5, symptomatic);
            ps.setString(6, symptoms);
            ps.setBoolean(7, late);
            ps.setLong(8, now);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public boolean existsSymptomaticVitals(long operationId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vitals_record WHERE operation_id = ? AND symptomatic = TRUE AND late = FALSE",
                Integer.class, operationId);
        return count != null && count > 0;
    }

    // ---- 事件 ----

    public void insertEvent(Long operationId, String eventType, String detail, long now) {
        jdbc.update("INSERT INTO dive_event (operation_id, event_type, detail, created_at) VALUES (?,?,?,?)",
                operationId, eventType, detail, now);
    }

    public List<String[]> findEvents(long operationId) {
        return jdbc.query("SELECT event_type, detail FROM dive_event WHERE operation_id = ? ORDER BY id",
                (rs, n) -> new String[] {rs.getString("event_type"), rs.getString("detail")}, operationId);
    }

    /** 事件（含 UTC 纪元秒）用于详情复算。 */
    public List<EventEntry> findEventEntries(long operationId) {
        return jdbc.query(
                "SELECT event_type, detail, created_at FROM dive_event WHERE operation_id = ? ORDER BY id",
                (rs, n) -> new EventEntry(rs.getString("event_type"), rs.getString("detail"),
                        rs.getLong("created_at")), operationId);
    }

    /** 事件审计行。 */
    public record EventEntry(String eventType, String detail, long createdAt) {
    }
}
