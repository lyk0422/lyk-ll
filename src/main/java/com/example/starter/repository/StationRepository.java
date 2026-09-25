package com.example.starter.repository;

import com.example.starter.domain.Station;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 换热站数据访问。
 */
@Repository
public class StationRepository {

    private static final RowMapper<Station> MAPPER = (rs, n) ->
            new Station(rs.getLong("id"), rs.getString("code"), rs.getString("name"));

    private final JdbcTemplate jdbc;

    public StationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按 ID 查询换热站。
     */
    public Optional<Station> findById(long id) {
        return jdbc.query("SELECT id, code, name FROM heat_station WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行级写锁（SELECT ... FOR UPDATE），用于串行化站内调节任务受理。
     */
    public Optional<Station> lockById(long id) {
        return jdbc.query("SELECT id, code, name FROM heat_station WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }
}
