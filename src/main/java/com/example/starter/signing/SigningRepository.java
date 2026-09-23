package com.example.starter.signing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 签署流程数据访问层：全部使用参数化 SQL；时间列按 UTC Instant 存取。
 */
@Repository
public class SigningRepository {

    private final JdbcTemplate jdbc;

    public SigningRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 签署包主表行。 */
    public record PackageRow(String id, String status, int currentRevision, String docSha256,
                             int currentStep, Instant expiresAt, long version,
                             String evidenceJson, String evidenceHash) {
    }

    /** 步骤签署人行。 */
    public record StepRow(int stepIndex, String signerId) {
    }

    /** 签名记录行。 */
    public record SignatureRow(int revision, int stepIndex, String signerId, String docSha256,
                               String signatureValue, String status, Instant createdAt) {
    }

    /** 事件行。 */
    public record EventRow(int seq, String eventType, String payload, String prevHash, String eventHash) {
    }

    /** 幂等请求日志行。 */
    public record RequestLogRow(String requestId, String fingerprint, int statusCode, String responseJson) {
    }

    private static PackageRow mapPackage(ResultSet rs, int rowNum) throws SQLException {
        return new PackageRow(
                rs.getString("id"),
                rs.getString("status"),
                rs.getInt("current_revision"),
                rs.getString("doc_sha256"),
                rs.getInt("current_step"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getLong("version"),
                rs.getString("evidence_json"),
                rs.getString("evidence_hash"));
    }

    public Optional<PackageRow> findPackage(String id) {
        List<PackageRow> rows = jdbc.query(
                "SELECT * FROM signing_package WHERE id = ?", SigningRepository::mapPackage, id);
        return rows.stream().findFirst();
    }

    /**
     * 以行锁读取签署包，同事务内的后续写操作与该锁串行化，保证并发下形成一致终态。
     */
    public Optional<PackageRow> findPackageForUpdate(String id) {
        List<PackageRow> rows = jdbc.query(
                "SELECT * FROM signing_package WHERE id = ? FOR UPDATE", SigningRepository::mapPackage, id);
        return rows.stream().findFirst();
    }

    public void insertPackage(String id, String status, int revision, String docSha256,
                              Instant expiresAt, Instant now) {
        jdbc.update(
                "INSERT INTO signing_package "
                        + "(id, status, current_revision, doc_sha256, current_step, expires_at, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 0, ?, 1, ?, ?)",
                id, status, revision, docSha256, Timestamp.from(expiresAt),
                Timestamp.from(now), Timestamp.from(now));
    }

    /**
     * 更新签署包可变字段并递增版本号；调用前须已持有 {@link #findPackageForUpdate} 行锁。
     */
    public void updatePackage(String id, String status, int revision, String docSha256,
                              int currentStep, Instant now) {
        jdbc.update(
                "UPDATE signing_package SET status = ?, current_revision = ?, doc_sha256 = ?, "
                        + "current_step = ?, version = version + 1, updated_at = ? WHERE id = ?",
                status, revision, docSha256, currentStep, Timestamp.from(now), id);
    }

    public void freezeEvidence(String id, String evidenceJson, String evidenceHash, Instant now) {
        jdbc.update(
                "UPDATE signing_package SET evidence_json = ?, evidence_hash = ?, updated_at = ? WHERE id = ?",
                evidenceJson, evidenceHash, Timestamp.from(now), id);
    }

    public void insertStep(String packageId, int stepIndex, String signerId) {
        jdbc.update("INSERT INTO package_step (package_id, step_index, signer_id) VALUES (?, ?, ?)",
                packageId, stepIndex, signerId);
    }

    public List<StepRow> findSteps(String packageId) {
        return jdbc.query(
                "SELECT step_index, signer_id FROM package_step WHERE package_id = ? "
                        + "ORDER BY step_index, signer_id",
                (rs, n) -> new StepRow(rs.getInt("step_index"), rs.getString("signer_id")),
                packageId);
    }

    public void insertSignature(String packageId, int revision, int stepIndex, String signerId,
                                String docSha256, String signatureValue, Instant now) {
        jdbc.update(
                "INSERT INTO signature (package_id, revision, step_index, signer_id, doc_sha256, "
                        + "signature_value, status, created_at) VALUES (?, ?, ?, ?, ?, ?, 'VALID', ?)",
                packageId, revision, stepIndex, signerId, docSha256, signatureValue, Timestamp.from(now));
    }

    /**
     * 尝试插入签名；唯一键 (package_id, revision, signer_id) 冲突时返回 false（重复签署人）。
     */
    public boolean tryInsertSignature(String packageId, int revision, int stepIndex, String signerId,
                                      String docSha256, String signatureValue, Instant now) {
        try {
            insertSignature(packageId, revision, stepIndex, signerId, docSha256, signatureValue, now);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public List<SignatureRow> findSignatures(String packageId) {
        return jdbc.query(
                "SELECT revision, step_index, signer_id, doc_sha256, signature_value, status, created_at "
                        + "FROM signature WHERE package_id = ? ORDER BY id",
                (rs, n) -> new SignatureRow(
                        rs.getInt("revision"), rs.getInt("step_index"), rs.getString("signer_id"),
                        rs.getString("doc_sha256"), rs.getString("signature_value"),
                        rs.getString("status"), rs.getTimestamp("created_at").toInstant()),
                packageId);
    }

    /**
     * 将当前全部有效签名置为失效（修订时调用），返回失效条数；历史行保留。
     */
    public int invalidateValidSignatures(String packageId) {
        return jdbc.update(
                "UPDATE signature SET status = 'INVALIDATED' WHERE package_id = ? AND status = 'VALID'",
                packageId);
    }

    /**
     * 下一事件序号；调用前须持有签署包行锁，保证序号连续唯一。
     */
    public int nextEventSeq(String packageId) {
        Integer seq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM signing_event WHERE package_id = ?",
                Integer.class, packageId);
        return seq == null ? 1 : seq;
    }

    public String lastEventHash(String packageId) {
        List<String> hashes = jdbc.query(
                "SELECT event_hash FROM signing_event WHERE package_id = ? ORDER BY seq DESC LIMIT 1",
                (rs, n) -> rs.getString("event_hash"),
                packageId);
        return hashes.isEmpty() ? "0".repeat(64) : hashes.get(0);
    }

    public void insertEvent(String packageId, int seq, String eventType, String payload,
                            String prevHash, String eventHash, Instant now) {
        jdbc.update(
                "INSERT INTO signing_event (package_id, seq, event_type, payload, prev_hash, event_hash, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                packageId, seq, eventType, payload, prevHash, eventHash, Timestamp.from(now));
    }

    public List<EventRow> findEvents(String packageId) {
        return jdbc.query(
                "SELECT seq, event_type, payload, prev_hash, event_hash FROM signing_event "
                        + "WHERE package_id = ? ORDER BY seq",
                (rs, n) -> new EventRow(rs.getInt("seq"), rs.getString("event_type"),
                        rs.getString("payload"), rs.getString("prev_hash"), rs.getString("event_hash")),
                packageId);
    }

    public Optional<RequestLogRow> findRequest(String requestId) {
        List<RequestLogRow> rows = jdbc.query(
                "SELECT request_id, fingerprint, status_code, response_json FROM request_log WHERE request_id = ?",
                (rs, n) -> new RequestLogRow(rs.getString("request_id"), rs.getString("fingerprint"),
                        rs.getInt("status_code"), rs.getString("response_json")),
                requestId);
        return rows.stream().findFirst();
    }

    /**
     * 尝试登记幂等请求；主键冲突返回 false（该键已被首次请求占用）。
     */
    public boolean tryInsertRequest(String requestId, String packageId, String fingerprint,
                                    int statusCode, String responseJson, Instant now) {
        try {
            jdbc.update(
                    "INSERT INTO request_log (request_id, package_id, fingerprint, status_code, response_json, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    requestId, packageId, fingerprint, statusCode, responseJson, Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
