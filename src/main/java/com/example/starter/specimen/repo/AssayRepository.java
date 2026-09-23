package com.example.starter.specimen.repo;

import com.example.starter.specimen.model.AssayRow;
import com.example.starter.specimen.model.AssayStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 检测申请表数据访问：状态迁移均通过带版本与前置状态条件的原子更新完成。
 */
@Repository
public class AssayRepository {

    private static final RowMapper<AssayRow> MAPPER = (rs, n) -> new AssayRow(
            rs.getString("assay_id"),
            rs.getString("aliquot_id"),
            rs.getString("project"),
            rs.getBigDecimal("reserved_volume_ul"),
            AssayStatus.valueOf(rs.getString("status")),
            rs.getString("result"),
            rs.getString("custodian"),
            rs.getLong("version"),
            rs.getTimestamp("last_event_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public AssayRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AssayRow row) {
        jdbc.update("INSERT INTO assay_request (assay_id, aliquot_id, project, reserved_volume_ul, status,"
                        + " result, custodian, version, last_event_at, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                row.assayId(), row.aliquotId(), row.project(), row.reservedVolumeUl(), row.status().name(),
                row.result(), row.custodian(), row.version(),
                Timestamp.from(row.lastEventAt()), Timestamp.from(row.createdAt()));
    }

    public Optional<AssayRow> findById(String assayId) {
        return jdbc.query("SELECT * FROM assay_request WHERE assay_id = ?", MAPPER, assayId)
                .stream().findFirst();
    }

    public List<AssayRow> findByAliquotId(String aliquotId) {
        return jdbc.query("SELECT * FROM assay_request WHERE aliquot_id = ? ORDER BY created_at, assay_id",
                MAPPER, aliquotId);
    }

    /**
     * 原子状态迁移：仅当当前状态与版本匹配时生效，返回受影响行数。
     */
    public int transition(String assayId, AssayStatus from, AssayStatus to, long expectedVersion) {
        return jdbc.update("UPDATE assay_request SET status = ?, version = version + 1"
                        + " WHERE assay_id = ? AND status = ? AND version = ?",
                to.name(), assayId, from.name(), expectedVersion);
    }

    /**
     * 原子写入结果：仅当申请处于 CONSUMED 且版本匹配时生效，返回受影响行数。
     */
    public int recordResult(String assayId, long expectedVersion, String result) {
        return jdbc.update("UPDATE assay_request SET result = ?, version = version + 1"
                        + " WHERE assay_id = ? AND status = 'CONSUMED' AND version = ?",
                result, assayId, expectedVersion);
    }

    /**
     * 污染传播：把指定分装下所有未开始（RESERVED）的申请原子取消，返回取消数量。
     */
    public int cancelPendingByAliquot(String aliquotId) {
        return jdbc.update("UPDATE assay_request SET status = 'CANCELLED', version = version + 1"
                        + " WHERE aliquot_id = ? AND status = 'RESERVED'", aliquotId);
    }

    /**
     * 原子交接：仅当版本与当前持有人匹配时更新保管方、事件时间与版本。
     */
    public int transfer(String assayId, long expectedVersion, String fromCustodian,
                        String toCustodian, Instant eventAt) {
        return jdbc.update("UPDATE assay_request SET custodian = ?, version = version + 1, last_event_at = ?"
                        + " WHERE assay_id = ? AND version = ? AND custodian = ?",
                toCustodian, Timestamp.from(eventAt), assayId, expectedVersion, fromCustodian);
    }
}
