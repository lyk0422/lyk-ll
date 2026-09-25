package com.example.starter.marpol.repo;

import com.example.starter.marpol.domain.DischargeType;
import com.example.starter.marpol.domain.EmissionRecord;
import com.example.starter.marpol.domain.EmissionRequest;
import com.example.starter.marpol.domain.RecordStatus;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 排放记录版本与 requestId 幂等登记数据访问。
 */
@Repository
public class EmissionRecordRepository {

    private final JdbcTemplate jdbc;

    public EmissionRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EmissionRequest> findRequest(String requestId) {
        List<EmissionRequest> list = jdbc.query(
                "SELECT request_id, fingerprint, action, record_no, version, created_at "
                        + "FROM emission_request WHERE request_id = ?",
                requestRowMapper(), requestId);
        return list.stream().findFirst();
    }

    /**
     * 登记幂等键；request_id 唯一冲突时抛出 {@link DuplicateKeyException}。
     */
    public void insertRequest(EmissionRequest request) {
        jdbc.update(
                "INSERT INTO emission_request "
                        + "(request_id, fingerprint, action, record_no, version, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                request.getRequestId(), request.getFingerprint(), request.getAction(),
                request.getRecordNo(), request.getVersion(),
                Timestamp.from(request.getCreatedAt()));
    }

    /**
     * 插入新版本；(record_no, version) 唯一，并发修正仅一个胜出。
     */
    public void insertVersion(EmissionRecord record) {
        jdbc.update(
                "INSERT INTO emission_record (record_no, version, request_id, ship_id, voyage_no, "
                        + "discharge_type, latitude, longitude, event_time, quantity, unit, "
                        + "compliant, compliance_detail, status, supersedes_record_no, "
                        + "supersedes_version, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.getRecordNo(), record.getVersion(), record.getRequestId(),
                record.getShipId(), record.getVoyageNo(),
                record.getDischargeType().name(), record.getLatitude(), record.getLongitude(),
                Timestamp.from(record.getEventTime()), record.getQuantity(), record.getUnit(),
                record.isCompliant(), record.getComplianceDetail(), record.getStatus().name(),
                record.getSupersedesRecordNo(), record.getSupersedesVersion(),
                Timestamp.from(record.getCreatedAt()));
    }

    public Optional<EmissionRecord> findActiveVersion(String recordNo) {
        List<EmissionRecord> list = jdbc.query(
                "SELECT * FROM emission_record WHERE record_no = ? AND status = 'ACTIVE'",
                recordRowMapper(), recordNo);
        return list.stream().findFirst();
    }

    /**
     * 悲观锁定当前 ACTIVE 版本行（FOR UPDATE 当前读）。
     * 与修正并发时：若对方已提交，当前读会直接定位到新的 ACTIVE 行。
     */
    public Optional<EmissionRecord> findActiveVersionForUpdate(String recordNo) {
        List<EmissionRecord> list = jdbc.query(
                "SELECT * FROM emission_record WHERE record_no = ? AND status = 'ACTIVE' "
                        + "FOR UPDATE",
                recordRowMapper(), recordNo);
        return list.stream().findFirst();
    }

    /**
     * 悲观锁定指定版本行（行锁，阻塞至并发事务结束）。
     * 修正以“请求所基于的版本行”为锁锚点：先提交者把该行置为 SUPERSEDED，
     * 后来者拿到锁后即可据此判负，且不影响顺序进行的下一轮修正。
     */
    public Optional<EmissionRecord> findVersionForUpdate(String recordNo, int version) {
        List<EmissionRecord> list = jdbc.query(
                "SELECT * FROM emission_record WHERE record_no = ? AND version = ? FOR UPDATE",
                recordRowMapper(), recordNo, version);
        return list.stream().findFirst();
    }

    public Optional<EmissionRecord> findVersion(String recordNo, int version) {
        List<EmissionRecord> list = jdbc.query(
                "SELECT * FROM emission_record WHERE record_no = ? AND version = ?",
                recordRowMapper(), recordNo, version);
        return list.stream().findFirst();
    }

    public List<EmissionRecord> findVersions(String recordNo) {
        return jdbc.query(
                "SELECT * FROM emission_record WHERE record_no = ? ORDER BY version",
                recordRowMapper(), recordNo);
    }

    public List<EmissionRecord> listActive(String shipId, String voyageNo) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM emission_record WHERE status = 'ACTIVE'");
        List<Object> args = new java.util.ArrayList<>();
        if (shipId != null && !shipId.isBlank()) {
            sql.append(" AND ship_id = ?");
            args.add(shipId);
        }
        if (voyageNo != null && !voyageNo.isBlank()) {
            sql.append(" AND voyage_no = ?");
            args.add(voyageNo);
        }
        sql.append(" ORDER BY id");
        return jdbc.query(sql.toString(), recordRowMapper(), args.toArray());
    }

    /**
     * CAS 将指定 ACTIVE 版本置为 SUPERSEDED；仅当确为当前有效版本时成功。
     *
     * @return 受影响行数，0 表示已被并发修正抢先取代
     */
    public int markSuperseded(String recordNo, int oldVersion) {
        return jdbc.update(
                "UPDATE emission_record SET status = 'SUPERSEDED' "
                        + "WHERE record_no = ? AND version = ? AND status = 'ACTIVE'",
                recordNo, oldVersion);
    }

    public Integer maxVersion(String recordNo) {
        Integer max = jdbc.queryForObject(
                "SELECT MAX(version) FROM emission_record WHERE record_no = ?",
                Integer.class, recordNo);
        return max;
    }

    private RowMapper<EmissionRequest> requestRowMapper() {
        return (rs, n) -> {
            EmissionRequest request = new EmissionRequest();
            request.setRequestId(rs.getString("request_id"));
            request.setFingerprint(rs.getString("fingerprint"));
            request.setAction(rs.getString("action"));
            request.setRecordNo(rs.getString("record_no"));
            request.setVersion(rs.getInt("version"));
            Timestamp created = rs.getTimestamp("created_at");
            request.setCreatedAt(created == null ? null : created.toInstant());
            return request;
        };
    }

    private RowMapper<EmissionRecord> recordRowMapper() {
        return (rs, n) -> {
            EmissionRecord record = new EmissionRecord();
            record.setId(rs.getLong("id"));
            record.setRecordNo(rs.getString("record_no"));
            record.setVersion(rs.getInt("version"));
            record.setRequestId(rs.getString("request_id"));
            record.setShipId(rs.getString("ship_id"));
            record.setVoyageNo(rs.getString("voyage_no"));
            record.setDischargeType(DischargeType.valueOf(rs.getString("discharge_type")));
            record.setLatitude(rs.getBigDecimal("latitude"));
            record.setLongitude(rs.getBigDecimal("longitude"));
            Timestamp event = rs.getTimestamp("event_time");
            record.setEventTime(event == null ? null : event.toInstant());
            record.setQuantity(rs.getBigDecimal("quantity"));
            record.setUnit(rs.getString("unit"));
            record.setCompliant(rs.getBoolean("compliant"));
            record.setComplianceDetail(rs.getString("compliance_detail"));
            record.setStatus(RecordStatus.valueOf(rs.getString("status")));
            record.setSupersedesRecordNo(rs.getString("supersedes_record_no"));
            int supVersion = rs.getInt("supersedes_version");
            record.setSupersedesVersion(rs.wasNull() ? null : supVersion);
            Timestamp created = rs.getTimestamp("created_at");
            record.setCreatedAt(created == null ? null : created.toInstant());
            return record;
        };
    }
}
