package com.example.starter.waste;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 处理设施目录数据访问。
 */
@Repository
public class FacilityRepository {

    private final JdbcClient jdbc;

    public FacilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按编码查询目录内有效设施。
     */
    public Optional<Facility> findActiveByCode(String code) {
        return jdbc.sql("SELECT id, code, name FROM facility WHERE code = :code AND active = TRUE")
                .param("code", code)
                .query((rs, rowNum) -> new Facility(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name")))
                .optional();
    }

    /**
     * 按主键查询设施编码。
     */
    public String findCodeById(long facilityId) {
        return jdbc.sql("SELECT code FROM facility WHERE id = :id")
                .param("id", facilityId)
                .query(String.class)
                .single();
    }

    /**
     * 设施目录行。
     */
    public record Facility(long id, String code, String name) {
    }
}
