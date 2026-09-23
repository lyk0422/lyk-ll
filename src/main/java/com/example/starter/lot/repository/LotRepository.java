package com.example.starter.lot.repository;

import com.example.starter.lot.domain.GenealogyEdge;
import com.example.starter.lot.domain.Lot;
import com.example.starter.lot.domain.LotStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批次主表与谱系边的数据访问。
 *
 * <p>所有数量列均为 DECIMAL(19,3)，读写保持 {@link java.math.BigDecimal}，
 * 谱系遍历使用 H2 支持的递归 CTE。</p>
 */
@Repository
public class LotRepository {

    private static final String COLUMNS =
            "lot_id, unit, initial_qty, consumed_qty, status, version, created_at, remark";

    private final JdbcTemplate jdbc;

    public LotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Lot> LOT_MAPPER = (rs, rowNum) -> mapLot(rs);

    static Lot mapLot(ResultSet rs) throws SQLException {
        return new Lot(
                rs.getString("lot_id"),
                rs.getString("unit"),
                rs.getBigDecimal("initial_qty"),
                rs.getBigDecimal("consumed_qty"),
                LotStatus.valueOf(rs.getString("status")),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getString("remark")
        );
    }

    /** 新建批次；lotId 冲突时由唯一约束抛出 DuplicateKeyException。 */
    public void insert(Lot lot) {
        jdbc.update("""
                        INSERT INTO lot
                            (lot_id, unit, initial_qty, consumed_qty, status, version, created_at, remark)
                        VALUES (?, ?, ?, 0, 'AVAILABLE', 0, CURRENT_TIMESTAMP, ?)
                        """,
                lot.lotId(), lot.unit(), lot.initialQty(), lot.remark());
    }

    public Lot findById(String lotId) {
        List<Lot> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM lot WHERE lot_id = ?", LOT_MAPPER, lotId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 按入参顺序返回存在的批次；不存在的 ID 被跳过（由调用方校验）。 */
    public Map<String, Lot> findByIds(Collection<String> lotIds) {
        Map<String, Lot> result = new LinkedHashMap<>();
        if (lotIds.isEmpty()) {
            return result;
        }
        String placeholders = String.join(",", lotIds.stream().map(x -> "?").toList());
        List<Lot> found = jdbc.query(
                "SELECT " + COLUMNS + " FROM lot WHERE lot_id IN (" + placeholders + ")",
                LOT_MAPPER, lotIds.toArray());
        Map<String, Lot> byId = new LinkedHashMap<>();
        for (Lot lot : found) {
            byId.put(lot.lotId(), lot);
        }
        for (String id : lotIds) {
            Lot lot = byId.get(id);
            if (lot != null) {
                result.put(id, lot);
            }
        }
        return result;
    }

    /**
     * 事务内对父批次行加写锁（SELECT ... FOR UPDATE），
     * 配合乐观条件更新共同保证并发转换不超用。
     */
    public Lot lockById(String lotId) {
        List<Lot> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM lot WHERE lot_id = ? FOR UPDATE", LOT_MAPPER, lotId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 持有全局写顺序锁至事务提交/回滚；转换与隔离两类写操作均须先获取。 */
    public void acquireGlobalLock() {
        jdbc.queryForObject("SELECT id FROM service_lock WHERE id = 1 FOR UPDATE", Integer.class);
    }

    /**
     * 乐观条件消费：仅当版本等于期望值且消费后不超初始量时更新成功。
     *
     * @return 受影响行数；0 表示版本变化或余额不足，调用方必须回滚。
     */
    public int consume(String lotId, java.math.BigDecimal qty, int expectedVersion) {
        return jdbc.update("""
                        UPDATE lot
                           SET consumed_qty = consumed_qty + ?,
                               version = version + 1
                         WHERE lot_id = ?
                           AND version = ?
                           AND consumed_qty + ? <= initial_qty
                        """,
                qty, lotId, expectedVersion, qty);
    }

    /** 仅更新隔离状态；隔离来源集合由 {@link QuarantineRepository} 维护。 */
    public void updateStatus(String lotId, LotStatus status) {
        jdbc.update("UPDATE lot SET status = ? WHERE lot_id = ?", status.name(), lotId);
    }

    /** 新建子批次，初始状态由转换事务按已提交的隔离来源计算后给定。 */
    public void insertChild(String lotId, String unit, java.math.BigDecimal initialQty,
                            LotStatus status, String remark) {
        jdbc.update("""
                        INSERT INTO lot
                            (lot_id, unit, initial_qty, consumed_qty, status, version, created_at, remark)
                        VALUES (?, ?, ?, 0, ?, 0, CURRENT_TIMESTAMP, ?)
                        """,
                lotId, unit, initialQty, status.name(), remark);
    }

    /** 插入谱系边；父子对重复时由主键约束拒绝（整笔回滚）。 */
    public void insertEdge(GenealogyEdge edge) {
        jdbc.update("""
                        INSERT INTO genealogy_edge (parent_lot_id, child_lot_id, conversion_id, qty)
                        VALUES (?, ?, ?, ?)
                        """,
                edge.parentLotId(), edge.childLotId(), edge.conversionId(), edge.qty());
    }

    public List<GenealogyEdge> parentsOf(String childLotId) {
        return jdbc.query("""
                        SELECT parent_lot_id, child_lot_id, conversion_id, qty
                          FROM genealogy_edge WHERE child_lot_id = ?
                         ORDER BY parent_lot_id
                        """,
                LotRepository::mapEdge, childLotId);
    }

    public List<GenealogyEdge> childrenOf(String parentLotId) {
        return jdbc.query("""
                        SELECT parent_lot_id, child_lot_id, conversion_id, qty
                          FROM genealogy_edge WHERE parent_lot_id = ?
                         ORDER BY child_lot_id
                        """,
                LotRepository::mapEdge, parentLotId);
    }

    /**
     * 从给定起点向下收集全部后代（不含起点），使用递归 CTE，天然检测并避开环。
     */
    public Set<String> descendantIds(Collection<String> roots) {
        if (roots.isEmpty()) {
            return new LinkedHashSet<>();
        }
        String placeholders = String.join(",", roots.stream().map(x -> "?").toList());
        List<String> ids = jdbc.queryForList("""
                        WITH RECURSIVE down(lot_id) AS (
                            SELECT child_lot_id FROM genealogy_edge
                             WHERE parent_lot_id IN (%s)
                            UNION
                            SELECT e.child_lot_id FROM genealogy_edge e
                             JOIN down d ON e.parent_lot_id = d.lot_id
                        )
                        SELECT DISTINCT lot_id FROM down ORDER BY lot_id
                        """.formatted(placeholders),
                String.class, roots.toArray());
        return new LinkedHashSet<>(ids);
    }

    /**
     * 从给定起点向上收集全部祖先（不含起点）。
     */
    public Set<String> ancestorIds(Collection<String> roots) {
        if (roots.isEmpty()) {
            return new LinkedHashSet<>();
        }
        String placeholders = String.join(",", roots.stream().map(x -> "?").toList());
        List<String> ids = jdbc.queryForList("""
                        WITH RECURSIVE up(lot_id) AS (
                            SELECT parent_lot_id FROM genealogy_edge
                             WHERE child_lot_id IN (%s)
                            UNION
                            SELECT e.parent_lot_id FROM genealogy_edge e
                             JOIN up u ON e.child_lot_id = u.lot_id
                        )
                        SELECT DISTINCT lot_id FROM up ORDER BY lot_id
                        """.formatted(placeholders),
                String.class, roots.toArray());
        return new LinkedHashSet<>(ids);
    }

    /**
     * 判定 ancestorId 是否为 lotId 的祖先（用于谱系环检测）：
     * 新子批次若已是任一父批次的祖先，则成环。
     */
    public boolean isAncestor(String ancestorId, Collection<String> descendantRoots) {
        return ancestorIds(descendantRoots).contains(ancestorId);
    }

    /** 全部批次（影响面/管理用途，数据量为合成规模）。 */
    public List<Lot> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM lot ORDER BY lot_id", LOT_MAPPER);
    }

    private static GenealogyEdge mapEdge(ResultSet rs, int rowNum) throws SQLException {
        return new GenealogyEdge(
                rs.getString("parent_lot_id"),
                rs.getString("child_lot_id"),
                rs.getLong("conversion_id"),
                rs.getBigDecimal("qty"));
    }
}
