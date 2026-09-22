package com.example.starter.perception.repository;

import com.example.starter.perception.domain.Track;
import com.example.starter.perception.domain.TrackState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 轨迹当前态表（track）数据访问。
 */
@Repository
public class TrackRepository {

    private static final TrackRowMapper ROW_MAPPER = new TrackRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public TrackRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 插入新轨迹（version 由实体携带，首版为1）。 */
    public void insert(Track track) {        String sql = "INSERT INTO track (track_id, category, state, confidence, pos_x, pos_y, "
                + "last_observed_at, evidence, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql,
                track.getTrackId(),
                track.getCategory(),
                track.getState().name(),
                track.getConfidence(),
                track.getPosX(),
                track.getPosY(),
                toTimestamp(track.getLastObservedAt()),
                track.getEvidence(),
                track.getVersion(),
                toTimestamp(track.getCreatedAt()),
                toTimestamp(track.getUpdatedAt()));
    }

    /**
     * 取轨迹业务序号（H2 序列 seq_track_no），生成确定性轨迹ID。
     */
    public long nextTrackNumber() {
        Long value = jdbcTemplate.queryForObject("SELECT NEXT VALUE FOR seq_track_no", Long.class);
        return value == null ? 0L : value;
    }

    /** 按轨迹ID查询当前态。 */
    public Optional<Track> findById(String trackId) {
        String sql = "SELECT track_id, category, state, confidence, pos_x, pos_y, last_observed_at, "
                + "evidence, version, created_at, updated_at FROM track WHERE track_id = ?";
        List<Track> tracks = jdbcTemplate.query(sql, ROW_MAPPER, trackId);
        return tracks.stream().findFirst();
    }

    /** 列出全部轨迹当前态（按轨迹ID排序，保证结果确定性）。 */
    public List<Track> findAll() {
        String sql = "SELECT track_id, category, state, confidence, pos_x, pos_y, last_observed_at, "
                + "evidence, version, created_at, updated_at FROM track ORDER BY track_id";
        return jdbcTemplate.query(sql, ROW_MAPPER);
    }

    /**
     * 按类别与状态集合查询轨迹（关联候选），参数化 IN 占位符。
     */
    public List<Track> findByCategoryAndStates(String category, List<String> states) {
        if (states.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", states.stream().map(s -> "?").toList());
        String sql = "SELECT track_id, category, state, confidence, pos_x, pos_y, last_observed_at, "
                + "evidence, version, created_at, updated_at FROM track "
                + "WHERE category = ? AND state IN (" + placeholders + ") ORDER BY track_id";
        Object[] args = new Object[states.size() + 1];
        args[0] = category;
        for (int i = 0; i < states.size(); i++) {
            args[i + 1] = states.get(i);
        }
        return jdbcTemplate.query(sql, ROW_MAPPER, args);
    }

    /**
     * 按状态集合查询全部类别的轨迹（批末超时补账用），按轨迹ID升序保证处理顺序确定。
     */
    public List<Track> findByStates(List<String> states) {
        if (states.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", states.stream().map(s -> "?").toList());
        String sql = "SELECT track_id, category, state, confidence, pos_x, pos_y, last_observed_at, "
                + "evidence, version, created_at, updated_at FROM track "
                + "WHERE state IN (" + placeholders + ") ORDER BY track_id";
        return jdbcTemplate.query(sql, ROW_MAPPER, states.toArray());
    }

    /**
     * 乐观锁条件更新：仅当数据库版本等于 expectedVersion 时写入并把版本加1。
     *
     * @return 是否更新成功（false=版本冲突，调用方必须回滚/拒绝整批请求）
     */
    public boolean compareAndUpdate(Track track, long expectedVersion) {
        String sql = "UPDATE track SET state = ?, confidence = ?, pos_x = ?, pos_y = ?, "
                + "last_observed_at = ?, evidence = ?, version = ?, updated_at = ? "
                + "WHERE track_id = ? AND version = ?";
        int affected = jdbcTemplate.update(sql,
                track.getState().name(),
                track.getConfidence(),
                track.getPosX(),
                track.getPosY(),
                toTimestamp(track.getLastObservedAt()),
                track.getEvidence(),
                track.getVersion(),
                toTimestamp(track.getUpdatedAt()),
                track.getTrackId(),
                expectedVersion);
        return affected == 1;
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static final class TrackRowMapper implements RowMapper<Track> {

        @Override
        public Track mapRow(ResultSet rs, int rowNum) throws SQLException {
            Track track = new Track();
            track.setTrackId(rs.getString("track_id"));
            track.setCategory(rs.getString("category"));
            track.setState(TrackState.valueOf(rs.getString("state")));
            track.setConfidence(rs.getDouble("confidence"));
            track.setPosX(rs.getDouble("pos_x"));
            track.setPosY(rs.getDouble("pos_y"));
            track.setLastObservedAt(toInstant(rs.getTimestamp("last_observed_at")));
            track.setEvidence(rs.getString("evidence"));
            track.setVersion(rs.getLong("version"));
            track.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
            track.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
            return track;
        }

        private static Instant toInstant(Timestamp timestamp) {
            if (timestamp == null) {
                return null;
            }
            return LocalDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC).toInstant(ZoneOffset.UTC);
        }
    }
}
