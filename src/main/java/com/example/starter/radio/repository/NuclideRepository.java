package com.example.starter.radio.repository;

import java.util.Optional;

import com.example.starter.radio.model.Nuclide;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 合成核素表数据访问。
 */
@Repository
public class NuclideRepository {

    private final JdbcTemplate jdbc;

    public NuclideRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Nuclide> findByCode(String code) {
        return jdbc.query("SELECT id, code, name, half_life_seconds FROM nuclide WHERE code = ?",
                (rs, i) -> new Nuclide(rs.getLong("id"), rs.getString("code"),
                        rs.getString("name"), rs.getLong("half_life_seconds")),
                code).stream().findFirst();
    }

    public Optional<Nuclide> findById(long id) {
        return jdbc.query("SELECT id, code, name, half_life_seconds FROM nuclide WHERE id = ?",
                (rs, i) -> new Nuclide(rs.getLong("id"), rs.getString("code"),
                        rs.getString("name"), rs.getLong("half_life_seconds")),
                id).stream().findFirst();
    }
}
