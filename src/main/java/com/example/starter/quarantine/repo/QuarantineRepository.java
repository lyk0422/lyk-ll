package com.example.starter.quarantine.repo;

import com.example.starter.quarantine.ArrivalResult;
import com.example.starter.quarantine.CertificateStatus;
import com.example.starter.quarantine.DeclarationStatus;
import com.example.starter.quarantine.Species;
import com.example.starter.quarantine.model.Rows.ArrivalRow;
import com.example.starter.quarantine.model.Rows.CertificateRow;
import com.example.starter.quarantine.model.Rows.DeclarationRow;
import com.example.starter.quarantine.model.Rows.LockdownRow;
import com.example.starter.quarantine.model.Rows.StockRow;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 检疫服务数据访问层（JDBC，参数化 SQL）。时间列统一以 UTC 时间戳读写。
 */
@Repository
public class QuarantineRepository {

    private static final RowMapper<LockdownRow> LOCKDOWN_MAPPER = (rs, n) -> new LockdownRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("farm_id"),
            rs.getString("reason"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<DeclarationRow> DECLARATION_MAPPER = (rs, n) -> new DeclarationRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("farm_id"),
            rs.getString("destination"),
            Species.valueOf(rs.getString("species")),
            rs.getInt("quantity"),
            rs.getString("ear_tag_range"),
            DeclarationStatus.valueOf(rs.getString("status")),
            rs.getString("reject_reason"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<CertificateRow> CERTIFICATE_MAPPER = (rs, n) -> new CertificateRow(
            rs.getLong("id"),
            rs.getString("cert_no"),
            rs.getString("issue_request_id"),
            rs.getString("fingerprint"),
            rs.getString("declaration_request_id"),
            rs.getString("farm_id"),
            rs.getString("destination"),
            Species.valueOf(rs.getString("species")),
            rs.getInt("quantity"),
            rs.getString("ear_tag_range"),
            CertificateStatus.valueOf(rs.getString("status")),
            rs.getBoolean("quarantine_observation"),
            rs.getInt("reported_qty"),
            rs.getTimestamp("issued_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant(),
            Optional.ofNullable(rs.getTimestamp("departed_at")).map(Timestamp::toInstant).orElse(null),
            Optional.ofNullable(rs.getTimestamp("closed_at")).map(Timestamp::toInstant).orElse(null));

    private static final RowMapper<StockRow> STOCK_MAPPER = (rs, n) -> new StockRow(
            rs.getLong("id"),
            rs.getString("farm_id"),
            Species.valueOf(rs.getString("species")),
            rs.getString("ear_tag_range"),
            rs.getInt("sellable_qty"));

    private static final RowMapper<ArrivalRow> ARRIVAL_MAPPER = (rs, n) -> new ArrivalRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("cert_no"),
            rs.getInt("quantity"),
            ArrivalResult.valueOf(rs.getString("result")),
            rs.getString("note"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public QuarantineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 养殖场登记与行级锁 ----------

    /** 登记养殖场（已存在则忽略），保证行级锁目标行存在。 */
    public void ensureFarmRegistry(String farmId, Instant now) {
        int updated = jdbc.update(
                "UPDATE farm_registry SET farm_id = farm_id WHERE farm_id = ?", farmId);
        if (updated == 0) {
            try {
                jdbc.update("INSERT INTO farm_registry (farm_id, created_at) VALUES (?, ?)",
                        farmId, Timestamp.from(now));
            } catch (DuplicateKeyException e) {
                // 并发登记同一养殖场：先提交者已建立登记行，忽略。
            }
        }
    }

    /** 对养殖场登记行加排他行锁，串行化封锁发布与出证/启运。 */
    public void lockFarm(String farmId) {
        jdbc.queryForObject(
                "SELECT farm_id FROM farm_registry WHERE farm_id = ? FOR UPDATE",
                String.class, farmId);
    }

    // ---------- 疫点封锁 ----------

    public Optional<LockdownRow> findLockdownByRequestId(String requestId) {
        return queryOne("SELECT * FROM lockdown_zone WHERE request_id = ?", LOCKDOWN_MAPPER, requestId);
    }

    public Optional<LockdownRow> findActiveLockdownByFarm(String farmId) {
        return queryOne("SELECT * FROM lockdown_zone WHERE farm_id = ? AND status = 'ACTIVE'",
                LOCKDOWN_MAPPER, farmId);
    }

    public long insertLockdown(String requestId, String fingerprint, String farmId,
                               String reason, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO lockdown_zone (request_id, fingerprint, farm_id, reason, status, created_at)"
                            + " VALUES (?, ?, ?, ?, 'ACTIVE', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setString(3, farmId);
            ps.setString(4, reason);
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    // ---------- 检疫申报 ----------

    public long insertDeclaration(String requestId, String fingerprint, String farmId,
                                  String destination, Species species, int quantity,
                                  String earTagRange, DeclarationStatus status,
                                  String rejectReason, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO quarantine_declaration (request_id, fingerprint, farm_id, destination,"
                            + " species, quantity, ear_tag_range, status, reject_reason, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setString(3, farmId);
            ps.setString(4, destination);
            ps.setString(5, species.name());
            ps.setInt(6, quantity);
            ps.setString(7, earTagRange);
            ps.setString(8, status.name());
            ps.setString(9, rejectReason);
            ps.setTimestamp(10, Timestamp.from(now));
            ps.setTimestamp(11, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<DeclarationRow> findDeclarationByRequestId(String requestId) {
        return queryOne("SELECT * FROM quarantine_declaration WHERE request_id = ?",
                DECLARATION_MAPPER, requestId);
    }

    /** 封锁联动一级：驳回该养殖场全部未出证申报，返回驳回数量。 */
    public int rejectPendingDeclarations(String farmId, String reason, Instant now) {
        return jdbc.update(
                "UPDATE quarantine_declaration SET status = 'REJECTED', reject_reason = ?, updated_at = ?"
                        + " WHERE farm_id = ? AND status = 'DECLARED'",
                reason, Timestamp.from(now), farmId);
    }

    public void markDeclarationCertified(String requestId, Instant now) {
        jdbc.update(
                "UPDATE quarantine_declaration SET status = 'CERTIFIED', updated_at = ?"
                        + " WHERE request_id = ? AND status = 'DECLARED'",
                Timestamp.from(now), requestId);
    }

    // ---------- 检疫合格证 ----------

    /** 插入合格证（证号先占位，同事务内按主键生成正式证号），返回主键。 */
    public long insertCertificate(String placeholderCertNo, String issueRequestId, String fingerprint,
                                  String declarationRequestId, String farmId, String destination,
                                  Species species, int quantity, String earTagRange,
                                  Instant issuedAt, Instant expiresAt, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO quarantine_certificate (cert_no, issue_request_id, fingerprint,"
                            + " declaration_request_id, farm_id, destination, species, quantity, ear_tag_range,"
                            + " status, quarantine_observation, reported_qty, issued_at, expires_at,"
                            + " created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ISSUED', FALSE, 0, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, placeholderCertNo);
            ps.setString(2, issueRequestId);
            ps.setString(3, fingerprint);
            ps.setString(4, declarationRequestId);
            ps.setString(5, farmId);
            ps.setString(6, destination);
            ps.setString(7, species.name());
            ps.setInt(8, quantity);
            ps.setString(9, earTagRange);
            ps.setTimestamp(10, Timestamp.from(issuedAt));
            ps.setTimestamp(11, Timestamp.from(expiresAt));
            ps.setTimestamp(12, Timestamp.from(now));
            ps.setTimestamp(13, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public void updateCertNo(long id, String certNo, Instant now) {
        jdbc.update("UPDATE quarantine_certificate SET cert_no = ?, updated_at = ? WHERE id = ?",
                certNo, Timestamp.from(now), id);
    }

    public Optional<CertificateRow> findCertByIssueRequestId(String issueRequestId) {
        return queryOne("SELECT * FROM quarantine_certificate WHERE issue_request_id = ?",
                CERTIFICATE_MAPPER, issueRequestId);
    }

    public Optional<CertificateRow> findCertByDeclarationRequestId(String declarationRequestId) {
        return queryOne("SELECT * FROM quarantine_certificate WHERE declaration_request_id = ?",
                CERTIFICATE_MAPPER, declarationRequestId);
    }

    public Optional<CertificateRow> findCertByNo(String certNo) {
        return queryOne("SELECT * FROM quarantine_certificate WHERE cert_no = ?",
                CERTIFICATE_MAPPER, certNo);
    }

    /** 按证号查询并加排他行锁，串行化同一证的落地报告。 */
    public Optional<CertificateRow> findCertByNoForUpdate(String certNo) {
        return queryOne("SELECT * FROM quarantine_certificate WHERE cert_no = ? FOR UPDATE",
                CERTIFICATE_MAPPER, certNo);
    }

    /** 封锁联动二级：作废该养殖场全部已出证未启运的证，返回被作废证号。 */
    public List<String> voidIssuedCertsByFarm(String farmId, Instant now) {
        List<String> certNos = jdbc.queryForList(
                "SELECT cert_no FROM quarantine_certificate WHERE farm_id = ? AND status = 'ISSUED'",
                String.class, farmId);
        if (!certNos.isEmpty()) {
            jdbc.update(
                    "UPDATE quarantine_certificate SET status = 'VOIDED', updated_at = ?"
                            + " WHERE farm_id = ? AND status = 'ISSUED'",
                    Timestamp.from(now), farmId);
        }
        return certNos;
    }

    /** 封锁联动三级：标记该养殖场全部在途证强制隔离观察，返回标记数量。 */
    public int markInTransitQuarantineByFarm(String farmId, Instant now) {
        return jdbc.update(
                "UPDATE quarantine_certificate SET quarantine_observation = TRUE, updated_at = ?"
                        + " WHERE farm_id = ? AND status = 'IN_TRANSIT'",
                Timestamp.from(now), farmId);
    }

    public void markDeparted(String certNo, Instant departedAt, Instant now) {
        jdbc.update(
                "UPDATE quarantine_certificate SET status = 'IN_TRANSIT', departed_at = ?, updated_at = ?"
                        + " WHERE cert_no = ? AND status = 'ISSUED'",
                Timestamp.from(departedAt), Timestamp.from(now), certNo);
    }

    public void updateCertStatus(String certNo, CertificateStatus status, Instant now) {
        jdbc.update(
                "UPDATE quarantine_certificate SET status = ?, updated_at = ? WHERE cert_no = ?",
                status.name(), Timestamp.from(now), certNo);
    }

    public void closeCert(String certNo, int reportedQty, Instant closedAt, Instant now) {
        jdbc.update(
                "UPDATE quarantine_certificate SET status = 'CLOSED', reported_qty = ?,"
                        + " closed_at = ?, updated_at = ? WHERE cert_no = ?",
                reportedQty, Timestamp.from(closedAt), Timestamp.from(now), certNo);
    }

    public void updateReportedQty(String certNo, int reportedQty, Instant now) {
        jdbc.update(
                "UPDATE quarantine_certificate SET reported_qty = ?, updated_at = ? WHERE cert_no = ?",
                reportedQty, Timestamp.from(now), certNo);
    }

    // ---------- 同批动物有效证占用 ----------

    public void insertHold(String farmId, String earTagRange, String certNo, Instant now) {
        jdbc.update("INSERT INTO cert_batch_hold (farm_id, ear_tag_range, cert_no, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                farmId, earTagRange, certNo, Timestamp.from(now));
    }

    public Optional<String> findHoldCertNo(String farmId, String earTagRange) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT cert_no FROM cert_batch_hold WHERE farm_id = ? AND ear_tag_range = ?",
                    String.class, farmId, earTagRange));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void deleteHoldByCertNo(String certNo) {
        jdbc.update("DELETE FROM cert_batch_hold WHERE cert_no = ?", certNo);
    }

    // ---------- 可售存栏 ----------

    /** 登记/覆盖可售存栏（合成数据入口），返回最终数量。 */
    public int upsertStock(String farmId, Species species, String earTagRange, int quantity, Instant now) {
        int updated = jdbc.update(
                "UPDATE farm_stock SET sellable_qty = ?, updated_at = ?"
                        + " WHERE farm_id = ? AND species = ? AND ear_tag_range = ?",
                quantity, Timestamp.from(now), farmId, species.name(), earTagRange);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO farm_stock (farm_id, species, ear_tag_range, sellable_qty, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    farmId, species.name(), earTagRange, quantity,
                    Timestamp.from(now), Timestamp.from(now));
        }
        return quantity;
    }

    public Optional<StockRow> findStock(String farmId, Species species, String earTagRange) {
        return queryOne(
                "SELECT * FROM farm_stock WHERE farm_id = ? AND species = ? AND ear_tag_range = ?",
                STOCK_MAPPER, farmId, species.name(), earTagRange);
    }

    /** 原子核减可售存栏：仅当余量充足时生效，返回受影响行数（0 表示不足）。 */
    public int decrementStock(String farmId, Species species, String earTagRange, int quantity, Instant now) {
        return jdbc.update(
                "UPDATE farm_stock SET sellable_qty = sellable_qty - ?, updated_at = ?"
                        + " WHERE farm_id = ? AND species = ? AND ear_tag_range = ? AND sellable_qty >= ?",
                quantity, Timestamp.from(now), farmId, species.name(), earTagRange, quantity);
    }

    // ---------- 落地报告 ----------

    public long insertArrival(String requestId, String fingerprint, String certNo, int quantity,
                              ArrivalResult result, String note, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO arrival_report (request_id, fingerprint, cert_no, quantity, result, note, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setString(3, certNo);
            ps.setInt(4, quantity);
            ps.setString(5, result.name());
            ps.setString(6, note);
            ps.setTimestamp(7, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<ArrivalRow> findArrivalByRequestId(String requestId) {
        return queryOne("SELECT * FROM arrival_report WHERE request_id = ?", ARRIVAL_MAPPER, requestId);
    }

    // ---------- 内部工具 ----------

    private <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
        List<T> rows = jdbc.query(sql, mapper, args);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
