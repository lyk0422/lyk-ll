package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.ReservationStatus;

/**
 * 检测申请（占用记录）仓储。
 */
@Repository
public class ReservationRepository {

    private static final String COLUMNS =
            "id, request_id, aliquot_id, project_code, volume_nl, status, custodian, version, "
                    + "result_payload, result_at, created_at, updated_at";

    private static final RowMapper<ReservationRow> MAPPER = ReservationRepository::mapRow;

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按幂等键查找申请。
     */
    public Optional<ReservationRow> findByRequestId(String requestId) {
        List<ReservationRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM reservation WHERE request_id = ?",
                MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 查询某分装管下的全部申请。
     */
    public List<ReservationRow> findByAliquotId(String aliquotId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM reservation WHERE aliquot_id = ? ORDER BY id",
                MAPPER, aliquotId);
    }

    /**
     * 汇总分装管当前 RESERVED 状态的占用体积（纳升）。
     * 调用方须先持有 aliquot 行锁，保证并发申请不超额。
     */
    public long sumReservedNl(String aliquotId) {
        Long value = jdbc.queryForObject(
                "SELECT COALESCE(SUM(volume_nl), 0) FROM reservation "
                        + "WHERE aliquot_id = ? AND status = ?",
                Long.class, aliquotId, ReservationStatus.RESERVED.name());
        return value == null ? 0L : value;
    }

    /**
     * 插入 RESERVED 申请，version 为 0。
     */
    public void insertReserved(String requestId, String aliquotId, String projectCode,
                               long volumeNl, String custodian, Instant now) {
        Timestamp ts = Timestamp.from(now);
        jdbc.update(
                "INSERT INTO reservation (request_id, aliquot_id, project_code, volume_nl, status, custodian, "
                        + "version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)",
                requestId, aliquotId, projectCode, volumeNl,
                ReservationStatus.RESERVED.name(), custodian, ts, ts);
    }

    /**
     * 乐观状态迁移，返回受影响行数。
     */
    public int compareAndUpdateStatus(String requestId, ReservationStatus expectedStatus,
                                      ReservationStatus newStatus, int expectedVersion,
                                      Instant now) {
        return jdbc.update(
                "UPDATE reservation SET status = ?, version = version + 1, updated_at = ? "
                        + "WHERE request_id = ? AND status = ? AND version = ?",
                newStatus.name(), Timestamp.from(now), requestId, expectedStatus.name(), expectedVersion);
    }

    /**
     * 乐观写入检测结果（仅 CONSUMED 且结果为空时），返回受影响行数。
     */
    public int writeResult(String requestId, int expectedVersion, String payload, Instant now) {
        Timestamp ts = Timestamp.from(now);
        return jdbc.update(
                "UPDATE reservation SET result_payload = ?, result_at = ?, version = version + 1, updated_at = ? "
                        + "WHERE request_id = ? AND version = ? AND status = ? AND result_payload IS NULL",
                payload, ts, ts, requestId, expectedVersion, ReservationStatus.CONSUMED.name());
    }

    /**
     * 在事务内锁定并返回某分装管下全部 RESERVED 申请（SELECT ... FOR UPDATE），
     * 供污染隔离逐条取消并记录保管事件。
     */
    public List<ReservationRow> lockReservedByAliquot(String aliquotId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM reservation WHERE aliquot_id = ? AND status = ? FOR UPDATE",
                MAPPER, aliquotId, ReservationStatus.RESERVED.name());
    }

    /**
     * 污染联动：将指定分装管下所有 RESERVED 申请批量置为 CANCELLED，返回更新行数。
     * 调用方须在同一事务内先锁定 aliquot 行。
     */
    public int cancelAllReservedOfAliquot(String aliquotId, Instant now) {
        return jdbc.update(
                "UPDATE reservation SET status = ?, version = version + 1, updated_at = ? "
                        + "WHERE aliquot_id = ? AND status = ?",
                ReservationStatus.CANCELLED.name(), Timestamp.from(now),
                aliquotId, ReservationStatus.RESERVED.name());
    }

    /**
     * 乐观变更保管人，返回受影响行数。
     */
    public int updateCustodian(String requestId, String newCustodian, int expectedVersion, Instant now) {
        return jdbc.update(
                "UPDATE reservation SET custodian = ?, version = version + 1, updated_at = ? "
                        + "WHERE request_id = ? AND version = ?",
                newCustodian, Timestamp.from(now), requestId, expectedVersion);
    }

    private static ReservationRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp resultAt = rs.getTimestamp("result_at");
        return new ReservationRow(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("aliquot_id"),
                rs.getString("project_code"),
                rs.getLong("volume_nl"),
                ReservationStatus.valueOf(rs.getString("status")),
                rs.getString("custodian"),
                rs.getInt("version"),
                rs.getString("result_payload"),
                resultAt == null ? null : resultAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
