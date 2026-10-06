package com.example.starter.tree;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 树木档案数据访问。
 */
@Repository
public class TreeRepository {

    private final JdbcClient jdbc;

    public TreeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 树木行。riskScore/riskLevel 为 NULL 表示未评估；riskLevel 一级为最高风险。
     */
    public record TreeRow(long id, long segmentId, String treeCode, String species, int age,
            int healthScore, double leanDegree, boolean cavity,
            Integer riskScore, Integer riskLevel, LocalDateTime evaluatedAt) {
    }

    /** 风险分布统计行：level 为 NULL 表示未评估。 */
    public record RiskCountRow(long segmentId, Integer riskLevel, long count) {
    }

    public long insert(long segmentId, String treeCode, String species, int age, int healthScore,
            double leanDegree, boolean cavity, int riskScore, int riskLevel, LocalDateTime evaluatedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO tree(segment_id, tree_code, species, tree_age, health_score, lean_degree, cavity,"
                        + " risk_score, risk_level, evaluated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(segmentId, treeCode, species, age, healthScore, leanDegree, cavity,
                        riskScore, riskLevel, evaluatedAt)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<TreeRow> findById(long id) {
        return jdbc.sql("SELECT * FROM tree WHERE id = ?")
                .param(id)
                .query(this::mapRow)
                .optional();
    }

    public Optional<TreeRow> findByCode(String treeCode) {
        return jdbc.sql("SELECT * FROM tree WHERE tree_code = ?")
                .param(treeCode)
                .query(this::mapRow)
                .optional();
    }

    public boolean existsByCode(String treeCode) {
        return jdbc.sql("SELECT COUNT(*) FROM tree WHERE tree_code = ?")
                .param(treeCode)
                .query(Long.class)
                .single() > 0;
    }

    /** 复评：更新评估指标与评级结果。 */
    public void updateEvaluation(long id, int healthScore, double leanDegree, int age, boolean cavity,
            int riskScore, int riskLevel, LocalDateTime evaluatedAt) {
        jdbc.sql("UPDATE tree SET health_score = ?, lean_degree = ?, tree_age = ?, cavity = ?,"
                        + " risk_score = ?, risk_level = ?, evaluated_at = ? WHERE id = ?")
                .params(healthScore, leanDegree, age, cavity, riskScore, riskLevel, evaluatedAt, id)
                .update();
    }

    /** 各路段风险分布（含未评估）。 */
    public List<RiskCountRow> riskDistribution() {
        return jdbc.sql("SELECT segment_id, risk_level, COUNT(*) AS cnt FROM tree GROUP BY segment_id, risk_level")
                .query((rs, i) -> new RiskCountRow(rs.getLong("segment_id"),
                        (Integer) rs.getObject("risk_level"), rs.getLong("cnt")))
                .list();
    }

    /** 高风险路段：存在一级风险树木的路段。 */
    public List<Long> segmentIdsWithLevelOneTrees() {
        return jdbc.sql("SELECT DISTINCT segment_id FROM tree WHERE risk_level = 1 ORDER BY segment_id")
                .query(Long.class)
                .list();
    }

    private TreeRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TreeRow(rs.getLong("id"), rs.getLong("segment_id"), rs.getString("tree_code"),
                rs.getString("species"), rs.getInt("tree_age"), rs.getInt("health_score"),
                rs.getDouble("lean_degree"), rs.getBoolean("cavity"),
                (Integer) rs.getObject("risk_score"), (Integer) rs.getObject("risk_level"),
                rs.getObject("evaluated_at", LocalDateTime.class));
    }
}
