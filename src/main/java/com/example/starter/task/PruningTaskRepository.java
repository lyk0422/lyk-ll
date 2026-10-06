package com.example.starter.task;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 修剪任务数据访问。
 */
@Repository
public class PruningTaskRepository {

    private final JdbcClient jdbc;

    public PruningTaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 修剪任务行。
     * status：PENDING 待办 / IN_PROGRESS 在途 / CLOSED 已闭环 / CANCELLED 已取消；
     * closedAt 为 NULL 表示未闭环；escalated 为逾期升级督办标记。
     */
    public record TaskRow(long id, long treeId, long segmentId, int riskLevel, String status,
            LocalDate createdDate, LocalDate deadlineDate, LocalDateTime closedAt,
            boolean escalated, int planVersion) {
    }

    public long insert(long treeId, long segmentId, int riskLevel, String status,
            LocalDate createdDate, LocalDate deadlineDate) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO pruning_task(tree_id, segment_id, risk_level, status, created_date, deadline_date)"
                        + " VALUES (?, ?, ?, ?, ?, ?)")
                .params(treeId, segmentId, riskLevel, status, createdDate, deadlineDate)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<TaskRow> findById(long id) {
        return jdbc.sql("SELECT * FROM pruning_task WHERE id = ?")
                .param(id)
                .query(this::mapRow)
                .optional();
    }

    /** 行级锁读取，用于许可签发与回传的串行化。 */
    public Optional<TaskRow> findByIdForUpdate(long id) {
        return jdbc.sql("SELECT * FROM pruning_task WHERE id = ? FOR UPDATE")
                .param(id)
                .query(this::mapRow)
                .optional();
    }

    /** 未闭环且未取消的任务（每棵树至多一个在办任务由业务保证）。 */
    public Optional<TaskRow> findOpenByTreeId(long treeId) {
        return jdbc.sql("SELECT * FROM pruning_task WHERE tree_id = ? AND status IN ('PENDING','IN_PROGRESS')"
                        + " ORDER BY id LIMIT 1")
                .param(treeId)
                .query(this::mapRow)
                .optional();
    }

    public void updateStatus(long id, String status) {
        jdbc.sql("UPDATE pruning_task SET status = ? WHERE id = ?")
                .params(status, id)
                .update();
    }

    /** 闭环：记录闭环时间与是否逾期升级督办。 */
    public void markClosed(long id, LocalDateTime closedAt, boolean escalated) {
        jdbc.sql("UPDATE pruning_task SET status = 'CLOSED', closed_at = ?, escalated = ? WHERE id = ?")
                .params(closedAt, escalated, id)
                .update();
    }

    /** 重排：更新等级与截止日并提升计划版本；仅用于无在途许可的待办任务。 */
    public void replan(long id, int newRiskLevel, LocalDate newDeadline) {
        jdbc.sql("UPDATE pruning_task SET risk_level = ?, deadline_date = ?, plan_version = plan_version + 1"
                        + " WHERE id = ?")
                .params(newRiskLevel, newDeadline, id)
                .update();
    }

    public void cancel(long id) {
        jdbc.sql("UPDATE pruning_task SET status = 'CANCELLED' WHERE id = ?")
                .param(id)
                .update();
    }

    /** 逾期升级督办：截止日早于今日且未闭环的任务标记 escalated，返回影响行数。 */
    public int escalateOverdue(LocalDate today) {
        return jdbc.sql("UPDATE pruning_task SET escalated = TRUE"
                        + " WHERE status IN ('PENDING','IN_PROGRESS') AND escalated = FALSE AND deadline_date < ?")
                .param(today)
                .update();
    }

    /** 台账统计原始行。 */
    public List<TaskRow> listAll() {
        return jdbc.sql("SELECT * FROM pruning_task ORDER BY id")
                .query(this::mapRow)
                .list();
    }

    private TaskRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TaskRow(rs.getLong("id"), rs.getLong("tree_id"), rs.getLong("segment_id"),
                rs.getInt("risk_level"), rs.getString("status"),
                rs.getObject("created_date", LocalDate.class),
                rs.getObject("deadline_date", LocalDate.class),
                rs.getObject("closed_at", LocalDateTime.class),
                rs.getBoolean("escalated"), rs.getInt("plan_version"));
    }
}
