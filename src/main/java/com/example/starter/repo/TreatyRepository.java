package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

import com.example.starter.domain.Models.Treaty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 比例再保合约数据访问。
 */
@Repository
public class TreatyRepository {

    private static final RowMapper<Treaty> MAPPER = (rs, n) -> map(rs);

    private final JdbcTemplate jdbc;

    public TreatyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Treaty map(ResultSet rs) throws SQLException {
        return new Treaty(rs.getLong("id"), rs.getString("treaty_no"), rs.getString("name"),
                rs.getBigDecimal("cession_rate"), rs.getBigDecimal("limit_per_policy"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    public Treaty insert(String treatyNo, String name, java.math.BigDecimal cessionRate,
                         java.math.BigDecimal limitPerPolicy, OffsetDateTime now) {
        jdbc.update("INSERT INTO treaty (treaty_no, name, cession_rate, limit_per_policy, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                treatyNo, name, cessionRate, limitPerPolicy, now);
        return findByTreatyNo(treatyNo).orElseThrow();
    }

    public Optional<Treaty> findByTreatyNo(String treatyNo) {
        return jdbc.query("SELECT * FROM treaty WHERE treaty_no = ?", MAPPER, treatyNo)
                .stream().findFirst();
    }

    public Optional<Treaty> findById(long id) {
        return jdbc.query("SELECT * FROM treaty WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }
}
