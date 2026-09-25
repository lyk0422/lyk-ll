package com.example.starter.tailings.repo;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 调洪演算结果存取，含可复算快照。
 */
@Repository
public class CalculationRepository {

    private final JdbcTemplate jdbc;

    public CalculationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 调洪演算行。
     *
     * @param id                主键
     * @param requestId         幂等请求ID
     * @param pondId            尾矿库ID
     * @param currentLevel      当前库水位(米)
     * @param designFloodVolume 设计洪水量(立方米)
     * @param availableCapacity 可用调洪库容(立方米)
     * @param dryBeachLength    干滩长度(米)
     * @param phreaticDepth     浸润线埋深(米)
     * @param capacityVerdict   库容口径判定
     * @param safetyVerdict     安全口径判定
     * @param verdict           综合判定
     * @param snapshot          演算输入快照与判定口径(JSON)
     * @param createdAt         演算时间
     */
    public record CalcRow(long id, String requestId, long pondId, BigDecimal currentLevel,
                          BigDecimal designFloodVolume, BigDecimal availableCapacity,
                          BigDecimal dryBeachLength, BigDecimal phreaticDepth,
                          String capacityVerdict, String safetyVerdict, String verdict,
                          String snapshot, LocalDateTime createdAt) {
    }

    public long insert(String requestId, long pondId, BigDecimal currentLevel, BigDecimal designFloodVolume,
                       BigDecimal availableCapacity, BigDecimal dryBeachLength, BigDecimal phreaticDepth,
                       String capacityVerdict, String safetyVerdict, String verdict, String snapshot) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO flood_calc (request_id, pond_id, current_level, design_flood_volume,"
                            + " available_capacity, dry_beach_length, phreatic_depth,"
                            + " capacity_verdict, safety_verdict, verdict, snapshot)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, requestId);
            ps.setLong(2, pondId);
            ps.setBigDecimal(3, currentLevel);
            ps.setBigDecimal(4, designFloodVolume);
            ps.setBigDecimal(5, availableCapacity);
            ps.setBigDecimal(6, dryBeachLength);
            ps.setBigDecimal(7, phreaticDepth);
            ps.setString(8, capacityVerdict);
            ps.setString(9, safetyVerdict);
            ps.setString(10, verdict);
            ps.setString(11, snapshot);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<CalcRow> findById(long id) {
        return jdbc.query(
                        "SELECT id, request_id, pond_id, current_level, design_flood_volume, available_capacity,"
                                + " dry_beach_length, phreatic_depth, capacity_verdict, safety_verdict, verdict,"
                                + " snapshot, created_at FROM flood_calc WHERE id = ?",
                        (rs, rowNum) -> new CalcRow(
                                rs.getLong("id"),
                                rs.getString("request_id"),
                                rs.getLong("pond_id"),
                                rs.getBigDecimal("current_level"),
                                rs.getBigDecimal("design_flood_volume"),
                                rs.getBigDecimal("available_capacity"),
                                rs.getBigDecimal("dry_beach_length"),
                                rs.getBigDecimal("phreatic_depth"),
                                rs.getString("capacity_verdict"),
                                rs.getString("safety_verdict"),
                                rs.getString("verdict"),
                                rs.getString("snapshot"),
                                rs.getObject("created_at", LocalDateTime.class)),
                        id)
                .stream()
                .findFirst();
    }
}
