package com.example.starter.roaming.repo;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.roaming.domain.BatchRow;
import com.example.starter.roaming.domain.DiscrepancyRow;
import com.example.starter.roaming.domain.DetailRow;
import com.example.starter.roaming.domain.IdempotentRow;
import com.example.starter.roaming.domain.SessionRow;
import com.example.starter.roaming.domain.SnapshotRow;

/**
 * 充电漫游数据访问层：所有写操作均为条件更新/唯一约束驱动，供服务层在事务内完成并发控制。
 * 时间参数一律为 UTC 的 LocalDateTime；金额单位元、电量单位 Wh。
 */
@Repository
public class RoamingRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public RoamingRepository(NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.jdbc = namedParameterJdbcTemplate;
    }

    // ---------------- 基础资料 ----------------

    public String findHomeOperator(String certFingerprint) {
        List<String> list = jdbc.queryForList(
                "SELECT home_operator FROM ev_vehicle WHERE cert_fingerprint = :fp",
                new MapSqlParameterSource("fp", certFingerprint), String.class);
        return list.isEmpty() ? null : list.get(0);
    }

    public String findPointOperator(String pointId) {
        List<String> list = jdbc.queryForList(
                "SELECT operator_code FROM charge_point WHERE point_id = :pid",
                new MapSqlParameterSource("pid", pointId), String.class);
        return list.isEmpty() ? null : list.get(0);
    }

    public BigDecimal findOperatorRate(String operatorCode) {
        List<BigDecimal> list = jdbc.queryForList(
                "SELECT tariff_rate FROM ev_operator WHERE operator_code = :code",
                new MapSqlParameterSource("code", operatorCode), BigDecimal.class);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 锁定车辆行：同一车辆的授权事务借此串行化，保证在检 ACTIVE 会话时无并发遗漏。 */
    public void lockVehicle(String certFingerprint) {
        jdbc.queryForList(
                "SELECT id FROM ev_vehicle WHERE cert_fingerprint = :fp FOR UPDATE",
                new MapSqlParameterSource("fp", certFingerprint), Long.class);
    }

    // ---------------- 幂等请求 ----------------

    /**
     * 以 requestId 抢占幂等键；同键并发时数据库行锁使后来者阻塞至首笔事务提交。
     *
     * @throws DuplicateKeyException requestId 已存在
     */
    public void insertIdempotentClaim(String requestId, String kind, String paramFingerprint,
                                      LocalDateTime now) {
        jdbc.update(
                "INSERT INTO idempotent_request (request_id, request_kind, param_fingerprint, "
                        + "http_status, response_body, created_at) "
                        + "VALUES (:rid, :kind, :fp, 0, '', :now)",
                new MapSqlParameterSource()
                        .addValue("rid", requestId)
                        .addValue("kind", kind)
                        .addValue("fp", paramFingerprint)
                        .addValue("now", Timestamp.valueOf(now)));
    }

    public IdempotentRow findIdempotent(String requestId) {
        List<IdempotentRow> list = jdbc.query(
                "SELECT id, request_id, request_kind, param_fingerprint, http_status, "
                        + "response_body, created_at FROM idempotent_request WHERE request_id = :rid",
                new MapSqlParameterSource("rid", requestId), idempotentMapper());
        return list.isEmpty() ? null : list.get(0);
    }

    public void completeIdempotent(String requestId, int httpStatus, String responseBody) {
        jdbc.update(
                "UPDATE idempotent_request SET http_status = :status, response_body = :body "
                        + "WHERE request_id = :rid",
                new MapSqlParameterSource()
                        .addValue("status", httpStatus)
                        .addValue("body", responseBody, Types.CLOB)
                        .addValue("rid", requestId));
    }

    // ---------------- 会话 ----------------

    public long insertSession(SessionRow row) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(
                "INSERT INTO charging_session (session_no, cert_fingerprint, point_id, "
                        + "home_operator, charging_operator, auth_at, start_at, end_at, status, "
                        + "total_energy_wh, last_meter_seq, batch_id, settlement_day, "
                        + "created_at, updated_at) "
                        + "VALUES (:sessionNo, :fp, :pid, :home, :charging, :authAt, :startAt, "
                        + ":endAt, :status, 0, 0, NULL, NULL, :now, :now)",
                new MapSqlParameterSource()
                        .addValue("sessionNo", row.sessionNo())
                        .addValue("fp", row.certFingerprint())
                        .addValue("pid", row.pointId())
                        .addValue("home", row.homeOperator())
                        .addValue("charging", row.chargingOperator())
                        .addValue("authAt", Timestamp.valueOf(row.authAt()))
                        .addValue("startAt", Timestamp.valueOf(row.startAt()))
                        .addValue("endAt", row.endAt() == null ? null : Timestamp.valueOf(row.endAt()),
                                Types.TIMESTAMP)
                        .addValue("status", row.status())
                        .addValue("now", Timestamp.valueOf(row.createdAt())),
                keyHolder);
        return keyHolder.getKey().longValue();
    }

    public SessionRow findSessionByNo(String sessionNo) {
        List<SessionRow> list = jdbc.query(
                "SELECT * FROM charging_session WHERE session_no = :no",
                new MapSqlParameterSource("no", sessionNo), sessionMapper());
        return list.isEmpty() ? null : list.get(0);
    }

    public SessionRow findActiveSessionForVehicle(String certFingerprint) {
        List<SessionRow> list = jdbc.query(
                "SELECT * FROM charging_session WHERE cert_fingerprint = :fp AND status = 'ACTIVE' "
                        + "ORDER BY auth_at DESC LIMIT 1",
                new MapSqlParameterSource("fp", certFingerprint), sessionMapper());
        return list.isEmpty() ? null : list.get(0);
    }

    public SessionRow findLatestSessionForVehicle(String certFingerprint) {
        List<SessionRow> list = jdbc.query(
                "SELECT * FROM charging_session WHERE cert_fingerprint = :fp "
                        + "ORDER BY auth_at DESC LIMIT 1",
                new MapSqlParameterSource("fp", certFingerprint), sessionMapper());
        return list.isEmpty() ? null : list.get(0);
    }

    public SessionRow findSessionByFingerprintAndAuthTime(String certFingerprint,
                                                          LocalDateTime authAt) {
        List<SessionRow> list = jdbc.query(
                "SELECT * FROM charging_session WHERE cert_fingerprint = :fp AND auth_at = :at",
                new MapSqlParameterSource()
                        .addValue("fp", certFingerprint)
                        .addValue("at", Timestamp.valueOf(authAt)),
                sessionMapper());
        return list.isEmpty() ? null : list.get(0);
    }

    /** 条件结束会话：仅 ACTIVE -> ENDED，并发结束只有一笔成功。 */
    public int casEndSession(String sessionNo, LocalDateTime endAt, LocalDateTime now) {
        return jdbc.update(
                "UPDATE charging_session SET status = 'ENDED', end_at = :endAt, updated_at = :now "
                        + "WHERE session_no = :no AND status = 'ACTIVE'",
                new MapSqlParameterSource()
                        .addValue("endAt", Timestamp.valueOf(endAt))
                        .addValue("now", Timestamp.valueOf(now))
                        .addValue("no", sessionNo));
    }

    /** 行锁会话，计量归位时串行化同一会话的乱序快照。 */
    public void lockSession(String sessionNo) {
        jdbc.queryForList(
                "SELECT id FROM charging_session WHERE session_no = :no FOR UPDATE",
                new MapSqlParameterSource("no", sessionNo), Long.class);
    }

    /**
     * 按已归位快照的最大抄表序号重算会话累计电量：乱序到达自动归位，重复序号不重计。
     */
    public void recomputeSessionEnergy(String sessionNo, LocalDateTime now) {
        jdbc.update(
                "UPDATE charging_session s SET "
                        + "last_meter_seq = COALESCE((SELECT MAX(meter_seq) FROM meter_snapshot m "
                        + "WHERE m.session_no = s.session_no), 0), "
                        + "total_energy_wh = COALESCE((SELECT m2.meter_wh FROM meter_snapshot m2 "
                        + "WHERE m2.session_no = s.session_no ORDER BY m2.meter_seq DESC LIMIT 1), 0), "
                        + "updated_at = :now WHERE s.session_no = :no",
                new MapSqlParameterSource()
                        .addValue("now", Timestamp.valueOf(now))
                        .addValue("no", sessionNo));
    }

    // ---------------- 计量快照 ----------------

    /**
     * @throws DuplicateKeyException 会话号＋抄表序号已存在
     */
    public void insertSnapshot(String sessionNo, int meterSeq, long meterWh,
                               LocalDateTime snapshotAt, String requestId, LocalDateTime now) {
        jdbc.update(
                "INSERT INTO meter_snapshot (session_no, meter_seq, meter_wh, snapshot_at, "
                        + "request_id, created_at) VALUES (:no, :seq, :wh, :at, :rid, :now)",
                new MapSqlParameterSource()
                        .addValue("no", sessionNo)
                        .addValue("seq", meterSeq)
                        .addValue("wh", meterWh)
                        .addValue("at", Timestamp.valueOf(snapshotAt))
                        .addValue("rid", requestId)
                        .addValue("now", Timestamp.valueOf(now)));
    }

    public SnapshotRow findSnapshot(String sessionNo, int meterSeq) {
        List<SnapshotRow> list = jdbc.query(
                "SELECT * FROM meter_snapshot WHERE session_no = :no AND meter_seq = :seq",
                new MapSqlParameterSource().addValue("no", sessionNo).addValue("seq", meterSeq),
                snapshotMapper());
        return list.isEmpty() ? null : list.get(0);
    }

    // ---------------- 清分批与明细 ----------------

    /**
     * @throws DuplicateKeyException 清分日已存在批次（并发冻结唯一成功）
     */
    public long insertBatch(String batchNo, LocalDate settlementDay, LocalDateTime now) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(
                "INSERT INTO clearing_batch (batch_no, settlement_day, status, session_count, "
                        + "total_energy_wh, total_receivable, total_payable, frozen_at) "
                        + "VALUES (:no, :day, 'FROZEN', 0, 0, 0, 0, :now)",
                new MapSqlParameterSource()
                        .addValue("no", batchNo)
                        .addValue("day", java.sql.Date.valueOf(settlementDay))
                        .addValue("now", Timestamp.valueOf(now)),
                keyHolder);
        return keyHolder.getKey().longValue();
    }

    public BatchRow findBatchByDay(LocalDate day) {
        List<BatchRow> list = jdbc.query(
                "SELECT * FROM clearing_batch WHERE settlement_day = :day",
                new MapSqlParameterSource("day", java.sql.Date.valueOf(day)), batchMapper());
        return list.isEmpty() ? null : list.get(0);
    }

    public BatchRow findBatchByNo(String batchNo) {
        List<BatchRow> list = jdbc.query(
                "SELECT * FROM clearing_batch WHERE batch_no = :no",
                new MapSqlParameterSource("no", batchNo), batchMapper());
        return list.isEmpty() ? null : list.get(0);
    }

    public BatchRow findBatchById(long batchId) {
        List<BatchRow> list = jdbc.query(
                "SELECT * FROM clearing_batch WHERE id = :id",
                new MapSqlParameterSource("id", batchId), batchMapper());
        return list.isEmpty() ? null : list.get(0);
    }

    /** 行锁批次：封存与迟到计量借此串行化，二者结果只能二选一。 */
    public BatchRow lockBatch(long batchId) {
        List<BatchRow> list = jdbc.query(
                "SELECT * FROM clearing_batch WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", batchId), batchMapper());
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 原子归批：把当日 [dayStart, dayNext) 已结束且未归批的跨运营商会话归入指定批。
     * 与结束会话并发时，条件 batch_id IS NULL 保证任一会话至多进入一个批次，
     * 不会既计入本批又留待下批。
     */
    public List<SessionRow> assignEndedSessionsToBatch(long batchId, LocalDate day) {
        LocalDateTime dayStart = day.atStartOfDay();
        LocalDateTime dayNext = day.plusDays(1).atStartOfDay();
        jdbc.update(
                "UPDATE charging_session SET batch_id = :bid, settlement_day = :day "
                        + "WHERE status = 'ENDED' AND batch_id IS NULL "
                        + "AND home_operator <> charging_operator "
                        + "AND end_at >= :start AND end_at < :end",
                new MapSqlParameterSource()
                        .addValue("bid", batchId)
                        .addValue("day", java.sql.Date.valueOf(day))
                        .addValue("start", Timestamp.valueOf(dayStart))
                        .addValue("end", Timestamp.valueOf(dayNext)));
        return jdbc.query(
                "SELECT * FROM charging_session WHERE batch_id = :bid ORDER BY session_no",
                new MapSqlParameterSource("bid", batchId), sessionMapper());
    }

    public void insertDetail(long batchId, SessionRow s, BigDecimal receivable,
                             BigDecimal payable, LocalDateTime now) {
        jdbc.update(
                "INSERT INTO clearing_detail (batch_id, session_no, home_operator, "
                        + "charging_operator, energy_wh, receivable_amount, payable_amount, created_at) "
                        + "VALUES (:bid, :no, :home, :charging, :wh, :recv, :pay, :now)",
                new MapSqlParameterSource()
                        .addValue("bid", batchId)
                        .addValue("no", s.sessionNo())
                        .addValue("home", s.homeOperator())
                        .addValue("charging", s.chargingOperator())
                        .addValue("wh", s.totalEnergyWh())
                        .addValue("recv", receivable)
                        .addValue("pay", payable)
                        .addValue("now", Timestamp.valueOf(now)));
    }

    public List<DetailRow> findDetailsByBatch(long batchId) {
        return jdbc.query(
                "SELECT * FROM clearing_detail WHERE batch_id = :bid ORDER BY session_no",
                new MapSqlParameterSource("bid", batchId), detailMapper());
    }

    public List<SessionRow> findSessionsByBatch(long batchId) {
        return jdbc.query(
                "SELECT * FROM charging_session WHERE batch_id = :bid ORDER BY session_no",
                new MapSqlParameterSource("bid", batchId), sessionMapper());
    }

    /** 封存时按会话最新累计电量重算该明细金额。 */
    public void updateDetailAmounts(long batchId, String sessionNo, long energyWh,
                                    BigDecimal receivable, BigDecimal payable) {
        jdbc.update(
                "UPDATE clearing_detail SET energy_wh = :wh, receivable_amount = :recv, "
                        + "payable_amount = :pay WHERE batch_id = :bid AND session_no = :no",
                new MapSqlParameterSource()
                        .addValue("wh", energyWh)
                        .addValue("recv", receivable)
                        .addValue("pay", payable)
                        .addValue("bid", batchId)
                        .addValue("no", sessionNo));
    }

    public void updateBatchTotals(long batchId, int sessionCount, long totalEnergyWh,
                                  BigDecimal receivable, BigDecimal payable) {        jdbc.update(
                "UPDATE clearing_batch SET session_count = :cnt, total_energy_wh = :wh, "
                        + "total_receivable = :recv, total_payable = :pay WHERE id = :bid",
                new MapSqlParameterSource()
                        .addValue("cnt", sessionCount)
                        .addValue("wh", totalEnergyWh)
                        .addValue("recv", receivable)
                        .addValue("pay", payable)
                        .addValue("bid", batchId));
    }

    /** 条件封存：仅 FROZEN -> SEALED，并发封存只有一笔执行重算后的终态写入。 */
    public int casSealBatch(long batchId, LocalDateTime now) {
        return jdbc.update(
                "UPDATE clearing_batch SET status = 'SEALED', sealed_at = :now WHERE id = :bid "
                        + "AND status = 'FROZEN'",
                new MapSqlParameterSource().addValue("now", Timestamp.valueOf(now))
                        .addValue("bid", batchId));
    }

    // ---------------- 差异挂账 ----------------

    /**
     * @throws DuplicateKeyException 同一会话同一抄表序号已挂账
     */
    public long insertDiscrepancy(DiscrepancyRow d, LocalDateTime now) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(
                "INSERT INTO discrepancy (discrepancy_no, session_no, meter_seq, meter_wh, "
                        + "snapshot_at, source_request_id, batch_id, status, created_at) "
                        + "VALUES (:no, :sess, :seq, :wh, :at, :rid, :bid, 'OPEN', :now)",
                new MapSqlParameterSource()
                        .addValue("no", d.discrepancyNo())
                        .addValue("sess", d.sessionNo())
                        .addValue("seq", d.meterSeq())
                        .addValue("wh", d.meterWh())
                        .addValue("at", Timestamp.valueOf(d.snapshotAt()))
                        .addValue("rid", d.sourceRequestId())
                        .addValue("bid", d.batchId())
                        .addValue("now", Timestamp.valueOf(now)),
                keyHolder);
        return keyHolder.getKey().longValue();
    }

    public DiscrepancyRow findDiscrepancyByNo(String no) {
        List<DiscrepancyRow> list = jdbc.query(
                "SELECT * FROM discrepancy WHERE discrepancy_no = :no",
                new MapSqlParameterSource("no", no), discrepancyMapper());
        return list.isEmpty() ? null : list.get(0);
    }

    /** 行锁挂账：并发核销串行化，配合条件更新保证唯一核销。 */
    public DiscrepancyRow lockDiscrepancyByNo(String no) {
        List<DiscrepancyRow> list = jdbc.query(
                "SELECT * FROM discrepancy WHERE discrepancy_no = :no FOR UPDATE",
                new MapSqlParameterSource("no", no), discrepancyMapper());
        return list.isEmpty() ? null : list.get(0);
    }

    public DiscrepancyRow findDiscrepancyBySessionSeq(String sessionNo, int meterSeq) {
        List<DiscrepancyRow> list = jdbc.query(
                "SELECT * FROM discrepancy WHERE session_no = :no AND meter_seq = :seq",
                new MapSqlParameterSource().addValue("no", sessionNo).addValue("seq", meterSeq),
                discrepancyMapper());
        return list.isEmpty() ? null : list.get(0);
    }

    /** 条件核销：仅 OPEN -> WRITTEN_OFF，同一挂账只允许核销一次。 */
    public int casWriteOff(long id, LocalDateTime now, String operator, String remark,
                           String requestId) {
        return jdbc.update(
                "UPDATE discrepancy SET status = 'WRITTEN_OFF', write_off_at = :now, "
                        + "write_off_by = :by, write_off_remark = :remark, "
                        + "write_off_request_id = :rid WHERE id = :id AND status = 'OPEN'",
                new MapSqlParameterSource()
                        .addValue("now", Timestamp.valueOf(now))
                        .addValue("by", operator)
                        .addValue("remark", remark)
                        .addValue("rid", requestId)
                        .addValue("id", id));
    }

    // ---------------- RowMapper ----------------

    private RowMapper<SessionRow> sessionMapper() {
        return (rs, n) -> new SessionRow(
                rs.getLong("id"),
                rs.getString("session_no"),
                rs.getString("cert_fingerprint"),
                rs.getString("point_id"),
                rs.getString("home_operator"),
                rs.getString("charging_operator"),
                rs.getTimestamp("auth_at").toLocalDateTime(),
                rs.getTimestamp("start_at").toLocalDateTime(),
                rs.getTimestamp("end_at") == null ? null : rs.getTimestamp("end_at").toLocalDateTime(),
                rs.getString("status"),
                rs.getLong("total_energy_wh"),
                rs.getInt("last_meter_seq"),
                rs.getObject("batch_id", Long.class),
                rs.getDate("settlement_day") == null ? null : rs.getDate("settlement_day").toLocalDate(),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private RowMapper<SnapshotRow> snapshotMapper() {
        return (rs, n) -> new SnapshotRow(
                rs.getLong("id"),
                rs.getString("session_no"),
                rs.getInt("meter_seq"),
                rs.getLong("meter_wh"),
                rs.getTimestamp("snapshot_at").toLocalDateTime(),
                rs.getString("request_id"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private RowMapper<BatchRow> batchMapper() {
        return (rs, n) -> new BatchRow(
                rs.getLong("id"),
                rs.getString("batch_no"),
                rs.getDate("settlement_day").toLocalDate(),
                rs.getString("status"),
                rs.getInt("session_count"),
                rs.getLong("total_energy_wh"),
                rs.getBigDecimal("total_receivable"),
                rs.getBigDecimal("total_payable"),
                rs.getTimestamp("frozen_at").toLocalDateTime(),
                rs.getTimestamp("sealed_at") == null ? null
                        : rs.getTimestamp("sealed_at").toLocalDateTime());
    }

    private RowMapper<DetailRow> detailMapper() {
        return (rs, n) -> new DetailRow(
                rs.getLong("id"),
                rs.getLong("batch_id"),
                rs.getString("session_no"),
                rs.getString("home_operator"),
                rs.getString("charging_operator"),
                rs.getLong("energy_wh"),
                rs.getBigDecimal("receivable_amount"),
                rs.getBigDecimal("payable_amount"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private RowMapper<DiscrepancyRow> discrepancyMapper() {
        return (rs, n) -> new DiscrepancyRow(
                rs.getLong("id"),
                rs.getString("discrepancy_no"),
                rs.getString("session_no"),
                rs.getInt("meter_seq"),
                rs.getLong("meter_wh"),
                rs.getTimestamp("snapshot_at").toLocalDateTime(),
                rs.getString("source_request_id"),
                rs.getLong("batch_id"),
                rs.getString("status"),
                rs.getTimestamp("write_off_at") == null ? null
                        : rs.getTimestamp("write_off_at").toLocalDateTime(),
                rs.getString("write_off_by"),
                rs.getString("write_off_remark"),
                rs.getString("write_off_request_id"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private RowMapper<IdempotentRow> idempotentMapper() {
        return (rs, n) -> new IdempotentRow(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("request_kind"),
                rs.getString("param_fingerprint"),
                rs.getInt("http_status"),
                rs.getString("response_body"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
