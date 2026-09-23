package com.example.starter.specimen.repo;

import com.example.starter.specimen.model.AliquotRow;
import com.example.starter.specimen.model.AliquotStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 分装表数据访问：状态迁移均通过带版本与前置状态条件的原子更新完成。
 */
@Repository
public class AliquotRepository {

    private static final RowMapper<AliquotRow> MAPPER = (rs, n) -> new AliquotRow(
            rs.getString("aliquot_id"),
            rs.getString("specimen_id"),
            rs.getBigDecimal("volume_ul"),
            AliquotStatus.valueOf(rs.getString("status")),
            rs.getString("custodian"),
            rs.getLong("version"),
            rs.getTimestamp("last_event_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public AliquotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AliquotRow row) {
        jdbc.update("INSERT INTO aliquot (aliquot_id, specimen_id, volume_ul, status, custodian,"
                        + " version, last_event_at, created_at) VALUES (?,?,?,?,?,?,?,?)",
                row.aliquotId(), row.specimenId(), row.volumeUl(), row.status().name(), row.custodian(),
                row.version(), Timestamp.from(row.lastEventAt()), Timestamp.from(row.createdAt()));
    }

    public Optional<AliquotRow> findById(String aliquotId) {
        return jdbc.query("SELECT * FROM aliquot WHERE aliquot_id = ?", MAPPER, aliquotId)
                .stream().findFirst();
    }

    public List<AliquotRow> findBySpecimenId(String specimenId) {
        return jdbc.query("SELECT * FROM aliquot WHERE specimen_id = ? ORDER BY created_at, aliquot_id",
                MAPPER, specimenId);
    }

    /**
     * 原子占用：仅当分装处于 AVAILABLE 且版本匹配时转为 RESERVED，返回受影响行数。
     */
    public int reserve(String aliquotId, long expectedVersion) {
        return jdbc.update("UPDATE aliquot SET status = 'RESERVED', version = version + 1"
                        + " WHERE aliquot_id = ? AND status = 'AVAILABLE' AND version = ?",
                aliquotId, expectedVersion);
    }

    /**
     * 原子状态迁移：仅当当前状态与版本匹配时生效，返回受影响行数。
     */
    public int transition(String aliquotId, AliquotStatus from, AliquotStatus to, long expectedVersion) {
        return jdbc.update("UPDATE aliquot SET status = ?, version = version + 1"
                        + " WHERE aliquot_id = ? AND status = ? AND version = ?",
                to.name(), aliquotId, from.name(), expectedVersion);
    }

    /**
     * 原子交接：仅当版本与当前持有人匹配时更新保管方、事件时间与版本。
     */
    public int transfer(String aliquotId, long expectedVersion, String fromCustodian,
                        String toCustodian, Instant eventAt) {
        return jdbc.update("UPDATE aliquot SET custodian = ?, version = version + 1, last_event_at = ?"
                        + " WHERE aliquot_id = ? AND version = ? AND custodian = ?",
                toCustodian, Timestamp.from(eventAt), aliquotId, expectedVersion, fromCustodian);
    }

    /**
     * 账本汇总：按状态分组统计指定样本的分装体积，单位微升。
     */
    public List<StatusVolume> sumByStatus(String specimenId) {
        return jdbc.query("SELECT status, COALESCE(SUM(volume_ul), 0) AS total, COUNT(*) AS cnt"
                        + " FROM aliquot WHERE specimen_id = ? GROUP BY status",
                (rs, n) -> new StatusVolume(AliquotStatus.valueOf(rs.getString("status")),
                        rs.getBigDecimal("total"), rs.getLong("cnt")),
                specimenId);
    }

    /**
     * 某状态下分装体积与数量的汇总项。
     */
    public record StatusVolume(AliquotStatus status, BigDecimal totalVolumeUl, long count) {
    }
}
