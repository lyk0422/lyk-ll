package com.example.starter.deicing.db;

import com.example.starter.deicing.domain.ReservationStatus;
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
 * 两步法预占数据访问。
 */
@Repository
public class FluidReservationRepository {

    private static final RowMapper<FluidReservationPo> MAPPER = (ResultSet rs, int rowNum) -> map(rs);

    private static FluidReservationPo map(ResultSet rs) throws SQLException {
        FluidReservationPo po = new FluidReservationPo();
        po.setId(rs.getLong("id"));
        po.setRequestId(rs.getString("request_id"));
        po.setFluidCode(rs.getString("fluid_code"));
        po.setQuantityLiters(rs.getBigDecimal("quantity_liters"));
        po.setStatus(ReservationStatus.valueOf(rs.getString("status")));
        po.setCreatedAt(RowMappers.instant(rs, "created_at"));
        po.setUpdatedAt(RowMappers.instant(rs, "updated_at"));
        return po;
    }

    private static final String COLUMNS =
            "id, request_id, fluid_code, quantity_liters, status, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;

    public FluidReservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(FluidReservationPo po) {
        jdbcTemplate.update("""
                        INSERT INTO fluid_reservation
                            (request_id, fluid_code, quantity_liters, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                po.getRequestId(), po.getFluidCode(), po.getQuantityLiters(), po.getStatus().name(),
                Timestamp.from(po.getCreatedAt()), Timestamp.from(po.getUpdatedAt()));
    }

    public FluidReservationPo findByRequestId(String requestId) {
        List<FluidReservationPo> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM fluid_reservation WHERE request_id = ?",
                MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 将 ACTIVE 预占迁移到目标状态（SETTLED/RELEASED），返回是否迁移成功。
     */
    public boolean transitionActive(String requestId, ReservationStatus target, Instant updatedAt) {
        int updated = jdbcTemplate.update("""
                        UPDATE fluid_reservation SET status = ?, updated_at = ?
                        WHERE request_id = ? AND status = 'ACTIVE'
                        """,
                target.name(), Timestamp.from(updatedAt), requestId);
        return updated > 0;
    }

    /**
     * 该液 ACTIVE 预占量合计（升）。
     */
    public BigDecimal sumActiveByFluid(String fluidCode) {
        BigDecimal value = jdbcTemplate.queryForObject("""
                        SELECT COALESCE(SUM(quantity_liters), 0) FROM fluid_reservation
                        WHERE fluid_code = ? AND status = 'ACTIVE'
                        """,
                BigDecimal.class, fluidCode);
        return value == null ? BigDecimal.ZERO : value;
    }
}
