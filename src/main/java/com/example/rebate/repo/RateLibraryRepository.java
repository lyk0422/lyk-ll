package com.example.rebate.repo;

import com.example.rebate.domain.RateLibrary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退税率文库版本数据访问（合成数据）。
 */
@Repository
public class RateLibraryRepository {

    private static final RowMapper<RateLibrary> MAPPER = new RateLibraryRowMapper();

    private final JdbcTemplate jdbc;

    public RateLibraryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记一个文库版本（合成数据维护用）。
     */
    @Transactional
    public void insert(RateLibrary lib) {
        jdbc.update("""
                INSERT INTO t_rate_library (product_code, version_no, effective_from, rebate_rate, created_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                lib.productCode(), lib.versionNo(), lib.effectiveFrom(), lib.rebateRate());
    }

    /**
     * 查询某商品编码全部版本（复算/管理用）。
     */
    public List<RateLibrary> findByProductCode(String productCode) {
        return jdbc.query(
                "SELECT id, product_code, version_no, effective_from, rebate_rate FROM t_rate_library "
                        + "WHERE product_code = ? ORDER BY effective_from DESC, id DESC",
                MAPPER, productCode);
    }

    /**
     * 按申报日（UTC 日界）取有效文库版本：{@code effective_from <= 申报日} 中生效日最新者。
     */
    public Optional<RateLibrary> findEffectiveVersion(String productCode, LocalDate declareDate) {
        List<RateLibrary> list = jdbc.query(
                "SELECT id, product_code, version_no, effective_from, rebate_rate FROM t_rate_library "
                        + "WHERE product_code = ? AND effective_from <= ? "
                        + "ORDER BY effective_from DESC, id DESC LIMIT 1",
                MAPPER, productCode, java.sql.Date.valueOf(declareDate));
        return list.stream().findFirst();
    }

    private static final class RateLibraryRowMapper implements RowMapper<RateLibrary> {
        @Override
        public RateLibrary mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RateLibrary(
                    rs.getLong("id"),
                    rs.getString("product_code"),
                    rs.getString("version_no"),
                    rs.getDate("effective_from").toLocalDate(),
                    rs.getBigDecimal("rebate_rate"));
        }
    }
}
