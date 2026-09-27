package com.example.starter.ride;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.ride.dto.RideDtos.CheckItem;

/**
 * 年度检验证书与大修后监督检验仓储。
 * 证书历史不可覆盖：仅插入与撤销标记更新；换证通过 ride.current_certificate_id 指针原子切换。
 */
@Repository
public class CertificateRepository {

    /** 年度检验证书记录（合成数据）。 */
    public record Certificate(
            long id,
            long rideId,
            String requestId,
            Instant passedAt,
            Instant validUntil,
            boolean revoked,
            String revokeReason,
            Instant revokedAt) {
    }

    private static final RowMapper<Certificate> MAPPER = (rs, rowNum) -> map(rs);

    private final JdbcTemplate jdbc;

    public CertificateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Certificate map(ResultSet rs) throws SQLException {
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new Certificate(
                rs.getLong("id"),
                rs.getLong("ride_id"),
                rs.getString("request_id"),
                rs.getTimestamp("passed_at").toInstant(),
                rs.getTimestamp("valid_until").toInstant(),
                rs.getBoolean("revoked"),
                rs.getString("revoke_reason"),
                revokedAt == null ? null : revokedAt.toInstant());
    }

    /** 登记新证书（历史证书不可覆盖，仅追加），返回新证书ID。 */
    public long insertCertificate(long rideId, String requestId,
            Instant passedAt, Instant validUntil) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO annual_certificate (ride_id, request_id, passed_at, valid_until)"
                            + " VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, rideId);
            ps.setString(2, requestId);
            ps.setTimestamp(3, Timestamp.from(passedAt));
            ps.setTimestamp(4, Timestamp.from(validUntil));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Certificate> findCertificate(long certificateId) {
        return jdbc.query("SELECT * FROM annual_certificate WHERE id = ?",
                MAPPER, certificateId).stream().findFirst();
    }

    /** 撤销证书：仅未撤销时写入撤销标记，返回是否本次撤销生效。 */
    public boolean revokeCertificate(long certificateId, String reason, Instant revokedAt) {
        int rows = jdbc.update(
                "UPDATE annual_certificate SET revoked = TRUE, revoke_reason = ?, revoked_at = ?"
                        + " WHERE id = ? AND revoked = FALSE",
                reason, Timestamp.from(revokedAt), certificateId);
        return rows == 1;
    }

    /** 证书列表（含历史与撤销信息），按登记顺序。 */
    public List<Map<String, Object>> listCertificates(long rideId) {
        return jdbc.queryForList(
                "SELECT id, request_id, passed_at, valid_until, revoked, revoke_reason,"
                        + " revoked_at, created_at"
                        + " FROM annual_certificate WHERE ride_id = ? ORDER BY id", rideId);
    }

    /** 登记大修后监督检验及清单明细，返回记录ID。 */
    public long insertOverhaulInspection(long rideId, String requestId, int overhaulVersion,
            String inspectorOne, String inspectorTwo, boolean allPassed, List<CheckItem> items) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO overhaul_inspection"
                            + " (ride_id, request_id, overhaul_version, inspector_one,"
                            + " inspector_two, all_passed) VALUES (?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, rideId);
            ps.setString(2, requestId);
            ps.setInt(3, overhaulVersion);
            ps.setString(4, inspectorOne);
            ps.setString(5, inspectorTwo);
            ps.setBoolean(6, allPassed);
            return ps;
        }, keyHolder);
        long inspectionId = keyHolder.getKey().longValue();
        for (CheckItem item : items) {
            jdbc.update("INSERT INTO overhaul_inspection_item (inspection_id, item_name, passed)"
                    + " VALUES (?, ?, ?)", inspectionId, item.itemName(), item.passed());
        }
        return inspectionId;
    }

    /** 是否存在绑定指定大修版本且全清单合格的监督检验确认。 */
    public boolean existsConfirmedInspection(long rideId, int overhaulVersion) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM overhaul_inspection"
                        + " WHERE ride_id = ? AND overhaul_version = ? AND all_passed = TRUE",
                Integer.class, rideId, overhaulVersion);
        return count != null && count > 0;
    }

    /** 监督检验记录列表（含明细），可复算。 */
    public List<Map<String, Object>> listOverhaulInspections(long rideId) {
        List<Map<String, Object>> inspections = jdbc.queryForList(
                "SELECT id, request_id, overhaul_version, inspector_one, inspector_two,"
                        + " all_passed, created_at"
                        + " FROM overhaul_inspection WHERE ride_id = ? ORDER BY id", rideId);
        for (Map<String, Object> inspection : inspections) {
            long inspectionId = ((Number) inspection.get("id")).longValue();
            inspection.put("items", jdbc.queryForList(
                    "SELECT item_name, passed FROM overhaul_inspection_item"
                            + " WHERE inspection_id = ? ORDER BY id", inspectionId));
        }
        return inspections;
    }
}
