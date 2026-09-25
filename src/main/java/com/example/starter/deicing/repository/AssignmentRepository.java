package com.example.starter.deicing.repository;

import com.example.starter.deicing.model.AssignmentState;
import com.example.starter.deicing.po.AssignmentPO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;

/**
 * 车位/除冰车占用仓储：占用均为半开区间 [start, end)。
 */
@Repository
public class AssignmentRepository {

    private static final RowMapper<AssignmentPO> MAPPER = (rs, rowNum) -> {
        AssignmentPO po = new AssignmentPO();
        po.setId(rs.getLong("id"));
        po.setRequestId(rs.getString("request_id"));
        po.setRoundNo(rs.getInt("round_no"));
        po.setBayId(rs.getString("bay_id"));
        po.setTruckId(rs.getString("truck_id"));
        po.setStartTs(rs.getLong("start_ts"));
        po.setEndTs(rs.getLong("end_ts"));
        long actualEnd = rs.getLong("actual_end_ts");
        po.setActualEndTs(rs.wasNull() ? null : actualEnd);
        po.setState(AssignmentState.valueOf(rs.getString("state")));
        return po;
    };

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 模板
     */
    public AssignmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 新增一条占用，自增主键写回 PO。
     *
     * @param po 占用记录
     */
    public void insert(AssignmentPO po) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO deicing_assignment (request_id, round_no, bay_id, truck_id, "
                            + "start_ts, end_ts, actual_end_ts, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[] {"id"});
            ps.setString(1, po.getRequestId());
            ps.setInt(2, po.getRoundNo());
            ps.setString(3, po.getBayId());
            ps.setString(4, po.getTruckId());
            ps.setLong(5, po.getStartTs());
            ps.setLong(6, po.getEndTs());
            ps.setObject(7, po.getActualEndTs(), Types.BIGINT);
            ps.setString(8, po.getState().name());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            po.setId(key.longValue());
        }
    }

    /**
     * @return 全部有效占用（RESERVED/ACTIVE/DONE），按起点升序，用于冲突复算与排期
     */
    public List<AssignmentPO> findAllEffective() {
        return jdbcTemplate.query(
                "SELECT * FROM deicing_assignment WHERE state IN ('RESERVED', 'ACTIVE', 'DONE') "
                        + "ORDER BY start_ts, id", MAPPER);
    }

    /**
     * @param requestId 申请号
     * @return 该申请全部占用（按轮次、起点、车位排序）
     */
    public List<AssignmentPO> findByRequestId(String requestId) {
        return jdbcTemplate.query(
                "SELECT * FROM deicing_assignment WHERE request_id = ? ORDER BY round_no, start_ts, bay_id",
                MAPPER, requestId);
    }

    /**
     * 将一个申请某轮次的全部占用状态更新。
     *
     * @param requestId 申请号
     * @param roundNo   轮次
     * @param newState  新状态
     * @return 更新行数
     */
    public int updateState(String requestId, int roundNo, AssignmentState newState) {
        return jdbcTemplate.update(
                "UPDATE deicing_assignment SET state = ? WHERE request_id = ? AND round_no = ?",
                newState.name(), requestId, roundNo);
    }

    /**
     * 作业开始：该轮占用转 ACTIVE（区间在出队确认时已锁定，不随后续开始时刻改动）。
     *
     * @param requestId 申请号
     * @param roundNo   轮次
     * @return 更新行数
     */
    public int markActive(String requestId, int roundNo) {
        return jdbcTemplate.update(
                "UPDATE deicing_assignment SET state = 'ACTIVE' "
                        + "WHERE request_id = ? AND round_no = ? AND state IN ('RESERVED', 'ACTIVE')",
                requestId, roundNo);
    }

    /**
     * 作业完成：该轮占用转 DONE 并记录实际结束时刻。
     *
     * @param requestId  申请号
     * @param roundNo    轮次
     * @param actualEnd  实际结束时刻
     * @return 更新行数
     */
    public int markDone(String requestId, int roundNo, long actualEnd) {
        return jdbcTemplate.update(
                "UPDATE deicing_assignment SET state = 'DONE', actual_end_ts = ? "
                        + "WHERE request_id = ? AND round_no = ? AND state IN ('ACTIVE', 'DONE')",
                actualEnd, requestId, roundNo);
    }

    /**
     * 取消未开始的排定：该轮 RESERVED 占用释放。
     *
     * @param requestId 申请号
     * @param roundNo   轮次
     * @return 释放行数
     */
    public int releaseReserved(String requestId, int roundNo) {
        return jdbcTemplate.update(
                "UPDATE deicing_assignment SET state = 'RELEASED' "
                        + "WHERE request_id = ? AND round_no = ? AND state = 'RESERVED'",
                requestId, roundNo);
    }
}
