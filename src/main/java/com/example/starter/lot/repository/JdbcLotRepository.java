package com.example.starter.lot.repository;

import com.example.starter.lot.api.TransformRequest;
import com.example.starter.lot.model.Lot;
import com.example.starter.lot.model.LotStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 基于 JdbcTemplate 的 H2（MODE=MySQL）仓储实现。
 *
 * <p>所有 SQL 均参数化；DECIMAL(19,3) 映射为 {@link BigDecimal}。谱系的传递闭包通过
 * lot_link 上逐层迭代查询完成（合成数据规模下足够，且不依赖 MySQL 递归 CTE 方言）。</p>
 */
@Repository
public class JdbcLotRepository implements LotRepository {

    private static final String SIDE_PARENT = "PARENT";
    private static final String SIDE_CHILD = "CHILD";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public JdbcLotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public void lockMutex() {
        // 单行表写锁，序列化跨批次写事务；未提交写锁会阻塞后来者的读取。
        // H2 默认锁等待 1s，显式放宽，连接复用后会话级设置持续有效。
        jdbc.execute("SET LOCK_TIMEOUT 30000");
        jdbc.queryForObject("SELECT id FROM tx_mutex WHERE id = 1 FOR UPDATE", Integer.class);
    }

    @Override
    public void insertLot(Lot lot) {
        jdbc.update("""
                INSERT INTO lot
                  (lot_id, unit, initial_qty, produced_qty, consumed_qty, status, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                lot.lotId(), lot.unit(), lot.initialQty(), lot.producedQty(), lot.consumedQty(),
                lot.status().name(), lot.version(), Timestamp.valueOf(lot.createdAt()));
    }

    @Override
    public Optional<Lot> findLot(String lotId) {
        List<Lot> list = jdbc.query("SELECT * FROM lot WHERE lot_id = ?", lotRowMapper(), lotId);
        return list.stream().findFirst();
    }

    @Override
    public List<Lot> findLots(Collection<String> lotIds) {
        if (lotIds.isEmpty()) {
            return List.of();
        }
        return named.query(
                "SELECT * FROM lot WHERE lot_id IN (:ids)",
                new MapSqlParameterSource("ids", lotIds),
                lotRowMapper());
    }

    @Override
    public boolean lotExists(String lotId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM lot WHERE lot_id = ?",
                Integer.class, lotId);
        return count != null && count > 0;
    }

    @Override
    public int tryConsume(String lotId, BigDecimal quantity, long expectedVersion) {
        // 条件更新：版本必须等于预期，且消费后 consumed <= produced，防止并发超用。
        return jdbc.update("""
                UPDATE lot
                   SET consumed_qty = consumed_qty + ?, version = version + 1
                 WHERE lot_id = ? AND version = ?
                   AND consumed_qty + ? <= produced_qty
                """,
                quantity, lotId, expectedVersion, quantity);
    }

    @Override
    public void insertChildLot(Lot lot) {
        // 转换产出的子批次没有“初始建档量”，initial_qty 记 0。
        jdbc.update("""
                INSERT INTO lot
                  (lot_id, unit, initial_qty, produced_qty, consumed_qty, status, version, created_at)
                VALUES (?, ?, 0, ?, 0, ?, ?, ?)
                """,
                lot.lotId(), lot.unit(), lot.producedQty(), lot.status().name(),
                lot.version(), Timestamp.valueOf(lot.createdAt()));
    }

    @Override
    public void insertLedger(String requestId, String lotId, String changeType,
                             BigDecimal quantity, BigDecimal balanceAfter, LocalDateTime at) {
        jdbc.update("""
                INSERT INTO lot_ledger
                  (request_id, lot_id, change_type, quantity, balance_after, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                requestId, lotId, changeType, quantity, balanceAfter, Timestamp.valueOf(at));
    }

    @Override
    public void insertTransformRecord(TransformRequest req, String fingerprint,
                                      String requestJson, BigDecimal totalConsumed,
                                      BigDecimal totalProduced, LocalDateTime at) {
        jdbc.update("""
                INSERT INTO transform_record
                  (request_id, fingerprint, unit, request_json, total_consumed,
                   total_produced, allowed_loss, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                req.requestId(), fingerprint, req.unit(), requestJson, totalConsumed,
                totalProduced, req.allowedLoss(), Timestamp.valueOf(at));
    }

    @Override
    public void updateTransformResponse(String requestId, String responseJson) {
        jdbc.update("UPDATE transform_record SET response_json = ? WHERE request_id = ?",
                responseJson, requestId);
    }

    @Override
    public Optional<TransformRecordRow> findTransformRecord(String requestId) {
        List<TransformRecordRow> rows = jdbc.query("""
                SELECT request_id, fingerprint, unit, request_json, response_json,
                       total_consumed, total_produced, allowed_loss
                  FROM transform_record WHERE request_id = ?
                """,
                (rs, i) -> new TransformRecordRow(
                        rs.getString("request_id"),
                        rs.getString("fingerprint"),
                        rs.getString("unit"),
                        rs.getString("request_json"),
                        rs.getString("response_json"),
                        rs.getBigDecimal("total_consumed"),
                        rs.getBigDecimal("total_produced"),
                        rs.getBigDecimal("allowed_loss")),
                requestId);
        return rows.stream().findFirst();
    }

    @Override
    public void insertTransformIo(String requestId, String side, String lotId,
                                  BigDecimal quantity, int seqNo) {
        jdbc.update("""
                INSERT INTO transform_io (request_id, side, lot_id, quantity, seq_no)
                VALUES (?, ?, ?, ?, ?)
                """,
                requestId, side, lotId, quantity, seqNo);
    }

    @Override
    public void insertLink(String requestId, String parentLotId, String childLotId) {
        jdbc.update("""
                INSERT INTO lot_link (request_id, parent_lot_id, child_lot_id)
                VALUES (?, ?, ?)
                """,
                requestId, parentLotId, childLotId);
    }

    @Override
    public List<String> findDirectChildren(String lotId) {
        return jdbc.queryForList(
                "SELECT DISTINCT child_lot_id FROM lot_link WHERE parent_lot_id = ? ORDER BY child_lot_id",
                String.class, lotId);
    }

    @Override
    public List<String> findDirectParents(String lotId) {
        return jdbc.queryForList(
                "SELECT DISTINCT parent_lot_id FROM lot_link WHERE child_lot_id = ? ORDER BY parent_lot_id",
                String.class, lotId);
    }

    @Override
    public List<String> findAllDescendants(String lotId) {
        // 逐层扩展：frontier 的直接子节点并入结果，直到没有新节点。
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> frontier = List.of(lotId);
        while (!frontier.isEmpty()) {
            MapSqlParameterSource params = new MapSqlParameterSource("ids", frontier);
            List<String> next = named.queryForList(
                    "SELECT DISTINCT child_lot_id FROM lot_link WHERE parent_lot_id IN (:ids)",
                    params, String.class);
            List<String> fresh = next.stream().filter(seen::add).toList();
            result.addAll(fresh);
            frontier = fresh;
        }
        return result;
    }

    @Override
    public List<String> findAllAncestors(String lotId) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> frontier = List.of(lotId);
        while (!frontier.isEmpty()) {
            MapSqlParameterSource params = new MapSqlParameterSource("ids", frontier);
            List<String> next = named.queryForList(
                    "SELECT DISTINCT parent_lot_id FROM lot_link WHERE child_lot_id IN (:ids)",
                    params, String.class);
            List<String> fresh = next.stream().filter(seen::add).toList();
            result.addAll(fresh);
            frontier = fresh;
        }
        return result;
    }

    @Override
    public void insertQuarantineEvent(String lotId, String action, String reason, LocalDateTime at) {
        jdbc.update("""
                INSERT INTO quarantine_event (lot_id, action, reason, created_at)
                VALUES (?, ?, ?, ?)
                """,
                lotId, action, reason, Timestamp.valueOf(at));
    }

    @Override
    public void addQuarantineSource(String lotId, String sourceLotId, LocalDateTime at) {
        // MERGE 保证重复 SET 同一来源幂等，不重复写传播表。
        jdbc.update("""
                MERGE INTO lot_quarantine (lot_id, source_lot_id, created_at)
                KEY(lot_id, source_lot_id)
                VALUES (?, ?, ?)
                """,
                lotId, sourceLotId, Timestamp.valueOf(at));
    }

    @Override
    public int removeQuarantineSource(String lotId, String sourceLotId) {
        return jdbc.update(
                "DELETE FROM lot_quarantine WHERE lot_id = ? AND source_lot_id = ?",
                lotId, sourceLotId);
    }

    @Override
    public List<String> findActiveSources(String lotId) {
        return jdbc.queryForList(
                "SELECT source_lot_id FROM lot_quarantine WHERE lot_id = ? ORDER BY source_lot_id",
                String.class, lotId);
    }

    @Override
    public List<String> findLotsWithSource(String sourceLotId) {
        return jdbc.queryForList(
                "SELECT lot_id FROM lot_quarantine WHERE source_lot_id = ? ORDER BY lot_id",
                String.class, sourceLotId);
    }

    @Override
    public int applyStatus(String lotId, LotStatus newStatus) {
        // 仅状态真正变化时更新；隔离状态变化不递增数量版本号，保证并发转换的
        // expectedVersion 只受数量台账变动影响。
        return jdbc.update(
                "UPDATE lot SET status = ? WHERE lot_id = ? AND status <> ?",
                newStatus.name(), lotId, newStatus.name());
    }

    private RowMapper<Lot> lotRowMapper() {
        return (rs, i) -> new Lot(
                rs.getString("lot_id"),
                rs.getString("unit"),
                rs.getBigDecimal("initial_qty"),
                rs.getBigDecimal("produced_qty"),
                rs.getBigDecimal("consumed_qty"),
                LotStatus.valueOf(rs.getString("status")),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
