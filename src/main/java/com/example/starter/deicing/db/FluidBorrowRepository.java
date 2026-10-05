package com.example.starter.deicing.db;

import com.example.starter.deicing.domain.BorrowStatus;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 跨班组借用单数据访问。
 */
@Repository
public class FluidBorrowRepository {

    private static final RowMapper<FluidBorrowPo> MAPPER = (ResultSet rs, int rowNum) -> map(rs);

    private static FluidBorrowPo map(ResultSet rs) throws SQLException {
        FluidBorrowPo po = new FluidBorrowPo();
        po.setId(rs.getLong("id"));
        po.setRequestId(rs.getString("request_id"));
        po.setFluidCode(rs.getString("fluid_code"));
        po.setTankCode(rs.getString("tank_code"));
        po.setQuantityLiters(rs.getBigDecimal("quantity_liters"));
        po.setFromTeam(rs.getString("from_team"));
        po.setToTeam(rs.getString("to_team"));
        po.setStatus(BorrowStatus.valueOf(rs.getString("status")));
        po.setFingerprint(rs.getString("fingerprint"));
        po.setCreatedAt(RowMappers.instant(rs, "created_at"));
        po.setReturnedAt(RowMappers.instant(rs, "returned_at"));
        return po;
    }

    private static final String COLUMNS = """
            id, request_id, fluid_code, tank_code, quantity_liters, from_team, to_team,
            status, fingerprint, created_at, returned_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public FluidBorrowRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(FluidBorrowPo po) {
        jdbcTemplate.update("""
                        INSERT INTO fluid_borrow
                            (request_id, fluid_code, tank_code, quantity_liters, from_team, to_team,
                             status, fingerprint, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                po.getRequestId(), po.getFluidCode(), po.getTankCode(), po.getQuantityLiters(),
                po.getFromTeam(), po.getToTeam(), po.getStatus().name(), po.getFingerprint(),
                Timestamp.from(po.getCreatedAt()));
    }

    public FluidBorrowPo findByRequestId(String requestId) {
        List<FluidBorrowPo> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM fluid_borrow WHERE request_id = ?",
                MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 该液罐是否存在未归还借用单。须在锁定除冰液行后调用。
     */
    public boolean existsOpenByTank(String tankCode) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM fluid_borrow
                        WHERE tank_code = ? AND status = 'OPEN'
                        """,
                Integer.class, tankCode);
        return count != null && count > 0;
    }

    public void markReturned(String requestId, Instant returnedAt) {
        jdbcTemplate.update("""
                        UPDATE fluid_borrow SET status = 'RETURNED', returned_at = ?
                        WHERE request_id = ?
                        """,
                Timestamp.from(returnedAt), requestId);
    }

    /**
     * 未归还借出量合计（升）。
     */
    public BigDecimal sumOpenByFluid(String fluidCode) {
        BigDecimal value = jdbcTemplate.queryForObject("""
                        SELECT COALESCE(SUM(quantity_liters), 0) FROM fluid_borrow
                        WHERE fluid_code = ? AND status = 'OPEN'
                        """,
                BigDecimal.class, fluidCode);
        return value == null ? BigDecimal.ZERO : value;
    }
}
