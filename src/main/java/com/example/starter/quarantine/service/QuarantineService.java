package com.example.starter.quarantine.service;

import com.example.starter.quarantine.AlertStatus;
import com.example.starter.quarantine.ArrivalResult;
import com.example.starter.quarantine.CertificateStatus;
import com.example.starter.quarantine.DeclarationStatus;
import com.example.starter.quarantine.Species;
import com.example.starter.quarantine.TransportType;
import com.example.starter.quarantine.api.ApiDtos.AlertHandleCmd;
import com.example.starter.quarantine.api.ApiDtos.AlertScanView;
import com.example.starter.quarantine.api.ApiDtos.AlertView;
import com.example.starter.quarantine.api.ApiDtos.ArrivalCmd;
import com.example.starter.quarantine.api.ApiDtos.ArrivalView;
import com.example.starter.quarantine.api.ApiDtos.CertificateView;
import com.example.starter.quarantine.api.ApiDtos.DeclarationCmd;
import com.example.starter.quarantine.api.ApiDtos.DeclarationView;
import com.example.starter.quarantine.api.ApiDtos.DepartCmd;
import com.example.starter.quarantine.api.ApiDtos.IssueCmd;
import com.example.starter.quarantine.api.ApiDtos.LockdownCmd;
import com.example.starter.quarantine.api.ApiDtos.LockdownView;
import com.example.starter.quarantine.api.ApiDtos.StockCmd;
import com.example.starter.quarantine.api.ApiDtos.StockView;
import com.example.starter.quarantine.api.ApiDtos.TrajectoryView;
import com.example.starter.quarantine.api.ApiException;
import com.example.starter.quarantine.model.Rows.AlertRow;
import com.example.starter.quarantine.model.Rows.ArrivalRow;
import com.example.starter.quarantine.model.Rows.CertificateRow;
import com.example.starter.quarantine.model.Rows.DeclarationRow;
import com.example.starter.quarantine.model.Rows.LockdownRow;
import com.example.starter.quarantine.repo.QuarantineRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 动物调运检疫出证与疫点封锁联动服务。
 *
 * <p>幂等约定：申报、出证、落地报告均以 requestId 为幂等键，指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果，同键异参返回 409 且不改变单据状态。
 *
 * <p>并发约定：封锁发布、出证、启运在同一养殖场登记行上以 SELECT ... FOR UPDATE 串行化，
 * 保证封锁生效与正常出证不会同时成立；同一证的落地报告以证行排他锁串行化。
 */
@Service
public class QuarantineService {

    private static final String LOCKDOWN_REJECT_REASON = "产地被划为疫点封锁区，申报驳回";

    /** 过境超时未核销预警事件类型。 */
    static final String EVENT_TRANSIT_OVERDUE = "TRANSIT_OVERDUE";

    private final QuarantineRepository repo;
    private final Clock clock;

    public QuarantineService(QuarantineRepository repo, Clock businessClock) {
        this.repo = repo;
        this.clock = businessClock;
    }

    // ---------- 检疫申报 ----------

    /**
     * 检疫申报。幂等；申报时养殖场已处于封锁区的，申报单直接落库为已驳回。
     * 运输类型缺省 LOCAL；TRANSIT 必须给定过境时限（小时），LOCAL 不带过境时限。
     */
    @Transactional
    public DeclarationView declare(DeclarationCmd cmd) {
        TransportType transportType = cmd.transportType() == null ? TransportType.LOCAL : cmd.transportType();
        Integer transitHours = transportType == TransportType.TRANSIT ? cmd.transitHours() : null;
        if (transportType == TransportType.TRANSIT && transitHours == null) {
            throw ApiException.unprocessable("过境申报必须给定过境时限 transitHours（小时）");
        }
        String fingerprint = fingerprint(cmd.requestId(), cmd.farmId(), cmd.destination(),
                cmd.species().name(), String.valueOf(cmd.quantity()), cmd.earTagRange(),
                transportType.name(), String.valueOf(transitHours));
        return repo.findDeclarationByRequestId(cmd.requestId())
                .map(existing -> replayDeclaration(existing, fingerprint))
                .orElseGet(() -> createDeclaration(cmd, transportType, transitHours, fingerprint));
    }

    private DeclarationView createDeclaration(DeclarationCmd cmd, TransportType transportType,
                                              Integer transitHours, String fingerprint) {
        Instant now = clock.instant();
        repo.ensureFarmRegistry(cmd.farmId(), now);
        // 与封锁发布串行化：持锁期间封锁要么已生效（本单驳回），要么未生效（封锁随后会驳回本单）。
        repo.lockFarm(cmd.farmId());
        boolean locked = repo.findActiveLockdownByFarm(cmd.farmId()).isPresent();
        DeclarationStatus status = locked ? DeclarationStatus.REJECTED : DeclarationStatus.DECLARED;
        String rejectReason = locked ? LOCKDOWN_REJECT_REASON : null;
        try {
            repo.insertDeclaration(cmd.requestId(), fingerprint, cmd.farmId(), cmd.destination(),
                    cmd.species(), cmd.quantity(), cmd.earTagRange(), status, rejectReason,
                    transportType, transitHours, now);
        } catch (DuplicateKeyException e) {
            // 并发同键申报：以先提交者为准。
            return repo.findDeclarationByRequestId(cmd.requestId())
                    .map(existing -> replayDeclaration(existing, fingerprint))
                    .orElseThrow(() -> e);
        }
        return repo.findDeclarationByRequestId(cmd.requestId())
                .map(this::toDeclarationView)
                .orElseThrow(() -> new IllegalStateException("申报写入后读取失败"));
    }

    private DeclarationView replayDeclaration(DeclarationRow existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId 已存在且业务参数不一致，拒绝重放");
        }
        return toDeclarationView(existing);
    }

    // ---------- 出证 ----------

    /**
     * 出证：核减可售存栏与生成证号在同一事务；同一养殖场同批动物（耳标段）仅允许一张有效证，
     * 重复出证按幂等处理返回已有证。
     */
    @Transactional
    public CertificateView issue(IssueCmd cmd) {
        return repo.findCertByIssueRequestId(cmd.requestId())
                .map(existing -> replayIssue(existing, cmd))
                .orElseGet(() -> createCertificate(cmd));
    }

    private CertificateView createCertificate(IssueCmd cmd) {
        DeclarationRow declaration = repo.findDeclarationByRequestId(cmd.declarationRequestId())
                .orElseThrow(() -> ApiException.notFound("申报单不存在: " + cmd.declarationRequestId()));
        String fingerprint = fingerprint(cmd.requestId(), cmd.declarationRequestId());
        Instant now = clock.instant();
        repo.ensureFarmRegistry(declaration.farmId(), now);
        // 与封锁发布串行化：持锁后封锁要么已生效（拒绝出证），要么未生效（封锁随后作废/标记本证）。
        repo.lockFarm(declaration.farmId());
        if (repo.findActiveLockdownByFarm(declaration.farmId()).isPresent()) {
            throw ApiException.unprocessable("产地处于疫点封锁区，禁止出证");
        }
        if (declaration.status() == DeclarationStatus.REJECTED) {
            throw ApiException.unprocessable("申报已被驳回，禁止出证");
        }
        if (declaration.status() == DeclarationStatus.CERTIFIED) {
            return repo.findCertByDeclarationRequestId(cmd.declarationRequestId())
                    .map(this::toCertificateView)
                    .orElseThrow(() -> new IllegalStateException("申报已出证但证书缺失"));
        }
        // 同批动物仅允许一张有效证：重复出证按幂等处理，返回已有证。
        var heldCertNo = repo.findHoldCertNo(declaration.farmId(), declaration.earTagRange());
        if (heldCertNo.isPresent()) {
            return repo.findCertByNo(heldCertNo.get())
                    .map(this::toCertificateView)
                    .orElseThrow(() -> new IllegalStateException("批次占用记录指向的证书缺失"));
        }
        // 核减可售存栏（原子条件更新），不足则整体失败。
        int decremented = repo.decrementStock(declaration.farmId(), declaration.species(),
                declaration.earTagRange(), declaration.quantity(), now);
        if (decremented == 0) {
            throw ApiException.unprocessable("可售存栏不足，出证失败");
        }
        Instant issuedAt = now;
        Instant expiresAt = issuedAt.plusSeconds(declaration.species().validityHours() * 3600L);
        // 唯一键（issue_request_id、批次占用）作为并发兜底：冲突时本事务回滚，
        // 由 IssueExecutor 在新事务中重放先提交结果。
        long id = repo.insertCertificate("TMP-" + cmd.requestId(), cmd.requestId(), fingerprint,
                declaration.requestId(), declaration.farmId(), declaration.destination(),
                declaration.species(), declaration.quantity(), declaration.earTagRange(),
                declaration.transportType(), declaration.transitHours(),
                issuedAt, expiresAt, now);
        String certNo = String.format("AQ%08d", id);
        repo.updateCertNo(id, certNo, now);
        repo.insertHold(declaration.farmId(), declaration.earTagRange(), certNo, now);
        repo.markDeclarationCertified(declaration.requestId(), now);
        return repo.findCertByIssueRequestId(cmd.requestId())
                .map(this::toCertificateView)
                .orElseThrow(() -> new IllegalStateException("证书写入后读取失败"));
    }

    private CertificateView replayIssue(CertificateRow existing, IssueCmd cmd) {
        String fingerprint = fingerprint(cmd.requestId(), cmd.declarationRequestId());
        if (!existing.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId 已存在且业务参数不一致，拒绝重放");
        }
        return toCertificateView(existing);
    }

    // ---------- 启运 ----------

    /**
     * 启运：已出证未启运 → 调运在途。重复启运返回当前证状态（幂等）。
     */
    @Transactional
    public CertificateView depart(DepartCmd cmd) {
        CertificateRow cert = repo.findCertByNo(cmd.certNo())
                .orElseThrow(() -> ApiException.notFound("证书不存在: " + cmd.certNo()));
        Instant now = clock.instant();
        repo.ensureFarmRegistry(cert.farmId(), now);
        repo.lockFarm(cert.farmId());
        CertificateRow locked = repo.findCertByNo(cmd.certNo()).orElseThrow();
        if (locked.status() == CertificateStatus.IN_TRANSIT) {
            return toCertificateView(locked);
        }
        if (locked.status() != CertificateStatus.ISSUED) {
            throw ApiException.unprocessable("证状态不允许启运: " + locked.status());
        }
        if (repo.findActiveLockdownByFarm(locked.farmId()).isPresent()) {
            throw ApiException.unprocessable("产地处于疫点封锁区，禁止启运");
        }
        // 过境证自启运时刻起算抵达核销时限终点。
        Instant transitDeadline = locked.transportType() == TransportType.TRANSIT
                ? now.plusSeconds(locked.transitHours() * 3600L) : null;
        repo.markDeparted(cmd.certNo(), now, transitDeadline, now);
        return repo.findCertByNo(cmd.certNo()).map(this::toCertificateView).orElseThrow();
    }

    // ---------- 疫点封锁 ----------

    /**
     * 发布疫点封锁：即刻生效。未出证申报一律驳回；已出证未启运的证自动作废；
     * 已在途的允许完成落地但强制隔离观察标记。
     */
    @Transactional
    public LockdownView lockdown(LockdownCmd cmd) {
        String fingerprint = fingerprint(cmd.requestId(), cmd.farmId(), cmd.reason());
        var byRequest = repo.findLockdownByRequestId(cmd.requestId());
        if (byRequest.isPresent()) {
            LockdownRow existing = byRequest.get();
            if (!existing.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且业务参数不一致，拒绝重放");
            }
            return toLockdownView(existing, 0, 0, 0);
        }
        var byFarm = repo.findActiveLockdownByFarm(cmd.farmId());
        if (byFarm.isPresent()) {
            return toLockdownView(byFarm.get(), 0, 0, 0);
        }
        Instant now = clock.instant();
        repo.ensureFarmRegistry(cmd.farmId(), now);
        repo.lockFarm(cmd.farmId());
        // 持锁后复查，避免与并发封锁重复生效。
        var recheck = repo.findActiveLockdownByFarm(cmd.farmId());
        if (recheck.isPresent()) {
            return toLockdownView(recheck.get(), 0, 0, 0);
        }
        repo.insertLockdown(cmd.requestId(), fingerprint, cmd.farmId(), cmd.reason(), now);
        int rejected = repo.rejectPendingDeclarations(cmd.farmId(), LOCKDOWN_REJECT_REASON, now);
        List<String> voided = repo.voidIssuedCertsByFarm(cmd.farmId(), now);
        voided.forEach(repo::deleteHoldByCertNo);
        int quarantined = repo.markInTransitQuarantineByFarm(cmd.farmId(), now);
        LockdownRow row = repo.findLockdownByRequestId(cmd.requestId()).orElseThrow();
        return toLockdownView(row, rejected, voided.size(), quarantined);
    }

    // ---------- 落地报告 ----------

    /**
     * 落地报告。幂等；恰在有效期终点前（含终点）有效，超时拒绝并将证标记证失效；
     * 累计数量恰等于证载数量即结案，超量整批拒绝；结案为终态，迟到事件只记录不改状态。
     *
     * <p>过境扩展：超过过境时限未核销自动生成预警事件（恰满时限不算超时，预警不改变证书效力）；
     * 途经合成疫区清单区域时自动加加强监测标记，标记与抵达记录在同一事务内写入。
     */
    @Transactional
    public ArrivalView arrive(ArrivalCmd cmd) {
        String fingerprint = fingerprint(cmd.requestId(), cmd.certNo(), String.valueOf(cmd.quantity()),
                viaRegionsKey(cmd.viaRegions()));
        return repo.findArrivalByRequestId(cmd.requestId())
                .map(existing -> replayArrival(existing, fingerprint))
                .orElseGet(() -> processArrival(cmd, fingerprint));
    }

    private ArrivalView processArrival(ArrivalCmd cmd, String fingerprint) {
        CertificateRow cert = repo.findCertByNoForUpdate(cmd.certNo())
                .orElseThrow(() -> ApiException.notFound("证书不存在: " + cmd.certNo()));
        // 持证行锁后复查幂等键，防止并发同键报告重复入账。
        var existing = repo.findArrivalByRequestId(cmd.requestId());
        if (existing.isPresent()) {
            return replayArrival(existing.get(), fingerprint);
        }
        Instant now = clock.instant();
        ArrivalResult result;
        String note = null;
        switch (cert.status()) {
            case CLOSED -> {
                result = ArrivalResult.LATE_IGNORED;
                note = "证已结案，迟到事件只记录不改状态";
            }
            case VOIDED, EXPIRED -> {
                result = ArrivalResult.REJECTED_CERT_INVALID;
                note = "证状态为 " + cert.status() + "，拒绝落地报告";
            }
            case ISSUED -> {
                result = ArrivalResult.REJECTED_NOT_IN_TRANSIT;
                note = "证未启运，拒绝落地报告";
            }
            case IN_TRANSIT -> {
                if (now.isAfter(cert.expiresAt())) {
                    repo.updateCertStatus(cert.certNo(), CertificateStatus.EXPIRED, now);
                    repo.deleteHoldByCertNo(cert.certNo());
                    result = ArrivalResult.REJECTED_EXPIRED;
                    note = "超过有效期终点，证失效";
                } else {
                    long cumulative = (long) cert.reportedQty() + cmd.quantity();
                    if (cumulative > cert.quantity()) {
                        result = ArrivalResult.REJECTED_OVERFLOW;
                        note = "累计数量超过证载数量，整批拒绝";
                    } else if (cumulative == cert.quantity()) {
                        repo.closeCert(cert.certNo(), (int) cumulative, now, now);
                        repo.deleteHoldByCertNo(cert.certNo());
                        result = ArrivalResult.ACCEPTED_FINAL;
                    } else {
                        repo.updateReportedQty(cert.certNo(), (int) cumulative, now);
                        result = ArrivalResult.ACCEPTED_PARTIAL;
                    }
                }
            }
            default -> throw new IllegalStateException("未知证状态: " + cert.status());
        }
        repo.insertArrival(cmd.requestId(), fingerprint, cmd.certNo(), cmd.quantity(),
                result, note, now);
        // 过境扩展：与抵达记录同一事务写入。
        if (cert.transportType() == TransportType.TRANSIT) {
            // 超时未核销自动生成预警（恰满时限不算超时）；预警不改变证书效力。
            if (cert.status() == CertificateStatus.IN_TRANSIT && cert.transitDeadline() != null
                    && now.isAfter(cert.transitDeadline())) {
                ensureTransitOverdueAlert(cert, now);
            }
            // 途经合成疫区清单区域：自动加加强监测标记。
            if (cmd.viaRegions() != null && cmd.viaRegions().stream().anyMatch(repo::isEpidemicArea)) {
                repo.setEnhancedMonitoring(cmd.certNo(), now);
            }
        }
        CertificateRow after = repo.findCertByNo(cmd.certNo()).orElseThrow();
        ArrivalRow row = repo.findArrivalByRequestId(cmd.requestId()).orElseThrow();
        return toArrivalView(row, after);
    }

    /** 生成过境超时未核销预警；同一证同一事件类型唯一，重复生成幂等。 */
    private void ensureTransitOverdueAlert(CertificateRow cert, Instant now) {
        if (repo.findAlertByCertAndType(cert.certNo(), EVENT_TRANSIT_OVERDUE).isPresent()) {
            return;
        }
        String requestId = "AUTO-TRANSIT-OVERDUE-" + cert.certNo();
        String fingerprint = fingerprint(requestId, cert.certNo(), EVENT_TRANSIT_OVERDUE);
        try {
            repo.insertAlert(requestId, fingerprint, cert.certNo(), EVENT_TRANSIT_OVERDUE,
                    "过境超过时限终点 " + cert.transitDeadline() + " 未完成抵达核销", now);
        } catch (DuplicateKeyException e) {
            // 并发生成同一预警：以先提交者为准。
        }
    }

    private ArrivalView replayArrival(ArrivalRow existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId 已存在且业务参数不一致，拒绝重放");
        }
        CertificateRow cert = repo.findCertByNo(existing.certNo())
                .orElseThrow(() -> new IllegalStateException("落地报告指向的证书缺失"));
        return toArrivalView(existing, cert);
    }

    // ---------- 可售存栏（合成数据入口） ----------

    /**
     * 登记/覆盖养殖场可售存栏。携带来源证号时按证书类型拦截不符用途的流向：
     * 过境证书不得用于本地销售或饲养流向登记。
     */
    @Transactional
    public StockView upsertStock(StockCmd cmd) {
        if (cmd.certNo() != null && !cmd.certNo().isBlank()) {
            CertificateRow cert = repo.findCertByNo(cmd.certNo())
                    .orElseThrow(() -> ApiException.notFound("证书不存在: " + cmd.certNo()));
            if (cert.transportType() == TransportType.TRANSIT) {
                throw ApiException.unprocessable("过境证书不得用于本地销售或饲养流向登记");
            }
        }
        Instant now = clock.instant();
        repo.ensureFarmRegistry(cmd.farmId(), now);
        repo.upsertStock(cmd.farmId(), cmd.species(), cmd.earTagRange(), cmd.quantity(), now);
        return new StockView(cmd.farmId(), cmd.species(), cmd.earTagRange(), cmd.quantity());
    }

    // ---------- 过境预警：扫描、处理闭环、轨迹查询 ----------

    /**
     * 扫描超时未核销的过境在途证并自动生成预警事件（恰满时限不算超时）；
     * 同一证同一事件类型唯一，重复扫描幂等。返回本次新生成的预警数。
     */
    @Transactional
    public AlertScanView scanTransitOverdue() {
        Instant now = clock.instant();
        int generated = 0;
        for (CertificateRow cert : repo.findOverdueTransitCerts(now)) {
            if (repo.findAlertByCertAndType(cert.certNo(), EVENT_TRANSIT_OVERDUE).isEmpty()) {
                ensureTransitOverdueAlert(cert, now);
                generated++;
            }
        }
        return new AlertScanView(generated);
    }

    /**
     * 预警事件处理闭环。幂等：指纹覆盖 requestId 与全部业务字段；
     * 同键同参返回首次结果，同键异参 409 且无副作用。
     */
    @Transactional
    public AlertView handleAlert(AlertHandleCmd cmd) {
        String fingerprint = fingerprint(cmd.requestId(), String.valueOf(cmd.alertId()),
                String.valueOf(cmd.note()));
        // 同一处理请求号已用于其他预警：同键异参，拒绝且无副作用。
        var byHandleRequest = repo.findAlertByHandleRequestId(cmd.requestId());
        if (byHandleRequest.isPresent() && byHandleRequest.get().id() != cmd.alertId()) {
            throw ApiException.conflict("requestId 已存在且业务参数不一致，拒绝重放");
        }
        AlertRow alert = repo.findAlertById(cmd.alertId())
                .orElseThrow(() -> ApiException.notFound("预警事件不存在: " + cmd.alertId()));
        if (alert.status() == AlertStatus.CLOSED) {
            if (cmd.requestId().equals(alert.handleRequestId())
                    && !fingerprint.equals(alert.handleFingerprint())) {
                throw ApiException.conflict("requestId 已存在且业务参数不一致，拒绝重放");
            }
            // 已闭环：幂等返回现状，不重复处理。
            return toAlertView(alert);
        }
        repo.closeAlert(alert.id(), cmd.requestId(), fingerprint, cmd.note(), clock.instant());
        return repo.findAlertById(alert.id()).map(this::toAlertView).orElseThrow();
    }

    /** 查询单个预警事件。 */
    @Transactional(readOnly = true)
    public AlertView getAlert(long id) {
        return repo.findAlertById(id)
                .map(this::toAlertView)
                .orElseThrow(() -> ApiException.notFound("预警事件不存在: " + id));
    }

    /** 证书轨迹查询：证书当前状态 + 落地报告留痕 + 预警事件留痕（审计口径）。 */
    @Transactional(readOnly = true)
    public TrajectoryView getTrajectory(String certNo) {
        CertificateView cert = repo.findCertByNo(certNo)
                .map(this::toCertificateView)
                .orElseThrow(() -> ApiException.notFound("证书不存在: " + certNo));
        List<ArrivalView> arrivals = repo.findArrivalsByCertNo(certNo).stream()
                .map(row -> toArrivalView(row, repo.findCertByNo(certNo).orElseThrow()))
                .toList();
        List<AlertView> alerts = repo.findAlertsByCertNo(certNo).stream()
                .map(this::toAlertView)
                .toList();
        return new TrajectoryView(cert, arrivals, alerts);
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public DeclarationView getDeclaration(String requestId) {
        return repo.findDeclarationByRequestId(requestId)
                .map(this::toDeclarationView)
                .orElseThrow(() -> ApiException.notFound("申报单不存在: " + requestId));
    }

    @Transactional(readOnly = true)
    public CertificateView getCertificate(String certNo) {
        return repo.findCertByNo(certNo)
                .map(this::toCertificateView)
                .orElseThrow(() -> ApiException.notFound("证书不存在: " + certNo));
    }

    @Transactional(readOnly = true)
    public StockView getStock(String farmId, String species, String earTagRange) {
        return repo.findStock(farmId, Species.valueOf(species), earTagRange)
                .map(row -> new StockView(row.farmId(), row.species(), row.earTagRange(), row.sellableQty()))
                .orElseThrow(() -> ApiException.notFound("存栏记录不存在"));
    }

    // ---------- 视图映射与指纹 ----------

    private DeclarationView toDeclarationView(DeclarationRow row) {
        return new DeclarationView(row.requestId(), row.farmId(), row.destination(), row.species(),
                row.quantity(), row.earTagRange(), row.status(), row.rejectReason(),
                row.transportType(), row.transitHours(), row.createdAt());
    }

    private CertificateView toCertificateView(CertificateRow row) {
        return new CertificateView(row.certNo(), row.issueRequestId(), row.declarationRequestId(),
                row.farmId(), row.destination(), row.species(), row.quantity(), row.earTagRange(),
                row.status(), row.quarantineObservation(), row.transportType(), row.transitDeadline(),
                row.enhancedMonitoring(), row.reportedQty(),
                row.issuedAt(), row.expiresAt(), row.departedAt(), row.closedAt());
    }

    private LockdownView toLockdownView(LockdownRow row, int rejected, int voided, int quarantined) {
        return new LockdownView(row.requestId(), row.farmId(), row.reason(), row.status(),
                row.createdAt(), rejected, voided, quarantined);
    }

    private ArrivalView toArrivalView(ArrivalRow row, CertificateRow cert) {
        return new ArrivalView(row.requestId(), row.certNo(), row.quantity(), row.result(), row.note(),
                cert.status(), cert.quarantineObservation(), cert.enhancedMonitoring(),
                cert.reportedQty(), row.createdAt());
    }

    private AlertView toAlertView(AlertRow row) {
        return new AlertView(row.id(), row.requestId(), row.certNo(), row.eventType(), row.status(),
                row.note(), row.handleNote(), row.createdAt(), row.handledAt());
    }

    /** 途经区域清单归一化为指纹字段（保持元素顺序，空清单与 null 等价）。 */
    private static String viaRegionsKey(List<String> viaRegions) {
        return viaRegions == null ? "" : String.join(",", viaRegions);
    }

    /**
     * 幂等指纹：requestId 与全部业务字段的 SHA-256。
     */
    static String fingerprint(String... fields) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (String field : fields) {
            digest.update(field.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0x1F);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
