package com.example.starter.specimen.repo;

import com.example.starter.specimen.model.SpecimenRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 样本表数据访问：全部使用参数化 SQL，版本与余额校验通过条件更新原子完成。
 */
@Repository
public class SpecimenRepository {

    private static final RowMapper<SpecimenRow> MAPPER = (rs, n) -> new SpecimenRow(
            rs.getString("specimen_id"),
            rs.getBigDecimal("total_volume_ul"),
            rs.getBigDecimal("remaining_volume_ul"),
            rs.getString("custodian"),
            rs.getLong("version"),
            rs.getTimestamp("last_event_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public SpecimenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(SpecimenRow row) {
        jdbc.update("INSERT INTO specimen (specimen_id, total_volume_ul, remaining_volume_ul, custodian,"
                        + " version, last_event_at, created_at) VALUES (?,?,?,?,?,?,?)",
                row.specimenId(), row.totalVolumeUl(), row.remainingVolumeUl(), row.custodian(),
                row.version(), Timestamp.from(row.lastEventAt()), Timestamp.from(row.createdAt()));
    }

    public Optional<SpecimenRow> findById(String specimenId) {
        return jdbc.query("SELECT * FROM specimen WHERE specimen_id = ?", MAPPER, specimenId)
                .stream().findFirst();
    }

    /**
     * 原子扣减剩余量并递增版本；仅当版本匹配且剩余量充足时生效，返回受影响行数。
     */
    public int deductRemaining(String specimenId, long expectedVersion,
                               BigDecimal deduct, BigDecimal newRemaining) {
        return jdbc.update("UPDATE specimen SET remaining_volume_ul = ?, version = version + 1"
                        + " WHERE specimen_id = ? AND version = ? AND remaining_volume_ul >= ?",
                newRemaining, specimenId, expectedVersion, deduct);
    }

    /**
     * 原子交接：仅当版本与当前持有人匹配时更新保管方、事件时间与版本。
     */
    public int transfer(String specimenId, long expectedVersion, String fromCustodian,
                        String toCustodian, Instant eventAt) {
        return jdbc.update("UPDATE specimen SET custodian = ?, version = version + 1, last_event_at = ?"
                        + " WHERE specimen_id = ? AND version = ? AND custodian = ?",
                toCustodian, Timestamp.from(eventAt), specimenId, expectedVersion, fromCustodian);
    }
}
