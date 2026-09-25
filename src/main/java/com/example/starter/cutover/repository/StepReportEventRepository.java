package com.example.starter.cutover.repository;

import com.example.starter.cutover.po.StepReportEventPO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

/**
 * 迟到步骤回报事件数据访问（仅留痕，不改变步骤与申请状态）。
 */
@Repository
public class StepReportEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public StepReportEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(StepReportEventPO po) {
        jdbcTemplate.update(
                "INSERT INTO step_report_event "
                        + "(cutover_id, step_seq, request_id, result, note, reported_at) "
                        + "VALUES (?,?,?,?,?,?)",
                po.getCutoverId(), po.getStepSeq(), po.getRequestId(), po.getResult(),
                po.getNote(), Timestamp.valueOf(po.getReportedAt()));
    }

    public int countByCutoverId(long cutoverId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM step_report_event WHERE cutover_id = ?",
                Integer.class, cutoverId);
        return count == null ? 0 : count;
    }
}
