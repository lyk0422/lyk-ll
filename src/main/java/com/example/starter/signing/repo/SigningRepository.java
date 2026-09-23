package com.example.starter.signing.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.signing.domain.SignEvent;
import com.example.starter.signing.domain.SignatureRecord;
import com.example.starter.signing.domain.SigningPackage;
import com.example.starter.signing.domain.SigningStep;
import com.example.starter.signing.model.EventType;
import com.example.starter.signing.model.PackageStatus;
import com.example.starter.signing.model.SignatureStatus;

/**
 * 签署流程数据访问：所有写操作要求在调用方事务内执行（{@link Propagation#MANDATORY}）。
 */
@Repository
public class SigningRepository {

    private final JdbcTemplate jdbc;

    public SigningRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPackage(SigningPackage pkg) {
        jdbc.update("""
                INSERT INTO signing_package
                    (id, current_revision, document_hash, expires_at, status, frozen_at, evidence_digest, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                pkg.getId(), pkg.getCurrentRevision(), pkg.getDocumentHash(),
                Timestamp.from(pkg.getExpiresAt()), pkg.getStatus().name(),
                pkg.getFrozenAt() == null ? null : Timestamp.from(pkg.getFrozenAt()),
                pkg.getEvidenceDigest(), Timestamp.from(pkg.getCreatedAt()));
    }

    /**
     * 悲观行锁读取签署包（SELECT ... FOR UPDATE），必须在事务内调用。
     */
    public SigningPackage findPackageForUpdate(String packageId) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM signing_package WHERE id = ? FOR UPDATE",
                    PACKAGE_MAPPER, packageId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public Optional<SigningPackage> findPackage(String packageId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM signing_package WHERE id = ?",
                    PACKAGE_MAPPER, packageId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<SigningStep> findSteps(String packageId) {
        return jdbc.query(
                "SELECT package_id, step_order, signer FROM signing_step WHERE package_id = ? ORDER BY step_order, signer",
                STEP_MAPPER, packageId);
    }

    public void insertStep(SigningStep step) {
        jdbc.update("INSERT INTO signing_step (package_id, step_order, signer) VALUES (?, ?, ?)",
                step.getPackageId(), step.getStepOrder(), step.getSigner());
    }

    /**
     * 条件更新：仅当当前修订号与期望一致（乐观版本校验）且状态未变时更新文档摘要/修订号。
     *
     * @return 受影响行数；0 表示并发冲突
     */
    public int compareAndUpdateRevision(String packageId, int expectedRevision,
                                        String newDocumentHash) {
        return jdbc.update("""
                UPDATE signing_package
                   SET current_revision = ?, document_hash = ?
                 WHERE id = ? AND current_revision = ? AND status = 'PENDING'
                """,
                expectedRevision + 1, newDocumentHash, packageId, expectedRevision);
    }

    public int compareAndCancel(String packageId, int expectedRevision) {
        return jdbc.update("""
                UPDATE signing_package
                   SET status = 'CANCELLED'
                 WHERE id = ? AND current_revision = ? AND status = 'PENDING'
                """,
                packageId, expectedRevision);
    }

    /**
     * 条件更新：仅当 PENDING 且当前修订号一致时转为 EXPIRED。
     */
    public int compareAndExpire(String packageId, int expectedRevision) {
        return jdbc.update("""
                UPDATE signing_package
                   SET status = 'EXPIRED'
                 WHERE id = ? AND current_revision = ? AND status = 'PENDING'
                """,
                packageId, expectedRevision);
    }

    /**
     * 完成冻结：仅当 PENDING 且修订号一致时置为 COMPLETED 并写入冻结信息。
     */
    public int compareAndComplete(String packageId, int expectedRevision,
                                  Instant frozenAt, String evidenceDigest) {
        return jdbc.update("""
                UPDATE signing_package
                   SET status = 'COMPLETED', frozen_at = ?, evidence_digest = ?
                 WHERE id = ? AND current_revision = ? AND status = 'PENDING'
                """,
                Timestamp.from(frozenAt), evidenceDigest, packageId, expectedRevision);
    }

    public void insertSignature(SignatureRecord record) {
        jdbc.update("""
                INSERT INTO signature_record
                    (id, package_id, revision, step_order, signer, document_hash,
                     signature_value, status, request_id, created_at,
                     invalidated_at, invalidated_by_revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.getId(), record.getPackageId(), record.getRevision(),
                record.getStepOrder(), record.getSigner(), record.getDocumentHash(),
                record.getSignatureValue(), record.getStatus().name(), record.getRequestId(),
                Timestamp.from(record.getCreatedAt()),
                record.getInvalidatedAt() == null ? null : Timestamp.from(record.getInvalidatedAt()),
                record.getInvalidatedByRevision());
    }

    /**
     * 将某修订的全部有效签名置为失效（修订事务内调用）。
     */
    public int invalidateSignatures(String packageId, int oldRevision,
                                    int newRevision, Instant invalidatedAt) {
        return jdbc.update("""
                UPDATE signature_record
                   SET status = 'INVALIDATED', invalidated_at = ?, invalidated_by_revision = ?
                 WHERE package_id = ? AND revision = ? AND status = 'VALID'
                """,
                Timestamp.from(invalidatedAt), newRevision, packageId, oldRevision);
    }

    public Optional<SignatureRecord> findValidSignature(String packageId, int revision,
                                                        int stepOrder, String signer) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT * FROM signature_record
                     WHERE package_id = ? AND revision = ? AND step_order = ? AND signer = ?
                       AND status = 'VALID'
                    """, SIGNATURE_MAPPER, packageId, revision, stepOrder, signer));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<SignatureRecord> findSignaturesByRevision(String packageId, int revision) {
        return jdbc.query(
                "SELECT * FROM signature_record WHERE package_id = ? AND revision = ? ORDER BY created_at, id",
                SIGNATURE_MAPPER, packageId, revision);
    }

    public List<SignatureRecord> findAllSignatures(String packageId) {
        return jdbc.query(
                "SELECT * FROM signature_record WHERE package_id = ? ORDER BY created_at, id",
                SIGNATURE_MAPPER, packageId);
    }

    public int countValidSignatures(String packageId, int revision) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM signature_record WHERE package_id = ? AND revision = ? AND status = 'VALID'",
                Integer.class, packageId, revision);
        return count == null ? 0 : count;
    }

    public void insertEvent(SignEvent event) {
        jdbc.update("""
                INSERT INTO sign_event
                    (package_id, seq, event_type, revision, detail, previous_hash, event_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.getPackageId(), event.getSeq(), event.getEventType().name(),
                event.getRevision(), event.getDetail(), event.getPreviousHash(),
                event.getEventHash(), Timestamp.from(event.getCreatedAt()));
    }

    public List<SignEvent> findEvents(String packageId) {
        return jdbc.query(
                "SELECT * FROM sign_event WHERE package_id = ? ORDER BY seq",
                EVENT_MAPPER, packageId);
    }

    public Optional<SignEvent> findLastEvent(String packageId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM sign_event WHERE package_id = ? ORDER BY seq DESC LIMIT 1",
                    EVENT_MAPPER, packageId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public int countEvents(String packageId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sign_event WHERE package_id = ?",
                Integer.class, packageId);
        return count == null ? 0 : count;
    }

    public void insertEvidence(String packageId, String content, String digest, Instant frozenAt) {
        jdbc.update("""
                INSERT INTO evidence_package (package_id, content, digest, created_at)
                VALUES (?, ?, ?, ?)
                """, packageId, content, digest, Timestamp.from(frozenAt));
    }

    public Optional<EvidenceRow> findEvidence(String packageId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT package_id, content, digest, created_at FROM evidence_package WHERE package_id = ?",
                    (rs, rowNum) -> new EvidenceRow(
                            rs.getString("package_id"),
                            rs.getString("content"),
                            rs.getString("digest"),
                            rs.getTimestamp("created_at").toInstant()),
                    packageId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void insertRequest(IdempotentRequest row) {
        jdbc.update("""
                INSERT INTO sign_request (request_id, package_id, fingerprint, http_status, response, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                row.requestId(), row.packageId(), row.fingerprint(),
                row.httpStatus(), row.response(), Timestamp.from(row.createdAt()));
    }

    public Optional<IdempotentRequest> findRequest(String requestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT request_id, package_id, fingerprint, http_status, response, created_at "
                            + "FROM sign_request WHERE request_id = ?",
                    (rs, rowNum) -> new IdempotentRequest(
                            rs.getString("request_id"),
                            rs.getString("package_id"),
                            rs.getString("fingerprint"),
                            rs.getInt("http_status"),
                            rs.getString("response"),
                            rs.getTimestamp("created_at").toInstant()),
                    requestId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 幂等请求记录行。 */
    public record IdempotentRequest(
            String requestId,
            String packageId,
            String fingerprint,
            int httpStatus,
            String response,
            Instant createdAt) {
    }

    /** 冻结证据包行。 */
    public record EvidenceRow(
            String packageId,
            String content,
            String digest,
            Instant createdAt) {
    }

    private static final RowMapper<SigningPackage> PACKAGE_MAPPER = (rs, rowNum) -> mapPackage(rs);

    private static final RowMapper<SigningStep> STEP_MAPPER = (rs, rowNum) -> new SigningStep(
            rs.getString("package_id"),
            rs.getInt("step_order"),
            rs.getString("signer"));

    private static final RowMapper<SignatureRecord> SIGNATURE_MAPPER =
            (rs, rowNum) -> mapSignature(rs);

    private static final RowMapper<SignEvent> EVENT_MAPPER = (rs, rowNum) -> mapEvent(rs);

    private static SigningPackage mapPackage(ResultSet rs) throws SQLException {
        SigningPackage pkg = new SigningPackage();
        pkg.setId(rs.getString("id"));
        pkg.setCurrentRevision(rs.getInt("current_revision"));
        pkg.setDocumentHash(rs.getString("document_hash"));
        pkg.setExpiresAt(rs.getTimestamp("expires_at").toInstant());
        pkg.setStatus(PackageStatus.valueOf(rs.getString("status")));
        Timestamp frozen = rs.getTimestamp("frozen_at");
        pkg.setFrozenAt(frozen == null ? null : frozen.toInstant());
        pkg.setEvidenceDigest(rs.getString("evidence_digest"));
        pkg.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return pkg;
    }

    private static SignatureRecord mapSignature(ResultSet rs) throws SQLException {
        SignatureRecord r = new SignatureRecord();
        r.setId(rs.getString("id"));
        r.setPackageId(rs.getString("package_id"));
        r.setRevision(rs.getInt("revision"));
        r.setStepOrder(rs.getInt("step_order"));
        r.setSigner(rs.getString("signer"));
        r.setDocumentHash(rs.getString("document_hash"));
        r.setSignatureValue(rs.getString("signature_value"));
        r.setStatus(SignatureStatus.valueOf(rs.getString("status")));
        r.setRequestId(rs.getString("request_id"));
        r.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        Timestamp invalidated = rs.getTimestamp("invalidated_at");
        r.setInvalidatedAt(invalidated == null ? null : invalidated.toInstant());
        int byRevision = rs.getInt("invalidated_by_revision");
        r.setInvalidatedByRevision(rs.wasNull() ? null : byRevision);
        return r;
    }

    private static SignEvent mapEvent(ResultSet rs) throws SQLException {
        SignEvent e = new SignEvent();
        e.setId(rs.getLong("id"));
        e.setPackageId(rs.getString("package_id"));
        e.setSeq(rs.getInt("seq"));
        e.setEventType(EventType.valueOf(rs.getString("event_type")));
        e.setRevision(rs.getInt("revision"));
        e.setDetail(rs.getString("detail"));
        e.setPreviousHash(rs.getString("previous_hash"));
        e.setEventHash(rs.getString("event_hash"));
        e.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return e;
    }
}
