package com.example.starter.quarantine.service;

import com.example.starter.quarantine.ArrivalResult;
import com.example.starter.quarantine.CertificateStatus;
import com.example.starter.quarantine.DeclarationStatus;
import com.example.starter.quarantine.FlowPurpose;
import com.example.starter.quarantine.Species;
import com.example.starter.quarantine.TransitAlertStatus;
import com.example.starter.quarantine.TransportType;
import com.example.starter.quarantine.api.ApiDtos.ArrivalCmd;
import com.example.starter.quarantine.api.ApiDtos.ArrivalView;
import com.example.starter.quarantine.api.ApiDtos.CertificateView;
import com.example.starter.quarantine.api.ApiDtos.DeclarationCmd;
import com.example.starter.quarantine.api.ApiDtos.DeclarationView;
import com.example.starter.quarantine.api.ApiDtos.DepartCmd;
import com.example.starter.quarantine.api.ApiDtos.FlowCmd;
import com.example.starter.quarantine.api.ApiDtos.FlowView;
import com.example.starter.quarantine.api.ApiDtos.IssueCmd;
import com.example.starter.quarantine.api.ApiDtos.LockdownCmd;
import com.example.starter.quarantine.api.ApiDtos.LockdownView;
import com.example.starter.quarantine.api.ApiDtos.ResolveAlertCmd;
import com.example.starter.quarantine.api.ApiDtos.StockCmd;
import com.example.starter.quarantine.api.ApiDtos.StockView;
import com.example.starter.quarantine.api.ApiDtos.TrajectoryEvent;
import com.example.starter.quarantine.api.ApiDtos.TrajectoryView;
import com.example.starter.quarantine.api.ApiDtos.TransitAlertView;
import com.example.starter.quarantine.api.ApiException;
import com.example.starter.quarantine.model.Rows.ArrivalRow;
import com.example.starter.quarantine.model.Rows.CertificateRow;
import com.example.starter.quarantine.model.Rows.DeclarationRow;
import com.example.starter.quarantine.model.Rows.FlowRegistrationRow;
import com.example.starter.quarantine.model.Rows.LockdownRow;
import com.example.starter.quarantine.model.Rows.TransitAlertRow;
import com.example.starter.quarantine.repo.QuarantineRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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

    private final QuarantineRepository repo;
    private final Clock clock;

    public QuarantineService(QuarantineRepository repo, Clock businessClock) {
        this.repo = repo;
        this.clock = businessClock;
    }

    // ---------- 检疫申报 ----------

    /**
     * 检疫申报。幂等；申报时养殖场已处于封锁区的，申报单直接落库为已驳回。
     * 运输类型 TRANSIT（过境）须给定时限与途经区域清单；LOCAL 不允许携带过境字段。
     */
    @Transactional
    public DeclarationView declare(DeclarationCmd cmd) {
        TransportType transportType = cmd.transportType() == null ? TransportType.LOCAL : cmd.transportType();
        Integer transitHours = transportType == TransportType.TRANSIT ? cmd.transitHours() : null;
        List<String> routeRegions = transportType == TransportType.TRANSIT
                ? normalizeRouteRegions(cmd.routeRegions()) : null;
        if (transportType == TransportType.TRANSIT) {
            if (transitHours == null) {
                throw ApiException.unprocessable("过境申报必须给定时限（transitHours，小时）");
            }
            if (routeRegions == null) {
                throw ApiException.unprocessable("过境申报必须给出途经区域清单（routeRegions）");
            }
        }
        String joinedRegions = routeRegions == null ? null : String.join(",", routeRegions);
        String fingerprint = fingerprint(cmd.requestId(), cmd.farmId(), cmd.destination(),
                cmd.species().name(), String.valueOf(cmd.quantity()), cmd.earTagRange(),
                transportType.name(), String.valueOf(transitHours), String.valueOf(joinedRegions));
        TransportType finalType = transportType;
        return repo.findDeclarationByRequestId(cmd.requestId())
                .map(existing -> replayDeclaration(existing, fingerprint))
                .orElseGet(() -> createDeclaration(cmd, fingerprint, finalType, transitHours, joinedRegions));
    }

    private List<String> normalizeRouteRegions(List<String> routeRegions) {
        if (routeRegions == null) {
            return null;
        }
        List<String> normalized = routeRegions.stream().map(String::trim).distinct().toList();
        return normalized.isEmpty() ? null : normalized;
    }

    private DeclarationView createDeclaration(DeclarationCmd cmd, String fingerprint,
                                              TransportType transportType, Integer transitHours,
                                              String joinedRegions) {
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
                    transportType, transitHours, joinedRegions, now);
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
                declaration.transportType(), declaration.transitHours(), declaration.routeRegions(),
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
        // 过境行程自启运起算核销时限：终点 = 启运时间 + 过境时限，恰满时限视为按时。
        Instant transitDeadlineAt = locked.transportType() == TransportType.TRANSIT
                ? now.plusSeconds(locked.transitHours() * 3600L) : null;
        repo.markDeparted(cmd.certNo(), now, transitDeadlineAt, now);
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
     */
    @Transactional
    public ArrivalView arrive(ArrivalCmd cmd) {
        String fingerprint = fingerprint(cmd.requestId(), cmd.certNo(), String.valueOf(cmd.quantity()));
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
        // 过境证：与抵达记录同一事务写入加强监测标记与超时预警（预警不改变证书效力）。
        if (cert.transportType() == TransportType.TRANSIT) {
            if (passesEpidemicArea(cert.routeRegions())) {
                repo.markEnhancedMonitoring(cert.certNo(), now);
            }
            ensureTransitAlertIfOverdue(cert, now);
        }
        CertificateRow after = repo.findCertByNo(cmd.certNo()).orElseThrow();
        ArrivalRow row = repo.findArrivalByRequestId(cmd.requestId()).orElseThrow();
        return toArrivalView(row, after);
    }

    /** 途经区域与合成疫区清单是否有交集。 */
    private boolean passesEpidemicArea(String routeRegions) {
        if (routeRegions == null || routeRegions.isBlank()) {
            return false;
        }
        List<String> regions = List.of(routeRegions.split(","));
        return repo.findEpidemicAreaCodes().stream().anyMatch(regions::contains);
    }

    /**
     * 过境超时未核销则生成预警事件（每证至多一条，幂等）：在途证当前时刻晚于核销时限终点，
     * 或已结案证结案时刻晚于终点。恰满时限（等于终点）视为按时，不触发预警。
     */
    private void ensureTransitAlertIfOverdue(CertificateRow cert, Instant now) {
        Instant deadline = cert.transitDeadlineAt();
        if (deadline == null) {
            return;
        }
        boolean overdue = cert.status() == CertificateStatus.CLOSED
                ? cert.closedAt() != null && cert.closedAt().isAfter(deadline)
                : now.isAfter(deadline);
        if (overdue) {
            repo.insertTransitAlertIfAbsent(alertKey(cert.certNo()), cert.certNo(), now);
        }
    }

    private static String alertKey(String certNo) {
        return "TA-" + certNo;
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
     * 登记/覆盖养殖场可售存栏。
     */
    @Transactional
    public StockView upsertStock(StockCmd cmd) {
        Instant now = clock.instant();
        repo.ensureFarmRegistry(cmd.farmId(), now);
        repo.upsertStock(cmd.farmId(), cmd.species(), cmd.earTagRange(), cmd.quantity(), now);
        return new StockView(cmd.farmId(), cmd.species(), cmd.earTagRange(), cmd.quantity());
    }

    // ---------- 流向登记 ----------

    /**
     * 流向登记。幂等；按证书运输类型拦截不符用途的流向：过境证仅允许 TRANSIT 用途，
     * 本地证仅允许 LOCAL_SALE/BREEDING；不符用途返回 422 且无副作用。
     */
    @Transactional
    public FlowView registerFlow(FlowCmd cmd) {
        String fingerprint = fingerprint(cmd.requestId(), cmd.certNo(), cmd.purpose().name());
        var existing = repo.findFlowByRequestId(cmd.requestId());
        if (existing.isPresent()) {
            FlowRegistrationRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且业务参数不一致，拒绝重放");
            }
            return toFlowView(row);
        }
        CertificateRow cert = repo.findCertByNo(cmd.certNo())
                .orElseThrow(() -> ApiException.notFound("证书不存在: " + cmd.certNo()));
        boolean purposeAllowed = cert.transportType() == TransportType.TRANSIT
                ? cmd.purpose() == FlowPurpose.TRANSIT
                : cmd.purpose() != FlowPurpose.TRANSIT;
        if (!purposeAllowed) {
            throw ApiException.unprocessable(
                    "证书运输类型 " + cert.transportType() + " 不允许流向用途 " + cmd.purpose());
        }
        Instant now = clock.instant();
        try {
            repo.insertFlowRegistration(cmd.requestId(), fingerprint, cmd.certNo(), cmd.purpose(), now);
        } catch (DuplicateKeyException e) {
            // 并发同键登记：以先提交者为准。
            return repo.findFlowByRequestId(cmd.requestId())
                    .map(row -> {
                        if (!row.fingerprint().equals(fingerprint)) {
                            throw ApiException.conflict("requestId 已存在且业务参数不一致，拒绝重放");
                        }
                        return toFlowView(row);
                    })
                    .orElseThrow(() -> e);
        }
        return repo.findFlowByRequestId(cmd.requestId()).map(this::toFlowView).orElseThrow();
    }

    // ---------- 过境超时预警 ----------

    /**
     * 扫描超时未核销的在途过境证并生成预警事件（每证至多一条，幂等）；预警不改变证书效力。
     * 返回当前全部预警事件（审计口径）。
     */
    @Transactional
    public List<TransitAlertView> scanTransitAlerts() {
        Instant now = clock.instant();
        for (CertificateRow cert : repo.findOverdueTransitCerts(now)) {
            repo.insertTransitAlertIfAbsent(alertKey(cert.certNo()), cert.certNo(), now);
        }
        return listTransitAlerts();
    }

    /**
     * 办结过境预警（处理闭环）。幂等：同键同参返回首次结果，同键异参 409；
     * 已办结的预警对新请求号幂等返回当前状态。
     */
    @Transactional
    public TransitAlertView resolveTransitAlert(String alertKey, ResolveAlertCmd cmd) {
        String fingerprint = fingerprint(cmd.requestId(), alertKey, String.valueOf(cmd.note()));
        var byRequest = repo.findAlertByResolveRequestId(cmd.requestId());
        if (byRequest.isPresent()) {
            TransitAlertRow row = byRequest.get();
            if (!row.resolveFingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且业务参数不一致，拒绝重放");
            }
            return toAlertView(row);
        }
        TransitAlertRow alert = repo.findAlertByKey(alertKey)
                .orElseThrow(() -> ApiException.notFound("过境预警不存在: " + alertKey));
        if (alert.status() == TransitAlertStatus.RESOLVED) {
            return toAlertView(alert);
        }
        Instant now = clock.instant();
        repo.resolveAlert(alertKey, cmd.requestId(), fingerprint, cmd.note(), now);
        return repo.findAlertByKey(alertKey).map(this::toAlertView).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<TransitAlertView> listTransitAlerts() {
        return repo.findAllAlerts().stream().map(this::toAlertView).toList();
    }

    // ---------- 轨迹查询（审计口径） ----------

    /**
     * 证书轨迹：汇总出证、启运、落地报告、流向登记、预警生成/办结与结案事件，按时间升序。
     */
    @Transactional(readOnly = true)
    public TrajectoryView getTrajectory(String certNo) {
        CertificateRow cert = repo.findCertByNo(certNo)
                .orElseThrow(() -> ApiException.notFound("证书不存在: " + certNo));
        List<TrajectoryEvent> events = new ArrayList<>();
        events.add(new TrajectoryEvent("ISSUED", "出证，证号 " + cert.certNo(), cert.issuedAt()));
        if (cert.departedAt() != null) {
            events.add(new TrajectoryEvent("DEPARTED", "启运", cert.departedAt()));
        }
        for (ArrivalRow arrival : repo.findArrivalsByCertNo(certNo)) {
            events.add(new TrajectoryEvent("ARRIVAL",
                    "落地报告 " + arrival.quantity() + " 头，结果 " + arrival.result(), arrival.createdAt()));
        }
        for (FlowRegistrationRow flow : repo.findFlowsByCertNo(certNo)) {
            events.add(new TrajectoryEvent("FLOW_REGISTERED",
                    "流向登记：" + flow.purpose(), flow.createdAt()));
        }
        for (TransitAlertRow alert : repo.findAlertsByCertNo(certNo)) {
            events.add(new TrajectoryEvent("ALERT_TRIGGERED",
                    "过境超时未核销预警 " + alert.alertKey(), alert.triggeredAt()));
            if (alert.resolvedAt() != null) {
                events.add(new TrajectoryEvent("ALERT_RESOLVED",
                        "预警办结：" + alert.resolveNote(), alert.resolvedAt()));
            }
        }
        if (cert.closedAt() != null) {
            events.add(new TrajectoryEvent("CLOSED", "结案", cert.closedAt()));
        }
        events.sort(Comparator.comparing(TrajectoryEvent::at));
        return new TrajectoryView(cert.certNo(), cert.transportType(), events);
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
                row.transportType(), row.transitHours(),
                row.routeRegions() == null ? null : List.of(row.routeRegions().split(",")),
                row.createdAt());
    }

    private CertificateView toCertificateView(CertificateRow row) {
        return new CertificateView(row.certNo(), row.issueRequestId(), row.declarationRequestId(),
                row.farmId(), row.destination(), row.species(), row.quantity(), row.earTagRange(),
                row.status(), row.quarantineObservation(), row.reportedQty(),
                row.transportType(), row.transitDeadlineAt(), row.enhancedMonitoring(),
                row.issuedAt(), row.expiresAt(), row.departedAt(), row.closedAt());
    }

    private LockdownView toLockdownView(LockdownRow row, int rejected, int voided, int quarantined) {
        return new LockdownView(row.requestId(), row.farmId(), row.reason(), row.status(),
                row.createdAt(), rejected, voided, quarantined);
    }

    private ArrivalView toArrivalView(ArrivalRow row, CertificateRow cert) {
        return new ArrivalView(row.requestId(), row.certNo(), row.quantity(), row.result(), row.note(),
                cert.status(), cert.quarantineObservation(), cert.reportedQty(),
                cert.enhancedMonitoring(), row.createdAt());
    }

    private FlowView toFlowView(FlowRegistrationRow row) {
        return new FlowView(row.requestId(), row.certNo(), row.purpose(), row.createdAt());
    }

    private TransitAlertView toAlertView(TransitAlertRow row) {
        return new TransitAlertView(row.alertKey(), row.certNo(), row.status(),
                row.triggeredAt(), row.resolvedAt(), row.resolveNote());
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
