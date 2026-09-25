package com.example.starter.cosmetic.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.example.starter.cosmetic.domain.ProductRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 产品主档数据访问。
 */
@Repository
public class ProductRepository {

    private final JdbcClient jdbc;

    public ProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 新建产品，返回自增主键。
     */
    public long insert(String name, String applicant, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO product (name, applicant, status, created_at) VALUES (:name, :applicant, 'ACTIVE', :now)")
                .param("name", name)
                .param("applicant", applicant)
                .param("now", Timestamp.from(now))
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按主键查询产品。
     */
    public Optional<ProductRow> findById(long id) {
        return jdbc.sql("SELECT id, name, applicant, status, created_at FROM product WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> new ProductRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("applicant"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    /**
     * 更新产品状态（如注销后置为 CANCELLED）。
     */
    public void updateStatus(long id, String status) {
        jdbc.sql("UPDATE product SET status = :status WHERE id = :id")
                .param("status", status)
                .param("id", id)
                .update();
    }
}
