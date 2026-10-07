package com.example.starter.rolling.service;

import com.example.starter.rolling.api.dto.RepairApplyRequest;
import com.example.starter.rolling.api.dto.RepairApplyView;
import com.example.starter.rolling.api.dto.RepairInspectionRequest;
import com.example.starter.rolling.api.dto.RepairInspectionView;
import com.example.starter.rolling.api.dto.RollGenerationChainView;
import com.example.starter.rolling.api.dto.RollGenerationChainView.GenerationLedgerView;
import com.example.starter.rolling.repo.IdAllocator;
import com.example.starter.rolling.repo.RollLifeRepository;
import com.example.starter.rolling.repo.RollLifeRepository.IdempotentRow;
import com.example.starter.rolling.repo.RollLifeRepository.PhysicalRollRow;
import com.example.starter.rolling.repo.RollLifeRepository.RollRow;
import com.example.starter.rolling.repo.RollRepairRepository;
import com.example.starter.rolling.repo.RollRepairRepository.InspectionRow;
import com.example.starter.rolling.repo.RollRepairRepository.RepairRequestRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 到期轧辊返修放行领域服务。
 *
 * <p>核心规则：</p>
 * <ul>
 *   <li>只有“达到寿命上限（BANNED）且已冻结”的物理辊当前代次能申请返修；
 *       每个旧代次最多一个成功后继，同一物理辊累计最多返修两次；</li>
 *   <li>磨后直径保留两位小数，不少于 500 毫米且不得比上一代大（首代未采集直径，无上代约束）；
 *       新周期上限为正且不超过上一代上限；新辊号不得占用已有号；</li>
 *   <li>返修经两名不同检验员确认合格才在同一事务内原子建立下一代：新辊累计里程从 0 起，
 *       另立独立台账，继续沿用原寿命累加、前 5 卷烫辊与到期禁排入口；
 *       任一检验不合格即终止（REJECTED）且不建新辊，终止后可另提新申请；</li>
 *   <li>并发双建由物理辊聚合乐观版本（expectedVersion，成功加 1，旧版本 409）
 *       与“旧代次成功后继”唯一索引共同裁决，并发放行只产生一代；</li>
 *   <li>检验、代次建立与幂等结果同事务；requestId 指纹覆盖完整参数，
 *       同键同参重放首次响应，同键异参 409，失败不占键；</li>
 *   <li>建代后回传到旧辊号的迟到实绩只落旧代迟到证据，不改旧代冻结结论、不影响新代台账。</li>
 * </ul>
 */
@Service
public class RollRepairService {

    /** 同一物理辊累计最多返修次数。 */
    public static final int MAX_REPAIR_COUNT = 2;

    /** 放行所需不同检验员合格确认人数。 */
    public static final int REQUIRED_APPROVALS = 2;

    /** 磨后直径下限（毫米）。 */
    public static final BigDecimal MIN_DIAMETER_MM = new BigDecimal("500.00");

    private static final String BIZ_APPLY = "ROLL_REPAIR_APPLY";
    private static final String BIZ_INSPECT = "ROLL_REPAIR_INSPECTION";

    private static final String STATUS_IN_SERVICE = "IN_SERVICE";
    private static final String STATUS_BANNED = "BANNED";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";

    private final RollLifeRepository lifeRepo;
    private final RollRepairRepository repairRepo;
    private final IdAllocator idAllocator;
    private final Clock clock;

    public RollRepairService(RollLifeRepository lifeRepo, RollRepairRepository repairRepo,
                             IdAllocator idAllocator, Clock clock) {
        this.lifeRepo = lifeRepo;
        this.repairRepo = repairRepo;
        this.idAllocator = idAllocator;
        this.clock = clock;
    }

    // ---------------- 返修申请 ----------------

    @Transactional
    public RepairApplyView apply(RepairApplyRequest request) {
        String fp = FingerprintUtil.fingerprint(request);
        Object replay = replayIfSame(request.requestId(), BIZ_APPLY, fp);
        if (replay != null) {
            return (RepairApplyView) replay;
        }

        RollRow old = requireRoll(request.oldRollNo());
        PhysicalRollRow physical = requirePhysical(lifeRepo.findPhysical(old.physicalId()));
        LocalDateTime now = LocalDateTime.now(clock);

        // 提交版本必须仍是聚合当前版本；旧版本 409，不占幂等键。
        if (!request.expectedVersion().equals(physical.version())) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "物理辊 " + physical.physicalId() + " 聚合版本已过期：提交基于版本 "
                            + request.expectedVersion() + "，当前版本 " + physical.version());
        }
        // 每个旧代次最多一个成功后继：已被后继替代的旧代次不能再建（409）；终止申请不受此限。
        if (repairRepo.findSuccessorRequest(old.rollNo()) != null) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "旧代次 " + old.rollNo() + " 已存在成功后继，不能再建立新一代");
        }
        // 只有物理辊当前代次能申请，跨代次/跨物理辊引用拒绝。
        if (!physical.currentRollNo().equals(old.rollNo())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "轧辊 " + old.rollNo() + " 不是物理辊 " + physical.physicalId()
                            + " 的当前代次（当前为 " + physical.currentRollNo() + "），不能申请返修");
        }
        // 达到寿命上限且已冻结。
        if (!STATUS_BANNED.equals(old.status())
                || old.totalKm().compareTo(old.cycleLimitKm()) < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "轧辊 " + old.rollNo() + " 未达到寿命上限（状态 " + old.status()
                            + "，累计里程 " + old.totalKm().stripTrailingZeros().toPlainString()
                            + " 公里/上限 " + old.cycleLimitKm().stripTrailingZeros().toPlainString()
                            + " 公里），不能申请返修");
        }
        if (old.frozenAt() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "轧辊 " + old.rollNo() + " 尚未日冻结，不能申请返修");
        }
        // 同一物理辊累计最多返修两次。
        if (physical.repairCount() >= MAX_REPAIR_COUNT) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "物理辊 " + physical.physicalId() + " 已累计返修 " + physical.repairCount()
                            + " 次，达到同一物理辊最多返修两次的上限");
        }

        // 直径保留两位小数：不少于 500 毫米，且不得比上一代大（首代未采集直径，无上代约束）。
        BigDecimal diameter = request.groundDiameterMm().setScale(2, RoundingMode.HALF_UP);
        if (diameter.compareTo(MIN_DIAMETER_MM) < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "磨后直径 " + diameter.toPlainString() + " 毫米少于 500 毫米下限");
        }
        if (old.diameterMm() != null && diameter.compareTo(old.diameterMm()) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "磨后直径 " + diameter.toPlainString() + " 毫米不得比上一代直径 "
                            + old.diameterMm().toPlainString() + " 毫米大");
        }
        // 新周期上限为正且不超过上一代上限。
        if (request.newCycleLimitKm().compareTo(old.cycleLimitKm()) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "新周期上限 " + request.newCycleLimitKm().stripTrailingZeros().toPlainString()
                            + " 公里不得超过上一代上限 "
                            + old.cycleLimitKm().stripTrailingZeros().toPlainString() + " 公里");
        }
        // 新辊号不得占用已有号（含旧辊号自身）。
        if (lifeRepo.findRoll(request.newRollNo()) != null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "新辊号 " + request.newRollNo() + " 已被占用，不得作为返修新代辊号");
        }

        long applicationId = idAllocator.nextId();
        RepairRequestRow row = new RepairRequestRow(applicationId, physical.physicalId(),
                old.rollNo(), request.newRollNo(), diameter, request.newCycleLimitKm(),
                request.inspectionCert(), request.expectedVersion(), STATUS_PENDING, null,
                request.requestId(), fp, now, now);
        repairRepo.insertRepairRequest(row);

        RepairApplyView view = buildApplyView(row, old.generationNo(), physical.version(), 0);
        saveIdempotent(request.requestId(), BIZ_APPLY, fp, FingerprintUtil.toJson(view));
        return view;
    }

    // ---------------- 两名检验员放行 ----------------

    @Transactional
    public RepairInspectionView inspect(long applicationId, RepairInspectionRequest request) {
        String fp = FingerprintUtil.fingerprint(new InspectionFingerprint(request.requestId(),
                applicationId, request.inspectorId(), request.approved(), request.certNo()));
        Object replay = replayIfSame(request.requestId(), BIZ_INSPECT, fp);
        if (replay != null) {
            return (RepairInspectionView) replay;
        }

        // 行级加锁：同一申请的并发检验确认串行化，保证“满两人”只裁决一次。
        RepairRequestRow pr = repairRepo.findRepairRequestForUpdate(applicationId);
        if (pr == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "返修申请不存在: " + applicationId);
        }
        LocalDateTime now = LocalDateTime.now(clock);

        if (!STATUS_PENDING.equals(pr.status())) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "返修申请 " + applicationId + " 已"
                            + (STATUS_APPROVED.equals(pr.status()) ? "放行建立新一代" : "终止")
                            + "，不接受新的检验确认");
        }

        // 两名检验员必须不同：先给出清晰业务错误，唯一索引 (申请,检验员) 再做并发兜底。
        boolean sameInspector = repairRepo.findInspections(applicationId).stream()
                .anyMatch(i -> i.inspectorId().equals(request.inspectorId()));
        if (sameInspector) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "返修申请 " + applicationId + " 已由检验员 " + request.inspectorId()
                            + " 确认，放行须两名不同检验员");
        }

        long inspectionId = idAllocator.nextId();
        boolean inserted = repairRepo.tryInsertInspection(new InspectionRow(inspectionId,
                applicationId, request.inspectorId(), request.approved(), request.certNo(), now, now));
        if (!inserted) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "返修申请 " + applicationId + " 已由检验员 " + request.inspectorId()
                            + " 确认，放行须两名不同检验员");
        }

        String status;
        String successorRollNo = null;
        if (!request.approved()) {
            // 任一不合格即终止，不建新辊；终止后可就同一旧代次另提新申请。
            repairRepo.updateStatus(applicationId, STATUS_REJECTED, null, now);
            status = STATUS_REJECTED;
        } else if (repairRepo.countApprovals(applicationId) >= REQUIRED_APPROVALS) {
            // 两名不同检验员均合格：同事务原子建立下一代。
            successorRollNo = approveAndCreateGeneration(pr, now);
            status = STATUS_APPROVED;
        } else {
            status = STATUS_PENDING;
        }

        PhysicalRollRow physical = requirePhysical(lifeRepo.findPhysical(pr.physicalId()));
        RepairInspectionView view = new RepairInspectionView(applicationId, request.requestId(),
                pr.physicalId(), status, request.inspectorId(), request.approved(),
                repairRepo.countApprovals(applicationId), successorRollNo, physical.version(), now);
        saveIdempotent(request.requestId(), BIZ_INSPECT, fp, FingerprintUtil.toJson(view));
        return view;
    }

    /**
     * 两人合格后的原子放行动作：重新校验全部前置条件，聚合乐观版本条件推进，
     * 建立里程从 0 起的下一代独立台账，回填成功后继；任一步冲突均整事务回滚，不产生新一代。
     */
    private String approveAndCreateGeneration(RepairRequestRow pr, LocalDateTime now) {
        RollRow old = requireRoll(pr.oldRollNo());
        PhysicalRollRow physical = requirePhysical(lifeRepo.findPhysical(pr.physicalId()));

        if (!physical.currentRollNo().equals(old.rollNo())) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "旧代次 " + old.rollNo() + " 已不是物理辊当前代次（当前 "
                            + physical.currentRollNo() + "），并发返修只允许建立一代");
        }
        if (!STATUS_BANNED.equals(old.status()) || old.frozenAt() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "旧代次 " + old.rollNo() + " 不满足到期禁排且已冻结的放行前提");
        }
        if (physical.repairCount() >= MAX_REPAIR_COUNT) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "物理辊 " + physical.physicalId() + " 已达累计返修两次上限");
        }
        if (lifeRepo.findRoll(pr.newRollNo()) != null) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "新辊号 " + pr.newRollNo() + " 已被并发占用");
        }

        int newGenerationNo = old.generationNo() + 1;

        // 先建立下一代独立台账（外键指向已存在的物理辊）：累计里程从 0 起，
        // 上机时刻为放行时刻（UTC），前 5 卷重新进入烫辊位。
        try {
            lifeRepo.insertRoll(new RollRow(pr.newRollNo(), physical.physicalId(), newGenerationNo,
                    STATUS_IN_SERVICE, BigDecimal.ZERO, pr.newCycleLimitKm(),
                    pr.groundDiameterMm(), 0, now, null, now, now));
        } catch (DuplicateKeyException e) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "新辊号 " + pr.newRollNo() + " 已被并发占用，放行终止");
        }

        // 再做聚合乐观版本条件推进：数据库裁决并发双建，affected=0 即版本过期（409），
        // 本事务连同新台账一并回滚，并发放行只产生一代。
        int affected = lifeRepo.advancePhysicalOnApproval(physical.physicalId(), pr.newRollNo(),
                newGenerationNo, physical.repairCount() + 1, pr.expectedVersion(), now);
        if (affected == 0) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "物理辊 " + physical.physicalId() + " 聚合版本 " + pr.expectedVersion()
                            + " 已过期，并发返修只允许产生一代");
        }

        // 回填成功后继：唯一索引裁决每个旧代次最多一个成功后继，并发双建第二个在此整单回滚。
        try {
            repairRepo.updateStatus(pr.id(), STATUS_APPROVED, pr.newRollNo(), now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "旧代次 " + pr.oldRollNo() + " 已有成功后继，并发放行只产生一代");
        }
        return pr.newRollNo();
    }

    // ---------------- 查询：代次链与各代独立台账 ----------------

    @Transactional(readOnly = true)
    public RepairApplyView getApplication(long applicationId) {
        RepairRequestRow row = repairRepo.findRepairRequest(applicationId);
        if (row == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "返修申请不存在: " + applicationId);
        }
        PhysicalRollRow physical = requirePhysical(lifeRepo.findPhysical(row.physicalId()));
        int oldGenerationNo = requireRoll(row.oldRollNo()).generationNo();
        return buildApplyView(row, oldGenerationNo, physical.version(),
                repairRepo.countApprovals(applicationId));
    }

    @Transactional(readOnly = true)
    public RollGenerationChainView getChainByRollNo(String rollNo) {
        PhysicalRollRow physical = lifeRepo.findPhysicalByRollNo(rollNo);
        if (physical == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND,
                    "轧辊不存在或未登记合成物理辊编号: " + rollNo);
        }
        List<GenerationLedgerView> generations = new ArrayList<>();
        for (RollRow g : lifeRepo.findGenerations(physical.physicalId())) {
            generations.add(new GenerationLedgerView(g.generationNo(), g.rollNo(), g.status(),
                    g.totalKm(), g.cycleLimitKm(),
                    g.cycleLimitKm().subtract(g.totalKm()), g.diameterMm(), g.coilsSinceMount(),
                    g.mountedAt(), g.frozenAt(), lifeRepo.countRepairLateEvidence(g.rollNo())));
        }
        return new RollGenerationChainView(physical.physicalId(), physical.currentRollNo(),
                physical.currentGenerationNo(), physical.repairCount(), physical.version(),
                generations);
    }

    // ---------------- 内部方法 ----------------

    private RollRow requireRoll(String rollNo) {
        RollRow roll = lifeRepo.findRoll(rollNo);
        if (roll == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "轧辊不存在: " + rollNo);
        }
        return roll;
    }

    private PhysicalRollRow requirePhysical(PhysicalRollRow physical) {
        if (physical == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "合成物理辊聚合不存在");
        }
        return physical;
    }

    private RepairApplyView buildApplyView(RepairRequestRow row, int oldGenerationNo,
                                           int aggregateVersion, int approvals) {
        return new RepairApplyView(row.id(), row.requestId(), row.physicalId(), row.oldRollNo(),
                oldGenerationNo, row.newRollNo(), row.groundDiameterMm(), row.newCycleLimitKm(),
                row.inspectionCert(), row.expectedVersion(), aggregateVersion, row.status(),
                approvals, row.successorRollNo(), row.createdAt());
    }

    /**
     * 同键重放：指纹一致返回首次响应；同键异参抛 409 且不产生任何写入。
     */
    private Object replayIfSame(String requestId, String bizType, String fingerprint) {
        IdempotentRow row = lifeRepo.findIdempotent(requestId);
        if (row == null) {
            return null;
        }
        if (!row.fingerprint().equals(fingerprint) || !row.bizType().equals(bizType)) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "requestId " + requestId + " 已用于不同业务参数的请求，拒绝执行且不产生任何写入");
        }
        Class<?> type = BIZ_INSPECT.equals(bizType) ? RepairInspectionView.class : RepairApplyView.class;
        return FingerprintUtil.fromJson(row.responseBody(), type);
    }

    private void saveIdempotent(String requestId, String bizType, String fingerprint, String body) {
        boolean inserted = lifeRepo.tryInsertIdempotent(requestId, bizType, fingerprint, 200,
                body, LocalDateTime.now(clock));
        if (!inserted) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "requestId " + requestId + " 正在被并发处理，唯一记录已存在，请用相同参数重放");
        }
    }

    /** 检验指纹载体：覆盖 requestId、申请 id 与全部检验字段。 */
    private record InspectionFingerprint(String requestId, long applicationId, String inspectorId,
                                         boolean approved, String certNo) {
    }
}
