package com.example.starter.ros.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节点已提交版本与已提交参数值的数据访问。
 *
 * <p>提交方法只允许在事务中调用：版本推进与参数写入必须与事件落库同生共死。
 */
@Repository
public class NodeStateRepository {

    private final JdbcTemplate jdbcTemplate;

    public NodeStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 确保节点存在；不存在时以版本 0 初始化，已存在时绝不回退版本。 */
    public void initIfAbsent(String nodeId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM node_state WHERE node_id = ?", Integer.class, nodeId);
        if (count != null && count == 0) {
            try {
                jdbcTemplate.update(
                        "INSERT INTO node_state (node_id, version) VALUES (?, 0)", nodeId);
            } catch (org.springframework.dao.DuplicateKeyException concurrentInsert) {
                // 并发首写由唯一约束兜底；保留既有行与版本
            }
        }
    }

    /** 读取节点当前单调版本号。 */
    public long currentVersion(String nodeId) {
        initIfAbsent(nodeId);
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM node_state WHERE node_id = ?", Long.class, nodeId);
        return version == null ? 0L : version;
    }

    /** 读取节点显式提交过的参数值（键 -> 文本值），不含默认值。 */
    public Map<String, String> committedValues(String nodeId) {
        Map<String, String> result = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT param_key, param_value FROM committed_param WHERE node_id = ? ORDER BY param_key",
                rs -> {
                    result.put(rs.getString("param_key"), rs.getString("param_value"));
                }, nodeId);
        return result;
    }

    /**
     * 原子推进节点版本并写入变更集（调用方负责外层事务）。
     *
     * <p>值为 {@code null} 的变更表示重置为默认值：删除显式提交行，使默认值重新生效。
     *
     * @param changes 本次变更（参数名 -> 规范化文本；null 表示重置）
     */
    public void applyChanges(String nodeId, long newVersion, Map<String, String> changes) {
        jdbcTemplate.update("UPDATE node_state SET version = ? WHERE node_id = ?", newVersion, nodeId);
        Instant now = Instant.now();
        for (Map.Entry<String, String> entry : changes.entrySet()) {
            if (entry.getValue() == null) {
                jdbcTemplate.update(
                        "DELETE FROM committed_param WHERE node_id = ? AND param_key = ?",
                        nodeId, entry.getKey());
            } else {
                jdbcTemplate.update(
                        "MERGE INTO committed_param (node_id, param_key, param_value, updated_at) "
                                + "KEY (node_id, param_key) VALUES (?, ?, ?, ?)",
                        nodeId, entry.getKey(), entry.getValue(), java.sql.Timestamp.from(now));
            }
        }
    }

    /** 事务内对节点行加锁并读回当前版本（调用方负责外层事务）。 */
    public long currentVersionForUpdate(String nodeId) {
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM node_state WHERE node_id = ? FOR UPDATE", Long.class, nodeId);
        return version == null ? 0L : version;
    }
}
