package com.example.starter.repo;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 风场数据访问。
 */
@Repository
public class FarmRepository {

    private final JdbcTemplate jdbc;

    public FarmRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按 id 查询风场编码。
     */
    public Optional<String> findById(long id) {
        return jdbc.query("SELECT code FROM farm WHERE id = ?",
                        (rs, i) -> rs.getString(1), id)
                .stream()
                .findFirst();
    }
}
