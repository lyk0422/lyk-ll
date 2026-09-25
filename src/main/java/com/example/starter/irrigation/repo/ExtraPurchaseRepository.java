package com.example.starter.irrigation.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 临时加购数据访问。
 */
@Repository
public class ExtraPurchaseRepository {

    /**
     * 加购单行。
     */
    public record ExtraPurchaseRow(long id, long groupId, int roundNo, String requestId, long amountM3) {
    }

    /**
     * 加购分配明细行。
     */
    public record ExtraPurchaseDetailRow(long outletId, long amountM3) {
    }

    private static final RowMapper<ExtraPurchaseRow> PURCHASE_MAPPER = (rs, n) ->
            new ExtraPurchaseRow(rs.getLong("id"), rs.getLong("group_id"), rs.getInt("round_no"),
                    rs.getString("request_id"), rs.getLong("amount_m3"));

    private static final RowMapper<ExtraPurchaseDetailRow> DETAIL_MAPPER = (rs, n) ->
            new ExtraPurchaseDetailRow(rs.getLong("outlet_id"), rs.getLong("amount_m3"));

    private final JdbcTemplate jdbc;

    public ExtraPurchaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ExtraPurchaseRow> findByRequestId(String requestId) {
        List<ExtraPurchaseRow> rows = jdbc.query("SELECT id, group_id, round_no, request_id, amount_m3"
                + " FROM extra_purchase WHERE request_id = ?", PURCHASE_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    public long insert(long groupId, int roundNo, String requestId, long amountM3) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO extra_purchase (group_id, round_no, request_id, amount_m3)"
                            + " VALUES (?, ?, ?, ?)", new String[]{"id"});
            ps.setLong(1, groupId);
            ps.setInt(2, roundNo);
            ps.setString(3, requestId);
            ps.setLong(4, amountM3);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public void insertDetail(long purchaseId, long outletId, long amountM3) {
        jdbc.update("INSERT INTO extra_purchase_detail (purchase_id, outlet_id, amount_m3)"
                + " VALUES (?, ?, ?)", purchaseId, outletId, amountM3);
    }

    public List<ExtraPurchaseDetailRow> findDetails(long purchaseId) {
        return jdbc.query("SELECT outlet_id, amount_m3 FROM extra_purchase_detail"
                + " WHERE purchase_id = ? ORDER BY outlet_id", DETAIL_MAPPER, purchaseId);
    }
}
