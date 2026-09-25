package com.example.starter.water.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;

import com.example.starter.water.domain.MeterRepository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 计量点仓储的 JdbcTemplate/H2 实现。
 */
@Repository
public class JdbcMeterRepository implements MeterRepository {

    private static final RowMapper<MeterRow> METER_MAPPER = (rs, n) ->
            new MeterRow(rs.getLong("id"), rs.getLong("user_id"), rs.getString("name"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcMeterRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public MeterRow create(Long userId, String name) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO meter_point (user_id, name) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, name);
            return ps;
        }, keyHolder);
        return new MeterRow(keyHolder.getKey().longValue(), userId, name);
    }

    @Override
    public Optional<MeterRow> findById(Long id) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, user_id, name FROM meter_point WHERE id = ?",
                    METER_MAPPER, id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }
}
