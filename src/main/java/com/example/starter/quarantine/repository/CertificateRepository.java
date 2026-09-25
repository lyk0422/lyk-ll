package com.example.starter.quarantine.repository;

import com.example.starter.quarantine.domain.AnimalType;
import com.example.starter.quarantine.domain.CertificatePo;
import com.example.starter.quarantine.domain.CertificateStatus;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 检疫合格证仓储。有效证的“养殖场+耳标段”唯一由 uq_cert_active_slot 约束保证。
 */
@Repository
public class CertificateRepository {

    private static final String COLUMNS =
            "id, certificate_no, request_id, farm_id, animal_type, ear_tag_range, quantity, issued_at,"
                    + " expires_at, status, quarantine_flag, active_slot, void_reason, created_at, updated_at";

    private static final RowMapper<CertificatePo> ROW_MAPPER = CertificateRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public CertificateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 插入证并回填自增主键；active_slot 唯一冲突抛出 DuplicateKeyException。 */
    public void insert(CertificatePo po) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO quarantine_certificate (certificate_no, request_id, farm_id, animal_type,"
                            + " ear_tag_range, quantity, issued_at, expires_at, status, quarantine_flag,"
                            + " active_slot, void_reason, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, po.getCertificateNo());
            ps.setString(2, po.getRequestId());
            ps.setString(3, po.getFarmId());
            ps.setString(4, po.getAnimalType().name());
            ps.setString(5, po.getEarTagRange());
            ps.setInt(6, po.getQuantity());
            ps.setTimestamp(7, Timestamp.from(po.getIssuedAt()));
            ps.setTimestamp(8, Timestamp.from(po.getExpiresAt()));
            ps.setString(9, po.getStatus().name());
            ps.setBoolean(10, po.isQuarantineFlag());
            ps.setString(11, po.getActiveSlot());
            ps.setString(12, po.getVoidReason());
            ps.setTimestamp(13, Timestamp.from(po.getCreatedAt()));
            ps.setTimestamp(14, Timestamp.from(po.getUpdatedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            po.setId(key.longValue());
        }
    }

    public CertificatePo findByRequestId(String requestId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM quarantine_certificate WHERE request_id = ?",
                    ROW_MAPPER, requestId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public CertificatePo findActiveBySlot(String farmId, String earTagRange) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM quarantine_certificate WHERE active_slot = ?",
                    ROW_MAPPER, activeSlot(farmId, earTagRange));
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 作废/失效：置非活动状态、释放 active_slot（NULL 不参与唯一约束）。 */
    public boolean deactivateIfActive(long id, CertificateStatus target, String reason,
                                      boolean quarantineFlag, Instant now) {
        int rows = jdbcTemplate.update(
                "UPDATE quarantine_certificate SET status = ?, active_slot = NULL, void_reason = ?,"
                        + " quarantine_flag = ?, updated_at = ? WHERE id = ? AND status = 'VALID'",
                target.name(), reason, quarantineFlag, Timestamp.from(now), id);
        return rows == 1;
    }

    /** 仅置强制隔离观察标记（在途证封锁联动），证保持 VALID 且 active_slot 不释放。 */
    public boolean markQuarantineIfActive(long id, Instant now) {
        int rows = jdbcTemplate.update(
                "UPDATE quarantine_certificate SET quarantine_flag = TRUE, updated_at = ?"
                        + " WHERE id = ? AND status = 'VALID' AND quarantine_flag = FALSE",
                Timestamp.from(now), id);
        return rows == 1;
    }

    /** 结案：状态 CLOSED 并释放 active_slot。 */
    public boolean closeIfValid(long id, Instant now) {
        int rows = jdbcTemplate.update(
                "UPDATE quarantine_certificate SET status = 'CLOSED', active_slot = NULL, updated_at = ?"
                        + " WHERE id = ? AND status = 'VALID'",
                Timestamp.from(now), id);
        return rows == 1;
    }

    /** 查某养殖场已出证未启运（单据 CERTIFIED）且证仍 VALID 的证。 */
    public List<CertificatePo> findValidCertifiedOnFarm(String farmId) {
        return queryJoined(farmId, "CERTIFIED");
    }

    /** 查某养殖场已在途（单据 IN_TRANSIT）且证仍 VALID 的证。 */
    public List<CertificatePo> findValidInTransitOnFarm(String farmId) {
        return queryJoined(farmId, "IN_TRANSIT");
    }

    private List<CertificatePo> queryJoined(String farmId, String requestStatus) {
        String sql = "SELECT c.id, c.certificate_no, c.request_id, c.farm_id, c.animal_type, c.ear_tag_range,"
                + " c.quantity, c.issued_at, c.expires_at, c.status, c.quarantine_flag, c.active_slot,"
                + " c.void_reason, c.created_at, c.updated_at"
                + " FROM quarantine_certificate c INNER JOIN quarantine_request q ON c.request_id = q.request_id"
                + " WHERE c.farm_id = ? AND c.status = 'VALID' AND q.status = ? ORDER BY c.id";
        return jdbcTemplate.query(sql, ROW_MAPPER, farmId, requestStatus);
    }

    /** 有效证唯一占位值。 */
    public static String activeSlot(String farmId, String earTagRange) {
        return farmId + "#" + earTagRange;
    }

    private static CertificatePo mapRow(ResultSet rs, int n) throws SQLException {
        CertificatePo po = new CertificatePo();
        po.setId(rs.getLong("id"));
        po.setCertificateNo(rs.getString("certificate_no"));
        po.setRequestId(rs.getString("request_id"));
        po.setFarmId(rs.getString("farm_id"));
        po.setAnimalType(AnimalType.valueOf(rs.getString("animal_type")));
        po.setEarTagRange(rs.getString("ear_tag_range"));
        po.setQuantity(rs.getInt("quantity"));
        po.setIssuedAt(rs.getTimestamp("issued_at").toInstant());
        po.setExpiresAt(rs.getTimestamp("expires_at").toInstant());
        po.setStatus(CertificateStatus.valueOf(rs.getString("status")));
        po.setQuarantineFlag(rs.getBoolean("quarantine_flag"));
        po.setActiveSlot(rs.getString("active_slot"));
        po.setVoidReason(rs.getString("void_reason"));
        po.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        po.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return po;
    }
}
