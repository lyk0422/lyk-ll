package com.example.starter.carbon.repo;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 清缴申请、逐批注销明细与清缴凭证数据访问。
 */
@Repository
public class SurrenderRequestRepository {

    private final JdbcTemplate jdbc;

    public SurrenderRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 清缴申请行视图。 */
    public record RequestRow(
            long id, String requestId, long enterpriseId, int complianceYear,
            BigDecimal allowanceAmount, BigDecimal offsetAmount, String fingerprint,
            String status, String rejectReason, Instant arrivalUtc, Instant processedUtc) {
    }

    /** 注销明细行视图。 */
    public record RetirementRow(
            long id, long surrenderRequestId, String requestId, String accountType,
            long batchId, String batchNo, BigDecimal amount, int seqNo) {
    }

    /** 凭证行视图。 */
    public record CertificateRow(
            long id, String certificateNo, String requestId, long enterpriseId, int complianceYear,
            BigDecimal totalRetired, BigDecimal allowanceRetired, BigDecimal offsetRetired, Instant issuedUtc) {
    }

    private static final RowMapper<RequestRow> REQUEST_MAPPER = (rs, n) -> new RequestRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("enterprise_id"),
            rs.getInt("compliance_year"),
            rs.getBigDecimal("allowance_amount"),
            rs.getBigDecimal("offset_amount"),
            rs.getString("fingerprint"),
            rs.getString("status"),
            rs.getString("reject_reason"),
            rs.getTimestamp("arrival_utc").toInstant(),
            rs.getTimestamp("processed_utc").toInstant());

    private static final RowMapper<RetirementRow> RETIREMENT_MAPPER = (rs, n) -> new RetirementRow(
            rs.getLong("id"),
            rs.getLong("surrender_request_id"),
            rs.getString("request_id"),
            rs.getString("account_type"),
            rs.getLong("batch_id"),
            rs.getString("batch_no"),
            rs.getBigDecimal("amount"),
            rs.getInt("seq_no"));

    private static final RowMapper<CertificateRow> CERTIFICATE_MAPPER = (rs, n) -> new CertificateRow(
            rs.getLong("id"),
            rs.getString("certificate_no"),
            rs.getString("request_id"),
            rs.getLong("enterprise_id"),
            rs.getInt("compliance_year"),
            rs.getBigDecimal("total_retired"),
            rs.getBigDecimal("allowance_retired"),
            rs.getBigDecimal("offset_retired"),
            rs.getTimestamp("issued_utc").toInstant());

    /** 按 requestId 查询申请，不存在返回 null。 */
    public RequestRow findByRequestId(String requestId) {
        List<RequestRow> rows = jdbc.query(
                "SELECT * FROM surrender_request WHERE request_id = ?", REQUEST_MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 插入清缴申请并回填自增主键。requestId 唯一冲突时抛 {@link DuplicateKeyException}，
     * 由服务层重查比对指纹。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long insertRequestAndReturnId(RequestRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO surrender_request (request_id, enterprise_id, compliance_year, "
                            + "allowance_amount, offset_amount, fingerprint, status, reject_reason, "
                            + "arrival_utc, processed_utc) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.requestId());
            ps.setLong(2, row.enterpriseId());
            ps.setInt(3, row.complianceYear());
            ps.setBigDecimal(4, row.allowanceAmount());
            ps.setBigDecimal(5, row.offsetAmount());
            ps.setString(6, row.fingerprint());
            ps.setString(7, row.status());
            if (row.rejectReason() == null) {
                ps.setNull(8, java.sql.Types.VARCHAR);
            } else {
                ps.setString(8, row.rejectReason());
            }
            ps.setTimestamp(9, Timestamp.from(row.arrivalUtc()));
            ps.setTimestamp(10, Timestamp.from(row.processedUtc()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入清缴申请未获得自增主键: " + row.requestId());
        }
        return key.longValue();
    }

    /**
     * 汇总企业某履约年度已成功注销（RETIRED）的配额与抵消累计量，用于5%上限与足额判定。
     * 返回键 allowance / offset，单位吨CO2e。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, BigDecimal> sumRetiredAmounts(long enterpriseId, int complianceYear) {
        BigDecimal allowance = jdbc.queryForObject(
                "SELECT COALESCE(SUM(allowance_amount),0) FROM surrender_request "
                        + "WHERE enterprise_id = ? AND compliance_year = ? AND status = 'RETIRED'",
                BigDecimal.class, enterpriseId, complianceYear);
        BigDecimal offset = jdbc.queryForObject(
                "SELECT COALESCE(SUM(offset_amount),0) FROM surrender_request "
                        + "WHERE enterprise_id = ? AND compliance_year = ? AND status = 'RETIRED'",
                BigDecimal.class, enterpriseId, complianceYear);
        return Map.of("allowance", allowance, "offset", offset);
    }

    /** 插入一笔逐批注销明细。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertRetirement(long surrenderRequestId, RetirementRow row) {
        jdbc.update("INSERT INTO retirement_record (surrender_request_id, request_id, account_type, "
                        + "batch_id, batch_no, amount, seq_no) VALUES (?,?,?,?,?,?,?)",
                surrenderRequestId, row.requestId(), row.accountType(), row.batchId(),
                row.batchNo(), row.amount(), row.seqNo());
    }

    /** 查询某申请的注销清单，按申请内顺序号升序。 */
    public List<RetirementRow> findRetirements(long surrenderRequestId) {
        return jdbc.query("SELECT * FROM retirement_record WHERE surrender_request_id = ? ORDER BY seq_no ASC",
                RETIREMENT_MAPPER, surrenderRequestId);
    }

    /** 插入清缴凭证（凭证号唯一）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertCertificate(CertificateRow row) {
        jdbc.update("INSERT INTO surrender_certificate (certificate_no, request_id, enterprise_id, "
                        + "compliance_year, total_retired, allowance_retired, offset_retired, issued_utc) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                row.certificateNo(), row.requestId(), row.enterpriseId(), row.complianceYear(),
                row.totalRetired(), row.allowanceRetired(), row.offsetRetired(),
                Timestamp.from(row.issuedUtc()));
    }

    /** 按凭证号查询凭证，不存在返回 null。 */
    public CertificateRow findCertificateByNo(String certificateNo) {
        List<CertificateRow> rows = jdbc.query(
                "SELECT * FROM surrender_certificate WHERE certificate_no = ?", CERTIFICATE_MAPPER, certificateNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按 requestId 查询凭证，不存在返回 null。 */
    public CertificateRow findCertificateByRequestId(String requestId) {
        List<CertificateRow> rows = jdbc.query(
                "SELECT * FROM surrender_certificate WHERE request_id = ?", CERTIFICATE_MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
