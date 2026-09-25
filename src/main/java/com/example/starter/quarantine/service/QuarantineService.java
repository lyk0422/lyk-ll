package com.example.starter.quarantine.service;

import com.example.starter.quarantine.ArrivalResult;
import com.example.starter.quarantine.CertificateStatus;
import com.example.starter.quarantine.DeclarationStatus;
import com.example.starter.quarantine.Species;
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
import com.example.starter.quarantine.api.ApiException;
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

    private final QuarantineRepository repo;
    private final Clock clock;

    public QuarantineService(QuarantineRepository repo, Clock businessClock) {
        this.repo = repo;
        this.clock = businessClock;
    }

    // ---------- 检疫申报 ----------

    /**
     * 检疫申报。幂等；申报时养殖场已处于封锁区的，申报单直接落库为已驳回。
     */
    @Transactional
    public DeclarationView declare(DeclarationCmd cmd) {
        String fingerprint = fingerprint(cmd.requestId(), cmd.farmId(), cmd.destination(),
                cmd.species().name(), String.valueOf(cmd.quantity()), cmd.earTagRange());
        return repo.findDeclarationByRequestId(cmd.requestId())
                .map(existing -> replayDeclaration(existing, fingerprint))
                .orElseGet(() -> createDeclaration(cmd, fingerprint));
    }

    private DeclarationView createDeclaration(DeclarationCmd cmd, String fingerprint) {
        Instant now = clock.instant();
        repo.ensureFarmRegistry(cmd.farmId(), now);
        // 与封锁发布串行化：持锁期间封锁要么已生效（本单驳回），要么未生效（封锁随后会驳回本单）。
        repo.lockFarm(cmd.farmId());
        boolean locked = repo.findActiveLockdownByFarm(cmd.farmId()).isPresent();
        DeclarationStatus status = locked ? DeclarationStatus.REJECTED : DeclarationStatus.DECLARED;
        String rejectReason = locked ? LOCKDOWN_REJECT_REASON : null;
        try {
            repo.insertDeclaration(cmd.requestId(), fingerprint, cmd.farmId(), cmd.destination(),
                    cmd.species(), cmd.quantity(), cmd.earTagRange(), status, rejectReason, now);
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
        repo.markDeparted(cmd.certNo(), now, now);
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
        CertificateRow after = repo.findCertByNo(cmd.certNo()).orElseThrow();
        ArrivalRow row = repo.findArrivalByRequestId(cmd.requestId()).orElseThrow();
        return toArrivalView(row, after);
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
                row.quantity(), row.earTagRange(), row.status(), row.rejectReason(), row.createdAt());
    }

    private CertificateView toCertificateView(CertificateRow row) {
        return new CertificateView(row.certNo(), row.issueRequestId(), row.declarationRequestId(),
                row.farmId(), row.destination(), row.species(), row.quantity(), row.earTagRange(),
                row.status(), row.quarantineObservation(), row.reportedQty(),
                row.issuedAt(), row.expiresAt(), row.departedAt(), row.closedAt());
    }

    private LockdownView toLockdownView(LockdownRow row, int rejected, int voided, int quarantined) {
        return new LockdownView(row.requestId(), row.farmId(), row.reason(), row.status(),
                row.createdAt(), rejected, voided, quarantined);
    }

    private ArrivalView toArrivalView(ArrivalRow row, CertificateRow cert) {
        return new ArrivalView(row.requestId(), row.certNo(), row.quantity(), row.result(), row.note(),
                cert.status(), cert.quarantineObservation(), cert.reportedQty(), row.createdAt());
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
