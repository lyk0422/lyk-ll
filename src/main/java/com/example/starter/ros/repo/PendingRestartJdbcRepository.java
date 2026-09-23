package com.example.starter.ros.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 待重启配置数据访问：静态参数变更只能落为待重启，不伪装成已应用。 */
@Repository
public class PendingRestartJdbcRepository {

    private final JdbcTemplate jdbc;

    public PendingRestartJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加或覆盖一条待重启参数（值以字符串保存，读取时按 Schema 类型还原）。 */
    public void append(String transactionId, String node, String group, String name,
                       String value, long createdAtMs) {
        int updated = jdbc.update(
                "UPDATE ros_pending_restart SET transaction_id = ?, param_value = ?, created_at = ?"
                        + " WHERE node = ? AND group_name = ? AND name = ?",
                transactionId, value, createdAtMs, node, group, name);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO ros_pending_restart"
                            + " (transaction_id, node, group_name, name, param_value, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    transactionId, node, group, name, value, createdAtMs);
        }
    }

    /** 查询某节点全部待重启项：键 = 组名.参数名。 */
    public Map<String, String> findByNode(String node) {
        Map<String, String> result = new LinkedHashMap<>();
        jdbc.query("SELECT group_name, name, param_value FROM ros_pending_restart WHERE node = ?"
                        + " ORDER BY group_name, name",
                rs -> {
                    result.put(rs.getString("group_name") + "." + rs.getString("name"),
                            rs.getString("param_value"));
                }, node);
        return result;
    }

    /** 全量列表（管理用途），每行含 transactionId/node/group/name/value。 */
    public List<Map<String, Object>> findAll() {
        return jdbc.query("SELECT transaction_id, node, group_name, name, param_value, created_at"
                        + " FROM ros_pending_restart ORDER BY id",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("transactionId", rs.getString("transaction_id"));
                    row.put("node", rs.getString("node"));
                    row.put("group", rs.getString("group_name"));
                    row.put("name", rs.getString("name"));
                    row.put("value", rs.getString("param_value"));
                    row.put("createdAtMs", rs.getLong("created_at"));
                    return row;
                });
    }
}
