package com.example.starter.rolling.service;

import com.example.starter.rolling.api.dto.PhysicalRollRegisterRequest;
import com.example.starter.rolling.api.dto.PhysicalRollView;
import com.example.starter.rolling.api.dto.RollLineageView;
import com.example.starter.rolling.api.dto.RollGenerationView;
import com.example.starter.rolling.api.dto.RollView;
import com.example.starter.rolling.api.dto.ReworkApplicationRequest;
import com.example.starter.rolling.api.dto.ReworkApplicationView;
import com.example.starter.rolling.api.dto.ReworkInspectionRequest;
import com.example.starter.rolling.repo.IdAllocator;
import com.example.starter.rolling.repo.RollLifeRepository.IdempotentRow;
import com.example.starter.rolling.repo.RollLifeRepository.RollRow;
import com.example.starter.rolling.repo.ReworkRepository;
import com.example.starter.rolling.repo.ReworkRepository.PhysicalRollRow;
import com.example.starter.rolling.repo.ReworkRepository.ReworkApplicationRow;
import com.example.starter.rolling.repo.ReworkRepository.RollGenerationRow;
import com.example.starter.rolling.repo.RollLifeRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 到期轧辊返修放行领域服务：合成物理辊登记、返修申请、两名检验员放行、代次链与独立台账查询。
 *
 * <p>核心规则：</p>
 * <ul>
 *   <li>仅当前代次达到寿命上限且已冻结才能申请返修；每个旧代次最多一个成功后继，
 *       同一物理辊累计最多返修两次；</li>
 *   <li>磨后直径保留两位小数、不少于 500 毫米且不大于上一代（首代无上一代直径时只校下界）；
 *       新上限为正且不超过上一代上限；新辊号不得占用已有号，跨物理辊引用拒绝；</li>
 *   <li>两名不同检验员均确认合格才原子建立下一代，新辊累计里程从 0 起，沿用寿命累加、
 *       烫辊与到期禁排入口；任一不合格即终止且不建新辊；终止可另提新申请，成功后继不能再建；</li>
 *   <li>返修聚合版本从 1 起，修改带 expectedVersion，成功 +1、旧版本 409；并发放行只产生一代；</li>
 *   <li>写入按 requestId 对完整参数作结构化指纹：同键同参重放首次响应、异参 409、失败不占键；
 *       检验、代次与幂等结果同事务；时刻均按 UTC。</li>
 * </ul>
 */
@Service
public class ReworkService {

    /** 同一物理辊累计最多返修次数。 */
    public static final int MAX_REWORK_COUNT = 2;
    /** 磨后直径下界（毫米）。 */
    public static final BigDecimal MIN_DIAMETER_MM = new BigDecimal("500.00");

    private static final String BIZ_REGISTER = "PHYSICAL_ROLL_REGISTER";
    private static final String BIZ_REWORK_APPLY = "REWORK_APPLY";
    private static final String BIZ_REWORK_INSPECT = "REWORK_INSPECT";

    private static final String STATUS_INSPECTING = "INSPECTING";
    private static final String STATUS_RELEASED = "RELEASED";
    private static final String STATUS_TERMINATED = "TERMINATED";
    private static final String STATUS_BANNED = "BANNED";

    private final ReworkRepository reworkRepo;
    private final RollLifeRepository lifeRepo;
    private final IdAllocator idAllocator;
    private final Clock clock;

    public ReworkService(ReworkRepository reworkRepo, RollLifeRepository lifeRepo,
                         IdAllocator idAllocator, Clock clock) {
        this.reworkRepo = reworkRepo;
        this.lifeRepo = lifeRepo;
        this.idAllocator = idAllocator;
        this.clock = clock.withZone(ZoneOffset.UTC);
    }

    // ---------------- 合成物理辊登记 ----------------

    @Transactional
    public PhysicalRollView registerPhysicalRoll(PhysicalRollRegisterRequest request) {
        String fp = FingerprintUtil.fingerprint(request);
        PhysicalRollView replay = replayIfSame(request.requestId(), BIZ_REGISTER, fp,
                PhysicalRollView.class);
        if (replay != null) {
            return replay;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (reworkRepo.findPhysicalRoll(request.physicalNo()) != null) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "物理辊编号 " + request.physicalNo() + " 已登记，不得重复登记");
        }
        RollRow roll = lifeRepo.findRoll(request.rollNo());
        if (roll == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND,
                    "首代绑定的辊号 " + request.rollNo() + " 尚未在寿命台账登记，请先申请上机");
        }
        RollGenerationRow bound = reworkRepo.findGenerationByRollNo(request.rollNo());
        if (bound != null) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "辊号 " + request.rollNo() + " 已绑定物理辊 " + bound.physicalNo()
                            + " 第 " + bound.generationNo() + " 代，不得重复绑定");
        }

        long generationId = idAllocator.nextId();
        try {
            reworkRepo.insertPhysicalRoll(new PhysicalRollRow(request.physicalNo(),
                    request.rollNo(), 1, 0, 1, now, now));
            reworkRepo.insertGeneration(new RollGenerationRow(generationId, request.physicalNo(),
                    1, request.rollNo(), null, null, now));
        } catch (DuplicateKeyException e) {
            // 并发登记同一物理辊编号或同一辊号：唯一索引裁决，本次失败不占幂等键。
            throw new ConflictException(HttpStatus.CONFLICT,
                    "物理辊 " + request.physicalNo() + " 或辊号 " + request.rollNo()
                            + " 正被并发登记，唯一约束已存在，请查询后重试");
        }

        PhysicalRollView view = new PhysicalRollView(request.physicalNo(), request.rollNo(), 1, 0, 1);
        saveIdempotent(request.requestId(), BIZ_REGISTER, fp, FingerprintUtil.toJson(view));
        return view;
    }

    // ---------------- 返修申请 ----------------

    @Transactional
    public ReworkApplicationView apply(ReworkApplicationRequest request) {
        String fp = FingerprintUtil.fingerprint(request);
        ReworkApplicationView replay = replayIfSame(request.requestId(), BIZ_REWORK_APPLY, fp,
                ReworkApplicationView.class);
        if (replay != null) {
            return replay;
        }

        LocalDateTime now = LocalDateTime.now(clock);

        PhysicalRollRow physical = reworkRepo.findPhysicalRoll(request.physicalNo());
        if (physical == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND,
                    "物理辊 " + request.physicalNo() + " 未登记，请先登记合成物理辊");
        }

        // 旧辊号归属校验：必须是本物理辊的当前代次，跨物理辊引用一律拒绝。
        RollGenerationRow oldGeneration = reworkRepo.findGenerationByRollNo(request.oldRollNo());
        if (oldGeneration == null || !oldGeneration.physicalNo().equals(request.physicalNo())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "旧辊号 " + request.oldRollNo() + " 不属于物理辊 " + request.physicalNo()
                            + "，跨物理辊引用拒绝");
        }
        if (oldGeneration.successorApplicationId() != null) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "旧代次 " + request.oldRollNo() + " 已有成功后继（申请 "
                            + oldGeneration.successorApplicationId() + "），成功后继不能再建");
        }
        if (oldGeneration.generationNo() != physical.headGenerationNo()
                || !physical.headRollNo().equals(request.oldRollNo())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "旧辊号 " + request.oldRollNo() + " 是物理辊 " + request.physicalNo()
                            + " 的历史代次，只有当前代次 " + physical.headRollNo()
                            + " 才能申请返修");
        }
        if (physical.reworkCount() >= MAX_REWORK_COUNT) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "物理辊 " + request.physicalNo() + " 已累计返修 " + physical.reworkCount()
                            + " 次，达到同一物理辊最多返修 " + MAX_REWORK_COUNT + " 次的上限");
        }

        RollRow oldRoll = lifeRepo.findRoll(request.oldRollNo());
        if (oldRoll == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND,
                    "旧辊号 " + request.oldRollNo() + " 的寿命台账不存在");
        }
        if (oldRoll.totalKm().compareTo(oldRoll.cycleLimitKm()) < 0
                || !STATUS_BANNED.equals(oldRoll.status())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "旧辊号 " + request.oldRollNo() + " 尚未达到寿命上限（累计 "
                            + oldRoll.totalKm().stripTrailingZeros().toPlainString() + "/"
                            + oldRoll.cycleLimitKm().stripTrailingZeros().toPlainString()
                            + " 公里），只有达到寿命上限且已禁排的当前代次能申请返修");
        }
        if (oldRoll.frozenAt() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "旧辊号 " + request.oldRollNo() + " 尚未日冻结，只有达到寿命上限且已冻结的"
                            + "当前代次能申请返修");
        }

        BigDecimal diameter = normalizeDiameter(request.diameterAfterGrindMm(),
                oldGeneration.diameterMm());
        if (request.cycleLimitKm().compareTo(oldRoll.cycleLimitKm()) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "新周期上限 " + request.cycleLimitKm().stripTrailingZeros().toPlainString()
                            + " 公里不得超过上一代上限 "
                            + oldRoll.cycleLimitKm().stripTrailingZeros().toPlainString() + " 公里");
        }

        // 新辊号不得占用已有号：寿命台账账号或已放行代次辊号均算占用。
        if (lifeRepo.findRoll(request.newRollNo()) != null
                || reworkRepo.findGenerationByRollNo(request.newRollNo()) != null) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "新辊号 " + request.newRollNo() + " 已被占用，不得使用已有辊号");
        }

        long applicationId = idAllocator.nextId();
        reworkRepo.insertApplication(new ReworkApplicationRow(
                applicationId, request.requestId(), request.physicalNo(), request.oldRollNo(),
                oldGeneration.generationNo(), request.newRollNo(), diameter,
                request.cycleLimitKm(), request.inspectionCertNo(), request.mountedAt(),
                STATUS_INSPECTING, null, null, null, null, null, null, null, null, 1, now, now));

        ReworkApplicationView view = buildApplicationView(
                reworkRepo.findApplicationById(applicationId));
        saveIdempotent(request.requestId(), BIZ_REWORK_APPLY, fp, FingerprintUtil.toJson(view));
        return view;
    }

    // ---------------- 两名检验员放行 ----------------

    @Transactional
    public ReworkApplicationView inspect(long applicationId, ReworkInspectionRequest request) {
        String fp = FingerprintUtil.fingerprint(
                new InspectionFingerprint(applicationId, request.requestId(), request.inspector(),
                        request.qualified(), request.expectedVersion()));
        ReworkApplicationView replay = replayIfSame(request.requestId(), BIZ_REWORK_INSPECT, fp,
                ReworkApplicationView.class);
        if (replay != null) {
            return replay;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        ReworkApplicationRow application = requireApplication(applicationId);
        if (STATUS_RELEASED.equals(application.status())) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "返修申请 " + applicationId + " 已放行并建立新一代，成功后继不能再建");
        }
        if (STATUS_TERMINATED.equals(application.status())) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "返修申请 " + applicationId + " 已终止且未建新辊，请另提新申请");
        }
        if (application.version() != request.expectedVersion()) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "返修申请 " + applicationId + " 聚合版本已变化：当前版本 "
                            + application.version() + "，expectedVersion "
                            + request.expectedVersion() + " 已过期，请重读后重试");
        }

        if (application.firstInspector() == null) {
            int affected = reworkRepo.updateFirstInspection(applicationId, request.inspector(),
                    request.qualified(), now, now, request.expectedVersion());
            if (affected == 0) {
                throw new ConflictException(HttpStatus.CONFLICT,
                        "返修申请 " + applicationId + " 第一次检验并发冲突（版本或状态已变化），"
                                + "请重读后重试");
            }
        } else {
            if (application.firstInspector().equals(request.inspector())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "两名检验员必须不同：第一名检验员 " + request.inspector()
                                + " 不能再作为第二名确认人");
            }
            if (!request.qualified()) {
                int affected = reworkRepo.terminateOnSecondInspection(applicationId,
                        request.inspector(), now, now, request.expectedVersion());
                if (affected == 0) {
                    throw new ConflictException(HttpStatus.CONFLICT,
                            "返修申请 " + applicationId + " 第二次检验并发冲突（版本或状态已变化），"
                                    + "请重读后重试");
                }
            } else {
                releaseAtomically(application, request.inspector(), now);
            }
        }

        ReworkApplicationView view = buildApplicationView(
                reworkRepo.findApplicationById(applicationId));
        saveIdempotent(request.requestId(), BIZ_REWORK_INSPECT, fp, FingerprintUtil.toJson(view));
        return view;
    }

    /**
     * 第二次检验合格后的原子放行：先抢占旧代次成功后继资格（并发双建的数据库裁决点），
     * 再置申请 RELEASED、建立新一代台账与寿命账号、推进物理辊聚合头指针；
     * 任一步失败整事务回滚，不产生新一代。
     */
    private void releaseAtomically(ReworkApplicationRow application, String secondInspector,
                                   LocalDateTime now) {
        PhysicalRollRow physical = requirePhysical(application.physicalNo());
        RollGenerationRow oldGeneration = reworkRepo.findGeneration(
                application.physicalNo(), application.oldGenerationNo());
        if (oldGeneration == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND,
                    "旧代次登记丢失：物理辊 " + application.physicalNo() + " 第 "
                            + application.oldGenerationNo() + " 代");
        }
        if (oldGeneration.successorApplicationId() != null) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "旧代次 " + application.oldRollNo() + " 已被并发返修申请建立成功后继，"
                            + "并发双建只产生一代，本申请不建新辊");
        }

        // 抢占成功后继资格：唯一裁决并发双建，失败者整事务回滚。
        int claimed = reworkRepo.claimSuccessor(oldGeneration.id(), application.id());
        if (claimed == 0) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "旧代次 " + application.oldRollNo() + " 已被并发返修申请建立成功后继，"
                            + "并发双建只产生一代，本申请不建新辊");
        }

        int released = reworkRepo.releaseWithSecondInspection(application.id(), secondInspector,
                now, now, now, application.version());
        if (released == 0) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "返修申请 " + application.id() + " 放行时版本已变化，请重读后重试");
        }

        int newGenerationNo = application.oldGenerationNo() + 1;
        long newGenerationId = idAllocator.nextId();
        try {
            reworkRepo.insertGeneration(new RollGenerationRow(newGenerationId,
                    application.physicalNo(), newGenerationNo, application.newRollNo(),
                    application.diameterAfterGrindMm(), null, now));
            lifeRepo.insertRoll(new RollRow(application.newRollNo(), "IN_SERVICE",
                    BigDecimal.ZERO, application.cycleLimitKm(), 0, application.mountedAt(),
                    null, now, now));
        } catch (DuplicateKeyException e) {
            // 并发占用同一新辊号：唯一索引裁决，整事务回滚，不产生新一代。
            throw new ConflictException(HttpStatus.CONFLICT,
                    "新辊号 " + application.newRollNo() + " 已被并发占用，本次放行不建新辊");
        }

        int advanced = reworkRepo.advanceHead(application.physicalNo(), application.newRollNo(),
                newGenerationNo, physical.version(), now);
        if (advanced == 0) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "物理辊 " + application.physicalNo() + " 聚合版本已变化，头指针推进失败，"
                            + "本次放行不建新辊");
        }
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public ReworkApplicationView getApplication(long applicationId) {
        return buildApplicationView(requireApplication(applicationId));
    }

    /**
     * 代次链与各代独立台账组合查询：旧代里程、实绩、禁排及冻结结论原样保留，
     * 新代台账独立从 0 起，不重置旧辊冒充新辊。
     */
    @Transactional(readOnly = true)
    public RollLineageView lineage(String physicalNo) {
        PhysicalRollRow physical = requirePhysical(physicalNo);
        List<RollGenerationRow> generations = reworkRepo.findGenerations(physicalNo);
        List<String> rollNos = generations.stream().map(RollGenerationRow::rollNo).toList();
        Map<String, RollRow> ledgerMap = new HashMap<>();
        for (RollRow ledger : reworkRepo.findLedgersByRollNos(rollNos)) {
            ledgerMap.put(ledger.rollNo(), ledger);
        }

        List<RollGenerationView> generationViews = new ArrayList<>();
        List<RollView> ledgerViews = new ArrayList<>();
        for (RollGenerationRow generation : generations) {
            generationViews.add(new RollGenerationView(generation.physicalNo(),
                    generation.generationNo(), generation.rollNo(), generation.diameterMm(),
                    generation.successorApplicationId()));
            RollRow ledger = ledgerMap.get(generation.rollNo());
            if (ledger == null) {
                throw new IllegalStateException(
                        "代次 " + generation.rollNo() + " 缺少寿命台账，代次链数据不完整");
            }
            ledgerViews.add(buildRollView(ledger));
        }

        return new RollLineageView(new PhysicalRollView(physical.physicalNo(),
                physical.headRollNo(), physical.headGenerationNo(), physical.reworkCount(),
                physical.version()), generationViews, ledgerViews);
    }

    // ---------------- 内部方法 ----------------

    /**
     * 磨后直径校验与规范化：保留两位小数、不少于 500 毫米、不得比上一代大
     * （首代无上一代直径记录时只校下界）。
     */
    private BigDecimal normalizeDiameter(BigDecimal diameter, BigDecimal previousDiameter) {
        if (diameter == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "磨后直径不能为空");
        }
        if (diameter.stripTrailingZeros().scale() > 2) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "磨后直径 " + diameter.toPlainString() + " 毫米超过两位小数，直径须保留两位小数");
        }
        BigDecimal normalized = diameter.setScale(2, RoundingMode.HALF_UP);
        if (normalized.compareTo(MIN_DIAMETER_MM) < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "磨后直径 " + normalized.toPlainString() + " 毫米少于 500 毫米，禁止返修放行");
        }
        if (previousDiameter != null && normalized.compareTo(previousDiameter) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "磨后直径 " + normalized.toPlainString() + " 毫米不得比上一代直径 "
                            + previousDiameter.toPlainString() + " 毫米大");
        }
        return normalized;
    }

    private PhysicalRollRow requirePhysical(String physicalNo) {
        PhysicalRollRow physical = reworkRepo.findPhysicalRoll(physicalNo);
        if (physical == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "物理辊不存在: " + physicalNo);
        }
        return physical;
    }

    private ReworkApplicationRow requireApplication(long applicationId) {
        ReworkApplicationRow application = reworkRepo.findApplicationById(applicationId);
        if (application == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "返修申请不存在: " + applicationId);
        }
        return application;
    }

    private ReworkApplicationView buildApplicationView(ReworkApplicationRow row) {
        Integer newGenerationNo = STATUS_RELEASED.equals(row.status())
                ? row.oldGenerationNo() + 1 : null;
        return new ReworkApplicationView(row.id(), row.requestId(), row.physicalNo(),
                row.oldRollNo(), row.oldGenerationNo(), row.newRollNo(),
                row.diameterAfterGrindMm(), row.cycleLimitKm(), row.inspectionCertNo(),
                row.mountedAt(), row.status(), row.firstInspector(), row.firstQualified(),
                row.firstConfirmedAt(), row.secondInspector(), row.secondQualified(),
                row.secondConfirmedAt(), row.releasedAt(), row.terminatedAt(), newGenerationNo,
                row.version());
    }

    private RollView buildRollView(RollRow roll) {
        BigDecimal remaining = roll.cycleLimitKm().subtract(roll.totalKm());
        return new RollView(roll.rollNo(), roll.status(), roll.totalKm(), roll.cycleLimitKm(),
                remaining, roll.coilsSinceMount(), roll.mountedAt(), roll.frozenAt());
    }

    /**
     * 同键重放：指纹一致返回首次响应；同键异参或跨业务类型抛 409 且不产生任何写入。
     *
     * @return 首次响应对象；null 表示该 requestId 首次出现
     */
    private <T> T replayIfSame(String requestId, String bizType, String fingerprint, Class<T> type) {
        IdempotentRow row = lifeRepo.findIdempotent(requestId);
        if (row == null) {
            return null;
        }
        if (!row.fingerprint().equals(fingerprint) || !row.bizType().equals(bizType)) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "requestId " + requestId + " 已用于不同业务参数的请求，拒绝执行且不产生任何写入");
        }
        return FingerprintUtil.fromJson(row.responseBody(), type);
    }

    private void saveIdempotent(String requestId, String bizType, String fingerprint, String body) {
        boolean inserted = lifeRepo.tryInsertIdempotent(requestId, bizType, fingerprint, 200,
                body, LocalDateTime.now(clock));
        if (!inserted) {
            // 并发同键首调竞争：唯一约束裁决后本事务所有写入随异常回滚，用相同参数重放即可。
            throw new ConflictException(HttpStatus.CONFLICT,
                    "requestId " + requestId + " 正在被并发处理，唯一记录已存在，请用相同参数重放");
        }
    }

    /** 检验指纹载体：覆盖路径申请 id 与全部检验参数。 */
    private record InspectionFingerprint(long applicationId, String requestId, String inspector,
                                         Boolean qualified, Integer expectedVersion) {
    }
}
