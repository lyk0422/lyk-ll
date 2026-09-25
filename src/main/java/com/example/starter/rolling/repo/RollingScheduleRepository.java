package com.example.starter.rolling.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 轧钢排产全部表的访问层：排产单、作业块、换辊窗口、生效版本、实绩、迟到事件、幂等记录。
 */
@Repository
public class RollingScheduleRepository {

    private final JdbcTemplate jdbc;

    public RollingScheduleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 排产单行。status 为状态枚举名；effective/locked/rollChangeRequired 为 0/1。 */
    public record OrderRow(long id, String requestId, String fingerprint, String lineCode, String unitCode,
                           int versionNo, Long baseVersionId, String status, boolean effective,
                           boolean locked, boolean rollChangeRequired, LocalDateTime frozenAt,
                           LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** 作业块行。 */
    public record ItemRow(long id, long orderId, int seq, int campaignNo, int seqInCampaign,
                          String contractNo, int widthMm, BigDecimal rollingKm, boolean warmup,
                          boolean locked, LocalDateTime startTime, LocalDateTime endTime) {
    }

    /** 换辊窗口行，半开区间 [startTime, endTime)。 */
    public record WindowRow(long id, long orderId, int campaignNo, String lineCode,
                            LocalDateTime startTime, LocalDateTime endTime) {
    }

    /** 实绩行。 */
    public record ActualRow(long id, long orderId, String contractNo, int passNo, BigDecimal rollingKm,
                            LocalDateTime recordedAt, String requestId, String fingerprint,
                            LocalDateTime createdAt) {
    }

    /** 幂等记录行。 */
    public record IdempotentRow(String requestId, String bizType, String fingerprint,
                                int httpStatus, String responseBody, LocalDateTime createdAt) {
    }

    private static final RowMapper<OrderRow> ORDER_MAPPER = (rs, n) -> new OrderRow(
            rs.getLong("id"), rs.getString("request_id"), rs.getString("fingerprint"),
            rs.getString("line_code"), rs.getString("unit_code"), rs.getInt("version_no"),
            (Long) rs.getObject("base_version_id"), rs.getString("status"),
            rs.getBoolean("effective"), rs.getBoolean("locked"),
            rs.getBoolean("roll_change_required"),
            rs.getTimestamp("frozen_at") == null ? null : rs.getTimestamp("frozen_at").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime());

    private static final RowMapper<ItemRow> ITEM_MAPPER = (rs, n) -> new ItemRow(
            rs.getLong("id"), rs.getLong("order_id"), rs.getInt("seq"),
            rs.getInt("campaign_no"), rs.getInt("seq_in_campaign"),
            rs.getString("contract_no"), rs.getInt("width_mm"), rs.getBigDecimal("rolling_km"),
            rs.getBoolean("warmup"), rs.getBoolean("locked"),
            rs.getTimestamp("start_time").toLocalDateTime(),
            rs.getTimestamp("end_time").toLocalDateTime());

    private static final RowMapper<WindowRow> WINDOW_MAPPER = (rs, n) -> new WindowRow(
            rs.getLong("id"), rs.getLong("order_id"), rs.getInt("campaign_no"),
            rs.getString("line_code"),
            rs.getTimestamp("start_time").toLocalDateTime(),
            rs.getTimestamp("end_time").toLocalDateTime());

    private static final RowMapper<ActualRow> ACTUAL_MAPPER = (rs, n) -> new ActualRow(
            rs.getLong("id"), rs.getLong("order_id"), rs.getString("contract_no"),
            rs.getInt("pass_no"), rs.getBigDecimal("rolling_km"),
            rs.getTimestamp("recorded_at").toLocalDateTime(), rs.getString("request_id"),
            rs.getString("fingerprint"), rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<IdempotentRow> IDEMPOTENT_MAPPER = (rs, n) -> new IdempotentRow(
            rs.getString("request_id"), rs.getString("biz_type"), rs.getString("fingerprint"),
            rs.getInt("http_status"), rs.getString("response_body"),
            rs.getTimestamp("created_at").toLocalDateTime());

    // ---------------- 排产单 ----------------

    public void insertOrder(OrderRow row) {
        jdbc.update("""
                INSERT INTO schedule_order
                  (id, request_id, fingerprint, line_code, unit_code, version_no, base_version_id,
                   status, effective, locked, roll_change_required, frozen_at, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                row.id(), row.requestId(), row.fingerprint(), row.lineCode(), row.unitCode(),
                row.versionNo(), row.baseVersionId(), row.status(),
                row.effective(), row.locked(), row.rollChangeRequired(),
                row.frozenAt() == null ? null : Timestamp.valueOf(row.frozenAt()),
                Timestamp.valueOf(row.createdAt()), Timestamp.valueOf(row.updatedAt()));
    }

    public OrderRow findOrderById(long orderId) {
        List<OrderRow> rows = jdbc.query(
                "SELECT * FROM schedule_order WHERE id = ?", ORDER_MAPPER, orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void updateStatus(long orderId, String status, LocalDateTime now) {
        jdbc.update("UPDATE schedule_order SET status = ?, updated_at = ? WHERE id = ?",
                status, Timestamp.valueOf(now), orderId);
    }

    public void updateEffectiveFlag(long orderId, boolean effective, LocalDateTime now) {
        jdbc.update("UPDATE schedule_order SET effective = ?, updated_at = ? WHERE id = ?",
                effective, Timestamp.valueOf(now), orderId);
    }

    public void markRollChangeLock(long orderId, LocalDateTime now) {
        jdbc.update(
                "UPDATE schedule_order SET locked = 1, roll_change_required = 1, updated_at = ? WHERE id = ?",
                Timestamp.valueOf(now), orderId);
    }

    public void markFrozen(long orderId, LocalDateTime now) {
        jdbc.update(
                "UPDATE schedule_order SET status = 'FROZEN', frozen_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.valueOf(now), Timestamp.valueOf(now), orderId);
    }

    // ---------------- 作业块与换辊窗口 ----------------

    public void insertItem(ItemRow row) {
        jdbc.update("""
                INSERT INTO schedule_item
                  (id, order_id, seq, campaign_no, seq_in_campaign, contract_no, width_mm,
                   rolling_km, warmup, locked, start_time, end_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                row.id(), row.orderId(), row.seq(), row.campaignNo(), row.seqInCampaign(),
                row.contractNo(), row.widthMm(), row.rollingKm(), row.warmup(), row.locked(),
                Timestamp.valueOf(row.startTime()), Timestamp.valueOf(row.endTime()));
    }

    public List<ItemRow> findItemsByOrderId(long orderId) {
        return jdbc.query("SELECT * FROM schedule_item WHERE order_id = ? ORDER BY seq",
                ITEM_MAPPER, orderId);
    }

    public ItemRow findItemByContract(long orderId, String contractNo) {
        List<ItemRow> rows = jdbc.query(
                "SELECT * FROM schedule_item WHERE order_id = ? AND contract_no = ?",
                ITEM_MAPPER, orderId, contractNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void lockItem(long itemId) {
        jdbc.update("UPDATE schedule_item SET locked = 1 WHERE id = ?", itemId);
    }

    public void insertWindow(WindowRow row) {
        jdbc.update("""
                INSERT INTO roll_change_window
                  (id, order_id, campaign_no, line_code, start_time, end_time)
                VALUES (?,?,?,?,?,?)
                """,
                row.id(), row.orderId(), row.campaignNo(), row.lineCode(),
                Timestamp.valueOf(row.startTime()), Timestamp.valueOf(row.endTime()));
    }

    public List<WindowRow> findWindowsByOrderId(long orderId) {
        return jdbc.query(
                "SELECT * FROM roll_change_window WHERE order_id = ? ORDER BY campaign_no",
                WINDOW_MAPPER, orderId);
    }

    // ---------------- 生效版本 ----------------

    public Long findEffectiveOrderId(String lineCode) {
        List<Long> ids = jdbc.query(
                "SELECT order_id FROM effective_version WHERE line_code = ?",
                (rs, n) -> rs.getLong("order_id"), lineCode);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 首版下达：插入产线生效版本行，主键冲突表示并发下达失败。
     *
     * @return 是否插入成功
     */
    public boolean tryInsertEffective(String lineCode, long orderId, LocalDateTime now) {
        try {
            jdbc.update(
                    "INSERT INTO effective_version (line_code, order_id, since_at) VALUES (?,?,?)",
                    lineCode, orderId, Timestamp.valueOf(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 修改版下达：仅当当前生效版本恰为 baseVersionId 时抢占生效位，
     * 靠条件更新让数据库裁决并发下达，affected=0 表示已有其他版本抢先生效。
     *
     * @return 是否抢占成功
     */
    public boolean tryReplaceEffective(String lineCode, long newOrderId, long baseVersionId,
                                       LocalDateTime now) {
        int affected = jdbc.update("""
                UPDATE effective_version SET order_id = ?, since_at = ?
                WHERE line_code = ? AND order_id = ?
                """, newOrderId, Timestamp.valueOf(now), lineCode, baseVersionId);
        return affected == 1;
    }

    // ---------------- 实绩与迟到事件 ----------------

    public void insertActual(ActualRow row) {
        jdbc.update("""
                INSERT INTO actual_result
                  (id, order_id, contract_no, pass_no, rolling_km, recorded_at,
                   request_id, fingerprint, created_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,
                row.id(), row.orderId(), row.contractNo(), row.passNo(), row.rollingKm(),
                Timestamp.valueOf(row.recordedAt()), row.requestId(), row.fingerprint(),
                Timestamp.valueOf(row.createdAt()));
    }

    public boolean existsActual(String contractNo, int passNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM actual_result WHERE contract_no = ? AND pass_no = ?",
                Integer.class, contractNo, passNo);
        return count != null && count > 0;
    }

    public ActualRow findActual(String contractNo, int passNo) {
        List<ActualRow> rows = jdbc.query(
                "SELECT * FROM actual_result WHERE contract_no = ? AND pass_no = ?",
                ACTUAL_MAPPER, contractNo, passNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 汇总某排产单指定换辊周期内、归属作业合同的全部去重实绩里程（公里）。
     */
    public BigDecimal sumActualKmForCampaign(long orderId, int campaignNo) {
        BigDecimal total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(a.rolling_km), 0)
                FROM actual_result a
                JOIN schedule_item i ON i.contract_no = a.contract_no AND i.order_id = a.order_id
                WHERE a.order_id = ? AND i.campaign_no = ?
                """, BigDecimal.class, orderId, campaignNo);
        return total == null ? BigDecimal.ZERO : total;
    }

    public long countActualsInOrder(long orderId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM actual_result WHERE order_id = ?", Long.class, orderId);
        return count == null ? 0L : count;
    }

    /**
     * 插入冻结后的迟到实绩事件（不影响已冻结里程与换辊结论）。
     */
    public void insertLateEvent(long id, long orderId, String requestId, String contractNo, int passNo,
                                BigDecimal rollingKm, LocalDateTime recordedAt, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO late_actual_event
                  (id, order_id, request_id, contract_no, pass_no, rolling_km, recorded_at, created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """,
                id, orderId, requestId, contractNo, passNo, rollingKm,
                Timestamp.valueOf(recordedAt), Timestamp.valueOf(now));
    }

    public long countLateEvents(long orderId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM late_actual_event WHERE order_id = ?", Long.class, orderId);
        return count == null ? 0L : count;
    }

    // ---------------- 幂等记录 ----------------

    public IdempotentRow findIdempotent(String requestId) {
        List<IdempotentRow> rows = jdbc.query(
                "SELECT * FROM idempotent_record WHERE request_id = ?", IDEMPOTENT_MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * @return true 插入成功；false 表示同 requestId 记录已存在（并发重放）
     */
    public boolean tryInsertIdempotent(String requestId, String bizType, String fingerprint,
                                       int httpStatus, String responseBody, LocalDateTime now) {
        try {
            jdbc.update("""
                    INSERT INTO idempotent_record
                      (request_id, biz_type, fingerprint, http_status, response_body, created_at)
                    VALUES (?,?,?,?,?,?)
                    """,
                    requestId, bizType, fingerprint, httpStatus, responseBody,
                    Timestamp.valueOf(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
