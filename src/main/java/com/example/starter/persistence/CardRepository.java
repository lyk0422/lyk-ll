package com.example.starter.persistence;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.CardStatus;

/**
 * 工序卡、工位执行守卫与紧固点工艺的 JDBC 访问。
 */
@Repository
public class CardRepository {

    private final JdbcTemplate jdbcTemplate;

    public CardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<CardRow> CARD_MAPPER = (rs, n) -> new CardRow(
            rs.getLong("id"),
            rs.getString("card_no"),
            rs.getString("station"),
            rs.getString("product_sn"),
            rs.getString("witness_point"),
            CardStatus.valueOf(rs.getString("status")),
            rs.getString("operator"),
            rs.getTimestamp("dispatched_at").toInstant(),
            ts(rs.getTimestamp("quarantined_at")),
            ts(rs.getTimestamp("archived_at")));

    private static Instant ts(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * 占用工位守卫：全库唯一主键保证同一工位同时刻只有一张执行中工序卡。
     *
     * @throws DuplicateKeyException 工位已有执行中工序卡
     */
    public void insertGuard(String station, String cardNo, Instant at) {
        jdbcTemplate.update(
                "INSERT INTO station_guard (station, card_no, created_at) VALUES (?, ?, ?)",
                station, cardNo, Timestamp.from(at));
    }

    /**
     * 插入工序卡。
     *
     * @throws DuplicateKeyException 工序卡号已存在
     */
    public long insertCard(String cardNo, String station, String productSn, String witnessPoint,
                           String operator, Instant dispatchedAt) {
        jdbcTemplate.update(
                "INSERT INTO operation_card (card_no, station, product_sn, witness_point, status, operator, "
                        + "dispatched_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                cardNo, station, productSn, witnessPoint, CardStatus.DISPATCHED.name(),
                operator, Timestamp.from(dispatchedAt), Timestamp.from(dispatchedAt));
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM operation_card WHERE card_no = ?", Long.class, cardNo);
        return id == null ? 0L : id;
    }

    /** 放行归档后释放工位守卫。 */
    public void releaseGuard(String station) {
        jdbcTemplate.update("DELETE FROM station_guard WHERE station = ?", station);
    }

    public CardRow findByCardNo(String cardNo) {
        List<CardRow> rows = jdbcTemplate.query(
                "SELECT id, card_no, station, product_sn, witness_point, status, operator, dispatched_at, "
                        + "quarantined_at, archived_at FROM operation_card WHERE card_no = ?",
                CARD_MAPPER, cardNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void updateStatus(long cardId, CardStatus status) {
        int updated = jdbcTemplate.update(
                "UPDATE operation_card SET status = ? WHERE id = ?", status.name(), cardId);
        if (updated != 1) {
            throw new IllegalStateException("工序卡状态更新失败: " + cardId);
        }
    }

    /** 条件更新状态（乐观守卫），返回是否更新成功。 */
    public boolean updateStatusIfCurrent(long cardId, CardStatus expected, CardStatus next) {
        return jdbcTemplate.update(
                "UPDATE operation_card SET status = ? WHERE id = ? AND status = ?",
                next.name(), cardId, expected.name()) == 1;
    }

    public void markQuarantined(long cardId, Instant at) {
        jdbcTemplate.update(
                "UPDATE operation_card SET status = ?, quarantined_at = ? WHERE id = ?",
                CardStatus.QUARANTINED.name(), Timestamp.from(at), cardId);
    }

    public void markArchived(long cardId, Instant at) {
        jdbcTemplate.update(
                "UPDATE operation_card SET status = ?, archived_at = ? WHERE id = ?",
                CardStatus.ARCHIVED.name(), Timestamp.from(at), cardId);
    }

    public void insertFastener(long cardId, String pointCode, BigDecimal nominalTorque, BigDecimal tolerancePct) {
        jdbcTemplate.update(
                "INSERT INTO fastener (card_id, point_code, nominal_torque, tolerance_pct) VALUES (?, ?, ?, ?)",
                cardId, pointCode, nominalTorque, tolerancePct);
    }

    public FastenerRow findFastener(long cardId, String pointCode) {
        List<FastenerRow> rows = jdbcTemplate.query(
                "SELECT id, card_id, point_code, nominal_torque, tolerance_pct FROM fastener "
                        + "WHERE card_id = ? AND point_code = ?",
                (rs, n) -> new FastenerRow(
                        rs.getLong("id"), rs.getLong("card_id"), rs.getString("point_code"),
                        rs.getBigDecimal("nominal_torque"), rs.getBigDecimal("tolerance_pct")),
                cardId, pointCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<FastenerRow> listFasteners(long cardId) {
        return jdbcTemplate.query(
                "SELECT id, card_id, point_code, nominal_torque, tolerance_pct FROM fastener "
                        + "WHERE card_id = ? ORDER BY id",
                (rs, n) -> new FastenerRow(
                        rs.getLong("id"), rs.getLong("card_id"), rs.getString("point_code"),
                        rs.getBigDecimal("nominal_torque"), rs.getBigDecimal("tolerance_pct")),
                cardId);
    }
}
