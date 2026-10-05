package com.example.starter.gas.repo;

import com.example.starter.gas.domain.QualitySampleRow;
import com.example.starter.gas.domain.VerificationRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 气质采样、组分核验单、差异签认与确认暂停的数据访问。
 */
@Repository
public class QualityRepository {

    private static final RowMapper<QualitySampleRow> SAMPLE_MAPPER = (rs, rowNum) -> new QualitySampleRow(
            rs.getLong("id"),
            rs.getString("station_code"),
            rs.getTimestamp("sample_time").toInstant(),
            rs.getDate("gas_date").toLocalDate(),
            rs.getBigDecimal("sample_volume"),
            rs.getBigDecimal("heating_value"),
            rs.getString("components"),
            rs.getString("request_id"));

    private static final RowMapper<VerificationRow> VERIFICATION_MAPPER = (rs, rowNum) -> new VerificationRow(
            rs.getLong("id"),
            rs.getString("verification_no"),
            rs.getString("request_id"),
            rs.getString("station_code"),
            rs.getDate("gas_date").toLocalDate(),
            rs.getString("voucher_no"),
            rs.getInt("version"),
            rs.getInt("sample_count"),
            rs.getBigDecimal("verified_heating_value"),
            rs.getBigDecimal("voucher_heating_value"),
            rs.getBigDecimal("relative_deviation"),
            rs.getInt("difference_flag") == 1,
            rs.getString("status"),
            rs.getTimestamp("sign_off_a_at") == null ? null : rs.getTimestamp("sign_off_a_at").toInstant(),
            rs.getTimestamp("sign_off_b_at") == null ? null : rs.getTimestamp("sign_off_b_at").toInstant(),
            rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public QualityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询组分热值；未知组分返回 null。 */
    public BigDecimal findComponentHeatingValue(String componentCode) {
        List<BigDecimal> values = jdbc.query(
                "SELECT heating_value FROM component_heating_value WHERE component_code = ?",
                (rs, rowNum) -> rs.getBigDecimal("heating_value"), componentCode);
        return values.isEmpty() ? null : values.get(0);
    }

    /** 按“站＋采样时刻”查询既有采样；不存在返回 null。 */
    public QualitySampleRow findSample(String stationCode, Instant sampleTime) {
        List<QualitySampleRow> rows = jdbc.query(
                "SELECT * FROM quality_sample WHERE station_code = ? AND sample_time = ?",
                SAMPLE_MAPPER, stationCode, Timestamp.from(sampleTime));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 插入采样记录；同站同时刻冲突（重复采样）返回 false，由调用方去重。
     */
    public boolean insertSample(String stationCode, Instant sampleTime, LocalDate gasDate,
                                BigDecimal sampleVolume, BigDecimal heatingValue,
                                String componentsJson, String requestId) {
        try {
            jdbc.update(
                    "INSERT INTO quality_sample (station_code, sample_time, gas_date, sample_volume, "
                            + "heating_value, components, request_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    stationCode, Timestamp.from(sampleTime), java.sql.Date.valueOf(gasDate),
                    sampleVolume, heatingValue, componentsJson, requestId);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /** 查询某站某气量日全部采样（按采样时刻升序，结果稳定）。 */
    public List<QualitySampleRow> findSamplesOfDay(String stationCode, LocalDate gasDate) {
        return jdbc.query(
                "SELECT * FROM quality_sample WHERE station_code = ? AND gas_date = ? "
                        + "ORDER BY sample_time, id",
                SAMPLE_MAPPER, stationCode, java.sql.Date.valueOf(gasDate));
    }

    /** 按核验单号查询；不存在返回 null。 */
    public VerificationRow findVerificationByNo(String verificationNo) {
        List<VerificationRow> rows = jdbc.query(
                "SELECT * FROM quality_verification WHERE verification_no = ?",
                VERIFICATION_MAPPER, verificationNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询某站某气量日的全部核验版本（版本号升序，旧结论可查）。 */
    public List<VerificationRow> findVerifications(String stationCode, LocalDate gasDate) {
        return jdbc.query(
                "SELECT * FROM quality_verification WHERE station_code = ? AND gas_date = ? "
                        + "ORDER BY version",
                VERIFICATION_MAPPER, stationCode, java.sql.Date.valueOf(gasDate));
    }

    /** 当前生效版本号；尚未核验过返回 null。 */
    public Integer findCurrentVersion(String stationCode, LocalDate gasDate) {
        List<Integer> values = jdbc.query(
                "SELECT current_version FROM station_day_verification "
                        + "WHERE station_code = ? AND gas_date = ?",
                (rs, rowNum) -> rs.getInt("current_version"),
                stationCode, java.sql.Date.valueOf(gasDate));
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * 占用“站＋气量日”版本序列（首个版本）；并发重复占用抛 {@link DuplicateKeyException}。
     */
    public void insertVersionSeq(String stationCode, LocalDate gasDate, int version) {
        jdbc.update(
                "INSERT INTO station_day_verification (station_code, gas_date, current_version) "
                        + "VALUES (?, ?, ?)",
                stationCode, java.sql.Date.valueOf(gasDate), version);
    }

    /**
     * CAS 推进版本序列：仅当当前版本仍为 expected 时置为 next，返回受影响行数；
     * 并发核验落败者受影响行数为 0，由调用方裁决唯一生效版本。
     */
    public int casVersionSeq(String stationCode, LocalDate gasDate, int expected, int next) {
        return jdbc.update(
                "UPDATE station_day_verification SET current_version = ?, "
                        + "updated_at = CURRENT_TIMESTAMP "
                        + "WHERE station_code = ? AND gas_date = ? AND current_version = ?",
                next, stationCode, java.sql.Date.valueOf(gasDate), expected);
    }

    /**
     * 追加核验版本；同站同日同版本并发冲突抛 {@link DuplicateKeyException}。
     */
    public void insertVerification(VerificationRow row) {
        jdbc.update(
                "INSERT INTO quality_verification (verification_no, request_id, station_code, gas_date, "
                        + "voucher_no, version, sample_count, verified_heating_value, voucher_heating_value, "
                        + "relative_deviation, difference_flag, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.verificationNo(), row.requestId(), row.stationCode(),
                java.sql.Date.valueOf(row.gasDate()), row.voucherNo(), row.version(), row.sampleCount(),
                row.verifiedHeatingValue(), row.voucherHeatingValue(), row.relativeDeviation(),
                row.differenceFlag() ? 1 : 0, row.status(), Timestamp.from(row.createdAt()));
    }

    /** 记录一方签认时刻。 */
    public void markSignedOff(String verificationNo, String party, Instant signedAt) {
        String column = "A".equals(party) ? "sign_off_a_at" : "sign_off_b_at";
        jdbc.update("UPDATE quality_verification SET " + column + " = ? WHERE verification_no = ?",
                Timestamp.from(signedAt), verificationNo);
    }

    /** 双方签认齐备，核验结论置为已解除。 */
    public void markResolved(String verificationNo, Instant resolvedAt) {
        jdbc.update("UPDATE quality_verification SET status = ?, resolved_at = ? WHERE verification_no = ?",
                VerificationRow.STATUS_DIFFERENT_RESOLVED, Timestamp.from(resolvedAt), verificationNo);
    }

    /** 插入差异签认记录；同方重复签认返回 false。 */
    public boolean insertSignOff(String verificationNo, String party, String requestId, Instant signedAt) {
        try {
            jdbc.update(
                    "INSERT INTO quality_difference_signoff (verification_no, party, request_id, signed_at) "
                            + "VALUES (?, ?, ?, ?)",
                    verificationNo, party, requestId, Timestamp.from(signedAt));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /** 某方是否已签认该核验单。 */
    public boolean isSignedOffBy(String verificationNo, String party) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM quality_difference_signoff WHERE verification_no = ? AND party = ?",
                Integer.class, verificationNo, party);
        return count != null && count > 0;
    }

    /**
     * 为凭证建立确认暂停（若不存在）；同一事务内与差异标记、版本记录一起提交。
     */
    public void ensurePause(String voucherNo, String verificationNo, Instant pausedAt) {
        try {
            jdbc.update(
                    "INSERT INTO voucher_confirm_pause (voucher_no, verification_no, paused_at) "
                            + "VALUES (?, ?, ?)",
                    voucherNo, verificationNo, Timestamp.from(pausedAt));
        } catch (DuplicateKeyException ex) {
            // 已存在暂停记录：保持首次暂停，不重复落库
        }
    }

    /** 凭证当前是否处于确认暂停中。 */
    public boolean isConfirmPaused(String voucherNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM voucher_confirm_pause WHERE voucher_no = ? AND released_at IS NULL",
                Integer.class, voucherNo);
        return count != null && count > 0;
    }

    /** 解除凭证的确认暂停；返回受影响行数。 */
    public int releasePause(String voucherNo, Instant releasedAt) {
        return jdbc.update(
                "UPDATE voucher_confirm_pause SET released_at = ? "
                        + "WHERE voucher_no = ? AND released_at IS NULL",
                Timestamp.from(releasedAt), voucherNo);
    }
}
