package com.example.starter.repo;

import com.example.starter.model.WindSegment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 分段数据访问。
 */
@Repository
public class SegmentRepository {

    private static final RowMapper<WindSegment> MAPPER = (rs, n) -> new WindSegment(
            rs.getLong("id"), rs.getLong("line_id"), rs.getInt("seq_no"),
            rs.getLong("from_station_id"), rs.getLong("to_station_id"));

    private final JdbcTemplate jdbc;

    public SegmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按 id 查询分段。
     */
    public Optional<WindSegment> findById(long id) {
        return jdbc.query(
                "SELECT id, line_id, seq_no, from_station_id, to_station_id FROM wind_segment WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 查询线路全部分段（按顺序号升序）。
     */
    public List<WindSegment> findByLineId(long lineId) {
        return jdbc.query(
                "SELECT id, line_id, seq_no, from_station_id, to_station_id FROM wind_segment "
                        + "WHERE line_id = ? ORDER BY seq_no",
                MAPPER, lineId);
    }

    /**
     * 查询以指定站点为端点的分段。
     */
    public List<WindSegment> findByStation(long lineId, long stationId) {
        return jdbc.query(
                "SELECT id, line_id, seq_no, from_station_id, to_station_id FROM wind_segment "
                        + "WHERE line_id = ? AND (from_station_id = ? OR to_station_id = ?) ORDER BY seq_no",
                MAPPER, lineId, stationId, stationId);
    }
}
