package com.example.starter.repo;

import com.example.starter.model.WindStation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 站点数据访问。
 */
@Repository
public class StationRepository {

    private static final RowMapper<WindStation> MAPPER = (rs, n) -> new WindStation(
            rs.getLong("id"), rs.getLong("line_id"), rs.getInt("seq_no"), rs.getString("name"));

    private final JdbcTemplate jdbc;

    public StationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按 id 查询站点。
     */
    public Optional<WindStation> findById(long id) {
        return jdbc.query("SELECT id, line_id, seq_no, name FROM wind_station WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询线路全部站点（按顺序号升序）。
     */
    public List<WindStation> findByLineId(long lineId) {
        return jdbc.query("SELECT id, line_id, seq_no, name FROM wind_station WHERE line_id = ? ORDER BY seq_no",
                MAPPER, lineId);
    }
}
