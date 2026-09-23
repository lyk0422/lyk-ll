package com.example.starter.calibration.repository;

import com.example.starter.calibration.domain.CanaryRelease;
import com.example.starter.calibration.domain.CanaryStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 灰度批次仓储。
 */
@Repository
public class CanaryReleaseJdbc {

    private static final String COLUMNS = """
            id, release_id, param_key, canary_version_id, status,
            created_by, created_at, promoted_by, promoted_at, rolled_back_by, rolled_back_at
            """;

    private final JdbcTemplate jdbc;

    public CanaryReleaseJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(CanaryRelease release) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO canary_release (
                        release_id, param_key, canary_version_id, status,
                        created_by, created_at, promoted_by, promoted_at, rolled_back_by, rolled_back_at
                    ) VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, release.getReleaseId());
            ps.setString(i++, release.getParamKey());
            ps.setLong(i++, release.getCanaryVersionId());
            ps.setString(i++, release.getStatus().name());
            ps.setString(i++, release.getCreatedBy());
            ps.setTimestamp(i++, Timestamp.from(release.getCreatedAt()));
            ps.setString(i++, release.getPromotedBy());
            ps.setTimestamp(i++, ts(release.getPromotedAt()));
            ps.setString(i++, release.getRolledBackBy());
            ps.setTimestamp(i, ts(release.getRolledBackAt()));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        release.setId(id);
        return id;
    }

    public Optional<CanaryRelease> findByReleaseId(String releaseId) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM canary_release WHERE release_id = ?",
                    rowMapper(), releaseId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<CanaryRelease> findById(long id) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM canary_release WHERE id = ?", rowMapper(), id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 查询某参数键下进行中的灰度批次。 */
    public List<CanaryRelease> findInProgressByParamKey(String paramKey) {
        return jdbc.query(
                "SELECT " + COLUMNS
                        + " FROM canary_release WHERE param_key = ? AND status = 'IN_PROGRESS' ORDER BY id",
                rowMapper(), paramKey);
    }

    public int update(CanaryRelease release) {
        return jdbc.update("""
                UPDATE canary_release SET
                    status = ?, promoted_by = ?, promoted_at = ?,
                    rolled_back_by = ?, rolled_back_at = ?
                WHERE id = ?
                """,
                release.getStatus().name(),
                release.getPromotedBy(), ts(release.getPromotedAt()),
                release.getRolledBackBy(), ts(release.getRolledBackAt()),
                release.getId());
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    static RowMapper<CanaryRelease> rowMapper() {
        return (rs, n) -> {
            CanaryRelease r = new CanaryRelease();
            r.setId(rs.getLong("id"));
            r.setReleaseId(rs.getString("release_id"));
            r.setParamKey(rs.getString("param_key"));
            r.setCanaryVersionId(rs.getLong("canary_version_id"));
            r.setStatus(CanaryStatus.valueOf(rs.getString("status")));
            r.setCreatedBy(rs.getString("created_by"));
            r.setCreatedAt(rs.getTimestamp("created_at").toInstant());
            r.setPromotedBy(rs.getString("promoted_by"));
            Timestamp promotedAt = rs.getTimestamp("promoted_at");
            r.setPromotedAt(promotedAt == null ? null : promotedAt.toInstant());
            r.setRolledBackBy(rs.getString("rolled_back_by"));
            Timestamp rolledBackAt = rs.getTimestamp("rolled_back_at");
            r.setRolledBackAt(rolledBackAt == null ? null : rolledBackAt.toInstant());
            return r;
        };
    }
}
