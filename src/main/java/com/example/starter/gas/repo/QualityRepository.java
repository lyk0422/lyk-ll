package com.example.starter.gas.repo;

import com.example.starter.gas.domain.SampleRow;
import com.example.starter.gas.domain.VerificationRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 气质核验单、采样记录、差异签认及组分热值表的数据访问。
 */
@Repository
public class QualityRepository {

    private static final RowMapper<VerificationRow> VERIFICATION_MAPPER = (rs, rowNum) -> new VerificationRow(
            rs.getLong("id"),
            rs.getString("verification_no"),
            rs.getString("request_id"),
            rs.getString("station_code"),
            rs.getDate("gas_date").toLocalDate(),
            rs.getString("voucher_no"),
            rs.getInt("version"),
            rs.getString("status"),
            rs.getBigDecimal("sample_volume"),
            rs.getBigDecimal("verified_heating_value"),
            rs.getBigDecimal("voucher_heating_value"),
            rs.getBigDecimal("deviation_rate"),
            rs.getString("conclusion"),
            rs.getString("diff_status"),
            rs.getInt("confirmation_paused") == 1,
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<SampleRow> SAMPLE_MAPPER = (rs, rowNum) -> new SampleRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("verification_no"),
            rs.getString("station_code"),
            rs.getTimestamp("sample_time").toInstant(),
            rs.getDate("gas_date").toLocalDate(),
            rs.getBigDecimal("sample_volume"),
            rs.getString("composition_json"),
            rs.getBigDecimal("sample_heating_value"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public QualityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 组分热值表：组分编码 → 组分热值。 */
    public Map<String, BigDecimal> findComponentHeatingValues() {
        Map<String, BigDecimal> table = new HashMap<>();
        jdbc.query("SELECT component_code, heating_value FROM component_heating_value",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> table.put(rs.getString("component_code"), rs.getBigDecimal("heating_value")));
        return table;
    }

    /** 按核验单号查询。 */
    public VerificationRow findVerificationByNo(String verificationNo) {
        List<VerificationRow> rows = jdbc.query(
                "SELECT * FROM quality_verification WHERE verification_no = ?",
                VERIFICATION_MAPPER, verificationNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按核验单号查询并加行锁（FOR UPDATE）：串行化同一核验单的并发签认。 */
    public VerificationRow findVerificationByNoForUpdate(String verificationNo) {
        List<VerificationRow> rows = jdbc.query(
                "SELECT * FROM quality_verification WHERE verification_no = ? FOR UPDATE",
                VERIFICATION_MAPPER, verificationNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询同站同气量日的生效（ACTIVE）核验版本；无则返回 null。 */
    public VerificationRow findActiveVerification(String stationCode, LocalDate gasDate) {
        List<VerificationRow> rows = jdbc.query(
                "SELECT * FROM quality_verification WHERE station_code = ? AND gas_date = ? "
                        + "AND status = 'ACTIVE' ORDER BY id LIMIT 1",
                VERIFICATION_MAPPER, stationCode, java.sql.Date.valueOf(gasDate));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 同站同气量日的全部核验版本（含被取代的旧结论），按版本号升序。 */
    public List<VerificationRow> findVersions(String stationCode, LocalDate gasDate) {
        return jdbc.query(
                "SELECT * FROM quality_verification WHERE station_code = ? AND gas_date = ? "
                        + "ORDER BY version ASC, id ASC",
                VERIFICATION_MAPPER, stationCode, java.sql.Date.valueOf(gasDate));
    }

    /** 同站同气量日已提交的最大版本号；无记录返回 0。 */
    public int maxVersion(String stationCode, LocalDate gasDate) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM quality_verification "
                        + "WHERE station_code = ? AND gas_date = ?",
                Integer.class, stationCode, java.sql.Date.valueOf(gasDate));
        return max == null ? 0 : max;
    }

    /** 插入核验单新版本（生效态）。 */
    public void insertVerification(VerificationRow row) {
        jdbc.update(
                "INSERT INTO quality_verification (verification_no, request_id, station_code, gas_date, "
                        + "voucher_no, version, status, sample_volume, verified_heating_value, "
                        + "voucher_heating_value, deviation_rate, conclusion, diff_status, "
                        + "confirmation_paused, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?)",
                row.verificationNo(), row.requestId(), row.stationCode(),
                java.sql.Date.valueOf(row.gasDate()), row.voucherNo(), row.version(),
                row.sampleVolume(), row.verifiedHeatingValue(), row.voucherHeatingValue(),
                row.deviationRate(), row.conclusion(), row.diffStatus(),
                Boolean.TRUE.equals(row.confirmationPaused()) ? 1 : 0,
                Timestamp.from(row.createdAt()));
    }

    /** 将旧生效版本置为被取代（旧结论保留可查）。 */
    public void supersede(String verificationNo) {
        jdbc.update(
                "UPDATE quality_verification SET status = 'SUPERSEDED' WHERE verification_no = ?",
                verificationNo);
    }

    /**
     * 占用“站＋气量日”生效核验名额；并发重复占用抛 {@link DuplicateKeyException}。
     */
    public void occupyStationDay(String stationCode, LocalDate gasDate, String verificationNo) {
        jdbc.update(
                "INSERT INTO station_day_verification (station_code, gas_date, verification_no) "
                        + "VALUES (?, ?, ?)",
                stationCode, java.sql.Date.valueOf(gasDate), verificationNo);
    }

    /**
     * 修订生效版本占位：仅当当前生效核验单仍为 expectedActiveNo 时替换，
     * 返回受影响行数；0 表示并发修订冲突。
     */
    public int replaceActiveVerification(String stationCode, LocalDate gasDate,
                                         String expectedActiveNo, String newVerificationNo) {
        return jdbc.update(
                "UPDATE station_day_verification SET verification_no = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE station_code = ? AND gas_date = ? AND verification_no = ?",
                newVerificationNo, stationCode, java.sql.Date.valueOf(gasDate), expectedActiveNo);
    }

    /** 关联凭证的双方确认是否被未解决气质差异暂停。 */
    public boolean isConfirmationPaused(String stationCode, LocalDate gasDate) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM quality_verification WHERE station_code = ? AND gas_date = ? "
                        + "AND status = 'ACTIVE' AND confirmation_paused = 1",
                Integer.class, stationCode, java.sql.Date.valueOf(gasDate));
        return count != null && count > 0;
    }

    /** 双方签认齐备后解除差异与暂停。 */
    public void markDiffResolved(String verificationNo) {
        jdbc.update(
                "UPDATE quality_verification SET diff_status = 'RESOLVED', confirmation_paused = 0 "
                        + "WHERE verification_no = ?",
                verificationNo);
    }

    /** 按站与采样时刻查找采样记录（去重判断）。 */
    public SampleRow findSampleByStationAndTime(String stationCode, Instant sampleTime) {
        List<SampleRow> rows = jdbc.query(
                "SELECT * FROM quality_sample WHERE station_code = ? AND sample_time = ?",
                SAMPLE_MAPPER, stationCode, Timestamp.from(sampleTime));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 插入采样记录；同站同时刻并发写入抛 {@link DuplicateKeyException}。 */
    public void insertSample(String requestId, String verificationNo, String stationCode,
                             Instant sampleTime, LocalDate gasDate, BigDecimal sampleVolume,
                             String compositionJson, BigDecimal sampleHeatingValue, Instant now) {
        jdbc.update(
                "INSERT INTO quality_sample (request_id, verification_no, station_code, sample_time, "
                        + "gas_date, sample_volume, composition_json, sample_heating_value, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                requestId, verificationNo, stationCode, Timestamp.from(sampleTime),
                java.sql.Date.valueOf(gasDate), sampleVolume, compositionJson,
                sampleHeatingValue, Timestamp.from(now));
    }

    /** 查询某核验单首次入账的采样记录（按采样时刻升序）。 */
    public List<SampleRow> findSamplesOf(String verificationNo) {
        return jdbc.query(
                "SELECT * FROM quality_sample WHERE verification_no = ? ORDER BY sample_time ASC, id ASC",
                SAMPLE_MAPPER, verificationNo);
    }

    /** 插入差异签认；同一核验单同一方重复签认返回 false。 */
    public boolean insertSignoff(String verificationNo, String party, String requestId, Instant now) {
        try {
            jdbc.update(
                    "INSERT INTO quality_diff_signoff (verification_no, party, request_id, signed_at) "
                            + "VALUES (?, ?, ?, ?)",
                    verificationNo, party, requestId, Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /** 某方是否已签认该核验单的差异。 */
    public boolean isSignedBy(String verificationNo, String party) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM quality_diff_signoff WHERE verification_no = ? AND party = ?",
                Integer.class, verificationNo, party);
        return count != null && count > 0;
    }
}
