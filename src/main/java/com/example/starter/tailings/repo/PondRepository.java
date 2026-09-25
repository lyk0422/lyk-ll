package com.example.starter.tailings.repo;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 尾矿库基础信息与库容曲线存取。
 */
@Repository
public class PondRepository {

    private final JdbcTemplate jdbc;

    public PondRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尾矿库行。
     *
     * @param id                       主键
     * @param name                     名称
     * @param maxFloodLevel            允许最高调洪水位(米)
     * @param downstreamSafeDischarge  下游安全泄量(立方米/秒)
     * @param dryBeachMin              干滩长度安全下限(米)
     * @param phreaticLineMin          浸润线埋深安全下限(米)
     */
    public record PondRow(long id, String name, BigDecimal maxFloodLevel,
                          BigDecimal downstreamSafeDischarge, BigDecimal dryBeachMin,
                          BigDecimal phreaticLineMin) {
    }

    /**
     * 库容曲线离散点：水位(米) → 总库容(立方米)。
     */
    public record CurvePoint(BigDecimal waterLevel, BigDecimal capacity) {
    }

    public long insertPond(String name, BigDecimal maxFloodLevel, BigDecimal downstreamSafeDischarge,
                           BigDecimal dryBeachMin, BigDecimal phreaticLineMin) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO tailings_pond (name, max_flood_level, downstream_safe_discharge,"
                            + " dry_beach_min, phreatic_line_min) VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, name);
            ps.setBigDecimal(2, maxFloodLevel);
            ps.setBigDecimal(3, downstreamSafeDischarge);
            ps.setBigDecimal(4, dryBeachMin);
            ps.setBigDecimal(5, phreaticLineMin);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void insertCurvePoint(long pondId, BigDecimal waterLevel, BigDecimal capacity) {
        jdbc.update("INSERT INTO storage_curve (pond_id, water_level, capacity) VALUES (?, ?, ?)",
                pondId, waterLevel, capacity);
    }

    public Optional<PondRow> findPond(long id) {
        return jdbc.query(
                        "SELECT id, name, max_flood_level, downstream_safe_discharge, dry_beach_min, phreatic_line_min"
                                + " FROM tailings_pond WHERE id = ?",
                        (rs, rowNum) -> new PondRow(
                                rs.getLong("id"),
                                rs.getString("name"),
                                rs.getBigDecimal("max_flood_level"),
                                rs.getBigDecimal("downstream_safe_discharge"),
                                rs.getBigDecimal("dry_beach_min"),
                                rs.getBigDecimal("phreatic_line_min")),
                        id)
                .stream()
                .findFirst();
    }

    /**
     * 按水位升序返回库容曲线点。
     */
    public List<CurvePoint> curve(long pondId) {
        return jdbc.query(
                "SELECT water_level, capacity FROM storage_curve WHERE pond_id = ? ORDER BY water_level",
                (rs, rowNum) -> new CurvePoint(rs.getBigDecimal("water_level"), rs.getBigDecimal("capacity")),
                pondId);
    }
}
