package com.example.starter.weight.repo;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.weight.repo.po.OffsiteWeighRow;

/**
 * 非现场动态称重接入仓储：幂等键唯一、按车牌+时刻窗口去重（窗口内只保留首次）。
 */
@Repository
public class OffsiteWeighRepository {

    private static final RowMapper<OffsiteWeighRow> MAPPER = (rs, n) -> new OffsiteWeighRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("plate_no"),
            rs.getString("lane_no"),
            rs.getTimestamp("weigh_time").toInstant(),
            rs.getBigDecimal("total_kg"),
            rs.getString("image_ref"),
            rs.getString("device_id"),
            rs.getString("status"),
            (Long) rs.getObject("case_id"),
            (Long) rs.getObject("decision_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public OffsiteWeighRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按幂等键查询；不存在返回 null。
     */
    public OffsiteWeighRow findByRequestId(String requestId) {
        try {
            return jdbc.queryForObject("SELECT * FROM offsite_weigh WHERE request_id = ?", MAPPER, requestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 按主键查询；不存在返回 null。
     */
    public OffsiteWeighRow findById(long id) {
        try {
            return jdbc.queryForObject("SELECT * FROM offsite_weigh WHERE id = ?", MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 查询同车牌在时刻窗口 [from, to] 内最早接入的非现场记录（去重保留首次）；不存在返回 null。
     */
    public OffsiteWeighRow findFirstByPlateWindow(String plateNo, Instant from, Instant to) {
        try {
            return jdbc.queryForObject("""
                    SELECT * FROM offsite_weigh
                    WHERE plate_no = ? AND weigh_time BETWEEN ? AND ?
                    ORDER BY weigh_time, id
                    LIMIT 1
                    """, MAPPER, plateNo, Timestamp.from(from), Timestamp.from(to));
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 插入非现场称重记录，返回生成的主键。
     */
    public long insert(OffsiteWeighRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO offsite_weigh
                      (request_id, fingerprint, plate_no, lane_no, weigh_time, total_kg,
                       image_ref, device_id, status, case_id, decision_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setString(1, row.requestId());
            ps.setString(2, row.fingerprint());
            ps.setString(3, row.plateNo());
            ps.setString(4, row.laneNo());
            ps.setTimestamp(5, Timestamp.from(row.weighTime()));
            ps.setBigDecimal(6, row.totalKg());
            ps.setString(7, row.imageRef());
            ps.setString(8, row.deviceId());
            ps.setString(9, row.status());
            if (row.caseId() == null) {
                ps.setObject(10, null);
            } else {
                ps.setLong(10, row.caseId());
            }
            if (row.decisionId() == null) {
                ps.setObject(11, null);
            } else {
                ps.setLong(11, row.decisionId());
            }
            ps.setTimestamp(12, Timestamp.from(row.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 统计同车牌接入记录数（测试核查用）。
     */
    public int countByPlate(String plateNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM offsite_weigh WHERE plate_no = ?", Integer.class, plateNo);
        return count == null ? 0 : count;
    }
}
