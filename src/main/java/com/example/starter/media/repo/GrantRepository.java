package com.example.starter.media.repo;

import com.example.starter.media.domain.GrantStatus;
import com.example.starter.media.domain.LicenseMode;
import com.example.starter.media.web.dto.GrantView;
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

/**
 * 授权线与授权版本的数据访问。
 */
@Repository
public class GrantRepository {

    private static final RowMapper<GrantView> VIEW_MAPPER = (rs, n) -> new GrantView(
            String.valueOf(rs.getLong("grant_id")),
            rs.getInt("version_no"),
            rs.getString("asset_id"),
            rs.getString("region"),
            rs.getString("platform"),
            rs.getTimestamp("window_start").toInstant(),
            rs.getTimestamp("window_end").toInstant(),
            LicenseMode.valueOf(rs.getString("license_mode")),
            GrantStatus.valueOf(rs.getString("status")),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()
    );

    private static final RowMapper<GrantVersionRow> ROW_MAPPER = (rs, n) -> new GrantVersionRow(
            rs.getLong("id"),
            rs.getLong("grant_id"),
            rs.getInt("version_no"),
            rs.getString("asset_id"),
            rs.getString("region"),
            rs.getString("platform"),
            rs.getTimestamp("window_start").toInstant(),
            rs.getTimestamp("window_end").toInstant(),
            LicenseMode.valueOf(rs.getString("license_mode")),
            GrantStatus.valueOf(rs.getString("status"))
    );

    private final JdbcTemplate jdbc;

    public GrantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 锁定 内容+地区+平台 键空间：创建锁行并加行锁，使同键写事务串行执行。
     * 必须在事务内调用。
     */
    public void lockKey(String assetId, String region, String platform) {
        jdbc.update("MERGE INTO rights_key_lock (asset_id, region, platform) KEY(asset_id, region, platform) VALUES (?, ?, ?)",
                assetId, region, platform);
        jdbc.query("SELECT asset_id FROM rights_key_lock WHERE asset_id = ? AND region = ? AND platform = ? FOR UPDATE",
                (rs, n) -> rs.getString(1), assetId, region, platform);
    }

    /**
     * 读取授权线的合成键，用于更正/撤销前进入对应键空间锁。
     */
    public String[] findGrantKey(long grantId) {
        List<String[]> rows = jdbc.query("SELECT asset_id, region, platform FROM rights_grant WHERE id = ?",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, grantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long createGrant(String assetId, String region, String platform, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO rights_grant (asset_id, region, platform, version_no, created_at, updated_at) VALUES (?, ?, ?, 0, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, assetId);
            ps.setString(2, region);
            ps.setString(3, platform);
            ps.setTimestamp(4, Timestamp.from(now));
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1L : key.longValue();
    }

    public void addVersion(long grantId, int versionNo, String assetId, String region, String platform,
                           Instant windowStart, Instant windowEnd, LicenseMode mode,
                           GrantStatus status, String requestId, Instant now) {
        jdbc.update("""
                        INSERT INTO rights_grant_version
                          (grant_id, version_no, asset_id, region, platform, window_start, window_end,
                           license_mode, status, request_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                grantId, versionNo, assetId, region, platform,
                Timestamp.from(windowStart), Timestamp.from(windowEnd),
                mode.name(), status.name(), requestId, Timestamp.from(now));
        jdbc.update("UPDATE rights_grant SET version_no = ?, updated_at = ? WHERE id = ?",
                versionNo, Timestamp.from(now), grantId);
    }

    public void markSuperseded(long grantId, int versionNo) {
        jdbc.update("UPDATE rights_grant_version SET status = 'SUPERSEDED' WHERE grant_id = ? AND version_no = ?",
                grantId, versionNo);
    }

    public void markRevoked(long grantId, int versionNo, String requestId, Instant now) {
        jdbc.update("UPDATE rights_grant_version SET status = 'REVOKED', revoked_at = ?, revoke_request_id = ? WHERE grant_id = ? AND version_no = ?",
                Timestamp.from(now), requestId, grantId, versionNo);
        jdbc.update("UPDATE rights_grant SET updated_at = ? WHERE id = ?", Timestamp.from(now), grantId);
    }

    public List<GrantView> findVersionsByKey(String assetId, String region, String platform) {
        return jdbc.query("""
                        SELECT grant_id, version_no, asset_id, region, platform, window_start, window_end,
                               license_mode, status, request_id, created_at, revoked_at
                        FROM rights_grant_version
                        WHERE asset_id = ? AND region = ? AND platform = ? AND status = 'ACTIVE'
                        ORDER BY grant_id, version_no""",
                VIEW_MAPPER, assetId, region, platform);
    }

    public List<GrantView> findHistory(long grantId) {
        return jdbc.query("""
                        SELECT grant_id, version_no, asset_id, region, platform, window_start, window_end,
                               license_mode, status, request_id, created_at, revoked_at
                        FROM rights_grant_version
                        WHERE grant_id = ?
                        ORDER BY version_no""",
                VIEW_MAPPER, grantId);
    }

    public boolean grantExists(long grantId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM rights_grant WHERE id = ?", Integer.class, grantId);
        return count != null && count > 0;
    }

    public List<GrantVersionRow> findActiveRowsByKey(String assetId, String region, String platform) {
        return jdbc.query("""
                        SELECT id, grant_id, version_no, asset_id, region, platform, window_start, window_end,
                               license_mode, status
                        FROM rights_grant_version
                        WHERE asset_id = ? AND region = ? AND platform = ? AND status = 'ACTIVE'""",
                ROW_MAPPER, assetId, region, platform);
    }

    public GrantVersionRow findActiveRow(long grantId) {
        List<GrantVersionRow> rows = jdbc.query("""
                        SELECT id, grant_id, version_no, asset_id, region, platform, window_start, window_end,
                               license_mode, status
                        FROM rights_grant_version WHERE grant_id = ? AND status = 'ACTIVE'""",
                ROW_MAPPER, grantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public GrantVersionRow findRowById(long rowId) {
        List<GrantVersionRow> rows = jdbc.query("""
                        SELECT id, grant_id, version_no, asset_id, region, platform, window_start, window_end,
                               license_mode, status
                        FROM rights_grant_version WHERE id = ?""",
                ROW_MAPPER, rowId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public GrantVersionRow findActiveRowForUpdate(long grantId) {
        List<GrantVersionRow> rows = jdbc.query("""
                        SELECT id, grant_id, version_no, asset_id, region, platform, window_start, window_end,
                               license_mode, status
                        FROM rights_grant_version WHERE grant_id = ? AND status = 'ACTIVE' FOR UPDATE""",
                ROW_MAPPER, grantId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
