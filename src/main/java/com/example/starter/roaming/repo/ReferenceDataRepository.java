package com.example.starter.roaming.repo;

import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 合成基础数据访问：运营商费率、车辆、充电桩。
 */
@Repository
public class ReferenceDataRepository {

    private final JdbcTemplate jdbc;

    public ReferenceDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 运营商费率。 */
    public record OperatorRow(Long id, String code, String name,
                              BigDecimal tariffReceivable, BigDecimal tariffPayable) {
    }

    /** 车辆。 */
    public record VehicleRow(Long id, String certFingerprint, Long homeOperatorId, boolean enabled) {
    }

    /** 充电桩。 */
    public record PileRow(Long id, String pileNo, Long operatorId, BigDecimal powerKw, boolean enabled) {
    }

    private static final RowMapper<OperatorRow> OPERATOR_MAPPER = (rs, n) -> new OperatorRow(
            rs.getLong("id"),
            rs.getString("operator_code"),
            rs.getString("operator_name"),
            rs.getBigDecimal("tariff_receivable"),
            rs.getBigDecimal("tariff_payable"));

    private static final RowMapper<VehicleRow> VEHICLE_MAPPER = (rs, n) -> new VehicleRow(
            rs.getLong("id"),
            rs.getString("cert_fingerprint"),
            rs.getLong("home_operator_id"),
            rs.getInt("enabled") == 1);

    private static final RowMapper<PileRow> PILE_MAPPER = (rs, n) -> new PileRow(
            rs.getLong("id"),
            rs.getString("pile_no"),
            rs.getLong("operator_id"),
            rs.getBigDecimal("power_kw"),
            rs.getInt("enabled") == 1);

    public Optional<VehicleRow> findVehicle(String fingerprint) {
        return jdbc.query(
                "SELECT id, cert_fingerprint, home_operator_id, enabled FROM vehicle WHERE cert_fingerprint = ?",
                VEHICLE_MAPPER, fingerprint).stream().findFirst();
    }

    public Optional<PileRow> findPile(String pileNo) {
        return jdbc.query(
                "SELECT id, pile_no, operator_id, power_kw, enabled FROM charging_pile WHERE pile_no = ?",
                PILE_MAPPER, pileNo).stream().findFirst();
    }

    public Optional<OperatorRow> findOperator(Long id) {
        return jdbc.query(
                "SELECT id, operator_code, operator_name, tariff_receivable, tariff_payable FROM operator WHERE id = ?",
                OPERATOR_MAPPER, id).stream().findFirst();
    }
}
