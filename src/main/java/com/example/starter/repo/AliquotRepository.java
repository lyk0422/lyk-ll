package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.AliquotStatus;

/**
 * 分装管仓储。
 */
@Repository
public class AliquotRepository {

    private static final String COLUMNS =
            "id, aliquot_id, specimen_id, volume_nl, consumed_nl, status, custodian, version, created_at, updated_at";

    private static final RowMapper<AliquotRow> MAPPER = AliquotRepository::mapRow;

    private final JdbcTemplate jdbc;

    public AliquotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按业务主键查找分装管。
     */
    public Optional<AliquotRow> findByAliquotId(String aliquotId) {
        List<AliquotRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM aliquot WHERE aliquot_id = ?",
                MAPPER, aliquotId);
        return rows.stream().findFirst();
    }

    /**
     * 在事务内对分装管行加写锁（SELECT ... FOR UPDATE），
     * 串行化同一 aliquot 上的申请占用、开始消耗与污染隔离。
     */
    public Optional<AliquotRow> lockByAliquotId(String aliquotId) {
        List<AliquotRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM aliquot WHERE aliquot_id = ? FOR UPDATE",
                MAPPER, aliquotId);
        return rows.stream().findFirst();
    }

    /**
     * 查询某原始样本下的全部分装管。
     */
    public List<AliquotRow> findBySpecimenId(String specimenId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM aliquot WHERE specimen_id = ? ORDER BY id",
                MAPPER, specimenId);
    }

    /**
     * 插入分装管，version 为 0。
     */
    public void insert(String aliquotId, String specimenId, long volumeNl,
                       String custodian, Instant now) {
        Timestamp ts = Timestamp.from(now);
        jdbc.update(
                "INSERT INTO aliquot (aliquot_id, specimen_id, volume_nl, consumed_nl, status, custodian, "
                        + "version, created_at, updated_at) VALUES (?, ?, ?, 0, ?, ?, 0, ?, ?)",
                aliquotId, specimenId, volumeNl, AliquotStatus.AVAILABLE.name(), custodian, ts, ts);
    }

    /**
     * 累加已消耗体积（RESERVED→CONSUMED），乐观版本控制，返回受影响行数。
     */
    public int addConsumed(String aliquotId, long amountNl, int expectedVersion, Instant now) {
        return jdbc.update(
                "UPDATE aliquot SET consumed_nl = consumed_nl + ?, version = version + 1, updated_at = ? "
                        + "WHERE aliquot_id = ? AND version = ? AND status = ? "
                        + "AND consumed_nl + ? <= volume_nl",
                amountNl, Timestamp.from(now), aliquotId, expectedVersion,
                AliquotStatus.AVAILABLE.name(), amountNl);
    }

    /**
     * 乐观变更保管人，返回受影响行数。
     */
    public int updateCustodian(String aliquotId, String newCustodian, int expectedVersion, Instant now) {
        return jdbc.update(
                "UPDATE aliquot SET custodian = ?, version = version + 1, updated_at = ? "
                        + "WHERE aliquot_id = ? AND version = ?",
                newCustodian, Timestamp.from(now), aliquotId, expectedVersion);
    }

    /**
     * 乐观将 AVAILABLE 分装管置为 QUARANTINED 并切换保管人，返回受影响行数。
     */
    public int quarantine(String aliquotId, String quarantineCustodian,
                          int expectedVersion, Instant now) {
        return jdbc.update(
                "UPDATE aliquot SET status = ?, custodian = ?, version = version + 1, updated_at = ? "
                        + "WHERE aliquot_id = ? AND version = ? AND status = ?",
                AliquotStatus.QUARANTINED.name(), quarantineCustodian, Timestamp.from(now),
                aliquotId, expectedVersion, AliquotStatus.AVAILABLE.name());
    }

    private static AliquotRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AliquotRow(
                rs.getLong("id"),
                rs.getString("aliquot_id"),
                rs.getString("specimen_id"),
                rs.getLong("volume_nl"),
                rs.getLong("consumed_nl"),
                AliquotStatus.valueOf(rs.getString("status")),
                rs.getString("custodian"),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
