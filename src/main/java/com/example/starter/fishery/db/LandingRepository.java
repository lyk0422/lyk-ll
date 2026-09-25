package com.example.starter.fishery.db;

import com.example.starter.fishery.db.support.GeneratedKeys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 进港卸渔报告访问。
 */
@Repository
public class LandingRepository {

    private final JdbcTemplate jdbc;

    public LandingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static LandingRow mapRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new LandingRow(
                rs.getLong("id"),
                rs.getString("trip_code"),
                rs.getTimestamp("landed_at").toInstant(),
                rs.getBigDecimal("total_kg"),
                rs.getBigDecimal("deviation_pct"),
                rs.getBoolean("review_triggered"),
                rs.getString("deviation_explanation"),
                rs.getTimestamp("created_at").toInstant());
    }

    /** 查航次进港报告。 */
    public Optional<LandingRow> findByTrip(String tripCode) {
        List<LandingRow> rows = jdbc.query(
                "SELECT id, trip_code, landed_at, total_kg, deviation_pct, review_triggered, "
                        + "deviation_explanation, created_at FROM landing WHERE trip_code = ?",
                LandingRepository::mapRow, tripCode);
        return rows.stream().findFirst();
    }

    /** 新进港报告（唯一约束 uq_landing_trip 兜底幂等）。 */
    public long insert(LandingRow row) {
        return GeneratedKeys.insertAndReturnKey(
                jdbc,
                "INSERT INTO landing(trip_code, landed_at, total_kg, deviation_pct, review_triggered, "
                        + "deviation_explanation, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.tripCode(), Timestamp.from(row.landedAt()), row.totalKg(), row.deviationPct(),
                row.reviewTriggered(), row.deviationExplanation(), Timestamp.from(row.createdAt())).longValue();
    }

    /** 写卸渔分鱼种行。 */
    public void insertLine(long landingId, String speciesCode, BigDecimal weightKg) {
        jdbc.update("INSERT INTO landing_line(landing_id, species_code, weight_kg) VALUES (?, ?, ?)",
                landingId, speciesCode, weightKg);
    }

    /** 查卸渔分鱼种行。 */
    public List<TripRepository.WeightLine> findLines(long landingId) {
        return jdbc.query("SELECT species_code, weight_kg FROM landing_line WHERE landing_id = ? "
                        + "ORDER BY species_code",
                (rs, n) -> new TripRepository.WeightLine(rs.getString("species_code"),
                        rs.getBigDecimal("weight_kg")),
                landingId);
    }
}
