package com.example.starter.marpol.repo;

import com.example.starter.marpol.domain.RecordType;
import com.example.starter.marpol.domain.RecordVersionStatus;
import com.example.starter.marpol.model.EmissionRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

/**
 * 排放记录版本仓储。版本唯一性由数据库约束 uk_emission_root_version 保证，
 * requestId 全局唯一由 uk_emission_request 保证；不提供物理删除。
 */
@Repository
public class EmissionRecordRepository {

    private static final RowMapper<EmissionRecord> MAPPER = (rs, n) -> new EmissionRecord(
            rs.getLong("id"),
            rs.getString("request_id"),
            (Long) rs.getObject("root_id"),
            rs.getInt("version_no"),
            (Long) rs.getObject("supersedes_id"),
            rs.getLong("ship_id"),
            rs.getLong("voyage_id"),
            RecordType.valueOf(rs.getString("record_type")),
            rs.getDouble("longitude"),
            rs.getDouble("latitude"),
            rs.getTimestamp("event_time").toInstant(),
            rs.getBigDecimal("quantity"),
            rs.getString("unit"),
            RecordVersionStatus.valueOf(rs.getString("status")),
            rs.getString("fingerprint"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public EmissionRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一个版本行；违反唯一约束（requestId 或 root+version）时由上层转 409。
     *
     * @return 新行 id
     */
    public Long insert(EmissionRecord r) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO emission_record (request_id, root_id, version_no, supersedes_id, "
                            + "ship_id, voyage_id, record_type, longitude, latitude, event_time, "
                            + "quantity, unit, status, fingerprint, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, r.requestId());
            if (r.rootId() == null) {
                ps.setObject(2, null);
            } else {
                ps.setLong(2, r.rootId());
            }
            ps.setInt(3, r.versionNo());
            if (r.supersedesId() == null) {
                ps.setObject(4, null);
            } else {
                ps.setLong(4, r.supersedesId());
            }
            ps.setLong(5, r.shipId());
            ps.setLong(6, r.voyageId());
            ps.setString(7, r.recordType().name());
            ps.setDouble(8, r.longitude());
            ps.setDouble(9, r.latitude());
            ps.setTimestamp(10, Timestamp.from(r.eventTime()));
            ps.setBigDecimal(11, r.quantity());
            ps.setString(12, r.unit());
            ps.setString(13, r.status().name());
            ps.setString(14, r.fingerprint());
            ps.setTimestamp(15, Timestamp.from(r.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKeyAs(Long.class);
    }

    /**
     * 首版本入库后将版本族根 id 回填为自身 id。
     */
    public void bindRootToSelf(Long id) {
        jdbc.update("UPDATE emission_record SET root_id = id WHERE id = ? AND root_id IS NULL", id);
    }

    public EmissionRecord findByRequestId(String requestId) {
        List<EmissionRecord> list = jdbc.query(selectWhere("request_id = ?"), MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    public EmissionRecord findById(Long id) {
        List<EmissionRecord> list = jdbc.query(selectWhere("id = ?"), MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 取版本族最新有效版本（FOR UPDATE，须在事务内调用）。
     */
    public EmissionRecord findActiveByRootForUpdate(Long rootId) {
        List<EmissionRecord> list = jdbc.query(
                selectWhere("root_id = ? AND status = 'ACTIVE' FOR UPDATE"), MAPPER, rootId);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<EmissionRecord> findVersions(Long rootId) {
        return jdbc.query(selectWhere("root_id = ? ORDER BY version_no"), MAPPER, rootId);
    }

    public List<EmissionRecord> findActiveByShip(Long shipId) {
        return jdbc.query(selectWhere("ship_id = ? AND status = 'ACTIVE' ORDER BY id"), MAPPER, shipId);
    }

    public EmissionRecord findActiveByRoot(Long rootId) {
        List<EmissionRecord> list = jdbc.query(
                selectWhere("root_id = ? AND status = 'ACTIVE'"), MAPPER, rootId);
        return list.isEmpty() ? null : list.get(0);
    }

    public int markSuperseded(Long id) {
        return jdbc.update("UPDATE emission_record SET status = 'SUPERSEDED' WHERE id = ? AND status = 'ACTIVE'", id);
    }

    private String selectWhere(String where) {
        return "SELECT id, request_id, root_id, version_no, supersedes_id, ship_id, voyage_id, "
                + "record_type, longitude, latitude, event_time, quantity, unit, status, fingerprint, created_at "
                + "FROM emission_record WHERE " + where;
    }
}
