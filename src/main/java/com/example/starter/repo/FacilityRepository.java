package com.example.starter.repo;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 合成处理设施目录的数据访问。
 */
@Repository
public class FacilityRepository {

    private final JdbcClient jdbc;

    public FacilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 处理设施行。
     *
     * @param id     设施 id
     * @param code   设施编码
     * @param name   设施名称
     * @param active 是否在目录内有效
     */
    public record FacilityRow(long id, String code, String name, boolean active) {
    }

    public Optional<FacilityRow> findByCode(String code) {
        return jdbc.sql("SELECT id, code, name, active FROM facility WHERE code = :code")
                .param("code", code)
                .query((rs, rowNum) -> new FacilityRow(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getInt("active") == 1))
                .optional();
    }
}
