package com.example.starter.fishery.db;

import com.example.starter.fishery.domain.TripStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 航次台账、出港申报行与航次分鱼种口径行访问。
 */
@Repository
public class TripRepository {

    private final JdbcTemplate jdbc;

    public TripRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static TripRow mapRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Timestamp landedAt = rs.getTimestamp("landed_at");
        return new TripRow(
                rs.getLong("id"),
                rs.getString("trip_code"),
                rs.getString("vessel_code"),
                rs.getTimestamp("depart_at").toInstant(),
                landedAt == null ? null : landedAt.toInstant(),
                TripStatus.valueOf(rs.getString("status")),
                rs.getBoolean("frozen"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /** 按航次编号查询。 */
    public Optional<TripRow> findByCode(String tripCode) {
        List<TripRow> rows = jdbc.query(
                "SELECT id, trip_code, vessel_code, depart_at, landed_at, status, frozen, created_at, updated_at "
                        + "FROM trip WHERE trip_code = ?",
                TripRepository::mapRow, tripCode);
        return rows.stream().findFirst();
    }

    /** 该船是否存在未结案航次（DEPARTED/DISCHARGED/IN_REVIEW）。 */
    public boolean existsOpenForVessel(String vesselCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM trip WHERE vessel_code = ? AND status <> 'CLOSED'",
                Integer.class, vesselCode);
        return count != null && count > 0;
    }

    /** 新增航次（出港申报唯一受理由调用方先做存在性校验，uq_trip_code 兜底）。 */
    public void insert(TripRow trip) {
        jdbc.update("INSERT INTO trip(trip_code, vessel_code, depart_at, landed_at, status, frozen, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                trip.tripCode(), trip.vesselCode(), Timestamp.from(trip.departAt()),
                trip.landedAt() == null ? null : Timestamp.from(trip.landedAt()),
                trip.status().name(), trip.frozen(),
                Timestamp.from(trip.createdAt()), Timestamp.from(trip.updatedAt()));
    }

    /**
     * 在航护栏占座；同一渔船同时刻只允许一个进行中航次。
     * 依赖主键冲突保证并发下唯一受理，返回 false 表示该船已有进行中航次。
     */
    public boolean tryOccupyActiveVessel(String vesselCode, String tripCode) {
        try {
            jdbc.update("INSERT INTO vessel_active_trip(vessel_code, trip_code) VALUES (?, ?)",
                    vesselCode, tripCode);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            return false;
        }
    }

    /** 结案后释放渔船在航占座。 */
    public void releaseActiveVessel(String vesselCode) {
        jdbc.update("DELETE FROM vessel_active_trip WHERE vessel_code = ?", vesselCode);
    }

    /** 行锁读取航次。 */
    public Optional<TripRow> findByCodeForUpdate(String tripCode) {
        List<TripRow> rows = jdbc.query(
                "SELECT id, trip_code, vessel_code, depart_at, landed_at, status, frozen, created_at, updated_at "
                        + "FROM trip WHERE trip_code = ? FOR UPDATE",
                TripRepository::mapRow, tripCode);
        return rows.stream().findFirst();
    }

    /** 更新状态与冻结标志。 */
    public void updateState(String tripCode, TripStatus status, boolean frozen, Instant now) {
        jdbc.update("UPDATE trip SET status = ?, frozen = ?, updated_at = ? WHERE trip_code = ?",
                status.name(), frozen, Timestamp.from(now), tripCode);
    }

    /** 进港登记进港时刻。 */
    public void markLanded(String tripCode, Instant landedAt, Instant now) {
        jdbc.update("UPDATE trip SET landed_at = ?, updated_at = ? WHERE trip_code = ?",
                Timestamp.from(landedAt), Timestamp.from(now), tripCode);
    }

    /** 写申报分鱼种行。 */
    public void insertDepartureLine(String tripCode, String speciesCode, BigDecimal weightKg) {
        jdbc.update("INSERT INTO departure_line(trip_code, species_code, weight_kg) VALUES (?, ?, ?)",
                tripCode, speciesCode, weightKg);
    }

    /** 查申报分鱼种行。 */
    public List<WeightLine> findDepartureLines(String tripCode) {
        return jdbc.query("SELECT species_code, weight_kg FROM departure_line WHERE trip_code = ? "
                        + "ORDER BY species_code",
                (rs, n) -> new WeightLine(rs.getString("species_code"), rs.getBigDecimal("weight_kg")),
                tripCode);
    }

    /** upsert 航次口径行的日志累计量（保留既有卸渔量与最终口径）。 */
    public void updateLoggedKg(String tripCode, String speciesCode, BigDecimal loggedKg) {
        jdbc.update("INSERT INTO trip_catch_line(trip_code, species_code, logged_kg, landed_kg, final_kg) "
                        + "VALUES (?, ?, ?, 0, 0) "
                        + "ON DUPLICATE KEY UPDATE logged_kg = VALUES(logged_kg)",
                tripCode, speciesCode, loggedKg);
    }

    /** upsert 航次口径行的卸渔量（保留既日志累计量），并可同时初始化最终口径量。 */
    public void updateLandedKg(String tripCode, String speciesCode, BigDecimal landedKg, BigDecimal finalKg) {
        jdbc.update("INSERT INTO trip_catch_line(trip_code, species_code, logged_kg, landed_kg, final_kg) "
                        + "VALUES (?, ?, 0, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE landed_kg = VALUES(landed_kg), final_kg = VALUES(final_kg)",
                tripCode, speciesCode, landedKg, finalKg);
    }

    /** 复核结论写入最终口径量。 */
    public void updateFinalKg(String tripCode, String speciesCode, BigDecimal finalKg) {
        jdbc.update("UPDATE trip_catch_line SET final_kg = ? WHERE trip_code = ? AND species_code = ?",
                finalKg, tripCode, speciesCode);
    }

    /** 查航次全部口径行。 */
    public List<CatchLine> findCatchLines(String tripCode) {
        return jdbc.query(
                "SELECT species_code, logged_kg, landed_kg, final_kg FROM trip_catch_line "
                        + "WHERE trip_code = ? ORDER BY species_code",
                (rs, n) -> new CatchLine(rs.getString("species_code"),
                        rs.getBigDecimal("logged_kg"),
                        rs.getBigDecimal("landed_kg"),
                        rs.getBigDecimal("final_kg")),
                tripCode);
    }

    /** 分鱼种重量行。 */
    public record WeightLine(String speciesCode, BigDecimal weightKg) {
    }

    /** 航次分鱼种口径行。 */
    public record CatchLine(String speciesCode, BigDecimal loggedKg, BigDecimal landedKg, BigDecimal finalKg) {
    }
}
