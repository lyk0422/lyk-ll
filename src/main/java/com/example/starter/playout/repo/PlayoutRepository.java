package com.example.starter.playout.repo;

import com.example.starter.playout.domain.AsRunRow;
import com.example.starter.playout.domain.FingerprintRow;
import com.example.starter.playout.domain.LiveInsertRow;
import com.example.starter.playout.domain.PlaylistItemRow;
import com.example.starter.playout.domain.PlaylistVersionRow;
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
 * 播出编排数据访问；所有写操作均在服务层事务内调用，时间列统一 UTC 毫秒精度。
 * 目标库为 H2 内存库（MODE=MySQL）；频道行加 FOR UPDATE 行锁串行化同频道结算与插播。
 */
@Repository
public class PlayoutRepository {

    private final JdbcTemplate jdbc;

    public PlayoutRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Timestamp ts(Instant t) {
        return Timestamp.from(t);
    }

    // ---- channel ----

    /** 幂等确保频道行存在（仅在不存在时插入，绝不覆盖已绑定版本与水位线）。 */
    public void ensureChannel(String channel, Instant now) {
        jdbc.update("INSERT INTO channel (channel, current_version_id, watermark, created_at, updated_at) "
                        + "SELECT ?, NULL, NULL, ?, ? "
                        + "WHERE NOT EXISTS (SELECT 1 FROM channel WHERE channel = ?)",
                channel, ts(now), ts(now), channel);
    }

    /**
     * 读取频道行并加行锁；返回 [currentVersionId 可空, watermark 可空]。
     * 频道不存在时返回 null。
     */
    public Object[] lockChannel(String channel) {
        try {
            return jdbc.queryForObject(
                    "SELECT current_version_id, watermark FROM channel WHERE channel = ? FOR UPDATE",
                    (rs, n) -> {
                        Long versionId = (Long) rs.getObject("current_version_id");
                        Timestamp watermark = rs.getTimestamp("watermark");
                        return new Object[]{versionId, watermark == null ? null : watermark.toInstant()};
                    }, channel);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 不加锁读取当前绑定版本ID（可空）；频道不存在时返回 null。 */
    public Long findCurrentVersionId(String channel) {
        try {
            return jdbc.queryForObject(
                    "SELECT current_version_id FROM channel WHERE channel = ?",
                    Long.class, channel);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 不加锁读取水位线（可空）；频道不存在时返回 null。 */
    public Instant findWatermark(String channel) {
        try {
            Timestamp value = jdbc.queryForObject(
                    "SELECT watermark FROM channel WHERE channel = ?",
                    Timestamp.class, channel);
            return value == null ? null : value.toInstant();
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 绑定当前播单版本（发布成功后调用）。 */
    public void bindCurrentVersion(String channel, Long versionId, Instant now) {
        jdbc.update("UPDATE channel SET current_version_id = ?, updated_at = ? WHERE channel = ?",
                versionId, ts(now), channel);
    }

    /** 推进结算水位线；只允许单调前进。 */
    public int advanceWatermark(String channel, Instant newWatermark) {
        return jdbc.update("UPDATE channel SET watermark = ?, updated_at = ? "
                        + "WHERE channel = ? AND (watermark IS NULL OR watermark < ?)",
                ts(newWatermark), ts(newWatermark), channel, ts(newWatermark));
    }

    // ---- playlist ----

    public Optional<PlaylistVersionRow> findVersionByLabel(String channel, String versionLabel) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT id, channel, version_label, published_at FROM playlist_version "
                            + "WHERE channel = ? AND version_label = ?",
                    VERSION_MAPPER, channel, versionLabel));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public PlaylistVersionRow findVersionById(long versionId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, channel, version_label, published_at FROM playlist_version WHERE id = ?",
                    VERSION_MAPPER, versionId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public long insertVersion(String channel, String versionLabel, Instant publishedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO playlist_version (channel, version_label, published_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channel);
            ps.setString(2, versionLabel);
            ps.setTimestamp(3, ts(publishedAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void insertItem(long versionId, String itemId, String materialDigest,
                           Instant startTime, Instant endTime, int seqNo) {
        jdbc.update("INSERT INTO playlist_item "
                        + "(version_id, item_id, material_digest, start_time, end_time, seq_no) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                versionId, itemId, materialDigest, ts(startTime), ts(endTime), seqNo);
    }

    public List<PlaylistItemRow> findItemsByVersion(long versionId) {
        return jdbc.query(
                "SELECT id, version_id, item_id, material_digest, start_time, end_time, seq_no "
                        + "FROM playlist_item WHERE version_id = ? ORDER BY seq_no",
                ITEM_MAPPER, versionId);
    }

    /** 查版本内覆盖时刻 t 的节目项（半开 [start,end)）。 */
    public Optional<PlaylistItemRow> findItemAt(long versionId, Instant t) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT id, version_id, item_id, material_digest, start_time, end_time, seq_no "
                            + "FROM playlist_item WHERE version_id = ? AND start_time <= ? AND end_time > ?",
                    ITEM_MAPPER, versionId, ts(t), ts(t)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private static final RowMapper<PlaylistVersionRow> VERSION_MAPPER = (rs, n) -> new PlaylistVersionRow(
            rs.getLong("id"),
            rs.getString("channel"),
            rs.getString("version_label"),
            rs.getTimestamp("published_at").toInstant());

    private static final RowMapper<PlaylistItemRow> ITEM_MAPPER = (rs, n) -> new PlaylistItemRow(
            rs.getLong("id"),
            rs.getLong("version_id"),
            rs.getString("item_id"),
            rs.getString("material_digest"),
            rs.getTimestamp("start_time").toInstant(),
            rs.getTimestamp("end_time").toInstant(),
            rs.getInt("seq_no"));

    // ---- live insert ----

    public Optional<LiveInsertRow> findLive(String channel, String requestId) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT id, channel, request_id, priority, start_time, end_time, end_kind, "
                            + "fingerprint, first_sequence, created_at FROM live_insert "
                            + "WHERE channel = ? AND request_id = ?",
                    LIVE_MAPPER, channel, requestId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 查时刻 t 仍在播出的插播（start &lt;= t 且未结束或结束时刻 &gt; t），按优先级降序、requestId 升序。
     */
    public List<LiveInsertRow> findActiveLivesAt(String channel, Instant t) {
        return jdbc.query(
                "SELECT id, channel, request_id, priority, start_time, end_time, end_kind, "
                        + "fingerprint, first_sequence, created_at FROM live_insert "
                        + "WHERE channel = ? AND start_time <= ? "
                        + "AND (end_time IS NULL OR end_time > ?) "
                        + "ORDER BY priority DESC, request_id ASC",
                LIVE_MAPPER, channel, ts(t), ts(t));
    }

    /** 频道内所有未结束插播，按开始时间升序。 */
    public List<LiveInsertRow> findOpenLives(String channel) {
        return jdbc.query(
                "SELECT id, channel, request_id, priority, start_time, end_time, end_kind, "
                        + "fingerprint, first_sequence, created_at FROM live_insert "
                        + "WHERE channel = ? AND end_time IS NULL ORDER BY start_time ASC, request_id ASC",
                LIVE_MAPPER, channel);
    }

    /**
     * 查与半开窗口 (from, to] 有交集的插播（start &lt;= to 且未结束或 end &gt; from）；
     * from 为 null 时只约束 start &lt;= to。
     */
    public List<LiveInsertRow> findLivesOverlapping(String channel, Instant from, Instant to) {
        if (from == null) {
            return jdbc.query(
                    "SELECT id, channel, request_id, priority, start_time, end_time, end_kind, "
                            + "fingerprint, first_sequence, created_at FROM live_insert "
                            + "WHERE channel = ? AND start_time <= ? "
                            + "ORDER BY start_time ASC, request_id ASC",
                    LIVE_MAPPER, channel, ts(to));
        }
        return jdbc.query(
                "SELECT id, channel, request_id, priority, start_time, end_time, end_kind, "
                        + "fingerprint, first_sequence, created_at FROM live_insert "
                        + "WHERE channel = ? AND start_time <= ? "
                        + "AND (end_time IS NULL OR end_time > ?) "
                        + "ORDER BY start_time ASC, request_id ASC",
                LIVE_MAPPER, channel, ts(to), ts(from));
    }

    public long insertLive(String channel, String requestId, int priority, Instant startTime,
                           String fingerprint, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO live_insert "
                            + "(channel, request_id, priority, start_time, end_time, end_kind, "
                            + "fingerprint, first_sequence, created_at) "
                            + "VALUES (?, ?, ?, ?, NULL, NULL, ?, NULL, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channel);
            ps.setString(2, requestId);
            ps.setInt(3, priority);
            ps.setTimestamp(4, ts(startTime));
            ps.setString(5, fingerprint);
            ps.setTimestamp(6, ts(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void markLiveEnded(String channel, String requestId, Instant endTime, String endKind) {
        jdbc.update("UPDATE live_insert SET end_time = ?, end_kind = ? "
                        + "WHERE channel = ? AND request_id = ? AND end_time IS NULL",
                ts(endTime), endKind, channel, requestId);
    }

    public void updateFirstSequence(String channel, String requestId, long firstSequence) {
        jdbc.update("UPDATE live_insert SET first_sequence = ? "
                        + "WHERE channel = ? AND request_id = ? AND first_sequence IS NULL",
                firstSequence, channel, requestId);
    }

    private static final RowMapper<LiveInsertRow> LIVE_MAPPER = (rs, n) -> new LiveInsertRow(
            rs.getLong("id"),
            rs.getString("channel"),
            rs.getString("request_id"),
            rs.getInt("priority"),
            rs.getTimestamp("start_time").toInstant(),
            rs.getTimestamp("end_time") == null ? null : rs.getTimestamp("end_time").toInstant(),
            rs.getString("end_kind"),
            rs.getString("fingerprint"),
            (Long) rs.getObject("first_sequence"),
            rs.getTimestamp("created_at").toInstant());

    // ---- as-run ----

    public Optional<Long> maxChannelSeq(String channel) {
        Long value = jdbc.queryForObject(
                "SELECT MAX(channel_seq) FROM as_run WHERE channel = ?", Long.class, channel);
        return Optional.ofNullable(value);
    }

    public long insertAsRun(String channel, long channelSeq, Long versionId, String kind,
                            String itemId, String materialDigest, String liveRequestId,
                            Integer priority, Instant startTime, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO as_run (channel, channel_seq, version_id, kind, item_id, "
                            + "material_digest, live_request_id, priority, start_time, end_time, "
                            + "ack_state, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 'PENDING', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channel);
            ps.setLong(2, channelSeq);
            if (versionId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, versionId);
            }
            ps.setString(4, kind);
            ps.setString(5, itemId);
            ps.setString(6, materialDigest);
            ps.setString(7, liveRequestId);
            if (priority == null) {
                ps.setNull(8, java.sql.Types.INTEGER);
            } else {
                ps.setInt(8, priority);
            }
            ps.setTimestamp(9, ts(startTime));
            ps.setTimestamp(10, ts(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 频道当前唯一开放段（end_time IS NULL），按序号降序取首条。 */
    public Optional<AsRunRow> findOpenAsRun(String channel) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT id, channel, channel_seq, version_id, kind, item_id, material_digest, "
                            + "live_request_id, priority, start_time, end_time, ack_state, created_at "
                            + "FROM as_run WHERE channel = ? AND end_time IS NULL "
                            + "ORDER BY channel_seq DESC LIMIT 1",
                    AS_RUN_MAPPER, channel));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<AsRunRow> findAsRunBySeq(String channel, long channelSeq) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT id, channel, channel_seq, version_id, kind, item_id, material_digest, "
                            + "live_request_id, priority, start_time, end_time, ack_state, created_at "
                            + "FROM as_run WHERE channel = ? AND channel_seq = ?",
                    AS_RUN_MAPPER, channel, channelSeq));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void closeAsRun(long id, Instant endTime) {
        jdbc.update("UPDATE as_run SET end_time = ? WHERE id = ? AND end_time IS NULL",
                ts(endTime), id);
    }

    /** 物理删除 as-run 段（仅用于关闭从未真正播出的零长度 PENDING 段）。 */
    public void deleteAsRun(long id) {
        jdbc.update("DELETE FROM as_run WHERE id = ? AND end_time IS NULL AND ack_state = 'PENDING'", id);
    }

    /**
     * 条件 ACK：仅当记录仍为 PENDING 时置 ACKED；已被后续记录取代（迟到 ACK 场景下状态已推进）
     * 不会被改写。返回受影响行数。
     */
    public int compareAndSetAck(long id, String expected, String next) {
        return jdbc.update("UPDATE as_run SET ack_state = ? WHERE id = ? AND ack_state = ?",
                next, id, expected);
    }

    public List<AsRunRow> findAsRun(String channel, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, channel, channel_seq, version_id, kind, item_id, material_digest, "
                        + "live_request_id, priority, start_time, end_time, ack_state, created_at "
                        + "FROM as_run WHERE channel = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(channel);
        if (from != null) {
            sql.append(" AND start_time >= ?");
            args.add(ts(from));
        }
        if (to != null) {
            sql.append(" AND start_time < ?");
            args.add(ts(to));
        }
        sql.append(" ORDER BY channel_seq ASC");
        return jdbc.query(sql.toString(), AS_RUN_MAPPER, args.toArray());
    }

    private static final RowMapper<AsRunRow> AS_RUN_MAPPER = (rs, n) -> new AsRunRow(
            rs.getLong("id"),
            rs.getString("channel"),
            rs.getLong("channel_seq"),
            (Long) rs.getObject("version_id"),
            rs.getString("kind"),
            rs.getString("item_id"),
            rs.getString("material_digest"),
            rs.getString("live_request_id"),
            (Integer) rs.getObject("priority"),
            rs.getTimestamp("start_time").toInstant(),
            rs.getTimestamp("end_time") == null ? null : rs.getTimestamp("end_time").toInstant(),
            rs.getString("ack_state"),
            rs.getTimestamp("created_at").toInstant());

    // ---- idempotency fingerprint ----

    public Optional<FingerprintRow> findFingerprint(String channel, String action, String requestKey) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT id, channel, request_key, action, fingerprint, status_code, "
                            + "frozen_response, created_at FROM request_fingerprint "
                            + "WHERE channel = ? AND action = ? AND request_key = ?",
                    FP_MAPPER, channel, action, requestKey));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 插入冻结指纹；唯一冲突时受影响 0 行（并发首请求竞争）。 */
    public int insertFingerprint(String channel, String requestKey, String action, String fingerprint,
                                 int statusCode, String frozenResponse, Instant createdAt) {
        return jdbc.update("INSERT INTO request_fingerprint "
                        + "(channel, request_key, action, fingerprint, status_code, frozen_response, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                channel, requestKey, action, fingerprint, statusCode, frozenResponse, ts(createdAt));
    }

    private static final RowMapper<FingerprintRow> FP_MAPPER = (rs, n) -> new FingerprintRow(
            rs.getLong("id"),
            rs.getString("channel"),
            rs.getString("request_key"),
            rs.getString("action"),
            rs.getString("fingerprint"),
            rs.getInt("status_code"),
            rs.getString("frozen_response"),
            rs.getTimestamp("created_at").toInstant());
}
