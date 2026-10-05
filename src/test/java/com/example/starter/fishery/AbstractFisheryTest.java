package com.example.starter.fishery;

import com.example.starter.fishery.api.dto.DepartureRequest;
import com.example.starter.fishery.api.dto.LandingRequest;
import com.example.starter.fishery.api.dto.LogbookRequest;
import com.example.starter.fishery.api.dto.SettlementRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 测试数据与流程辅助：每个场景使用独立渔船与配额，互不干扰；仅合成数据。
 */
public abstract class AbstractFisheryTest {

    protected static final Instant T0 = Instant.parse("2026-03-14T00:00:00Z");

    @Autowired
    protected JdbcTemplate jdbc;

    private final List<String> registeredVessels = new ArrayList<>();
    private final List<String> requestIds = new ArrayList<>();

    protected String newRequestId() {
        String id = "TEST-" + UUID.randomUUID();
        requestIds.add(id);
        return id;
    }

    /** 注册合成渔船及其当前年度分鱼种配额（千克）。 */
    protected String registerVessel(String vesselCode, java.util.Map<String, BigDecimal> quotas) {
        jdbc.update("INSERT INTO vessel(code, name, created_at) VALUES (?, ?, ?)",
                vesselCode, "测试船-" + vesselCode, java.sql.Timestamp.from(T0));
        int year = T0.atZone(java.time.ZoneOffset.UTC).getYear();
        quotas.forEach((species, quota) ->
                jdbc.update("INSERT INTO quota(vessel_code, species_code, quota_year, quota_kg, used_kg, version) "
                        + "VALUES (?, ?, ?, ?, 0, 0)", vesselCode, species, year, quota));
        registeredVessels.add(vesselCode);
        return vesselCode;
    }

    /** 为已注册渔船补登指定年度配额（千克）。 */
    protected void addQuota(String vesselCode, String species, int year, String quotaKg) {
        jdbc.update("INSERT INTO quota(vessel_code, species_code, quota_year, quota_kg, used_kg, version) "
                + "VALUES (?, ?, ?, ?, 0, 0)", vesselCode, species, year, new BigDecimal(quotaKg));
    }

    protected String registerVesselWithQuota(String species, String quota) {
        String vessel = "VT-" + UUID.randomUUID().toString().substring(0, 8);
        return registerVessel(vessel, java.util.Map.of(species, new BigDecimal(quota)));
    }

    protected DepartureRequest departureRequest(String tripCode, String vessel, Instant departAt,
                                                String species, String kg) {
        return new DepartureRequest(newRequestId(), tripCode, vessel, departAt,
                List.of(new DepartureRequest.SpeciesWeight(species, new BigDecimal(kg))));
    }

    protected DepartureRequest departureRequestMulti(String tripCode, String vessel, Instant departAt,
                                                     List<DepartureRequest.SpeciesWeight> lines) {
        return new DepartureRequest(newRequestId(), tripCode, vessel, departAt, lines);
    }

    protected LogbookRequest logbookRequest(String tripCode, String zone, Instant fishingAt,
                                            List<LogbookRequest.SpeciesLine> lines) {
        return new LogbookRequest(newRequestId(), tripCode, zone, fishingAt, lines);
    }

    protected LogbookRequest.SpeciesLine catchLine(String species, String kg) {
        return new LogbookRequest.SpeciesLine(species, new BigDecimal(kg));
    }

    protected LandingRequest landingRequest(String tripCode, Instant landedAt,
                                            List<LandingRequest.SpeciesLine> lines, String explanation) {
        return new LandingRequest(newRequestId(), tripCode, landedAt, lines, explanation);
    }

    protected LandingRequest.SpeciesLine landLine(String species, String kg) {
        return new LandingRequest.SpeciesLine(species, new BigDecimal(kg));
    }

    protected SettlementRequest settlementRequest(String tripCode) {
        return new SettlementRequest(newRequestId(), tripCode);
    }

    /** 标准航次：出港→一份日志（单鱼种）→进港（卸渔量=日志量）。 */
    protected void runTripToDischarged(com.example.starter.fishery.service.FisheryService service,
                                       String vessel, String tripCode, String species, String kg) {
        runTripToDischarged(service, vessel, tripCode, species, kg, "Z-" + tripCode, T0, T0.plusSeconds(3600),
                T0.plusSeconds(7200));
    }

    /** 标准航次（可指定渔区与时刻，避免同船多航次船＋日期＋渔区去重）。 */
    protected void runTripToDischarged(com.example.starter.fishery.service.FisheryService service,
                                       String vessel, String tripCode, String species, String kg,
                                       String zone, Instant departAt, Instant fishingAt, Instant landedAt) {
        service.declareDeparture(departureRequest(tripCode, vessel, departAt, species, kg));
        service.submitLogbook(logbookRequest(tripCode, zone, fishingAt,
                List.of(catchLine(species, kg))));
        service.reportLanding(landingRequest(tripCode, landedAt,
                List.of(landLine(species, kg)), null));
    }

    protected BigDecimal usedQuota(String vessel, String species) {
        int year = T0.atZone(java.time.ZoneOffset.UTC).getYear();
        BigDecimal used = jdbc.queryForObject(
                "SELECT used_kg FROM quota WHERE vessel_code = ? AND species_code = ? AND quota_year = ?",
                BigDecimal.class, vessel, species, year);
        return used == null ? null : used.stripTrailingZeros();
    }

    protected long countSettlements(String tripCode) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM settlement WHERE trip_code = ?", Long.class, tripCode);
        return count == null ? 0L : count;
    }

    protected String tripStatus(String tripCode) {
        return jdbc.queryForObject("SELECT status FROM trip WHERE trip_code = ?", String.class, tripCode);
    }

    protected boolean tripFrozen(String tripCode) {
        Boolean frozen = jdbc.queryForObject(
                "SELECT frozen FROM trip WHERE trip_code = ?", Boolean.class, tripCode);
        return Boolean.TRUE.equals(frozen);
    }

    protected void cleanup() {
        if (!registeredVessels.isEmpty()) {
            String in = String.join("','", registeredVessels);
            String vesselExpr = "'" + in + "'";
            String tripExpr = "SELECT trip_code FROM trip WHERE vessel_code IN (" + vesselExpr + ")";
            jdbc.update("DELETE FROM carryover_settlement WHERE vessel_code IN (" + vesselExpr + ")");
            jdbc.update("DELETE FROM quota_carryover WHERE vessel_code IN (" + vesselExpr + ")");
            jdbc.update("DELETE FROM origin_alert WHERE vessel_code IN (" + vesselExpr + ")");
            jdbc.update("DELETE FROM transfer_active_guard WHERE vessel_code IN (" + vesselExpr + ")");
            jdbc.update("DELETE FROM quota_transfer WHERE from_vessel_code IN (" + vesselExpr
                    + ") OR to_vessel_code IN (" + vesselExpr + ")");
            jdbc.update("DELETE FROM settlement WHERE vessel_code IN (" + vesselExpr + ")");
            jdbc.update("DELETE FROM review_case WHERE vessel_code IN (" + vesselExpr + ")");
            jdbc.update("DELETE FROM late_log_event WHERE vessel_code IN (" + vesselExpr + ")");
            jdbc.update("DELETE FROM logbook_line WHERE logbook_id IN ("
                    + "SELECT id FROM logbook WHERE vessel_code IN (" + vesselExpr + "))");
            jdbc.update("DELETE FROM logbook WHERE vessel_code IN (" + vesselExpr + ")");
            jdbc.update("DELETE FROM trip_catch_line WHERE trip_code IN (" + tripExpr + ")");
            jdbc.update("DELETE FROM departure_line WHERE trip_code IN (" + tripExpr + ")");
            jdbc.update("DELETE FROM landing_line WHERE landing_id IN ("
                    + "SELECT id FROM landing WHERE trip_code IN (" + tripExpr + "))");
            jdbc.update("DELETE FROM landing WHERE trip_code IN (" + tripExpr + ")");
            jdbc.update("DELETE FROM vessel_active_trip WHERE vessel_code IN (" + vesselExpr + ")");
            jdbc.update("DELETE FROM trip WHERE vessel_code IN (" + vesselExpr + ")");
            jdbc.update("DELETE FROM quota WHERE vessel_code IN (" + vesselExpr + ")");
            jdbc.update("DELETE FROM vessel WHERE code IN (" + vesselExpr + ")");
        }
        if (!requestIds.isEmpty()) {
            String in = String.join("','", requestIds);
            jdbc.update("DELETE FROM idempotency_record WHERE request_id IN ('" + in + "')");
        }
        registeredVessels.clear();
        requestIds.clear();
    }
}
