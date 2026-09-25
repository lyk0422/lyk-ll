package com.example.starter.deicing.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 车位占用数据访问。占用按半开区间 [start_time, end_time) 记录。
 */
@Repository
public class AllocationRepository {

    private static final RowMapper<DeicingAllocationPo> MAPPER = (ResultSet rs, int rowNum) -> {
        DeicingAllocationPo po = new DeicingAllocationPo();
        po.setId(rs.getLong("id"));
        po.setRequestId(rs.getString("request_id"));
        po.setFlightNumber(rs.getString("flight_number"));
        po.setBayCode(rs.getString("bay_code"));
        po.setStartTime(RowMappers.instant(rs, "start_time"));
        po.setEndTime(RowMappers.instant(rs, "end_time"));
        po.setStatus(rs.getString("status"));
        po.setCreatedAt(RowMappers.instant(rs, "created_at"));
        return po;
    };

    private final JdbcTemplate jdbcTemplate;

    public AllocationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(DeicingAllocationPo po) {
        jdbcTemplate.update("""
                        INSERT INTO deicing_allocation
                            (request_id, flight_number, bay_code, start_time, end_time, status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                po.getRequestId(), po.getFlightNumber(), po.getBayCode(),
                Timestamp.from(po.getStartTime()), Timestamp.from(po.getEndTime()),
                po.getStatus(), Timestamp.from(po.getCreatedAt()));
    }

    public List<DeicingAllocationPo> findByRequestId(String requestId) {
        return jdbcTemplate.query("""
                        SELECT id, request_id, flight_number, bay_code, start_time, end_time, status, created_at
                        FROM deicing_allocation WHERE request_id = ? ORDER BY bay_code
                        """,
                MAPPER, requestId);
    }

    public List<DeicingAllocationPo> findAll() {
        return jdbcTemplate.query("""
                        SELECT id, request_id, flight_number, bay_code, start_time, end_time, status, created_at
                        FROM deicing_allocation ORDER BY bay_code, start_time, id
                        """,
                MAPPER);
    }

    /**
     * 查询给定车位列表中，与 [start, end) 内部重叠的占用；端点相接（end=start）不返回。
     * 已取消申请的占用不参与冲突判定。
     */
    public List<DeicingAllocationPo> findOverlapping(List<String> bayCodes,
                                                     java.time.Instant start,
                                                     java.time.Instant end) {
        if (bayCodes.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", bayCodes.stream().map(b -> "?").toList());
        return jdbcTemplate.query("""
                        SELECT a.id, a.request_id, a.flight_number, a.bay_code, a.start_time, a.end_time,
                               a.status, a.created_at
                        FROM deicing_allocation a
                        JOIN deicing_request r ON r.request_id = a.request_id
                        WHERE a.bay_code IN (%s)
                          AND a.start_time < ? AND ? < a.end_time
                          AND r.status <> 'CANCELLED'
                        ORDER BY a.bay_code, a.start_time
                        """.formatted(placeholders),
                MAPPER,
                concatArgs(bayCodes, start, end));
    }

    private static Object[] concatArgs(List<String> bayCodes, java.time.Instant start,
                                       java.time.Instant end) {
        Object[] args = new Object[bayCodes.size() + 2];
        for (int i = 0; i < bayCodes.size(); i++) {
            args[i] = bayCodes.get(i);
        }
        args[bayCodes.size()] = Timestamp.from(start);
        args[bayCodes.size() + 1] = Timestamp.from(end);
        return args;
    }

    public void updateStatusByRequest(String requestId, String status) {
        jdbcTemplate.update(
                "UPDATE deicing_allocation SET status = ? WHERE request_id = ?", status, requestId);
    }

    public void deleteByRequest(String requestId) {
        jdbcTemplate.update("DELETE FROM deicing_allocation WHERE request_id = ?", requestId);
    }
}
