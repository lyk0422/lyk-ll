package com.example.starter.rights.repo;

import com.example.starter.rights.LicenseMode;
import com.example.starter.rights.LicenseStatus;
import com.example.starter.rights.ScheduleItemStatus;
import com.example.starter.rights.model.LicenseVersion;
import com.example.starter.rights.model.PlaybackRecord;
import com.example.starter.rights.model.ScheduleItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 版权窗口数据访问层；时间均以 UTC 写入 DATETIME(3)，SQL 全部参数化。
 */
@Repository
public class RightsRepository {

    private final JdbcTemplate jdbc;

    public RightsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<LicenseVersion> LICENSE_MAPPER = (rs, n) -> new LicenseVersion(
            rs.getLong("id"),
            rs.getString("license_id"),
            rs.getInt("version_no"),
            rs.getString("asset_id"),
            rs.getString("region"),
            rs.getString("platform"),
            rs.getTimestamp("window_start").toInstant(),
            rs.getTimestamp("window_end").toInstant(),
            LicenseMode.valueOf(rs.getString("license_mode")),
            LicenseStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            Optional.ofNullable(rs.getTimestamp("revoked_at")).map(Timestamp::toInstant).orElse(null),
            rs.getString("request_id"));

    private static final RowMapper<ScheduleItem> ITEM_MAPPER = (rs, n) -> new ScheduleItem(
            rs.getLong("id"),
            rs.getString("submission_id"),
            rs.getInt("item_index"),
            rs.getString("asset_id"),
            rs.getString("region"),
            rs.getString("platform"),
            rs.getTimestamp("segment_start").toInstant(),
            rs.getTimestamp("segment_end").toInstant(),
            ScheduleItemStatus.valueOf(rs.getString("status")),
            rs.getString("license_id"),
            rs.getInt("license_version"),
            rs.getTimestamp("bound_at").toInstant(),
            Optional.ofNullable(rs.getTimestamp("blocked_at")).map(Timestamp::toInstant).orElse(null));

    private static final RowMapper<PlaybackRecord> PLAYBACK_MAPPER = (rs, n) -> new PlaybackRecord(
            rs.getLong("id"),
            rs.getString("submission_id"),
            rs.getInt("item_index"),
            rs.getTimestamp("air_start").toInstant(),
            rs.getTimestamp("air_end").toInstant(),
            rs.getString("license_id"),
            rs.getInt("license_version"),
            rs.getTimestamp("window_start").toInstant(),
            rs.getTimestamp("window_end").toInstant(),
            LicenseMode.valueOf(rs.getString("license_mode")),
            rs.getTimestamp("completed_at").toInstant(),
            rs.getString("request_id"));

    // ---- 授权作用域串行锁 ----

    /**
     * 在当前事务内对（内容,地区,平台）作用域加行锁：MERGE 保证锁行存在，
     * 随后 FOR UPDATE 取写锁直到事务提交/回滚，串行化该作用域的创建与更正，
     * 使独占冲突检查与插入构成不可交错的临界区。
     */
    public void lockScope(String assetId, String region, String platform) {
        jdbc.update("MERGE INTO scope_lock (asset_id, region, platform) KEY(asset_id, region, platform) VALUES (?,?,?)",
                assetId, region, platform);
        jdbc.query("SELECT 1 FROM scope_lock WHERE asset_id = ? AND region = ? AND platform = ? FOR UPDATE",
                (rs, n) -> 1, assetId, region, platform);
    }

    // ---- 授权版本 ----

    public void insertLicenseVersion(LicenseVersion v) {
        jdbc.update("""
                INSERT INTO license_version
                  (license_id, version_no, asset_id, region, platform, window_start, window_end,
                   license_mode, status, created_at, revoked_at, request_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                v.licenseId(), v.versionNo(), v.assetId(), v.region(), v.platform(),
                Timestamp.from(v.windowStart()), Timestamp.from(v.windowEnd()),
                v.mode().name(), v.status().name(), Timestamp.from(v.createdAt()),
                v.revokedAt() == null ? null : Timestamp.from(v.revokedAt()), v.requestId());
    }

    public List<LicenseVersion> findVersions(String licenseId) {
        return jdbc.query("SELECT * FROM license_version WHERE license_id = ? ORDER BY version_no",
                LICENSE_MAPPER, licenseId);
    }

    public Optional<LicenseVersion> findActiveVersion(String licenseId) {
        return jdbc.query("SELECT * FROM license_version WHERE license_id = ? AND status = 'ACTIVE'",
                LICENSE_MAPPER, licenseId).stream().findFirst();
    }

    public Optional<LicenseVersion> findVersion(String licenseId, int versionNo) {
        return jdbc.query("SELECT * FROM license_version WHERE license_id = ? AND version_no = ?",
                LICENSE_MAPPER, licenseId, versionNo).stream().findFirst();
    }

    /**
     * 查找能整段覆盖 [start,end) 的 ACTIVE 授权并对命中行加写意向行锁（FOR UPDATE），
     * 使排期提交与撤销在同一授权版本上形成确定先后顺序。覆盖条件（半开）：
     * window_start &lt;= start 且 end &lt;= window_end，端点对齐合法。
     */
    public List<LicenseVersion> findActiveCoversForUpdate(String assetId, String region, String platform,
                                                          Instant start, Instant end) {
        return jdbc.query("""
                SELECT * FROM license_version
                WHERE asset_id = ? AND region = ? AND platform = ? AND status = 'ACTIVE'
                  AND window_start <= ? AND ? <= window_end
                FOR UPDATE
                """, LICENSE_MAPPER, assetId, region, platform,
                Timestamp.from(start), Timestamp.from(end));
    }

    /**
     * 取一个整段覆盖的 ACTIVE 版本（多份非独占并存时确定性选择版本号最小、创建最早者）。
     */
    public Optional<LicenseVersion> findActiveCoverForUpdate(String assetId, String region, String platform,
                                                             Instant start, Instant end) {
        return findActiveCoversForUpdate(assetId, region, platform, start, end).stream()
                .min(java.util.Comparator.comparingInt(LicenseVersion::versionNo)
                        .thenComparing(LicenseVersion::createdAt)
                        .thenComparing(LicenseVersion::licenseId));
    }

    /**
     * 查找同内容/地区/平台下与给定窗口有正长度交集的其他有效授权版本。
     * 半开区间正长度交集条件：start &lt; other.end AND other.start &lt; end；端点相接不命中。
     */
    public List<LicenseVersion> findActiveOverlaps(String assetId, String region, String platform,
                                                   Instant start, Instant end, String excludeLicenseId) {
        return jdbc.query("""
                SELECT * FROM license_version
                WHERE asset_id = ? AND region = ? AND platform = ? AND status = 'ACTIVE'
                  AND window_start < ? AND ? < window_end
                  AND license_id <> ?
                """, LICENSE_MAPPER, assetId, region, platform,
                Timestamp.from(end), Timestamp.from(start), excludeLicenseId);
    }

    /** 将指定授权的 ACTIVE 版本置为 SUPERSEDED（仅当当前版本号匹配）。 */
    public int supersedeActive(String licenseId, int expectedVersion) {
        return jdbc.update("""
                UPDATE license_version SET status = 'SUPERSEDED'
                WHERE license_id = ? AND version_no = ? AND status = 'ACTIVE'
                """, licenseId, expectedVersion);
    }

    /** 将指定版本置为 REVOKED（仅当仍为 ACTIVE）；返回受影响行数用于乐观锁判定。 */
    public int markRevoked(String licenseId, int version, Instant revokedAt) {
        return jdbc.update("""
                UPDATE license_version SET status = 'REVOKED', revoked_at = ?
                WHERE license_id = ? AND version_no = ? AND status = 'ACTIVE'
                """, Timestamp.from(revokedAt), licenseId, version);
    }

    // ---- 排期 ----

    public void insertSubmission(String submissionId, String requestId, int itemCount, Instant createdAt) {
        jdbc.update("""
                INSERT INTO schedule_submission (submission_id, request_id, item_count, created_at)
                VALUES (?,?,?,?)
                """, submissionId, requestId, itemCount, Timestamp.from(createdAt));
    }

    public Optional<String> findSubmissionIdByRequest(String requestId) {
        return jdbc.query("SELECT submission_id FROM schedule_submission WHERE request_id = ?",
                (rs, n) -> rs.getString(1), requestId).stream().findFirst();
    }

    public void insertItem(ScheduleItem item) {
        jdbc.update("""
                INSERT INTO schedule_item
                  (submission_id, item_index, asset_id, region, platform, segment_start, segment_end,
                   status, license_id, license_version, bound_at, blocked_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                item.submissionId(), item.itemIndex(), item.assetId(), item.region(), item.platform(),
                Timestamp.from(item.segmentStart()), Timestamp.from(item.segmentEnd()),
                item.status().name(), item.licenseId(), item.licenseVersion(),
                Timestamp.from(item.boundAt()),
                item.blockedAt() == null ? null : Timestamp.from(item.blockedAt()));
    }

    public List<ScheduleItem> findItems(String submissionId) {
        return jdbc.query("SELECT * FROM schedule_item WHERE submission_id = ? ORDER BY item_index",
                ITEM_MAPPER, submissionId);
    }

    public Optional<ScheduleItem> findItem(String submissionId, int itemIndex) {
        return jdbc.query("SELECT * FROM schedule_item WHERE submission_id = ? AND item_index = ?",
                ITEM_MAPPER, submissionId, itemIndex).stream().findFirst();
    }

    /**
     * 撤销联动：绑定到该授权版本、状态为 BOUND 且尚未开始（segment_start 严格晚于撤销时刻）
     * 的时段转为 BLOCKED。返回受影响行数。
     */
    public int blockNotStartedItems(String licenseId, int licenseVersion, Instant cutoff) {
        return jdbc.update("""
                UPDATE schedule_item SET status = 'BLOCKED', blocked_at = ?
                WHERE license_id = ? AND license_version = ? AND status = 'BOUND'
                  AND segment_start > ?
                """, Timestamp.from(cutoff), licenseId, licenseVersion, Timestamp.from(cutoff));
    }

    // ---- 播出回执 ----

    public void insertPlayback(PlaybackRecord record) {
        jdbc.update("""
                INSERT INTO playback_record
                  (submission_id, item_index, air_start, air_end, license_id, license_version,
                   window_start, window_end, license_mode, completed_at, request_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,
                record.submissionId(), record.itemIndex(),
                Timestamp.from(record.airStart()), Timestamp.from(record.airEnd()),
                record.licenseId(), record.licenseVersion(),
                Timestamp.from(record.windowStart()), Timestamp.from(record.windowEnd()),
                record.mode().name(), Timestamp.from(record.completedAt()), record.requestId());
    }

    public Optional<PlaybackRecord> findPlaybackByRequest(String requestId) {
        return jdbc.query("SELECT * FROM playback_record WHERE request_id = ?",
                PLAYBACK_MAPPER, requestId).stream().findFirst();
    }

    public Optional<PlaybackRecord> findPlayback(String submissionId, int itemIndex) {
        return jdbc.query("SELECT * FROM playback_record WHERE submission_id = ? AND item_index = ?",
                PLAYBACK_MAPPER, submissionId, itemIndex).stream().findFirst();
    }

    public List<PlaybackRecord> findEvidenceBySubmission(String submissionId) {
        return jdbc.query("SELECT * FROM playback_record WHERE submission_id = ? ORDER BY item_index",
                PLAYBACK_MAPPER, submissionId);
    }

    public List<PlaybackRecord> findEvidenceByLicense(String licenseId) {
        return jdbc.query("SELECT * FROM playback_record WHERE license_id = ? ORDER BY submission_id, item_index",
                PLAYBACK_MAPPER, licenseId);
    }
}
