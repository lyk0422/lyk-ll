package com.example.starter.weight.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.weight.repo.po.WeighRecordRow;

/**
 * 过磅记录仓储：去重键（车牌+检测站+过磅时刻+方式）唯一、
 * 静态复检取代动态（角色变更）、迟到证据留存。
 */
@Repository
public class WeighRecordRepository {

    private static final RowMapper<WeighRecordRow> MAPPER = (rs, n) -> new WeighRecordRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("plate_no"),
            rs.getString("station_code"),
            rs.getTimestamp("weigh_time").toInstant(),
            rs.getString("mode"),
            rs.getBigDecimal("total_kg"),
            rs.getString("axle_type"),
            rs.getBigDecimal("limit_kg"),
            rs.getBigDecimal("overload_rate"),
            rs.getString("tier"),
            rs.getString("role"),
            (Long) rs.getObject("case_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public WeighRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按幂等键查询；不存在返回 null。
     */
    public WeighRecordRow findByRequestId(String requestId) {
        try {
            return jdbc.queryForObject("SELECT * FROM weigh_record WHERE request_id = ?", MAPPER, requestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 按去重键（车牌+检测站+过磅时刻+方式）查询；不存在返回 null。
     */
    public WeighRecordRow findByDedupKey(String plateNo, String stationCode, Instant weighTime, String mode) {
        try {
            return jdbc.queryForObject("""
                    SELECT * FROM weigh_record
                    WHERE plate_no = ? AND station_code = ? AND weigh_time = ? AND mode = ?
                    """, MAPPER, plateNo, stationCode, Timestamp.from(weighTime), mode);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 查询同“车牌+检测站+过磅时刻”下当前有效的权威记录；不存在返回 null。
     */
    public WeighRecordRow findAuthoritative(String plateNo, String stationCode, Instant weighTime) {
        try {
            return jdbc.queryForObject("""
                    SELECT * FROM weigh_record
                    WHERE plate_no = ? AND station_code = ? AND weigh_time = ? AND role = 'AUTHORITATIVE'
                    """, MAPPER, plateNo, stationCode, Timestamp.from(weighTime));
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 查询同“车牌+检测站+过磅时刻”下全部记录（含被取代记录，保证可查）。
     */
    public List<WeighRecordRow> findAllByBizKey(String plateNo, String stationCode, Instant weighTime) {
        return jdbc.query("""
                SELECT * FROM weigh_record
                WHERE plate_no = ? AND station_code = ? AND weigh_time = ?
                ORDER BY id
                """, MAPPER, plateNo, stationCode, Timestamp.from(weighTime));
    }

    /**
     * 插入过磅记录，返回生成的主键。
     */
    public long insert(WeighRecordRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO weigh_record
                      (request_id, fingerprint, plate_no, station_code, weigh_time, mode, total_kg,
                       axle_type, limit_kg, overload_rate, tier, role, case_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setString(1, row.requestId());
            ps.setString(2, row.fingerprint());
            ps.setString(3, row.plateNo());
            ps.setString(4, row.stationCode());
            ps.setTimestamp(5, Timestamp.from(row.weighTime()));
            ps.setString(6, row.mode());
            ps.setBigDecimal(7, row.totalKg());
            ps.setString(8, row.axleType());
            ps.setBigDecimal(9, row.limitKg());
            ps.setBigDecimal(10, row.overloadRate());
            ps.setString(11, row.tier());
            ps.setString(12, row.role());
            if (row.caseId() == null) {
                ps.setObject(13, null);
            } else {
                ps.setLong(13, row.caseId());
            }
            ps.setTimestamp(14, Timestamp.from(row.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 将原权威记录标记为被静态复检取代。
     *
     * @return 受影响行数
     */
    public int markSuperseded(long id) {
        return jdbc.update("UPDATE weigh_record SET role = 'SUPERSEDED' WHERE id = ? AND role = 'AUTHORITATIVE'",
                id);
    }

    /**
     * 将记录改挂到指定案件（静态复检取代动态后同步案件关联）。
     */
    public void attachCase(long id, Long caseId) {
        jdbc.update("UPDATE weigh_record SET case_id = ? WHERE id = ?", caseId, id);
    }

    /**
     * 按主键查询；不存在返回 null。
     */
    public WeighRecordRow findById(long id) {
        try {
            return jdbc.queryForObject("SELECT * FROM weigh_record WHERE id = ?", MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}
