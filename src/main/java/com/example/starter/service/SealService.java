package com.example.starter.service;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.domain.HeatingUnit;
import com.example.starter.domain.IdempotencyRecord;
import com.example.starter.domain.QuotaEvent;
import com.example.starter.domain.UnsealPermit;
import com.example.starter.domain.Valve;
import com.example.starter.domain.ValveSeal;
import com.example.starter.repository.AdjustmentTaskRepository;
import com.example.starter.repository.IdempotencyRepository;
import com.example.starter.repository.PermitRepository;
import com.example.starter.repository.QuotaEventRepository;
import com.example.starter.repository.SealRepository;
import com.example.starter.repository.StationRepository;
import com.example.starter.repository.UnitRepository;
import com.example.starter.repository.ValveRepository;
import com.example.starter.repository.ValveVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

/**
 * 平衡阀铅封、临时解封与复封闭环服务。
 *
 * <p>铅封登记唯一铅封号、当前开度版本与封存时刻；完整铅封下旧调节入口一律拒绝。
 * 解封单须两名不同授权人确认，固定 UTC 半开窗口与最多两次额度；受控调节在事务内
 * 重查解封未撤销、在窗内、额度足够及版本一致后，原子更新开度、写版本并消费一次额度。
 * 复封由两名不同人员核对版本并登记新铅封号，原子终结旧解封权限且保留封号链。
 * 新增写操作均携带 requestId/expectedVersion，业务、版本与幂等结果同事务提交；
 * 同键同参重放首次冻结结果，异参 409，失败不占键。</p>
 */
@Service
public class SealService {

    /** 24 小时窗口长度（沿用原调节规则）。 */
    public static final Duration ADJUSTMENT_WINDOW = Duration.ofHours(24);
    /** 单单元 24 小时内最多调节次数（沿用原调节规则，恰 3 次合法）。 */
    public static final int MAX_ADJUSTMENTS_PER_24H = 3;

    private final SealRepository sealRepository;
    private final PermitRepository permitRepository;
    private final QuotaEventRepository quotaEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ValveRepository valveRepository;
    private final ValveVersionRepository versionRepository;
    private final AdjustmentTaskRepository taskRepository;
    private final StationRepository stationRepository;
    private final UnitRepository unitRepository;
    private final BalanceService balanceService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public SealService(SealRepository sealRepository,
                       PermitRepository permitRepository,
                       QuotaEventRepository quotaEventRepository,
                       IdempotencyRepository idempotencyRepository,
                       ValveRepository valveRepository,
                       ValveVersionRepository versionRepository,
                       AdjustmentTaskRepository taskRepository,
                       StationRepository stationRepository,
                       UnitRepository unitRepository,
                       BalanceService balanceService,
                       ObjectMapper objectMapper,
                       Clock clock,
                       PlatformTransactionManager transactionManager) {
        this.sealRepository = sealRepository;
        this.permitRepository = permitRepository;
        this.quotaEventRepository = quotaEventRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.valveRepository = valveRepository;
        this.versionRepository = versionRepository;
        this.taskRepository = taskRepository;
        this.stationRepository = stationRepository;
        this.unitRepository = unitRepository;
        this.balanceService = balanceService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 铅封登记命令。
     *
     * @param requestId       客户端幂等键
     * @param unitId          目标单元 ID
     * @param sealCode        铅封号，全局唯一
     * @param expectedVersion 期望的当前阀门开度版本：无变更为 0，首次成功调节后为 1，逐次 +1
     */
    public record RegisterSealCommand(String requestId, long unitId, String sealCode,
                                      Integer expectedVersion) {
    }

    /**
     * 临时解封批准命令。
     *
     * @param requestId       客户端幂等键
     * @param sealCode        引用的铅封号
     * @param expectedVersion 铅封时引用的阀门开度版本，须与台账一致
     * @param approver1       第一名授权人
     * @param approver2       第二名授权人，须与第一名不同
     * @param windowStart     有效窗口起点（UTC，含）
     * @param windowEnd       有效窗口终点（UTC，不含；恰到终点失效）
     */
    public record ApproveUnsealCommand(String requestId, String sealCode, Integer expectedVersion,
                                       String approver1, String approver2,
                                       Instant windowStart, Instant windowEnd) {
    }

    /**
     * 解封撤销命令。
     *
     * @param requestId       客户端幂等键
     * @param permitId        解封单 ID
     * @param expectedVersion 期望的解封单版本
     * @param revokedBy       撤销操作授权人
     */
    public record RevokeUnsealCommand(String requestId, long permitId, Integer expectedVersion,
                                      String revokedBy) {
    }

    /**
     * 复封命令。
     *
     * @param requestId       客户端幂等键
     * @param oldSealCode     被终结的旧铅封号
     * @param expectedVersion 复封时核对的当前阀门开度版本
     * @param newSealCode     新铅封号，全局唯一
     * @param verifier1       第一名复封核对人员
     * @param verifier2       第二名复封核对人员，须与第一名不同
     */
    public record ResealCommand(String requestId, String oldSealCode, Integer expectedVersion,
                                String newSealCode, String verifier1, String verifier2) {
    }

    /**
     * 受控调节命令：在临时解封窗口与额度内对受控阀门下达调节令。
     *
     * @param requestId       客户端幂等键
     * @param stationId       目标换热站 ID
     * @param unitId          目标单元 ID
     * @param permitId        引用的临时解封单 ID
     * @param expectedVersion 期望的当前阀门开度版本，须与解封单最近核对版本一致
     * @param stepPercent     步进幅度，单位 %，0 &lt; step &le; 10
     */
    public record ControlledAdjustCommand(String requestId, long stationId, long unitId, long permitId,
                                          Integer expectedVersion, BigDecimal stepPercent) {
    }

    /**
     * 铅封登记：唯一铅封号、当前开度版本与封存时刻；登记后旧调节入口拒绝新调节令。
     */
    public ValveSeal registerSeal(RegisterSealCommand command) {
        return executeWithReplay(ValveSeal.class, () -> doRegisterSeal(command));
    }

    /**
     * 解封批准：两名不同授权人共同确认，固定 UTC 半开窗口与最多两次额度；批准不改变开度。
     */
    public UnsealPermit approveUnseal(ApproveUnsealCommand command) {
        return executeWithReplay(UnsealPermit.class, () -> doApproveUnseal(command));
    }

    /**
     * 撤销解封：仅生效单可撤销，版本须一致；撤销后禁止新调节，已发生调节不回拨。
     */
    public UnsealPermit revokeUnseal(RevokeUnsealCommand command) {
        return executeWithReplay(UnsealPermit.class, () -> doRevokeUnseal(command));
    }

    /**
     * 复封：两名不同人员核对当前开度版本并登记新铅封号，原子终结旧解封权限，保留历史封号链。
     */
    public ValveSeal reseal(ResealCommand command) {
        return executeWithReplay(ValveSeal.class, () -> doReseal(command));
    }

    /**
     * 受控调节：事务内重查解封未撤销、在半开窗内、额度足够及版本一致，
     * 再原子更新开度、写版本并消费一次额度；失败不消费，重复同键不消费两次。
     */
    public AdjustmentTask controlledAdjust(ControlledAdjustCommand command) {
        return executeWithReplay(AdjustmentTask.class, () -> doControlledAdjust(command));
    }

    private ValveSeal doRegisterSeal(RegisterSealCommand command) {
        requireText(command.requestId(), "requestId");
        requireText(command.sealCode(), "sealCode");
        requireVersion(command.expectedVersion(), "expectedVersion");
        String fingerprint = fingerprint(Operation.SEAL_REGISTER, command.requestId(), List.of(
                String.valueOf(command.unitId()), command.sealCode(),
                String.valueOf(command.expectedVersion())));
        replayIfRecorded(command.requestId(), fingerprint);
        HeatingUnit unit = unitRepository.findById(command.unitId())
                .orElseThrow(() -> new NotFoundException("单元不存在: unitId=" + command.unitId()));
        Valve valve = valveRepository.findByUnitId(command.unitId())
                .orElseThrow(() -> new NotFoundException("单元未配置阀门: unitId=" + command.unitId()));
        // 锁阀门行串行化同一阀门的登记/调节/复封竞争。
        valveRepository.lockById(valve.id());
        // 持锁后重查幂等键：并发同键在前序事务提交后按重放处理。
        replayIfRecorded(command.requestId(), fingerprint);
        if (sealRepository.findActiveByValveId(valve.id()).isPresent()) {
            throw new UnprocessableException("阀门已存在生效铅封，须先复封: valveId=" + valve.id());
        }
        int currentVersion = versionRepository.maxVersionNo(valve.id());
        if (currentVersion != command.expectedVersion()) {
            throw new UnprocessableException("铅封登记版本不一致: expected=" + command.expectedVersion()
                    + ", actual=" + currentVersion);
        }
        if (sealRepository.existsByCode(command.sealCode())) {
            throw new UnprocessableException("铅封号已登记: sealCode=" + command.sealCode());
        }
        Instant now = clock.instant();
        ValveSeal seal = new ValveSeal(0, valve.id(), command.sealCode(), currentVersion,
                ValveSeal.SealStatus.ACTIVE, now, null, now);
        long sealId;
        try {
            sealId = sealRepository.insert(seal);
        } catch (DuplicateKeyException e) {
            throw new UnprocessableException("铅封号已登记: sealCode=" + command.sealCode());
        }
        ValveSeal saved = new ValveSeal(sealId, valve.id(), command.sealCode(), currentVersion,
                ValveSeal.SealStatus.ACTIVE, now, null, now);
        saveIdempotency(command.requestId(), fingerprint, Operation.SEAL_REGISTER, saved, now);
        return saved;
    }

    private UnsealPermit doApproveUnseal(ApproveUnsealCommand command) {
        requireText(command.requestId(), "requestId");
        requireText(command.sealCode(), "sealCode");
        requireVersion(command.expectedVersion(), "expectedVersion");
        requireApprovers(command.approver1(), command.approver2());
        if (command.windowStart() == null || command.windowEnd() == null) {
            throw new BadRequestException("解封有效窗口起止时刻必填（UTC）");
        }
        if (!command.windowEnd().isAfter(command.windowStart())) {
            throw new BadRequestException("解封有效窗口终点必须晚于起点（半开区间 [start, end)）");
        }
        String fingerprint = fingerprint(Operation.UNSEAL_APPROVE, command.requestId(), List.of(
                command.sealCode(), String.valueOf(command.expectedVersion()),
                List.of(command.approver1(), command.approver2()),
                command.windowStart().toString(), command.windowEnd().toString()));
        replayIfRecorded(command.requestId(), fingerprint);
        // 锁铅封行串行化同一铅封的批准/撤销/复封竞争。
        ValveSeal seal = sealRepository.lockByCode(command.sealCode())
                .orElseThrow(() -> new NotFoundException("铅封号不存在: sealCode=" + command.sealCode()));
        replayIfRecorded(command.requestId(), fingerprint);
        if (seal.status() != ValveSeal.SealStatus.ACTIVE) {
            throw new UnprocessableException("铅封已复封，不可再批准解封: sealCode=" + command.sealCode());
        }
        if (seal.valveVersion() != command.expectedVersion()) {
            throw new UnprocessableException("解封引用阀门版本与铅封台账不一致: expected="
                    + command.expectedVersion() + ", sealed=" + seal.valveVersion());
        }
        if (permitRepository.findActiveBySealId(seal.id()).isPresent()) {
            throw new UnprocessableException("铅封下已存在生效解封单，须先撤销或复封: sealCode="
                    + command.sealCode());
        }
        Instant now = clock.instant();
        List<String> approvers = sortedApprovers(command.approver1(), command.approver2());
        UnsealPermit permit = new UnsealPermit(0, seal.id(), seal.valveId(),
                command.expectedVersion(), approvers, command.windowStart(), command.windowEnd(),
                UnsealPermit.MAX_QUOTA, 0, UnsealPermit.PermitStatus.ACTIVE, 1,
                null, null, now);
        long permitId = permitRepository.insert(permit);
        UnsealPermit saved = new UnsealPermit(permitId, seal.id(), seal.valveId(),
                command.expectedVersion(), approvers, command.windowStart(), command.windowEnd(),
                UnsealPermit.MAX_QUOTA, 0, UnsealPermit.PermitStatus.ACTIVE, 1,
                null, null, now);
        saveIdempotency(command.requestId(), fingerprint, Operation.UNSEAL_APPROVE, saved, now);
        return saved;
    }

    private UnsealPermit doRevokeUnseal(RevokeUnsealCommand command) {
        requireText(command.requestId(), "requestId");
        requireText(command.revokedBy(), "revokedBy");
        requireVersion(command.expectedVersion(), "expectedVersion");
        String fingerprint = fingerprint(Operation.UNSEAL_REVOKE, command.requestId(), List.of(
                String.valueOf(command.permitId()), String.valueOf(command.expectedVersion()),
                command.revokedBy()));
        replayIfRecorded(command.requestId(), fingerprint);
        UnsealPermit permit = permitRepository.lockById(command.permitId())
                .orElseThrow(() -> new NotFoundException("解封单不存在: permitId=" + command.permitId()));
        replayIfRecorded(command.requestId(), fingerprint);
        if (permit.status() != UnsealPermit.PermitStatus.ACTIVE) {
            throw new UnprocessableException("解封单非生效中，不可撤销: permitId=" + command.permitId()
                    + ", status=" + permit.status());
        }
        if (permit.version() != command.expectedVersion()) {
            throw new UnprocessableException("撤销版本不一致: expected=" + command.expectedVersion()
                    + ", actual=" + permit.version());
        }
        Instant now = clock.instant();
        int rows = permitRepository.revoke(command.permitId(), command.expectedVersion(),
                command.revokedBy(), now);
        if (rows != 1) {
            throw new ConflictException("解封单撤销冲突，可能已被并发处理: permitId=" + command.permitId());
        }
        UnsealPermit saved = permitRepository.findById(command.permitId()).orElseThrow();
        saveIdempotency(command.requestId(), fingerprint, Operation.UNSEAL_REVOKE, saved, now);
        return saved;
    }

    private ValveSeal doReseal(ResealCommand command) {
        requireText(command.requestId(), "requestId");
        requireText(command.oldSealCode(), "oldSealCode");
        requireText(command.newSealCode(), "newSealCode");
        requireVersion(command.expectedVersion(), "expectedVersion");
        requireApprovers(command.verifier1(), command.verifier2());
        if (command.oldSealCode().equals(command.newSealCode())) {
            throw new UnprocessableException("新铅封号不得与旧铅封号相同: sealCode=" + command.oldSealCode());
        }
        String fingerprint = fingerprint(Operation.RESEAL, command.requestId(), List.of(
                command.oldSealCode(), String.valueOf(command.expectedVersion()),
                command.newSealCode(), List.of(command.verifier1(), command.verifier2())));
        replayIfRecorded(command.requestId(), fingerprint);
        ValveSeal oldSeal = sealRepository.findByCode(command.oldSealCode())
                .orElseThrow(() -> new NotFoundException("旧铅封号不存在: sealCode=" + command.oldSealCode()));
        // 锁阀门行：与受控调节竞争，保证复封后不可能再追加调节版本。
        valveRepository.lockById(oldSeal.valveId());
        replayIfRecorded(command.requestId(), fingerprint);
        ValveSeal current = sealRepository.findByCode(command.oldSealCode()).orElseThrow();
        if (current.status() != ValveSeal.SealStatus.ACTIVE) {
            throw new UnprocessableException("铅封已复封，不可重复复封: sealCode=" + command.oldSealCode());
        }
        int currentVersion = versionRepository.maxVersionNo(current.valveId());
        if (currentVersion != command.expectedVersion()) {
            throw new UnprocessableException("复封核对版本不一致: expected=" + command.expectedVersion()
                    + ", actual=" + currentVersion);
        }
        if (sealRepository.existsByCode(command.newSealCode())) {
            throw new UnprocessableException("新铅封号已登记: sealCode=" + command.newSealCode());
        }
        Instant now = clock.instant();
        // 原子终结旧解封权限（全部生效单）与旧铅封，再登记新铅封，历史封号链保留。
        permitRepository.closeActiveBySealId(current.id(), now);
        int sealedRows = sealRepository.markResealed(current.id(), now);
        if (sealedRows != 1) {
            throw new ConflictException("铅封复封冲突，可能已被并发复封: sealCode=" + command.oldSealCode());
        }
        ValveSeal newSeal = new ValveSeal(0, current.valveId(), command.newSealCode(), currentVersion,
                ValveSeal.SealStatus.ACTIVE, now, null, now);
        long newSealId;
        try {
            newSealId = sealRepository.insert(newSeal);
        } catch (DuplicateKeyException e) {
            throw new UnprocessableException("新铅封号已登记: sealCode=" + command.newSealCode());
        }
        ValveSeal saved = new ValveSeal(newSealId, current.valveId(), command.newSealCode(),
                currentVersion, ValveSeal.SealStatus.ACTIVE, now, null, now);
        saveIdempotency(command.requestId(), fingerprint, Operation.RESEAL, saved, now);
        return saved;
    }

    private AdjustmentTask doControlledAdjust(ControlledAdjustCommand command) {
        requireText(command.requestId(), "requestId");
        requireVersion(command.expectedVersion(), "expectedVersion");
        if (command.stepPercent() == null || command.stepPercent().compareTo(BigDecimal.ZERO) <= 0
                || command.stepPercent().compareTo(AdjustmentService.MAX_STEP_PERCENT) > 0) {
            throw new UnprocessableException("单次步进幅度须为 (0, 10]%，当前: " + command.stepPercent());
        }
        String fingerprint = fingerprint(Operation.CONTROLLED_ADJUST, command.requestId(), List.of(
                String.valueOf(command.stationId()), String.valueOf(command.unitId()),
                String.valueOf(command.permitId()), String.valueOf(command.expectedVersion()),
                normalizeNumber(command.stepPercent())));
        replayIfRecorded(command.requestId(), fingerprint);
        // 锁顺序固定为换热站 → 阀门 → 解封单，与旧调节入口一致，避免死锁。
        stationRepository.lockById(command.stationId())
                .orElseThrow(() -> new NotFoundException("换热站不存在: stationId=" + command.stationId()));
        HeatingUnit unit = unitRepository.findById(command.unitId())
                .filter(u -> u.stationId() == command.stationId())
                .orElseThrow(() -> new NotFoundException(
                        "单元不存在或不属于该换热站: unitId=" + command.unitId()));
        Valve valve = valveRepository.findByUnitId(command.unitId())
                .orElseThrow(() -> new NotFoundException("单元未配置阀门: unitId=" + command.unitId()));
        valveRepository.lockById(valve.id());
        replayIfRecorded(command.requestId(), fingerprint);
        // 重查解封单并持行锁：未撤销/未终结、在窗内、额度足够、版本一致。
        UnsealPermit permit = permitRepository.lockById(command.permitId())
                .orElseThrow(() -> new NotFoundException("解封单不存在: permitId=" + command.permitId()));
        Instant now = clock.instant();
        validatePermitForAdjust(permit, valve.id(), command.expectedVersion(), now);
        // 受控调节仍遵守原 24 小时最多 3 次规则：第 4 次转人工复核，留任务证据但不改开度、不消费额度。
        int executed = taskRepository.countExecutedSince(command.unitId(), now.minus(ADJUSTMENT_WINDOW));
        if (executed >= MAX_ADJUSTMENTS_PER_24H) {
            AdjustmentTask review = new AdjustmentTask(0, command.stationId(), command.unitId(),
                    command.requestId(), fingerprint, null, null, null, null,
                    AdjustmentTask.TaskStatus.MANUAL_REVIEW, null, null, null, now);
            long reviewId = taskRepository.insert(review);
            AdjustmentTask saved = taskRepository.findById(reviewId).orElseThrow();
            saveIdempotency(command.requestId(), fingerprint, Operation.CONTROLLED_ADJUST, saved, now);
            return saved;
        }
        int currentVersion = versionRepository.maxVersionNo(valve.id());
        if (currentVersion != command.expectedVersion()) {
            throw new UnprocessableException("受控调节版本不一致: expected=" + command.expectedVersion()
                    + ", actual=" + currentVersion);
        }
        BalanceService.BalanceView balance = balanceService.evaluateBalance(command.stationId(), now);
        AdjustmentTask.ImbalanceType classification = balance.units().stream()
                .filter(u -> u.unitId() == unit.id())
                .map(BalanceService.UnitTemperature::classification)
                .findFirst()
                .orElseThrow(() -> new UnprocessableException(
                        "单元无温度数据或未失衡，无法判定调节方向: unitId=" + unit.id()));
        BigDecimal oldOpening = valve.openingPercent();
        BigDecimal newOpening = classification == AdjustmentTask.ImbalanceType.UNDERFLOW
                ? oldOpening.add(command.stepPercent()) : oldOpening.subtract(command.stepPercent());
        if (newOpening.compareTo(BigDecimal.ZERO) < 0
                || newOpening.compareTo(new BigDecimal("100")) > 0) {
            throw new UnprocessableException("调节后开度越界 [0,100]%: " + newOpening);
        }
        AdjustmentTask task = new AdjustmentTask(0, command.stationId(), command.unitId(),
                command.requestId(), fingerprint, classification, command.stepPercent(),
                oldOpening, newOpening, AdjustmentTask.TaskStatus.EXECUTING, now, null, null, now);
        long taskId = taskRepository.insert(task);
        // 原子消费额度 + 条件更新开度（版本一致）+ 写版本链 + 额度轨迹，任一失败整体回滚，失败不消费。
        int consumed = permitRepository.consumeQuota(permit.id(), permit.version(),
                currentVersion + 1, now);
        if (consumed != 1) {
            throw new ConflictException("解封额度消费冲突，本次调节失败且不消费额度: permitId=" + permit.id());
        }
        int updated = valveRepository.updateOpeningIfVersion(valve.id(), oldOpening, newOpening,
                currentVersion, now);
        if (updated != 1) {
            throw new ConflictException("阀门开度版本已变化，本次调节失败且不消费额度: valveId=" + valve.id());
        }
        versionRepository.insert(valve.id(), taskId, currentVersion + 1, oldOpening, newOpening, now);
        UnsealPermit after = permitRepository.findById(permit.id()).orElseThrow();
        quotaEventRepository.insert(permit.id(), taskId, currentVersion + 1, after.quotaUsed(), now);
        AdjustmentTask saved = taskRepository.findById(taskId).orElseThrow();
        saveIdempotency(command.requestId(), fingerprint, Operation.CONTROLLED_ADJUST, saved, now);
        return saved;
    }

    /**
     * 旧调节入口的铅封校验：受控阀门存在生效铅封时一律拒绝，调节必须引用有效解封单走受控入口；
     * 不存在台账的旧阀门不受影响，仍按原规则工作。
     */
    @Transactional(readOnly = true)
    public void assertAdjustable(long valveId) {
        if (sealRepository.findActiveByValveId(valveId).isPresent()) {
            throw new UnprocessableException("阀门处于完整铅封状态，普通调节令拒绝；请持有效临时解封单走受控调节: valveId="
                    + valveId);
        }
    }

    /**
     * 按铅封号查询台账。
     */
    @Transactional(readOnly = true)
    public ValveSeal getSeal(String sealCode) {
        return sealRepository.findByCode(sealCode)
                .orElseThrow(() -> new NotFoundException("铅封号不存在: sealCode=" + sealCode));
    }

    /**
     * 按单元查询完整封号链（含历史封号）。
     */
    @Transactional(readOnly = true)
    public List<ValveSeal> getSealChain(long unitId) {
        Valve valve = valveRepository.findByUnitId(unitId)
                .orElseThrow(() -> new NotFoundException("单元未配置阀门: unitId=" + unitId));
        return sealRepository.findChainByValveId(valve.id());
    }

    /**
     * 查询解封单。
     */
    @Transactional(readOnly = true)
    public UnsealPermit getPermit(long permitId) {
        return permitRepository.findById(permitId)
                .orElseThrow(() -> new NotFoundException("解封单不存在: permitId=" + permitId));
    }

    /**
     * 查询解封单额度消费轨迹。
     */
    @Transactional(readOnly = true)
    public List<QuotaEvent> getQuotaEvents(long permitId) {
        if (permitRepository.findById(permitId).isEmpty()) {
            throw new NotFoundException("解封单不存在: permitId=" + permitId);
        }
        return quotaEventRepository.findByPermitId(permitId);
    }

    private void validatePermitForAdjust(UnsealPermit permit, long valveId, int expectedVersion, Instant now) {
        if (permit.valveId() != valveId) {
            throw new UnprocessableException("解封单与目标阀门不匹配: permitId=" + permit.id());
        }
        if (permit.status() != UnsealPermit.PermitStatus.ACTIVE) {
            throw new UnprocessableException("解封单已" + permit.status() + "，禁止新调节: permitId=" + permit.id());
        }
        if (now.isBefore(permit.windowStart())) {
            throw new UnprocessableException("解封窗口尚未生效: windowStart=" + permit.windowStart());
        }
        if (!now.isBefore(permit.windowEnd())) {
            throw new UnprocessableException("解封窗口已失效（半开区间，恰到终点失效）: windowEnd="
                    + permit.windowEnd());
        }
        if (permit.quotaUsed() >= permit.quotaTotal()) {
            throw new UnprocessableException("解封调节额度已用尽: used=" + permit.quotaUsed()
                    + ", total=" + permit.quotaTotal());
        }
        if (permit.valveVersion() != expectedVersion) {
            throw new UnprocessableException("解封单核对阀门版本与请求不一致: expected=" + expectedVersion
                    + ", permit=" + permit.valveVersion());
        }
    }

    /**
     * 在事务内执行业务；同键重放时业务异常触发回滚（本事务写入全部撤销），
     * 事务外捕获后返回首次冻结结果快照。
     */
    private <T> T executeWithReplay(Class<T> type, Supplier<T> action) {
        try {
            return txTemplate.execute(status -> action.get());
        } catch (IdempotentReplayException e) {
            try {
                return objectMapper.readValue(e.snapshot(), type);
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new IllegalStateException("幂等结果快照反序列化失败", ex);
            }
        }
    }

    /**
     * 已存在幂等记录时：同参抛重放异常（回滚后由入口返回冻结快照），异参抛 409。
     */
    private void replayIfRecorded(String requestId, String fingerprint) {
        idempotencyRepository.findByRequestId(requestId).ifPresent(record -> {
            if (!record.fingerprint().equals(fingerprint)) {
                throw new ConflictException("同键异参，返回 409: requestId=" + record.requestId());
            }
            throw new IdempotentReplayException(record.resultSnapshot());
        });
    }

    private void saveIdempotency(String requestId, String fingerprint, Operation operation,
                                 Object result, Instant now) {
        final String snapshot;
        try {
            snapshot = objectMapper.writeValueAsString(result);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("幂等结果快照序列化失败", e);
        }
        try {
            idempotencyRepository.insert(new IdempotencyRecord(
                    requestId, fingerprint, operation.name(), snapshot, now));
        } catch (DuplicateKeyException e) {
            // 同键并发：首个事务已提交，本事务业务写入随回滚撤销（不重复生效），按重放/异参处理。
            IdempotencyRecord record = idempotencyRepository.findByRequestId(requestId)
                    .orElseThrow(() -> e);
            if (!record.fingerprint().equals(fingerprint)) {
                throw new ConflictException("同键异参，返回 409: requestId=" + record.requestId());
            }
            throw new IdempotentReplayException(record.resultSnapshot());
        }
    }

    /**
     * 同键同参重放信号：携带首次冻结结果快照；触发当前事务回滚，由入口在事务外返回快照。
     */
    private static final class IdempotentReplayException extends RuntimeException {
        private final String snapshot;

        private IdempotentReplayException(String snapshot) {
            super(null, null, false, false);
            this.snapshot = snapshot;
        }

        private String snapshot() {
            return snapshot;
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " 不能为空");
        }
    }

    private void requireVersion(Integer version, String field) {
        if (version == null) {
            throw new BadRequestException(field + " 不能为空");
        }
        if (version < 0) {
            throw new BadRequestException(field + " 不能为负数");
        }
    }

    private void requireApprovers(String a, String b) {
        requireText(a, "授权人");
        requireText(b, "授权人");
        if (a.equals(b)) {
            throw new BadRequestException("两名授权人须为不同人员");
        }
    }

    private List<String> sortedApprovers(String a, String b) {
        return a.compareTo(b) <= 0 ? List.of(a, b) : List.of(b, a);
    }

    private static String normalizeNumber(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /**
     * 幂等操作类型。
     */
    public enum Operation {
        SEAL_REGISTER, UNSEAL_APPROVE, UNSEAL_REVOKE, RESEAL, CONTROLLED_ADJUST
    }

    /**
     * 结构化无歧义指纹：每个字段以“长度:内容”编码，集合带元素个数前缀，
     * 合法文本即使含分隔符 '|' 也不会与字段边界混淆；最后对整体求 SHA-256。
     */
    static String fingerprint(Operation operation, String requestId, List<Object> parts) {
        StringBuilder sb = new StringBuilder();
        appendField(sb, operation.name());
        sb.append('|');
        appendField(sb, requestId);
        for (Object part : parts) {
            sb.append('|');
            appendObject(sb, part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void appendObject(StringBuilder sb, Object value) {
        if (value == null) {
            appendField(sb, "null");
        } else if (value instanceof java.util.Collection<?> collection) {
            sb.append('[').append(collection.size()).append(']');
            for (Object element : collection) {
                appendObject(sb, element);
            }
        } else {
            appendField(sb, String.valueOf(value));
        }
    }

    private static void appendField(StringBuilder sb, String value) {
        sb.append(value.length()).append(':').append(value);
    }
}
