package com.example.starter.tank;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 跨罐调拨单仓储：运输批号唯一约束兜底并发重复入账；
 * 状态流转（RESERVED→EXECUTED/CANCELLED）在调拨单行锁内完成，版本条件更新兜底。
 */
@Repository
public class TankTransferRepository {

    private static final String COLUMNS = "id, request_id, source_tank_id, target_tank_id, "
            + "variety_no, quantity_kg, transport_batch_no, first_operator, second_operator, "
            + "status, version, actual_qty_kg, credential_no, applied_at, executed_at, cancelled_at";

    private final JdbcTemplate jdbc;

    public TankTransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入调拨单（版本从 1 开始）；运输批号重复时抛出 DuplicateKeyException，由服务层裁决。
     */
    public Long insert(String requestId, Long sourceTankId, Long targetTankId, String varietyNo,
                       BigDecimal quantityKg, String transportBatchNo, String firstOperator,
                       String secondOperator, LocalDateTime appliedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO tank_transfer
                                (request_id, source_tank_id, target_tank_id, variety_no,
                                 quantity_kg, transport_batch_no, first_operator, second_operator,
                                 status, version, applied_at, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RESERVED', 1, ?, CURRENT_TIMESTAMP)
                            """,
                    new String[]{"id"});
            ps.setString(1, requestId);
            ps.setLong(2, sourceTankId);
            ps.setLong(3, targetTankId);
            ps.setString(4, varietyNo);
            ps.setBigDecimal(5, quantityKg);
            ps.setString(6, transportBatchNo);
            ps.setString(7, firstOperator);
            ps.setString(8, secondOperator);
            ps.setTimestamp(9, Timestamp.valueOf(appliedAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    public Optional<TankTransfer> findById(Long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tank_transfer WHERE id = ?",
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(), id);
    }

    /**
     * 事务内行级锁定读取，确认/撤销并发时唯一计量（只能全撤销或全调拨）。
     */
    public Optional<TankTransfer> findByIdForUpdate(Long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tank_transfer WHERE id = ? FOR UPDATE",
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(), id);
    }

    /**
     * 标记执行：版本条件更新（期望版本必中，否则为并发缺陷），返回受影响行数。
     */
    public int markExecuted(Long id, BigDecimal actualQtyKg, String credentialNo,
                            LocalDateTime executedAt, long newVersion, long expectedVersion) {
        return jdbc.update("""
                        UPDATE tank_transfer
                        SET status = 'EXECUTED', actual_qty_kg = ?, credential_no = ?,
                            executed_at = ?, version = ?
                        WHERE id = ? AND version = ?
                        """,
                actualQtyKg, credentialNo, Timestamp.valueOf(executedAt), newVersion,
                id, expectedVersion);
    }

    /**
     * 标记撤销：版本条件更新，返回受影响行数。
     */
    public int markCancelled(Long id, LocalDateTime cancelledAt, long newVersion,
                             long expectedVersion) {
        return jdbc.update("""
                        UPDATE tank_transfer
                        SET status = 'CANCELLED', cancelled_at = ?, version = ?
                        WHERE id = ? AND version = ?
                        """,
                Timestamp.valueOf(cancelledAt), newVersion, id, expectedVersion);
    }

    /**
     * 指定罐参与的全部调拨单（作为源或目标）。
     */
    public List<TankTransfer> listByTank(Long tankId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tank_transfer "
                        + "WHERE source_tank_id = ? OR target_tank_id = ? ORDER BY id",
                (rs, rowNum) -> map(rs), tankId, tankId);
    }

    private TankTransfer map(ResultSet rs) throws SQLException {
        Timestamp executedAt = rs.getTimestamp("executed_at");
        Timestamp cancelledAt = rs.getTimestamp("cancelled_at");
        return new TankTransfer(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getLong("source_tank_id"),
                rs.getLong("target_tank_id"),
                rs.getString("variety_no"),
                rs.getBigDecimal("quantity_kg"),
                rs.getString("transport_batch_no"),
                rs.getString("first_operator"),
                rs.getString("second_operator"),
                rs.getString("status"),
                rs.getLong("version"),
                rs.getBigDecimal("actual_qty_kg"),
                rs.getString("credential_no"),
                rs.getTimestamp("applied_at").toLocalDateTime(),
                executedAt == null ? null : executedAt.toLocalDateTime(),
                cancelledAt == null ? null : cancelledAt.toLocalDateTime());
    }
}
