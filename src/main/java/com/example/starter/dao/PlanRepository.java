package com.example.starter.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 减压方案版本访问。一个潜次可有多版方案；方案变更后旧版本 frozen=TRUE。
 */
@Repository
public class PlanRepository {

    private static final RowMapper<PlanRow> MAPPER = (rs, n) -> new PlanRow(
            rs.getLong("id"),
            rs.getString("dive_request_id"),
            rs.getInt("version"),
            rs.getInt("depth_m"),
            rs.getInt("bottom_min"),
            rs.getInt("ndl_limit_min"),
            rs.getInt("descent_rate_limit"),
            rs.getInt("ascent_rate_limit"),
            rs.getString("profile_json"),
            rs.getBoolean("frozen"),
            rs.getBoolean("score_controlled"),
            rs.getString("fingerprint"),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int insert(PlanRow row) {
        return jdbc.update("INSERT INTO dive_plan "
                        + "(dive_request_id, version, depth_m, bottom_min, ndl_limit_min, "
                        + "descent_rate_limit, ascent_rate_limit, profile_json, frozen, "
                        + "score_controlled, fingerprint, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.diveRequestId(), row.version(), row.depthM(), row.bottomMin(),
                row.ndlLimitMin(), row.descentRateLimit(), row.ascentRateLimit(),
                row.profileJson(), row.frozen(), row.scoreControlled(), row.fingerprint(),
                Timestamp.from(row.createdAtUtc()));
    }

    public Optional<PlanRow> findVersion(String requestId, int version) {
        return jdbc.query("SELECT * FROM dive_plan WHERE dive_request_id = ? AND version = ?",
                MAPPER, requestId, version).stream().findFirst();
    }

    public PlanRow getVersion(String requestId, int version) {
        return findVersion(requestId, version).orElseThrow(() ->
                new IllegalArgumentException("方案版本不存在: " + requestId + "#v" + version));
    }

    public Optional<PlanRow> findCurrent(String requestId) {
        return jdbc.query("SELECT * FROM dive_plan WHERE dive_request_id = ? AND frozen = FALSE "
                        + "ORDER BY version DESC LIMIT 1",
                MAPPER, requestId).stream().findFirst();
    }

    public List<PlanRow> findAll(String requestId) {
        return jdbc.query("SELECT * FROM dive_plan WHERE dive_request_id = ? ORDER BY version",
                MAPPER, requestId);
    }

    /** 方案变更：冻结指定潜次全部旧版本，必须与旧记录冻结在同一事务内完成。 */
    public int freezeAll(String requestId) {
        return jdbc.update("UPDATE dive_plan SET frozen = TRUE WHERE dive_request_id = ?",
                requestId);
    }

    /** 提升潜次当前方案版本并进入/回到 PLANNED 状态。 */
    public void bumpDiveVersion(String requestId, int version, java.time.Instant now) {
        jdbc.update("UPDATE dive SET current_plan_version = ?, status = 'PLANNED', "
                        + "updated_at_utc = ? WHERE request_id = ?",
                version, Timestamp.from(now), requestId);
    }
}
