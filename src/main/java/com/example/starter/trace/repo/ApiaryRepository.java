package com.example.starter.trace.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 蜂场主数据访问。
 */
@Repository
public class ApiaryRepository {

    private final JdbcTemplate jdbc;

    public ApiaryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 判断蜂场是否存在。
     */
    public boolean existsById(long apiaryId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM apiary WHERE id = ?", Integer.class, apiaryId);
        return count != null && count > 0;
    }

    /**
     * 按 ID 查询蜂场编码与名称。
     */
    public Optional<ApiaryRow> findById(long apiaryId) {
        return jdbc.query(
                        "SELECT id, code, name FROM apiary WHERE id = ?",
                        (rs, i) -> new ApiaryRow(rs.getLong("id"), rs.getString("code"), rs.getString("name")),
                        apiaryId)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行级写锁，用于召回创建与采收批登记在同一蜂场上的串行化。
     */
    public Optional<ApiaryRow> findByIdForUpdate(long apiaryId) {
        return jdbc.query(
                        "SELECT id, code, name FROM apiary WHERE id = ? FOR UPDATE",
                        (rs, i) -> new ApiaryRow(rs.getLong("id"), rs.getString("code"), rs.getString("name")),
                        apiaryId)
                .stream().findFirst();
    }

    /**
     * 蜂场行记录。
     */
    public record ApiaryRow(Long id, String code, String name) {
    }
}
