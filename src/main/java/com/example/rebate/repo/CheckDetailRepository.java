package com.example.rebate.repo;

import com.example.rebate.domain.CheckDetail;
import com.example.rebate.domain.CheckResult;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 关联校验明细数据访问，可按批次复算重放。
 */
@Repository
public class CheckDetailRepository {

    private static final RowMapper<CheckDetail> MAPPER = new DetailRowMapper();

    private final JdbcTemplate jdbc;

    public CheckDetailRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一条校验明细并回填自增主键。
     */
    public long insert(CheckDetail d) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO t_check_detail
                        (batch_id, declaration_no, invoice_no, product_code, library_version_id, version_no,
                         fob_amount, fob_usd, rebate_rate, tax_basis, exchange_cost, result, doubt_type)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, d.batchId());
            ps.setString(2, d.declarationNo());
            ps.setString(3, d.invoiceNo());
            ps.setString(4, d.productCode());
            if (d.libraryVersionId() == null) {
                ps.setObject(5, null);
            } else {
                ps.setLong(5, d.libraryVersionId());
            }
            ps.setString(6, d.versionNo());
            ps.setBigDecimal(7, d.fobAmount());
            ps.setBigDecimal(8, d.fobUsd());
            ps.setBigDecimal(9, d.rebateRate());
            ps.setBigDecimal(10, d.taxBasis());
            ps.setBigDecimal(11, d.exchangeCost());
            ps.setString(12, d.result().name());
            ps.setString(13, d.doubtType());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询某批次全部校验明细。
     */
    public List<CheckDetail> findByBatchId(long batchId) {
        return jdbc.query(BASE_SELECT + " WHERE batch_id = ? ORDER BY id", MAPPER, batchId);
    }

    /**
     * 查询某批次下指定报关单的校验明细（冲回冻结退税额取值依据）。
     */
    public Optional<CheckDetail> findByBatchAndDeclaration(long batchId, String declarationNo) {
        return jdbc.query(
                        BASE_SELECT + " WHERE batch_id = ? AND declaration_no = ? ORDER BY id LIMIT 1",
                        MAPPER, batchId, declarationNo).stream()
                .findFirst();
    }

    /**
     * 删除某批次校验明细（重新校验前清理）。
     */
    public void deleteByBatchId(long batchId) {
        jdbc.update("DELETE FROM t_check_detail WHERE batch_id = ?", batchId);
    }

    private static final String BASE_SELECT =
            "SELECT id, batch_id, declaration_no, invoice_no, product_code, library_version_id, "
                    + "version_no, fob_amount, fob_usd, rebate_rate, tax_basis, exchange_cost, "
                    + "result, doubt_type FROM t_check_detail";

    private static final class DetailRowMapper implements RowMapper<CheckDetail> {
        @Override
        public CheckDetail mapRow(ResultSet rs, int rowNum) throws SQLException {
            Long versionId = (Long) rs.getObject("library_version_id");
            BigDecimal rate = rs.getBigDecimal("rebate_rate");
            BigDecimal basis = rs.getBigDecimal("tax_basis");
            BigDecimal cost = rs.getBigDecimal("exchange_cost");
            return new CheckDetail(
                    rs.getLong("id"),
                    rs.getLong("batch_id"),
                    rs.getString("declaration_no"),
                    rs.getString("invoice_no"),
                    rs.getString("product_code"),
                    versionId,
                    rs.getString("version_no"),
                    rs.getBigDecimal("fob_amount"),
                    rs.getBigDecimal("fob_usd"),
                    rate == null ? null : rate.setScale(6, java.math.RoundingMode.HALF_UP),
                    basis == null ? null : basis.setScale(4, java.math.RoundingMode.HALF_UP),
                    cost == null ? null : cost.setScale(6, java.math.RoundingMode.HALF_UP),
                    CheckResult.valueOf(rs.getString("result")),
                    rs.getString("doubt_type"));
        }
    }
}
