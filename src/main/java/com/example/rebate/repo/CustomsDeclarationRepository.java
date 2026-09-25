package com.example.rebate.repo;

import com.example.rebate.domain.CustomsDeclaration;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报关单数据访问。
 */
@Repository
public class CustomsDeclarationRepository {

    private static final RowMapper<CustomsDeclaration> MAPPER = new CustomsRowMapper();

    private final JdbcTemplate jdbc;

    public CustomsDeclarationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 采集报关单。报关单号唯一，重复采集返回 {@code false}（去重幂等）。
     */
    @Transactional
    public boolean insertIfAbsent(CustomsDeclaration d) {
        try {
            jdbc.update("""
                    INSERT INTO t_customs_declaration
                        (declaration_no, product_code, fob_amount, fob_usd, collected_at)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    d.declarationNo(), d.productCode(), d.fobAmount(), d.fobUsd(),
                    java.sql.Timestamp.from(d.collectedAt()));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 查询报关单。
     */
    public Optional<CustomsDeclaration> findByNo(String declarationNo) {
        List<CustomsDeclaration> list = jdbc.query(
                "SELECT id, declaration_no, product_code, fob_amount, fob_usd, collected_at "
                        + "FROM t_customs_declaration WHERE declaration_no = ?",
                MAPPER, declarationNo);
        return list.stream().findFirst();
    }

    /**
     * 查询全部已采集报关单号（管理/复算用）。
     */
    public List<String> findAllNos() {
        return jdbc.queryForList(
                "SELECT declaration_no FROM t_customs_declaration ORDER BY id", String.class);
    }

    private static final class CustomsRowMapper implements RowMapper<CustomsDeclaration> {
        @Override
        public CustomsDeclaration mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CustomsDeclaration(
                    rs.getLong("id"),
                    rs.getString("declaration_no"),
                    rs.getString("product_code"),
                    rs.getBigDecimal("fob_amount"),
                    rs.getBigDecimal("fob_usd"),
                    rs.getTimestamp("collected_at").toInstant());
        }
    }
}
