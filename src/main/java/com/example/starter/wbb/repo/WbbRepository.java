package com.example.starter.wbb.repo;

import com.example.starter.wbb.po.AircraftConfig;
import com.example.starter.wbb.po.EnvelopePointPo;
import com.example.starter.wbb.po.IdempotentRequestPo;
import com.example.starter.wbb.po.LateEventPo;
import com.example.starter.wbb.po.ManifestPo;
import com.example.starter.wbb.po.PayloadItemPo;
import com.example.starter.wbb.po.ReviewPo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 载重平衡全部表的 JDBC 仓储；参数化 SQL，无字符串拼接。
 */
@Repository
public class WbbRepository {

    private final JdbcTemplate jdbc;

    public WbbRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 机型与包线 ----------------

    private static final RowMapper<AircraftConfig> CONFIG_MAPPER = (rs, n) -> new AircraftConfig(
            rs.getLong("id"),
            rs.getString("aircraft_type"),
            rs.getBigDecimal("max_zero_fuel_weight_kg"),
            rs.getBigDecimal("operating_empty_weight_kg"),
            rs.getBigDecimal("operating_empty_moment"),
            rs.getBigDecimal("default_fuel_arm"));

    public Optional<AircraftConfig> findConfig(String aircraftType) {
        return jdbc.query("SELECT * FROM aircraft_config WHERE aircraft_type = ?", CONFIG_MAPPER, aircraftType)
                .stream().findFirst();
    }

    public long insertConfig(AircraftConfig config) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO aircraft_config (aircraft_type, max_zero_fuel_weight_kg, "
                            + "operating_empty_weight_kg, operating_empty_moment, default_fuel_arm) "
                            + "VALUES (?,?,?,?,?)",
                    new String[]{"id"});
            ps.setString(1, config.aircraftType());
            ps.setBigDecimal(2, config.maxZeroFuelWeightKg());
            ps.setBigDecimal(3, config.operatingEmptyWeightKg());
            ps.setBigDecimal(4, config.operatingEmptyMoment());
            ps.setBigDecimal(5, config.defaultFuelArm());
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public List<EnvelopePointPo> findEnvelopePoints(long configId) {
        return jdbc.query(
                "SELECT * FROM envelope_point WHERE config_id = ? ORDER BY seq_no",
                (rs, n) -> new EnvelopePointPo(
                        rs.getLong("id"), rs.getLong("config_id"), rs.getInt("seq_no"),
                        rs.getBigDecimal("weight_kg"), rs.getBigDecimal("forward_arm"),
                        rs.getBigDecimal("aft_arm")),
                configId);
    }

    public void insertEnvelopePoint(long configId, int seqNo, BigDecimal weight,
                                    BigDecimal forwardArm, BigDecimal aftArm) {
        jdbc.update("INSERT INTO envelope_point (config_id, seq_no, weight_kg, forward_arm, aft_arm) "
                + "VALUES (?,?,?,?,?)", configId, seqNo, weight, forwardArm, aftArm);
    }

    // ---------------- 舱单 ----------------

    private static final RowMapper<ManifestPo> MANIFEST_MAPPER = (rs, n) -> {
        Timestamp closed = rs.getTimestamp("closed_at");
        return new ManifestPo(
                rs.getLong("id"),
                rs.getString("flight_key"),
                rs.getString("flight_no"),
                rs.getDate("flight_date").toLocalDate(),
                rs.getString("aircraft_type"),
                rs.getString("aircraft_registration"),
                rs.getInt("version_no"),
                rs.getString("status"),
                (Long) rs.getObject("parent_id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getBigDecimal("fuel_kg"),
                rs.getBigDecimal("fuel_arm"),
                rs.getBigDecimal("fuel_moment"),
                rs.getInt("passenger_count"),
                rs.getBigDecimal("payload_kg"),
                rs.getBigDecimal("payload_moment"),
                rs.getBigDecimal("zero_fuel_weight_kg"),
                rs.getBigDecimal("zfw_moment"),
                rs.getBigDecimal("zfw_cg_arm"),
                rs.getBigDecimal("takeoff_weight_kg"),
                rs.getBigDecimal("tow_moment"),
                rs.getBigDecimal("tow_cg_arm"),
                rs.getInt("lmc_delta_pax"),
                rs.getBigDecimal("lmc_delta_kg"),
                rs.getBoolean("frozen"),
                closed == null ? null : closed.toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    };

    public long insertManifest(ManifestPo m) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO manifest (flight_key, flight_no, flight_date, aircraft_type, "
                            + "aircraft_registration, version_no, status, parent_id, request_id, fingerprint, "
                            + "fuel_kg, fuel_arm, fuel_moment, passenger_count, payload_kg, payload_moment, "
                            + "zero_fuel_weight_kg, zfw_moment, zfw_cg_arm, takeoff_weight_kg, tow_moment, tow_cg_arm, "
                            + "lmc_delta_pax, lmc_delta_kg, frozen, closed_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    new String[]{"id"});
            int i = 1;
            ps.setString(i++, m.flightKey());
            ps.setString(i++, m.flightNo());
            ps.setDate(i++, Date.valueOf(m.flightDate()));
            ps.setString(i++, m.aircraftType());
            ps.setString(i++, m.aircraftRegistration());
            ps.setInt(i++, m.versionNo());
            ps.setString(i++, m.status());
            if (m.parentId() == null) {
                ps.setNull(i++, java.sql.Types.BIGINT);
            } else {
                ps.setLong(i++, m.parentId());
            }
            ps.setString(i++, m.requestId());
            ps.setString(i++, m.fingerprint());
            ps.setBigDecimal(i++, m.fuelKg());
            ps.setBigDecimal(i++, m.fuelArm());
            ps.setBigDecimal(i++, m.fuelMoment());
            ps.setInt(i++, m.passengerCount());
            ps.setBigDecimal(i++, m.payloadKg());
            ps.setBigDecimal(i++, m.payloadMoment());
            ps.setBigDecimal(i++, m.zeroFuelWeightKg());
            ps.setBigDecimal(i++, m.zfwMoment());
            ps.setBigDecimal(i++, m.zfwCgArm());
            ps.setBigDecimal(i++, m.takeoffWeightKg());
            ps.setBigDecimal(i++, m.towMoment());
            ps.setBigDecimal(i++, m.towCgArm());
            ps.setInt(i++, m.lmcDeltaPax());
            ps.setBigDecimal(i++, m.lmcDeltaKg());
            ps.setBoolean(i++, m.frozen());
            if (m.closedAt() == null) {
                ps.setNull(i, java.sql.Types.TIMESTAMP);
            } else {
                ps.setTimestamp(i, Timestamp.valueOf(m.closedAt()));
            }
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public Optional<ManifestPo> findManifest(long id) {
        return jdbc.query("SELECT * FROM manifest WHERE id = ?", MANIFEST_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<Long> findActiveManifestId(String flightKey) {
        return jdbc.query("SELECT manifest_id FROM flight_active_manifest WHERE flight_key = ?",
                (rs, n) -> rs.getLong(1), flightKey).stream().findFirst();
    }

    /**
     * 行锁航班有效指针，供事务内串行化同航班并发操作；新航班尚无指针行时返回空。
     */
    public Optional<Long> lockActiveManifestId(String flightKey) {
        return jdbc.query("SELECT manifest_id FROM flight_active_manifest WHERE flight_key = ? FOR UPDATE",
                (rs, n) -> rs.getLong(1), flightKey).stream().findFirst();
    }

    /**
     * 建立航班首个有效版本指针；并发时唯一约束保证只成功一个。
     */
    public void insertActiveManifest(String flightKey, long manifestId, int versionNo) {
        jdbc.update("INSERT INTO flight_active_manifest (flight_key, manifest_id, version_no) VALUES (?,?,?)",
                flightKey, manifestId, versionNo);
    }

    public void updateActiveManifest(String flightKey, long manifestId, int versionNo) {
        jdbc.update("UPDATE flight_active_manifest SET manifest_id = ?, version_no = ? WHERE flight_key = ?",
                manifestId, versionNo, flightKey);
    }

    /**
     * 全量更新舱单可变态（状态、重量平衡结果、LMC 累计、冻结、关闭时间）。
     */
    public void updateComputedManifest(ManifestPo m) {
        jdbc.update("UPDATE manifest SET status = ?, fuel_kg = ?, fuel_arm = ?, fuel_moment = ?, "
                        + "passenger_count = ?, payload_kg = ?, payload_moment = ?, zero_fuel_weight_kg = ?, "
                        + "zfw_moment = ?, zfw_cg_arm = ?, takeoff_weight_kg = ?, tow_moment = ?, tow_cg_arm = ?, "
                        + "lmc_delta_pax = ?, lmc_delta_kg = ?, frozen = ?, closed_at = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                m.status(), m.fuelKg(), m.fuelArm(), m.fuelMoment(), m.passengerCount(),
                m.payloadKg(), m.payloadMoment(), m.zeroFuelWeightKg(), m.zfwMoment(), m.zfwCgArm(),
                m.takeoffWeightKg(), m.towMoment(), m.towCgArm(), m.lmcDeltaPax(), m.lmcDeltaKg(),
                m.frozen(), m.closedAt() == null ? null : Timestamp.valueOf(m.closedAt()), m.id());
    }

    public void updateStatus(long manifestId, String status) {
        jdbc.update("UPDATE manifest SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                status, manifestId);
    }

    public int countActiveVersions(String flightKey) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM manifest WHERE flight_key = ? AND status NOT IN ('SUPERSEDED')",
                Integer.class, flightKey);
        return c == null ? 0 : c;
    }

    public List<ManifestPo> findVersionChain(String flightKey) {
        return jdbc.query("SELECT * FROM manifest WHERE flight_key = ? ORDER BY version_no", MANIFEST_MAPPER,
                flightKey);
    }

    // ---------------- 业载明细 ----------------

    public void insertPayloadItem(long manifestId, PayloadItemPo item) {
        jdbc.update("INSERT INTO payload_item (manifest_id, category, zone_code, piece_count, weight_kg, arm, moment) "
                + "VALUES (?,?,?,?,?,?,?)",
                manifestId, item.category(), item.zoneCode(), item.pieceCount(),
                item.weightKg(), item.arm(), item.moment());
    }

    public void deletePayloadItems(long manifestId) {
        jdbc.update("DELETE FROM payload_item WHERE manifest_id = ?", manifestId);
    }

    public List<PayloadItemPo> findPayloadItems(long manifestId) {
        return jdbc.query("SELECT * FROM payload_item WHERE manifest_id = ? ORDER BY id",
                (rs, n) -> new PayloadItemPo(
                        rs.getLong("id"), rs.getLong("manifest_id"), rs.getString("category"),
                        rs.getString("zone_code"), rs.getInt("piece_count"), rs.getBigDecimal("weight_kg"),
                        rs.getBigDecimal("arm"), rs.getBigDecimal("moment")),
                manifestId);
    }

    // ---------------- 复核 ----------------

    public void insertReview(ReviewPo review) {
        jdbc.update("INSERT INTO manifest_review (manifest_id, review_role, reviewer, request_id, fingerprint) "
                + "VALUES (?,?,?,?,?)",
                review.manifestId(), review.reviewRole(), review.reviewer(),
                review.requestId(), review.fingerprint());
    }

    public List<ReviewPo> findReviews(long manifestId) {
        return jdbc.query("SELECT * FROM manifest_review WHERE manifest_id = ? ORDER BY id",
                (rs, n) -> new ReviewPo(rs.getLong("id"), rs.getLong("manifest_id"),
                        rs.getString("review_role"), rs.getString("reviewer"),
                        rs.getString("request_id"), rs.getString("fingerprint"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                manifestId);
    }

    // ---------------- 幂等记录 ----------------

    /**
     * 业务事务提交前回填首次结果（舱单版本 id 与结果码）。
     */
    public void updateIdempotentOutcome(String requestId, Long manifestId, String outcome) {
        jdbc.update("UPDATE idempotent_request SET manifest_id = ?, outcome = ? WHERE request_id = ?",
                manifestId, outcome, requestId);
    }

    /**
     * 行锁读取幂等记录：同 requestId 并发请求在此串行，后到者可读到首笔已提交结果。
     */
    public Optional<IdempotentRequestPo> lockIdempotent(String requestId) {
        return jdbc.query("SELECT * FROM idempotent_request WHERE request_id = ? FOR UPDATE",
                (rs, n) -> new IdempotentRequestPo(
                        rs.getString("request_id"), rs.getString("biz_type"), rs.getString("fingerprint"),
                        (Long) rs.getObject("manifest_id"), rs.getString("outcome"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                requestId).stream().findFirst();
    }

    /**
     * 插入 PENDING 占位：并发同键时唯一约束只允许一笔成功。
     */
    public boolean tryInsertPendingIdempotent(String requestId, String bizType, String fingerprint) {
        try {
            jdbc.update("INSERT INTO idempotent_request (request_id, biz_type, fingerprint, manifest_id, outcome) "
                            + "VALUES (?,?,?,NULL,'PENDING')",
                    requestId, bizType, fingerprint);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    // ---------------- 迟到事件 ----------------

    public long insertLateEvent(String flightKey, long manifestId, String eventType, String detail) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO late_event (flight_key, manifest_id, event_type, detail, alarmed) "
                            + "VALUES (?,?,?,?,TRUE)", new String[]{"id"});
            ps.setString(1, flightKey);
            ps.setLong(2, manifestId);
            ps.setString(3, eventType);
            ps.setString(4, detail);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public List<LateEventPo> findLateEvents(String flightKey) {
        return jdbc.query("SELECT * FROM late_event WHERE flight_key = ? ORDER BY id",
                (rs, n) -> new LateEventPo(rs.getLong("id"), rs.getString("flight_key"),
                        rs.getLong("manifest_id"), rs.getString("event_type"), rs.getString("detail"),
                        rs.getBoolean("alarmed"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                flightKey);
    }
}
