package com.example.starter.waste;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 收运联单数据访问。
 */
@Repository
public class ManifestRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, request_id, fingerprint, vehicle_id, community, category, facility_id,
                   gross_kg, tare_kg, net_kg, status, created_at
            FROM manifest
            """;

    private final JdbcClient jdbc;

    public ManifestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按幂等键查询联单。
     */
    public Optional<Manifest> findByRequestId(String requestId) {
        return jdbc.sql(SELECT_COLUMNS + " WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query(this::map)
                .optional();
    }

    /**
     * 按主键查询联单。
     */
    public Optional<Manifest> findById(long id) {
        return jdbc.sql(SELECT_COLUMNS + " WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    /**
     * 登记联单；active_vehicle_key 置为车辆主键，唯一约束保证一车一执行中联单。
     */
    public long insert(ManifestDraft draft) {
        jdbc.sql("""
                INSERT INTO manifest (request_id, fingerprint, vehicle_id, community, category, facility_id,
                                      gross_kg, tare_kg, net_kg, status, active_vehicle_key, created_at)
                VALUES (:requestId, :fingerprint, :vehicleId, :community, :category, :facilityId,
                        :grossKg, :tareKg, :netKg, :status, :activeVehicleKey, :createdAt)
                """)
                .param("requestId", draft.requestId())
                .param("fingerprint", draft.fingerprint())
                .param("vehicleId", draft.vehicleId())
                .param("community", draft.community())
                .param("category", draft.category())
                .param("facilityId", draft.facilityId())
                .param("grossKg", draft.grossKg())
                .param("tareKg", draft.tareKg())
                .param("netKg", draft.netKg())
                .param("status", draft.status().name())
                .param("activeVehicleKey", draft.status() == ManifestStatus.IN_PROGRESS ? draft.vehicleId() : null)
                .param("createdAt", Timestamp.valueOf(draft.createdAt()))
                .update();
        return findByRequestId(draft.requestId()).orElseThrow().id();
    }

    /**
     * 联单离开执行中状态：更新状态并释放车辆占位键。
     */
    public void closeStatus(long manifestId, ManifestStatus status) {
        jdbc.sql("UPDATE manifest SET status = :status, active_vehicle_key = NULL WHERE id = :id")
                .param("status", status.name())
                .param("id", manifestId)
                .update();
    }

    private Manifest map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Manifest(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("vehicle_id"),
                rs.getString("community"),
                rs.getString("category"),
                rs.getLong("facility_id"),
                rs.getBigDecimal("gross_kg"),
                rs.getBigDecimal("tare_kg"),
                rs.getBigDecimal("net_kg"),
                ManifestStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    /**
     * 联单登记草稿。
     */
    public record ManifestDraft(
            String requestId,
            String fingerprint,
            long vehicleId,
            String community,
            String category,
            long facilityId,
            java.math.BigDecimal grossKg,
            java.math.BigDecimal tareKg,
            java.math.BigDecimal netKg,
            ManifestStatus status,
            LocalDateTime createdAt) {
    }
}
