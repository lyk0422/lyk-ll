package com.example.starter.rosconfig.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节点配置版本、已提交参数值、待重启静态参数的数据访问。
 */
@Repository
public class ConfigRepository {

    /** 参数值来源：已提交生效 */
    public static final String SOURCE_COMMITTED = "COMMITTED";
    /** 参数值来源：待重启生效（仅静态参数） */
    public static final String SOURCE_PENDING = "PENDING";

    private final JdbcTemplate jdbc;

    public ConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 读取节点当前单调版本；节点不存在时初始化为 0 并返回 0。并发初始化时重读。 */
    public long getOrInitVersion(String node) {
        Long version = jdbc.query("SELECT version FROM node_config_version WHERE node = ?",
                rs -> rs.next() ? rs.getLong(1) : null, node);
        if (version != null) {
            return version;
        }
        try {
            jdbc.update("INSERT INTO node_config_version(node, version) "
                            + "SELECT ?, 0 WHERE NOT EXISTS (SELECT 1 FROM node_config_version WHERE node = ?)",
                    node, node);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发初始化同一节点：另一事务已插入，直接重读
        }
        Long reread = jdbc.query("SELECT version FROM node_config_version WHERE node = ?",
                rs -> rs.next() ? rs.getLong(1) : null, node);
        return reread == null ? 0L : reread;
    }

    /**
     * 条件单调自增版本：仅当当前版本等于 expectedVersion 时加 1。
     *
     * @return 受影响行数；0 表示版本冲突
     */
    public int incrementVersionIfMatches(String node, long expectedVersion) {
        return jdbc.update("UPDATE node_config_version SET version = version + 1 "
                        + "WHERE node = ? AND version = ?", node, expectedVersion);
    }

    /** 读取节点指定来源的全部参数值（参数名 -> JSON 文本），保持插入顺序无关（由上层排序）。 */
    public Map<String, String> findValueTexts(String node, String source) {
        return jdbc.query("SELECT name, value_text FROM param_value WHERE node = ? AND source = ? ORDER BY name",
                rs -> {
                    Map<String, String> map = new LinkedHashMap<>();
                    while (rs.next()) {
                        map.put(rs.getString("name"), rs.getString("value_text"));
                    }
                    return map;
                }, node, source);
    }

    /** 读取节点全部已提交参数值（参数名 -> JSON 文本）。 */
    public Map<String, String> findCommittedValues(String node) {
        return findValueTexts(node, SOURCE_COMMITTED);
    }

    /** 读取节点待重启生效的静态参数（参数名 -> JSON 文本）。 */
    public Map<String, String> findPendingValues(String node) {
        return findValueTexts(node, SOURCE_PENDING);
    }

    /** 写入（覆盖）节点某个参数的指定来源值；valueText 为 null 表示显式置空。 */
    public void upsertValue(String node, String name, String source, String valueText) {
        jdbc.update("MERGE INTO param_value(node, name, source, value_text) KEY(node, name, source) VALUES (?, ?, ?, ?)",
                node, name, source, valueText);
    }

    /** 删除节点某个参数的指定来源值（显式清空时恢复默认值口径）。 */
    public int deleteValue(String node, String name, String source) {
        return jdbc.update("DELETE FROM param_value WHERE node = ? AND name = ? AND source = ?",
                node, name, source);
    }
}
