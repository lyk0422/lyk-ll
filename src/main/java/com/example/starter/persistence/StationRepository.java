package com.example.starter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 交接站数据访问。
 */
@Repository
public class StationRepository {

    private final JdbcTemplate jdbc;

    public StationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<StationRow> ROW_MAPPER = (rs, n) -> new StationRow(
            rs.getString("station_code"),
            rs.getString("station_name"),
            rs.getString("party_a"),
            rs.getString("party_b"),
            rs.getTimestamp("created_at").toInstant());

    /** 新增交接站；唯一键冲突时返回 empty。 */
    public Optional<StationRow> insert(String code, String name, String partyA, String partyB,
            Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO station (station_code, station_name, party_a, party_b, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, code, name, partyA, partyB, Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
        return findByCode(code);
    }

    /** 按编码查询交接站。 */
    public Optional<StationRow> findByCode(String code) {
        return jdbc.query("SELECT * FROM station WHERE station_code = ?", ROW_MAPPER, code)
                .stream().findFirst();
    }

    /** 交接站行记录。 */
    public record StationRow(String code, String name, String partyA, String partyB,
            Instant createdAt) {
    }
}
