package com.example.starter.repo;

import com.example.starter.domain.Crane;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 塔机数据访问。
 */
@Repository
public class CraneRepository {

    private final JdbcTemplate jdbc;

    public CraneRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Crane> findById(String id) {
        return jdbc.query("SELECT id, name, rated_load_tonnes FROM tower_crane WHERE id = ?",
                (rs, n) -> new Crane(rs.getString("id"), rs.getString("name"),
                        rs.getBigDecimal("rated_load_tonnes")),
                id).stream().findFirst();
    }
}
