package com.example.starter.water.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.starter.water.domain.RestrictionRepository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 限取事件仓储的 JdbcTemplate/H2 实现。
 */
@Repository
public class JdbcRestrictionRepository implements RestrictionRepository {

    private static final RowMapper<RestrictionRow> MAPPER = (rs, n) -> new RestrictionRow(
            rs.getLong("id"),
            rs.getString("river_section"),
            rs.getTimestamp("effective_at").toInstant(),
            rs.getString("reason"),
            rs.getBoolean("active"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public JdbcRestrictionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public RestrictionRow trigger(String riverSection, Instant effectiveAt, String reason, Instant now) {
        // 同河段历史事件全部置为非激活，再插入新激活事件；与取水户行锁配合由服务层保证串行顺序。
        jdbcTemplate.update("UPDATE restriction_event SET active = FALSE WHERE river_section = ? AND active = TRUE",
                riverSection);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO restriction_event (river_section, effective_at, reason, active, created_at) "
                            + "VALUES (?, ?, ?, TRUE, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, riverSection);
            ps.setTimestamp(2, Timestamp.from(effectiveAt));
            ps.setString(3, reason);
            ps.setTimestamp(4, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return new RestrictionRow(keyHolder.getKey().longValue(), riverSection, effectiveAt, reason, true, now);
    }

    @Override
    public Optional<RestrictionRow> findActive(String riverSection) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, river_section, effective_at, reason, active, created_at "
                            + "FROM restriction_event WHERE river_section = ? AND active = TRUE "
                            + "ORDER BY id DESC FETCH FIRST 1 ROWS ONLY",
                    MAPPER, riverSection));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<RestrictionRow> findById(Long id) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, river_section, effective_at, reason, active, created_at "
                            + "FROM restriction_event WHERE id = ?",
                    MAPPER, id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public List<RestrictionRow> findAllActive() {
        return jdbcTemplate.query(
                "SELECT id, river_section, effective_at, reason, active, created_at FROM restriction_event "
                        + "WHERE active = TRUE ORDER BY id",
                MAPPER);
    }
}
