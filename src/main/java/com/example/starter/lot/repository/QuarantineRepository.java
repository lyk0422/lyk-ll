package com.example.starter.lot.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 隔离事件流水与“后代当前生效隔离来源集合”的数据访问。
 *
 * <p>事件流水仅追加；生效集合表通过 active 标记与 released_at 回填表达解除，
 * 历史行不删除、不覆盖。所有写操作均在持有全局写顺序锁的事务内进行。</p>
 */
@Repository
public class QuarantineRepository {

    private final JdbcTemplate jdbc;

    public QuarantineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加隔离事件流水（QUARANTINE / RELEASE）。 */
    public void insertEvent(String sourceLotId, String action, String reason) {
        jdbc.update("""
                        INSERT INTO quarantine_event (source_lot_id, action, reason, created_at)
                        VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                        """,
                sourceLotId, action, reason);
    }

    /** 某来源对某批次是否已有生效中的传播记录（重复 QUARANTINE 不新增重复来源）。 */
    public boolean activeSourceExists(String lotId, String sourceLotId) {
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM quarantine_source
                         WHERE lot_id = ? AND source_lot_id = ? AND active = 1
                        """,
                Integer.class, lotId, sourceLotId);
        return count != null && count > 0;
    }

    /** 新增一条生效隔离来源（调用方保证不存在重复 active 记录）。 */
    public void activate(String lotId, String sourceLotId) {
        jdbc.update("""
                        INSERT INTO quarantine_source
                            (lot_id, source_lot_id, active, quarantined_at)
                        VALUES (?, ?, 1, CURRENT_TIMESTAMP)
                        """,
                lotId, sourceLotId);
    }

    /**
     * 解除某来源在给定批次集合上的全部生效记录（回填解除时间）。
     *
     * @return 实际被解除的批次 ID 集合（原本有该生效来源的批次）。
     */
    public List<String> deactivate(String sourceLotId, Collection<String> lotIds) {
        if (lotIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", lotIds.stream().map(x -> "?").toList());
        Object[] args = new Object[lotIds.size() + 1];
        args[0] = sourceLotId;
        int i = 1;
        for (String id : lotIds) {
            args[i++] = id;
        }
        List<String> affected = jdbc.queryForList("""
                        SELECT lot_id FROM quarantine_source
                         WHERE source_lot_id = ? AND active = 1
                           AND lot_id IN (%s) ORDER BY lot_id
                        """.formatted(placeholders),
                String.class, args);
        if (!affected.isEmpty()) {
            String affectedPlaceholders = String.join(",", affected.stream().map(x -> "?").toList());
            Object[] updateArgs = new Object[affected.size() + 1];
            updateArgs[0] = sourceLotId;
            for (int j = 0; j < affected.size(); j++) {
                updateArgs[j + 1] = affected.get(j);
            }
            jdbc.update("""
                            UPDATE quarantine_source
                               SET active = 0, released_at = CURRENT_TIMESTAMP
                             WHERE source_lot_id = ? AND active = 1
                               AND lot_id IN (%s)
                            """.formatted(affectedPlaceholders),
                    updateArgs);
        }
        return affected;
    }

    /** 查询若干批次当前生效的隔离来源（按来源 ID 排序）。 */
    public Map<String, List<String>> activeSources(Collection<String> lotIds) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String lotId : lotIds) {
            // 必须为可变列表：RowCallback 中会向已有键追加来源。
            result.put(lotId, new java.util.ArrayList<>());
        }
        if (lotIds.isEmpty()) {
            return result;
        }
        String placeholders = String.join(",", lotIds.stream().map(x -> "?").toList());
        jdbc.query("""
                        SELECT lot_id, source_lot_id FROM quarantine_source
                         WHERE active = 1 AND lot_id IN (%s) ORDER BY lot_id, source_lot_id
                        """.formatted(placeholders),
                        rs -> {
                            result.computeIfAbsent(rs.getString("lot_id"), k -> new java.util.ArrayList<>())
                                    .add(rs.getString("source_lot_id"));
                        },
                lotIds.toArray());
        return result;
    }

    /** 当前仍受某来源影响的全部批次。 */
    public List<String> lotsImpactedBy(String sourceLotId) {
        return jdbc.queryForList("""
                        SELECT lot_id FROM quarantine_source
                         WHERE source_lot_id = ? AND active = 1
                         ORDER BY lot_id
                        """,
                String.class, sourceLotId);
    }
}
