package com.example.starter.ros.db;

import com.example.starter.ros.PendingRebootRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 静态参数待重启配置数据访问。
 */
@Repository
public class PendingRebootRepository {

    private static final RowMapper<PendingRebootRecord> MAPPER = (rs, n) -> new PendingRebootRecord(
            rs.getLong("id"),
            rs.getString("node_id"),
            rs.getString("param_key"),
            rs.getString("new_value"),
            rs.getString("transaction_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public PendingRebootRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(PendingRebootRecord record) {
        jdbcTemplate.update(
                "INSERT INTO pending_reboot (node_id, param_key, new_value, transaction_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                record.nodeId(), record.paramKey(), record.newValue(),
                record.transactionId(), Timestamp.from(record.createdAt()));
    }

    public List<PendingRebootRecord> findByNode(String nodeId) {
        return jdbcTemplate.query(
                "SELECT id, node_id, param_key, new_value, transaction_id, created_at "
                        + "FROM pending_reboot WHERE node_id = ? ORDER BY id",
                MAPPER, nodeId);
    }

    /** 幂等重放：按事务 ID 与节点读取已登记的待重启配置。 */
    public List<PendingRebootRecord> findByTransaction(String transactionId, String nodeId) {
        return jdbcTemplate.query(
                "SELECT id, node_id, param_key, new_value, transaction_id, created_at "
                        + "FROM pending_reboot WHERE transaction_id = ? AND node_id = ? ORDER BY id",
                MAPPER, transactionId, nodeId);
    }
}
