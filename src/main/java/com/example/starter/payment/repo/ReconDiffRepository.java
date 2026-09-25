package com.example.starter.payment.repo;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.example.starter.payment.domain.DiffType;
import com.example.starter.payment.domain.ResolveAction;
import com.example.starter.payment.dto.DiffView;

/**
 * 对账差异与处理留痕仓储。
 * 唯一键 (batch_date, channel_serial_no, diff_type) 防止重复生成；
 * 核销使用条件更新 status=OPEN，保证同一差异不能重复核销。
 */
@Repository
public class ReconDiffRepository {

    private static final RowMapper<DiffView> VIEW_MAPPER = (rs, n) -> {
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        return new DiffView(
                rs.getLong("id"),
                rs.getString("batch_date"),
                rs.getString("channel_serial_no"),
                rs.getString("merchant_no"),
                rs.getString("diff_type"),
                rs.getBigDecimal("local_amount"),
                rs.getBigDecimal("channel_amount"),
                rs.getString("status"),
                rs.getString("resolve_action"),
                rs.getString("resolve_remark"),
                resolvedAt == null ? null : resolvedAt.toInstant());
    };

    private static final String VIEW_COLUMNS = "id, batch_date, channel_serial_no, merchant_no, diff_type, "
            + "local_amount, channel_amount, status, resolve_action, resolve_remark, resolved_at";

    private final JdbcTemplate jdbc;
    private final JdbcClient client;

    public ReconDiffRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.client = JdbcClient.create(jdbc);
    }

    public int countByBatch(String batchDate) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM recon_diff WHERE batch_date = ?", Integer.class, batchDate);
        return count == null ? 0 : count;
    }

    /** 登记对账执行；主键冲突说明该批次已对账。 */
    public void insertRun(String batchDate) {
        jdbc.update("INSERT INTO recon_run (batch_date) VALUES (?)", batchDate);
    }

    public void insert(String batchDate, String serialNo, String merchantNo, DiffType type,
            BigDecimal localAmount, BigDecimal channelAmount) {
        jdbc.update("INSERT INTO recon_diff (batch_date, channel_serial_no, merchant_no, diff_type, "
                + "local_amount, channel_amount, status) VALUES (?,?,?,?,?,?, 'OPEN')",
                batchDate, serialNo, merchantNo, type.name(), localAmount, channelAmount);
    }

    public List<DiffView> findByBatch(String batchDate) {
        return client.sql("SELECT " + VIEW_COLUMNS + " FROM recon_diff WHERE batch_date = :bd ORDER BY id")
                .param("bd", batchDate)
                .query(VIEW_MAPPER)
                .list();
    }

    public Optional<DiffView> findById(long id) {
        return client.sql("SELECT " + VIEW_COLUMNS + " FROM recon_diff WHERE id = :id")
                .param("id", id)
                .query(VIEW_MAPPER)
                .optional();
    }

    /** 事务内锁定差异行。 */
    public Optional<DiffView> lockById(long id) {
        return client.sql("SELECT " + VIEW_COLUMNS + " FROM recon_diff WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(VIEW_MAPPER)
                .optional();
    }

    /**
     * 仅当差异仍为 OPEN 时标记为 RESOLVED。
     *
     * @return 1 本次核销成功；0 差异不存在或已被处理
     */
    public int resolveIfOpen(long id, ResolveAction action, String remark, Instant resolvedAt) {
        return jdbc.update("UPDATE recon_diff SET status = 'RESOLVED', resolve_action = ?, resolve_remark = ?, "
                + "resolved_at = ? WHERE id = ? AND status = 'OPEN'",
                action.name(), remark, Timestamp.from(resolvedAt), id);
    }

    public void insertLog(long diffId, ResolveAction action, String operator, String remark) {
        jdbc.update("INSERT INTO recon_diff_log (diff_id, action, operator, remark) VALUES (?,?,?,?)",
                diffId, action.name(), operator, remark);
    }
}
