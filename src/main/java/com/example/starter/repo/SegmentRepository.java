package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 线路分段数据访问。
 */
@Repository
public class SegmentRepository {

    /** 分段行记录。 */
    public record SegmentRow(long id, long lineId, int seq, int fromStationSeq, int toStationSeq, String name) {
    }

    private final JdbcTemplate jdbc;

    public SegmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SegmentRow> findById(long id) {
        return jdbc.query("SELECT id, line_id, seq, from_station_seq, to_station_seq, name FROM segment WHERE id = ?",
                (rs, n) -> map(rs), id).stream().findFirst();
    }

    /** 按顺序号升序返回线路全部分段。 */
    public List<SegmentRow> findByLineId(long lineId) {
        return jdbc.query("SELECT id, line_id, seq, from_station_seq, to_station_seq, name FROM segment "
                + "WHERE line_id = ? ORDER BY seq", (rs, n) -> map(rs), lineId);
    }

    private SegmentRow map(ResultSet rs) throws SQLException {
        return new SegmentRow(rs.getLong("id"), rs.getLong("line_id"), rs.getInt("seq"),
                rs.getInt("from_station_seq"), rs.getInt("to_station_seq"), rs.getString("name"));
    }
}
