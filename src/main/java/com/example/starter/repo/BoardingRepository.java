package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 上客记录数据访问。
 */
@Repository
public class BoardingRepository {

    private final JdbcTemplate jdbc;

    public BoardingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录一次上客，返回生成主键。
     */
    public long insert(long lineId, String requestId, int passengerCount) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO boarding (line_id, request_id, passenger_count) VALUES (?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, lineId);
            ps.setString(2, requestId);
            ps.setInt(3, passengerCount);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }
}
