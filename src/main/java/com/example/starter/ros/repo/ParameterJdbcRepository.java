package com.example.starter.ros.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 已提交参数值与节点单调版本数据访问。 */
@Repository
public class ParameterJdbcRepository {

    private final JdbcTemplate jdbc;

    public ParameterJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 节点全部已提交值：键 = 组名.参数名，值 = 字符串存储值。 */
    public Map<String, String> findValues(String node) {
        List<Map.Entry<String, String>> rows = jdbc.query(
                "SELECT group_name, name, param_value FROM ros_param_value WHERE node = ?",
                (rs, n) -> Map.entry(rs.getString("group_name") + "." + rs.getString("name"),
                        rs.getString("param_value")),
                node);
        Map<String, String> result = new LinkedHashMap<>();
        rows.forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    /** 写入已提交值时带上提交版本。 */
    public void upsertValuesWithVersion(String node, String group, Map<String, String> valuesByParam, long version) {
        for (Map.Entry<String, String> e : valuesByParam.entrySet()) {
            int updated = jdbc.update(
                    "UPDATE ros_param_value SET param_value = ?, version = ?"
                            + " WHERE node = ? AND group_name = ? AND name = ?",
                    e.getValue(), version, node, group, e.getKey());
            if (updated == 0) {
                jdbc.update(
                        "INSERT INTO ros_param_value (node, group_name, name, param_value, version)"
                                + " VALUES (?, ?, ?, ?, ?)",
                        node, group, e.getKey(), e.getValue(), version);
            }
        }
    }

    /** 当前版本；无版本行时返回 0。 */
    public long currentVersion(String node) {
        List<Long> versions = jdbc.query(
                "SELECT version FROM ros_node_version WHERE node = ?",
                (rs, n) -> rs.getLong("version"), node);
        return versions.isEmpty() ? 0L : versions.get(0);
    }

    /**
     * 对节点版本行加行锁并返回当前版本；无行时先初始化版本 0 再加锁查询。
     * 必须在外层事务中调用。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long currentVersionForUpdate(String node) {
        initVersionIfAbsent(node);
        return jdbc.queryForObject(
                "SELECT version FROM ros_node_version WHERE node = ? FOR UPDATE",
                Long.class, node);
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public void initVersionIfAbsent(String node) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ros_node_version WHERE node = ?", Integer.class, node);
        if (count != null && count == 0) {
            jdbc.update("INSERT INTO ros_node_version (node, version, updated_at) VALUES (?, 0, 0)",
                    node);
        }
    }

    /** 版本 +1 并记录更新时间。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void incrementVersion(String node, long updatedAtMs) {
        int updated = jdbc.update(
                "UPDATE ros_node_version SET version = version + 1, updated_at = ? WHERE node = ?",
                updatedAtMs, node);
        if (updated == 0) {
            jdbc.update("INSERT INTO ros_node_version (node, version, updated_at) VALUES (?, 1, ?)",
                    node, updatedAtMs);
        }
    }

    public Map<String, Long> currentVersions(Collection<String> nodes) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String node : nodes) {
            result.put(node, currentVersion(node));
        }
        return result;
    }
}
