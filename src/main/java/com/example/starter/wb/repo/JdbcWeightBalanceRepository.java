package com.example.starter.wb.repo;

import com.example.starter.wb.domain.Aircraft;
import com.example.starter.wb.domain.CargoItemRow;
import com.example.starter.wb.domain.CargoOccupancyRow;
import com.example.starter.wb.domain.CargoPlanRow;
import com.example.starter.wb.domain.CompartmentRow;
import com.example.starter.wb.domain.EnvelopePoint;
import com.example.starter.wb.domain.Flight;
import com.example.starter.wb.domain.IdempotentRow;
import com.example.starter.wb.domain.LateEventRow;
import com.example.starter.wb.domain.LoadDetailRow;
import com.example.starter.wb.domain.LoadStation;
import com.example.starter.wb.domain.LmcLogRow;
import com.example.starter.wb.domain.ManifestRow;
import com.example.starter.wb.domain.ReviewRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 基于 JdbcClient 的载重平衡仓储。
 * 写操作使用参数化 SQL；时间戳按 Asia/Shanghai 本地时间存储。
 */
@Repository
public class JdbcWeightBalanceRepository implements WeightBalanceRepository {

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public JdbcWeightBalanceRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Aircraft findAircraftById(long id) {
        String sql = """
                SELECT id, tail_no, aircraft_type, oew_kg, oew_arm, mzfw_kg, mtow_kg, mlw_kg,
                       lemac, mac_length, fuel_arm
                FROM aircraft WHERE id = :id
                """;
        Aircraft base = jdbc.sql(sql).param("id", id).query((rs, n) -> new Aircraft(
                rs.getLong("id"),
                rs.getString("tail_no"),
                rs.getString("aircraft_type"),
                rs.getInt("oew_kg"),
                rs.getBigDecimal("oew_arm"),
                rs.getInt("mzfw_kg"),
                rs.getInt("mtow_kg"),
                rs.getInt("mlw_kg"),
                rs.getBigDecimal("lemac"),
                rs.getBigDecimal("mac_length"),
                rs.getBigDecimal("fuel_arm"),
                List.of(),
                List.of()
        )).optional().orElse(null);
        if (base == null) {
            return null;
        }
        List<EnvelopePoint> envelope = jdbc.sql("""
                        SELECT weight_kg, mac_pct FROM envelope_point
                        WHERE aircraft_id = :id ORDER BY seq_no
                        """)
                .param("id", id)
                .query((rs, n) -> new EnvelopePoint(rs.getInt("weight_kg"), rs.getBigDecimal("mac_pct")))
                .list();
        List<LoadStation> stations = jdbc.sql("""
                        SELECT station_code, arm, label FROM load_station WHERE aircraft_id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new LoadStation(rs.getString("station_code"),
                        rs.getBigDecimal("arm"), rs.getString("label")))
                .list();
        return new Aircraft(base.id(), base.tailNo(), base.aircraftType(), base.oewKg(), base.oewArm(),
                base.mzfwKg(), base.mtowKg(), base.mlwKg(), base.lemac(), base.macLength(),
                base.fuelArm(), List.copyOf(envelope), List.copyOf(stations));
    }

    @Override
    public Flight findFlightById(long id) {
        return jdbc.sql("""
                        SELECT id, flight_no, origin, destination, aircraft_id, scheduled_time
                        FROM flight WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new Flight(
                        rs.getLong("id"),
                        rs.getString("flight_no"),
                        rs.getString("origin"),
                        rs.getString("destination"),
                        rs.getLong("aircraft_id"),
                        rs.getTimestamp("scheduled_time").toLocalDateTime()))
                .optional().orElse(null);
    }

    @Override
    public Optional<ManifestRow> findActiveByFlightId(long flightId) {
        return queryManifest("""
                SELECT * FROM manifest_version WHERE flight_id = :fid AND active_flag = :fid
                """, flightId);
    }

    @Override
    public Optional<ManifestRow> findActiveByFlightIdForUpdate(long flightId) {
        return queryManifest("""
                SELECT * FROM manifest_version WHERE flight_id = :fid AND active_flag = :fid FOR UPDATE
                """, flightId);
    }

    @Override
    public Optional<ManifestRow> findManifestById(long id) {
        return jdbc.sql("SELECT * FROM manifest_version WHERE id = :id")
                .param("id", id).query(MANIFEST_MAPPER).optional();
    }

    @Override
    public List<ManifestRow> findVersionsByFlightId(long flightId) {
        return jdbc.sql("SELECT * FROM manifest_version WHERE flight_id = :fid ORDER BY version_no")
                .param("fid", flightId).query(MANIFEST_MAPPER).list();
    }

    private Optional<ManifestRow> queryManifest(String sql, long flightId) {
        return jdbc.sql(sql).param("fid", flightId).query(MANIFEST_MAPPER).optional();
    }

    @Override
    public List<LoadDetailRow> findDetails(long manifestId) {
        return jdbc.sql("""
                        SELECT id, manifest_id, category, station_code, piece_count,
                               unit_weight_kg, total_weight_kg, seq_no
                        FROM load_detail WHERE manifest_id = :id ORDER BY seq_no, id
                        """)
                .param("id", manifestId)
                .query((rs, n) -> new LoadDetailRow(
                        rs.getLong("id"), rs.getLong("manifest_id"), rs.getString("category"),
                        rs.getString("station_code"), rs.getInt("piece_count"),
                        rs.getBigDecimal("unit_weight_kg"), rs.getBigDecimal("total_weight_kg"),
                        rs.getInt("seq_no")))
                .list();
    }

    @Override
    public List<ReviewRow> findReviews(long manifestId) {
        return jdbc.sql("""
                        SELECT id, manifest_id, request_id, fingerprint, role_code, reviewer, created_at
                        FROM review_confirmation WHERE manifest_id = :id ORDER BY id
                        """)
                .param("id", manifestId)
                .query((rs, n) -> new ReviewRow(
                        rs.getLong("id"), rs.getLong("manifest_id"), rs.getString("request_id"),
                        rs.getString("fingerprint"), rs.getString("role_code"),
                        rs.getString("reviewer"), rs.getTimestamp("created_at").toLocalDateTime()))
                .list();
    }

    @Override
    public List<LmcLogRow> findLmcLogs(long manifestId) {
        return jdbc.sql("""
                        SELECT id, manifest_id, request_id, passenger_delta, weight_delta_kg, fuel_delta_kg,
                               cumulative_pax, cumulative_weight, within_lmc_limit, new_version_id, created_at
                        FROM lmc_log WHERE manifest_id = :id ORDER BY id
                        """)
                .param("id", manifestId)
                .query((rs, n) -> new LmcLogRow(
                        rs.getLong("id"), rs.getLong("manifest_id"), rs.getString("request_id"),
                        rs.getInt("passenger_delta"), rs.getBigDecimal("weight_delta_kg"),
                        rs.getBigDecimal("fuel_delta_kg"), rs.getInt("cumulative_pax"),
                        rs.getBigDecimal("cumulative_weight"), rs.getBoolean("within_lmc_limit"),
                        (Long) rs.getObject("new_version_id"),
                        rs.getTimestamp("created_at").toLocalDateTime()))
                .list();
    }

    @Override
    public List<LateEventRow> findLateEvents(long flightId) {
        return jdbc.sql("""
                        SELECT id, flight_id, manifest_id, passenger_count, weight_kg, payload_json,
                               alerted, created_at
                        FROM late_event WHERE flight_id = :fid ORDER BY id
                        """)
                .param("fid", flightId)
                .query((rs, n) -> new LateEventRow(
                        rs.getLong("id"), rs.getLong("flight_id"), rs.getLong("manifest_id"),
                        rs.getInt("passenger_count"), rs.getBigDecimal("weight_kg"),
                        rs.getString("payload_json"), rs.getBoolean("alerted"),
                        rs.getTimestamp("created_at").toLocalDateTime()))
                .list();
    }

    @Override
    public long insertManifest(ManifestRow r) {
        KeyHolder kh = new GeneratedKeyHolder();
        String sql = """
                INSERT INTO manifest_version
                  (flight_id, version_no, parent_version_id, request_id, fingerprint, status,
                   passenger_count, payload_kg, fuel_kg, zfw_kg, tow_kg, cg_zfw_pct, cg_tow_pct,
                   within_limits, lmc_pax_delta, lmc_weight_delta, active_flag, created_by,
                   created_at, updated_at, closed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setLong(i++, r.flightId());
            ps.setInt(i++, r.versionNo());
            setNullableLong(ps, i++, r.parentVersionId());
            ps.setString(i++, r.requestId());
            ps.setString(i++, r.fingerprint());
            ps.setString(i++, r.status());
            ps.setInt(i++, r.passengerCount());
            ps.setBigDecimal(i++, r.payloadKg());
            ps.setBigDecimal(i++, r.fuelKg());
            ps.setBigDecimal(i++, r.zfwKg());
            ps.setBigDecimal(i++, r.towKg());
            ps.setBigDecimal(i++, r.cgZfwPct());
            ps.setBigDecimal(i++, r.cgTowPct());
            ps.setBoolean(i++, r.withinLimits());
            ps.setInt(i++, r.lmcPaxDelta());
            ps.setBigDecimal(i++, r.lmcWeightDelta());
            setNullableLong(ps, i++, r.activeFlag());
            ps.setString(i++, r.createdBy());
            ps.setTimestamp(i++, Timestamp.valueOf(r.createdAt()));
            ps.setTimestamp(i++, Timestamp.valueOf(r.updatedAt()));
            if (r.closedAt() == null) {
                ps.setNull(i, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(i, Timestamp.valueOf(r.closedAt()));
            }
            return ps;
        }, kh);
        return kh.getKey() == null ? 0L : kh.getKey().longValue();
    }

    @Override
    public void insertDetails(long manifestId, List<LoadDetailRow> details) {
        String sql = """
                INSERT INTO load_detail
                  (manifest_id, category, station_code, piece_count, unit_weight_kg, total_weight_kg, seq_no)
                VALUES (?,?,?,?,?,?,?)
                """;
        int seq = 0;
        for (LoadDetailRow d : details) {
            final int seqNo = d.seqNo() >= 0 ? d.seqNo() : seq;
            jdbc.sql(sql)
                    .params(List.of(manifestId, d.category(), d.stationCode(), d.pieces(),
                            d.unitWeightKg(), d.totalWeightKg(), seqNo))
                    .update();
            seq++;
        }
    }

    @Override
    public void deleteDetailsByManifest(long manifestId) {
        jdbc.sql("DELETE FROM load_detail WHERE manifest_id = :id").param("id", manifestId).update();
    }

    @Override
    public void supersede(long manifestId) {
        jdbc.sql("""
                UPDATE manifest_version
                SET active_flag = NULL, status = 'SUPERSEDED', updated_at = CURRENT_TIMESTAMP
                WHERE id = :id AND active_flag IS NOT NULL
                """).param("id", manifestId).update();
    }

    @Override
    public void updateComputed(ManifestRow r) {
        jdbc.sql("""
                UPDATE manifest_version SET
                  status = :status, passenger_count = :pax, payload_kg = :payload, fuel_kg = :fuel,
                  zfw_kg = :zfw, tow_kg = :tow, cg_zfw_pct = :cgz, cg_tow_pct = :cgt,
                  within_limits = :within, lmc_pax_delta = :lpax, lmc_weight_delta = :lweight,
                  updated_at = :updated
                WHERE id = :id
                """)
                .param("status", r.status())
                .param("pax", r.passengerCount())
                .param("payload", r.payloadKg())
                .param("fuel", r.fuelKg())
                .param("zfw", r.zfwKg())
                .param("tow", r.towKg())
                .param("cgz", r.cgZfwPct())
                .param("cgt", r.cgTowPct())
                .param("within", r.withinLimits())
                .param("lpax", r.lmcPaxDelta())
                .param("lweight", r.lmcWeightDelta())
                .param("updated", Timestamp.valueOf(r.updatedAt()))
                .param("id", r.id())
                .update();
    }

    @Override
    public void updateStatus(long manifestId, String status, java.time.LocalDateTime closedAt) {
        jdbc.sql("UPDATE manifest_version SET status = :status, closed_at = :closed, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("status", status)
                .param("closed", closedAt == null ? null : Timestamp.valueOf(closedAt))
                .param("id", manifestId)
                .update();
    }

    @Override
    public long insertLmcLog(LmcLogRow r) {
        KeyHolder kh = new GeneratedKeyHolder();
        String sql = """
                INSERT INTO lmc_log
                  (manifest_id, request_id, passenger_delta, weight_delta_kg, fuel_delta_kg,
                   cumulative_pax, cumulative_weight, within_lmc_limit, new_version_id, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """;
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setLong(i++, r.manifestId());
            ps.setString(i++, r.requestId());
            ps.setInt(i++, r.passengerDelta());
            ps.setBigDecimal(i++, r.weightDeltaKg());
            ps.setBigDecimal(i++, r.fuelDeltaKg());
            ps.setInt(i++, r.cumulativePax());
            ps.setBigDecimal(i++, r.cumulativeWeight());
            ps.setBoolean(i++, r.withinLmcLimit());
            setNullableLong(ps, i++, r.newVersionId());
            ps.setTimestamp(i, Timestamp.valueOf(r.createdAt()));
            return ps;
        }, kh);
        return kh.getKey() == null ? 0L : kh.getKey().longValue();
    }

    @Override
    public void linkLmcNewVersion(long lmcLogId, Long newVersionId) {
        jdbc.sql("UPDATE lmc_log SET new_version_id = :vid WHERE id = :id")
                .param("vid", newVersionId)
                .param("id", lmcLogId)
                .update();
    }

    @Override
    public long insertReview(ReviewRow r) {
        KeyHolder kh = new GeneratedKeyHolder();
        String sql = """
                INSERT INTO review_confirmation
                  (manifest_id, request_id, fingerprint, role_code, reviewer, created_at)
                VALUES (?,?,?,?,?,?)
                """;
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setLong(i++, r.manifestId());
            ps.setString(i++, r.requestId());
            ps.setString(i++, r.fingerprint());
            ps.setString(i++, r.roleCode());
            ps.setString(i++, r.reviewer());
            ps.setTimestamp(i, Timestamp.valueOf(r.createdAt()));
            return ps;
        }, kh);
        return kh.getKey() == null ? 0L : kh.getKey().longValue();
    }

    @Override
    public long insertLateEvent(LateEventRow r) {
        KeyHolder kh = new GeneratedKeyHolder();
        String sql = """
                INSERT INTO late_event
                  (flight_id, manifest_id, passenger_count, weight_kg, payload_json, alerted, created_at)
                VALUES (?,?,?,?,?,?,?)
                """;
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setLong(i++, r.flightId());
            ps.setLong(i++, r.manifestId());
            ps.setInt(i++, r.passengerCount());
            ps.setBigDecimal(i++, r.weightKg());
            ps.setString(i++, r.payloadJson());
            ps.setBoolean(i++, r.alerted());
            ps.setTimestamp(i, Timestamp.valueOf(r.createdAt()));
            return ps;
        }, kh);
        return kh.getKey() == null ? 0L : kh.getKey().longValue();
    }

    @Override
    public Optional<IdempotentRow> findIdempotent(String requestId) {
        return jdbc.sql("SELECT request_id, fingerprint, http_status, result_json, created_at FROM idempotent_request WHERE request_id = :id")
                .param("id", requestId)
                .query((rs, n) -> new IdempotentRow(
                        rs.getString("request_id"), rs.getString("fingerprint"),
                        rs.getInt("http_status"), rs.getString("result_json"),
                        rs.getTimestamp("created_at").toLocalDateTime()))
                .optional();
    }

    @Override
    public void insertIdempotent(IdempotentRow r) {
        jdbc.sql("""
                INSERT INTO idempotent_request (request_id, fingerprint, http_status, result_json, created_at)
                VALUES (?,?,?,?,?)
                """)
                .params(List.of(r.requestId(), r.fingerprint(), r.httpStatus(),
                        r.resultJson(), Timestamp.valueOf(r.createdAt())))
                .update();
    }

    // ------------------------------------------------------------- 集装器装配图

    @Override
    public List<CompartmentRow> findCompartments(long aircraftId) {
        return jdbc.sql("""
                        SELECT id, aircraft_id, compartment_code, arm_mm, max_load_kg, allowed_uld_types
                        FROM cargo_compartment WHERE aircraft_id = :id ORDER BY id
                        """)
                .param("id", aircraftId)
                .query((rs, n) -> new CompartmentRow(
                        rs.getLong("id"), rs.getLong("aircraft_id"),
                        rs.getString("compartment_code"), rs.getInt("arm_mm"),
                        rs.getInt("max_load_kg"),
                        Arrays.stream(rs.getString("allowed_uld_types").split(","))
                                .map(String::trim).filter(s -> !s.isEmpty()).toList()))
                .list();
    }

    @Override
    public List<CargoPlanRow> findPlansByFlight(long flightId) {
        return jdbc.sql("""
                        SELECT p.* FROM cargo_loading_plan p
                        WHERE p.flight_id = :fid
                        ORDER BY p.plan_version
                        """)
                .param("fid", flightId).query(PLAN_MAPPER).list();
    }

    @Override
    public Optional<CargoPlanRow> findPlanById(long planId) {
        return jdbc.sql("SELECT * FROM cargo_loading_plan WHERE id = :id")
                .param("id", planId).query(PLAN_MAPPER).optional();
    }

    @Override
    public List<CargoItemRow> findPlanItems(long planId) {
        return jdbc.sql("""
                        SELECT id, plan_id, seq_no, uld_no, compartment_code, arm_mm, tare_kg, net_kg, gross_kg
                        FROM cargo_loading_item WHERE plan_id = :id ORDER BY seq_no, id
                        """)
                .param("id", planId)
                .query((rs, n) -> new CargoItemRow(
                        rs.getLong("id"), rs.getLong("plan_id"), rs.getInt("seq_no"),
                        rs.getString("uld_no"), rs.getString("compartment_code"),
                        rs.getInt("arm_mm"), rs.getInt("tare_kg"), rs.getInt("net_kg"),
                        rs.getInt("gross_kg")))
                .list();
    }

    @Override
    public List<CargoOccupancyRow> findActiveOccupanciesByUldNos(Collection<String> uldNos) {
        if (uldNos.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT * FROM cargo_uld_occupancy
                        WHERE active_flag = 1 AND uld_no IN (:uldNos)
                        """)
                .param("uldNos", List.copyOf(uldNos))
                .query(OCCUPANCY_MAPPER).list();
    }

    @Override
    public long insertPlan(CargoPlanRow r) {
        KeyHolder kh = new GeneratedKeyHolder();
        String sql = """
                INSERT INTO cargo_loading_plan
                  (flight_id, manifest_id, manifest_version_no, plan_version, request_id, fingerprint,
                   cargo_net_kg, cargo_gross_kg, total_moment_kg_mm, fuel_kg, lmc_pax_delta,
                   lmc_weight_delta, cg_zfw_pct, cg_tow_pct, within_limits, created_by,
                   occurred_at, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setLong(i++, r.flightId());
            ps.setLong(i++, r.manifestId());
            ps.setInt(i++, r.manifestVersionNo());
            ps.setInt(i++, r.planVersion());
            ps.setString(i++, r.requestId());
            ps.setString(i++, r.fingerprint());
            ps.setInt(i++, r.cargoNetKg());
            ps.setInt(i++, r.cargoGrossKg());
            ps.setLong(i++, r.totalMomentKgMm());
            ps.setBigDecimal(i++, r.fuelKg());
            ps.setInt(i++, r.lmcPaxDelta());
            ps.setBigDecimal(i++, r.lmcWeightDelta());
            ps.setBigDecimal(i++, r.cgZfwPct());
            ps.setBigDecimal(i++, r.cgTowPct());
            ps.setBoolean(i++, r.withinLimits());
            ps.setString(i++, r.createdBy());
            ps.setTimestamp(i++, Timestamp.valueOf(r.occurredAt()));
            ps.setTimestamp(i, Timestamp.valueOf(r.createdAt()));
            return ps;
        }, kh);
        return kh.getKey() == null ? 0L : kh.getKey().longValue();
    }

    @Override
    public void insertPlanItems(long planId, List<CargoItemRow> items) {
        String sql = """
                INSERT INTO cargo_loading_item
                  (plan_id, seq_no, uld_no, compartment_code, arm_mm, tare_kg, net_kg, gross_kg)
                VALUES (?,?,?,?,?,?,?,?)
                """;
        for (CargoItemRow it : items) {
            jdbc.sql(sql).params(List.of(planId, it.seqNo(), it.uldNo(), it.compartmentCode(),
                    it.armMm(), it.tareKg(), it.netKg(), it.grossKg())).update();
        }
    }

    @Override
    public long insertOccupancy(CargoOccupancyRow r) {
        KeyHolder kh = new GeneratedKeyHolder();
        String sql = """
                INSERT INTO cargo_uld_occupancy
                  (uld_no, flight_id, manifest_id, plan_id, active_flag, occupied_at, released_at)
                VALUES (?,?,?,?,1,?,?)
                """;
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, r.uldNo());
            ps.setLong(i++, r.flightId());
            ps.setLong(i++, r.manifestId());
            ps.setLong(i++, r.planId());
            ps.setTimestamp(i++, Timestamp.valueOf(r.occupiedAt()));
            if (r.releasedAt() == null) {
                ps.setNull(i, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(i, Timestamp.valueOf(r.releasedAt()));
            }
            return ps;
        }, kh);
        return kh.getKey() == null ? 0L : kh.getKey().longValue();
    }

    @Override
    public int releaseOccupanciesByFlight(long flightId, java.time.LocalDateTime releasedAt) {
        return jdbc.sql("""
                UPDATE cargo_uld_occupancy
                SET active_flag = NULL, released_at = :ts
                WHERE flight_id = :fid AND active_flag = 1
                """)
                .param("fid", flightId)
                .param("ts", Timestamp.valueOf(releasedAt))
                .update();
    }

    @Override
    public int deleteReviewsByManifest(long manifestId) {
        return jdbc.sql("DELETE FROM review_confirmation WHERE manifest_id = :id")
                .param("id", manifestId).update();
    }

    private static final RowMapper<CargoPlanRow> PLAN_MAPPER = (rs, n) -> new CargoPlanRow(
            rs.getLong("id"),
            rs.getLong("flight_id"),
            rs.getLong("manifest_id"),
            rs.getInt("manifest_version_no"),
            rs.getInt("plan_version"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getInt("cargo_net_kg"),
            rs.getInt("cargo_gross_kg"),
            rs.getLong("total_moment_kg_mm"),
            rs.getBigDecimal("fuel_kg"),
            rs.getInt("lmc_pax_delta"),
            rs.getBigDecimal("lmc_weight_delta"),
            rs.getBigDecimal("cg_zfw_pct"),
            rs.getBigDecimal("cg_tow_pct"),
            rs.getBoolean("within_limits"),
            rs.getString("created_by"),
            rs.getTimestamp("occurred_at").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private static final RowMapper<CargoOccupancyRow> OCCUPANCY_MAPPER = (rs, n) ->
            new CargoOccupancyRow(
                    rs.getLong("id"),
                    rs.getString("uld_no"),
                    rs.getLong("flight_id"),
                    rs.getLong("manifest_id"),
                    rs.getLong("plan_id"),
                    (Long) rs.getObject("active_flag"),
                    rs.getTimestamp("occupied_at").toLocalDateTime(),
                    rs.getTimestamp("released_at") == null ? null
                            : rs.getTimestamp("released_at").toLocalDateTime()
            );

    private static void setNullableLong(PreparedStatement ps, int idx, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(idx, Types.BIGINT);
        } else {
            ps.setLong(idx, value);
        }
    }

    private static final RowMapper<ManifestRow> MANIFEST_MAPPER = (rs, n) -> new ManifestRow(
            rs.getLong("id"),
            rs.getLong("flight_id"),
            rs.getInt("version_no"),
            (Long) rs.getObject("parent_version_id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("status"),
            rs.getInt("passenger_count"),
            rs.getBigDecimal("payload_kg"),
            rs.getBigDecimal("fuel_kg"),
            rs.getBigDecimal("zfw_kg"),
            rs.getBigDecimal("tow_kg"),
            rs.getBigDecimal("cg_zfw_pct"),
            rs.getBigDecimal("cg_tow_pct"),
            rs.getBoolean("within_limits"),
            rs.getInt("lmc_pax_delta"),
            rs.getBigDecimal("lmc_weight_delta"),
            (Long) rs.getObject("active_flag"),
            rs.getString("created_by"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime(),
            rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toLocalDateTime()
    );
}
