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

import com.example.starter.domain.SpecimenStatus;

/**
 * 原始样本仓储。
 */
@Repository
public class SpecimenRepository {

    private static final String COLUMNS =
            "id, specimen_id, volume_nl, remaining_nl, status, custodian, version, created_at, updated_at";

    private static final RowMapper<SpecimenRow> MAPPER = SpecimenRepository::mapRow;

    private final JdbcTemplate jdbc;

    public SpecimenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按业务主键查找样本。
     */
    public Optional<SpecimenRow> findBySpecimenId(String specimenId) {
        List<SpecimenRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM specimen WHERE specimen_id = ?",
                MAPPER, specimenId);
        return rows.stream().findFirst();
    }

    /**
     * 在事务内对样本行加写锁（SELECT ... FOR UPDATE），串行化同一标本的分装并发。
     */
    public Optional<SpecimenRow> lockBySpecimenId(String specimenId) {
        List<SpecimenRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM specimen WHERE specimen_id = ? FOR UPDATE",
                MAPPER, specimenId);
        return rows.stream().findFirst();
    }

    /**
     * 插入新入库样本，version 为 0。
     */
    public void insert(String specimenId, long volumeNl, String custodian, Instant now) {
        Timestamp ts = Timestamp.from(now);
        jdbc.update(
                "INSERT INTO specimen (specimen_id, volume_nl, remaining_nl, status, custodian, "
                        + "version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 0, ?, ?)",
                specimenId, volumeNl, volumeNl, SpecimenStatus.IN_STOCK.name(), custodian, ts, ts);
    }

    /**
     * 乐观扣减剩余体积：仅当版本匹配且剩余量足够时更新，返回受影响行数。
     */
    public int decreaseRemaining(String specimenId, long amountNl, int expectedVersion, Instant now) {
        return jdbc.update(
                "UPDATE specimen SET remaining_nl = remaining_nl - ?, version = version + 1, updated_at = ? "
                        + "WHERE specimen_id = ? AND version = ? AND remaining_nl >= ?",
                amountNl, Timestamp.from(now), specimenId, expectedVersion, amountNl);
    }

    /**
     * 乐观变更保管人，返回受影响行数。
     */
    public int updateCustodian(String specimenId, String newCustodian, int expectedVersion, Instant now) {
        return jdbc.update(
                "UPDATE specimen SET custodian = ?, version = version + 1, updated_at = ? "
                        + "WHERE specimen_id = ? AND version = ?",
                newCustodian, Timestamp.from(now), specimenId, expectedVersion);
    }

    /**
     * 仅供测试清理。
     */
    public List<SpecimenRow> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM specimen ORDER BY id", MAPPER);
    }

    private static SpecimenRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SpecimenRow(
                rs.getLong("id"),
                rs.getString("specimen_id"),
                rs.getLong("volume_nl"),
                rs.getLong("remaining_nl"),
                SpecimenStatus.valueOf(rs.getString("status")),
                rs.getString("custodian"),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
