package com.example.starter.water.repo;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.starter.water.domain.WaterUserRepository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 取水户与年度额度仓储的 JdbcTemplate/H2 实现。
 */
@Repository
public class JdbcWaterUserRepository implements WaterUserRepository {

    private static final RowMapper<UserRow> USER_MAPPER = (rs, n) -> new UserRow(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("river_section"),
            rs.getBigDecimal("annual_quota"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<AnnualQuotaRow> QUOTA_MAPPER = (rs, n) -> {
        BigDecimal excess = rs.getBigDecimal("excess");
        BigDecimal actualTotal = rs.getBigDecimal("actual_total");
        Timestamp sealedAt = rs.getTimestamp("sealed_at");
        return new AnnualQuotaRow(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getInt("quota_year"),
                rs.getBigDecimal("permitted_quota"),
                rs.getBigDecimal("carryover_deduction"),
                rs.getBoolean("sealed"),
                sealedAt == null ? null : sealedAt.toInstant(),
                actualTotal,
                excess);
    };

    private final JdbcTemplate jdbcTemplate;

    public JdbcWaterUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UserRow create(String name, String riverSection, BigDecimal annualQuota, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO water_user (name, river_section, annual_quota, created_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, riverSection);
            ps.setBigDecimal(3, annualQuota);
            ps.setTimestamp(4, Timestamp.from(now));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        return new UserRow(id, name, riverSection, annualQuota, now);
    }

    @Override
    public Optional<UserRow> findById(Long id) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, name, river_section, annual_quota, created_at FROM water_user WHERE id = ?",
                    USER_MAPPER, id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public UserRow lockById(Long id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, name, river_section, annual_quota, created_at FROM water_user WHERE id = ? FOR UPDATE",
                    USER_MAPPER, id);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    @Override
    public List<UserRow> lockByRiverSection(String riverSection) {
        return jdbcTemplate.query(
                "SELECT id, name, river_section, annual_quota, created_at FROM water_user "
                        + "WHERE river_section = ? ORDER BY id FOR UPDATE",
                USER_MAPPER, riverSection);
    }

    @Override
    public AnnualQuotaRow getOrCreateAnnualQuota(Long userId, int year, BigDecimal permittedQuota) {
        Optional<AnnualQuotaRow> existing = findAnnualQuota(userId, year);
        if (existing.isPresent()) {
            return existing.get();
        }
        BigDecimal carryover = jdbcTemplate.query(
                "SELECT excess FROM annual_quota WHERE user_id = ? AND quota_year = ? AND sealed = TRUE",
                rs -> rs.next() ? rs.getBigDecimal("excess") : null, userId, year - 1);
        if (carryover == null) {
            carryover = BigDecimal.ZERO;
        }
        BigDecimal finalCarryover = carryover;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO annual_quota (user_id, quota_year, permitted_quota, carryover_deduction, "
                                + "sealed, sealed_at, actual_total, excess) "
                                + "VALUES (?, ?, ?, ?, FALSE, NULL, NULL, NULL)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, userId);
                ps.setInt(2, year);
                ps.setBigDecimal(3, permittedQuota);
                ps.setBigDecimal(4, finalCarryover);
                return ps;
            }, keyHolder);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // 并发惰性创建时，唯一约束兜底，重查已存在行。
            return findAnnualQuotaInternal(userId, year).orElseThrow();
        }
        return new AnnualQuotaRow(keyHolder.getKey().longValue(), userId, year,
                permittedQuota, carryover, false, null, null, null);
    }

    @Override
    public void applySeal(AnnualQuotaRow row, Instant sealedAt, BigDecimal actualTotal,
                          BigDecimal excess, int nextYear) {
        jdbcTemplate.update(
                "UPDATE annual_quota SET sealed = TRUE, sealed_at = ?, actual_total = ?, excess = ? WHERE id = ?",
                Timestamp.from(sealedAt), actualTotal, excess, row.id());
        upsertNextYearCarryover(row.userId(), nextYear, row.permittedQuota(), excess);
    }

    /**
     * 把超额按 1:1 写入次年额度行的结转扣减；次年行不存在则创建，已封存（终态）则跳过，未封存覆盖同一值。
     */
    private void upsertNextYearCarryover(Long userId, int nextYear, BigDecimal permittedQuota,
                                         BigDecimal carryover) {
        Optional<AnnualQuotaRow> next = findAnnualQuotaInternal(userId, nextYear);
        if (next.isPresent()) {
            if (next.get().sealed()) {
                return;
            }
            jdbcTemplate.update(
                    "UPDATE annual_quota SET carryover_deduction = ? WHERE id = ?",
                    carryover, next.get().id());
        } else {
            jdbcTemplate.update(
                    "INSERT INTO annual_quota (user_id, quota_year, permitted_quota, carryover_deduction, "
                            + "sealed, sealed_at, actual_total, excess) "
                            + "VALUES (?, ?, ?, ?, FALSE, NULL, NULL, NULL)",
                    userId, nextYear, permittedQuota, carryover);
        }
    }

    @Override
    public Optional<AnnualQuotaRow> findAnnualQuota(Long userId, int year) {
        return findAnnualQuotaInternal(userId, year);
    }

    private Optional<AnnualQuotaRow> findAnnualQuotaInternal(Long userId, int year) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, user_id, quota_year, permitted_quota, carryover_deduction, sealed, sealed_at, "
                            + "actual_total, excess FROM annual_quota WHERE user_id = ? AND quota_year = ?",
                    QUOTA_MAPPER, userId, year));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }
}
