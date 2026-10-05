package com.example.starter.quarantine.repo;

import com.example.starter.quarantine.ArrivalResult;
import com.example.starter.quarantine.CertificateStatus;
import com.example.starter.quarantine.DeclarationStatus;
import com.example.starter.quarantine.FlowPurpose;
import com.example.starter.quarantine.Species;
import com.example.starter.quarantine.TransitAlertStatus;
import com.example.starter.quarantine.TransportType;
import com.example.starter.quarantine.model.Rows.ArrivalRow;
import com.example.starter.quarantine.model.Rows.CertificateRow;
import com.example.starter.quarantine.model.Rows.DeclarationRow;
import com.example.starter.quarantine.model.Rows.FlowRegistrationRow;
import com.example.starter.quarantine.model.Rows.LockdownRow;
import com.example.starter.quarantine.model.Rows.StockRow;
import com.example.starter.quarantine.model.Rows.TransitAlertRow;
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
            TransportType.valueOf(rs.getString("transport_type")),
            (Integer) rs.getObject("transit_hours"),
            rs.getString("route_regions"),
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
            TransportType.valueOf(rs.getString("transport_type")),
            (Integer) rs.getObject("transit_hours"),
            Optional.ofNullable(rs.getTimestamp("transit_deadline_at")).map(Timestamp::toInstant).orElse(null),
            rs.getString("route_regions"),
            rs.getBoolean("enhanced_monitoring"),
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

    private static final RowMapper<FlowRegistrationRow> FLOW_MAPPER = (rs, n) -> new FlowRegistrationRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("cert_no"),
            FlowPurpose.valueOf(rs.getString("purpose")),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<TransitAlertRow> ALERT_MAPPER = (rs, n) -> new TransitAlertRow(
            rs.getLong("id"),
            rs.getString("alert_key"),
            rs.getString("cert_no"),
            TransitAlertStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("triggered_at").toInstant(),
            rs.getString("resolve_request_id"),
            rs.getString("resolve_fingerprint"),
            rs.getString("resolve_note"),
            Optional.ofNullable(rs.getTimestamp("resolved_at")).map(Timestamp::toInstant).orElse(null),
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
                                  String rejectReason, TransportType transportType,
                                  Integer transitHours, String routeRegions, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO quarantine_declaration (request_id, fingerprint, farm_id, destination,"
                            + " species, quantity, ear_tag_range, status, reject_reason,"
                            + " transport_type, transit_hours, route_regions, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
            ps.setString(10, transportType.name());
            if (transitHours == null) {
                ps.setNull(11, java.sql.Types.INTEGER);
            } else {
                ps.setInt(11, transitHours);
            }
            ps.setString(12, routeRegions);
            ps.setTimestamp(13, Timestamp.from(now));
            ps.setTimestamp(14, Timestamp.from(now));
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
                                  TransportType transportType, Integer transitHours, String routeRegions,
                                  Instant issuedAt, Instant expiresAt, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO quarantine_certificate (cert_no, issue_request_id, fingerprint,"
                            + " declaration_request_id, farm_id, destination, species, quantity, ear_tag_range,"
                            + " status, quarantine_observation, reported_qty,"
                            + " transport_type, transit_hours, route_regions, enhanced_monitoring,"
                            + " issued_at, expires_at, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ISSUED', FALSE, 0, ?, ?, ?, FALSE, ?, ?, ?, ?)",
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
            ps.setString(10, transportType.name());
            if (transitHours == null) {
                ps.setNull(11, java.sql.Types.INTEGER);
            } else {
                ps.setInt(11, transitHours);
            }
            ps.setString(12, routeRegions);
            ps.setTimestamp(13, Timestamp.from(issuedAt));
            ps.setTimestamp(14, Timestamp.from(expiresAt));
            ps.setTimestamp(15, Timestamp.from(now));
            ps.setTimestamp(16, Timestamp.from(now));
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

    /** 启运：置在途；过境证同事务写入核销时限终点（启运时间 + 过境时限）。 */
    public void markDeparted(String certNo, Instant departedAt, Instant transitDeadlineAt, Instant now) {
        jdbc.update(
                "UPDATE quarantine_certificate SET status = 'IN_TRANSIT', departed_at = ?,"
                        + " transit_deadline_at = ?, updated_at = ?"
                        + " WHERE cert_no = ? AND status = 'ISSUED'",
                Timestamp.from(departedAt),
                transitDeadlineAt == null ? null : Timestamp.from(transitDeadlineAt),
                Timestamp.from(now), certNo);
    }

    /** 过境车辆途经疫区清单区域：置加强监测标记（与抵达记录同事务）。 */
    public void markEnhancedMonitoring(String certNo, Instant now) {
        jdbc.update(
                "UPDATE quarantine_certificate SET enhanced_monitoring = TRUE, updated_at = ?"
                        + " WHERE cert_no = ?",
                Timestamp.from(now), certNo);
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

    /** 按证号查询全部落地报告（轨迹查询用），按报告时间升序。 */
    public List<ArrivalRow> findArrivalsByCertNo(String certNo) {
        return jdbc.query("SELECT * FROM arrival_report WHERE cert_no = ? ORDER BY created_at, id",
                ARRIVAL_MAPPER, certNo);
    }

    // ---------- 合成疫区清单 ----------

    /** 全部合成疫区区域编码。 */
    public List<String> findEpidemicAreaCodes() {
        return jdbc.queryForList("SELECT region_code FROM epidemic_area", String.class);
    }

    // ---------- 流向登记 ----------

    public long insertFlowRegistration(String requestId, String fingerprint, String certNo,
                                       FlowPurpose purpose, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO flow_registration (request_id, fingerprint, cert_no, purpose, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setString(3, certNo);
            ps.setString(4, purpose.name());
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<FlowRegistrationRow> findFlowByRequestId(String requestId) {
        return queryOne("SELECT * FROM flow_registration WHERE request_id = ?", FLOW_MAPPER, requestId);
    }

    /** 按证号查询全部流向登记（轨迹查询用），按登记时间升序。 */
    public List<FlowRegistrationRow> findFlowsByCertNo(String certNo) {
        return jdbc.query("SELECT * FROM flow_registration WHERE cert_no = ? ORDER BY created_at, id",
                FLOW_MAPPER, certNo);
    }

    // ---------- 过境超时预警 ----------

    /** 插入过境预警；同一证已有预警（唯一键冲突）时忽略，保证每证至多一条。 */
    public void insertTransitAlertIfAbsent(String alertKey, String certNo, Instant now) {
        int updated = jdbc.update(
                "UPDATE transit_alert SET alert_key = alert_key WHERE alert_key = ?", alertKey);
        if (updated > 0) {
            return;
        }
        try {
            jdbc.update(
                    "INSERT INTO transit_alert (alert_key, cert_no, status, triggered_at, created_at, updated_at)"
                            + " VALUES (?, ?, 'OPEN', ?, ?, ?)",
                    alertKey, certNo, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            // 并发生成同一证的预警：先提交者已写入，忽略。
        }
    }

    public Optional<TransitAlertRow> findAlertByKey(String alertKey) {
        return queryOne("SELECT * FROM transit_alert WHERE alert_key = ?", ALERT_MAPPER, alertKey);
    }

    public Optional<TransitAlertRow> findAlertByResolveRequestId(String resolveRequestId) {
        return queryOne("SELECT * FROM transit_alert WHERE resolve_request_id = ?",
                ALERT_MAPPER, resolveRequestId);
    }

    /** 全部预警事件，按生成时间升序（审计口径）。 */
    public List<TransitAlertRow> findAllAlerts() {
        return jdbc.query("SELECT * FROM transit_alert ORDER BY triggered_at, id", ALERT_MAPPER);
    }

    /** 按证号查询预警事件（轨迹查询用）。 */
    public List<TransitAlertRow> findAlertsByCertNo(String certNo) {
        return jdbc.query("SELECT * FROM transit_alert WHERE cert_no = ? ORDER BY triggered_at, id",
                ALERT_MAPPER, certNo);
    }

    /** 办结预警：仅当仍为 OPEN 时生效，返回受影响行数。 */
    public int resolveAlert(String alertKey, String resolveRequestId, String resolveFingerprint,
                            String resolveNote, Instant now) {
        return jdbc.update(
                "UPDATE transit_alert SET status = 'RESOLVED', resolve_request_id = ?,"
                        + " resolve_fingerprint = ?, resolve_note = ?, resolved_at = ?, updated_at = ?"
                        + " WHERE alert_key = ? AND status = 'OPEN'",
                resolveRequestId, resolveFingerprint, resolveNote,
                Timestamp.from(now), Timestamp.from(now), alertKey);
    }

    /** 查询超时未核销的在途过境证：核销时限终点早于当前时刻。 */
    public List<CertificateRow> findOverdueTransitCerts(Instant now) {
        return jdbc.query(
                "SELECT * FROM quarantine_certificate WHERE transport_type = 'TRANSIT'"
                        + " AND status = 'IN_TRANSIT' AND transit_deadline_at IS NOT NULL"
                        + " AND transit_deadline_at < ?",
                CERTIFICATE_MAPPER, Timestamp.from(now));
    }

    // ---------- 内部工具 ----------

    private <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
        List<T> rows = jdbc.query(sql, mapper, args);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
