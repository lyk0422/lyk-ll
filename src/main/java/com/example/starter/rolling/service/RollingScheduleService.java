package com.example.starter.rolling.service;

import com.example.starter.rolling.api.dto.ActualResultRequest;
import com.example.starter.rolling.api.dto.ActualResultView;
import com.example.starter.rolling.api.dto.FreezeRequest;
import com.example.starter.rolling.api.dto.IssueRequest;
import com.example.starter.rolling.api.dto.ItemView;
import com.example.starter.rolling.api.dto.RollWindowRequest;
import com.example.starter.rolling.api.dto.ScheduleCreateRequest;
import com.example.starter.rolling.api.dto.ScheduleView;
import com.example.starter.rolling.api.dto.WindowView;
import com.example.starter.rolling.domain.ScheduleStatus;
import com.example.starter.rolling.repo.IdAllocator;
import com.example.starter.rolling.repo.RollingScheduleRepository;
import com.example.starter.rolling.repo.RollingScheduleRepository.ActualRow;
import com.example.starter.rolling.repo.RollingScheduleRepository.IdempotentRow;
import com.example.starter.rolling.repo.RollingScheduleRepository.ItemRow;
import com.example.starter.rolling.repo.RollingScheduleRepository.OrderRow;
import com.example.starter.rolling.repo.RollingScheduleRepository.WindowRow;
import com.example.starter.rolling.repo.WidthGradeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 轧钢排产领域服务：编制/校验、下达、实绩回传、日终冻结。
 *
 * <p>幂等：指纹覆盖 requestId 与全部业务字段；同键同参返回首次结果，
 * 同键异参返回 409 且不产生新版本/不入库。</p>
 */
@Service
public class RollingScheduleService {

    /** 实绩累计里程达到的换辊阈值（公里），恰达即触发锁定与换辊提示。 */
    public static final BigDecimal ROLL_CHANGE_KM_THRESHOLD = BigDecimal.TEN;

    private static final String BIZ_SCHEDULE = "SCHEDULE_CREATE";
    private static final String BIZ_ISSUE = "SCHEDULE_ISSUE";
    private static final String BIZ_FREEZE = "SCHEDULE_FREEZE";
    private static final String BIZ_ACTUAL = "ACTUAL_REPORT";

    private final RollingScheduleRepository repo;
    private final WidthGradeRepository gradeRepo;
    private final IdAllocator idAllocator;
    private final Clock clock;

    public RollingScheduleService(RollingScheduleRepository repo,
                                  WidthGradeRepository gradeRepo,
                                  IdAllocator idAllocator,
                                  Clock clock) {
        this.repo = repo;
        this.gradeRepo = gradeRepo;
        this.idAllocator = idAllocator;
        this.clock = clock;
    }

    // ---------------- 编制 / 修改 ----------------

    @Transactional
    public ScheduleView createOrRevise(ScheduleCreateRequest request) {
        String fp = FingerprintUtil.fingerprint(request);
        Object replay = replayIfSame(request.requestId(), BIZ_SCHEDULE, fp);
        if (replay != null) {
            return (ScheduleView) replay;
        }

        int versionNo = 1;
        if (request.baseVersionId() != null) {
            OrderRow base = requireOrder(request.baseVersionId());
            if (!base.lineCode().equals(request.lineCode()) || !base.unitCode().equals(request.unitCode())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "修改版本的产线/轧制单元必须与基线版本一致");
            }
            if (base.status().equals(ScheduleStatus.DRAFT.name())
                    || base.status().equals(ScheduleStatus.VALIDATED.name())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "仅下达后的版本允许修改并生成新版本，当前基线状态为 " + base.status());
            }
            versionNo = base.versionNo() + 1;
        }

        // 违例整单拒绝：校验失败时事务内不写入任何排程数据。
        ScheduleValidator.Result result = ScheduleValidator.validate(
                request.items(), request.windows() == null ? List.of() : request.windows(),
                gradeRepo.loadGrades());
        if (!result.valid()) {
            throw new ValidationException(result.violations());
        }

        LocalDateTime now = LocalDateTime.now(clock);
        long orderId = idAllocator.nextId();
        OrderRow order = new OrderRow(orderId, request.requestId(), fp, request.lineCode(),
                request.unitCode(), versionNo, request.baseVersionId(),
                ScheduleStatus.DRAFT.name(), false, false, false, null, now, now);
        repo.insertOrder(order);

        IdAllocator.IdRange itemIds = idAllocator.nextIds(request.items().size());
        long itemId = itemIds.start();
        for (ScheduleValidator.ValidatedItem vi : result.items()) {
            var it = vi.item();
            repo.insertItem(new ItemRow(itemId++, orderId, vi.seq(), vi.campaignNo(),
                    vi.seqInCampaign(), it.contractNo(), it.widthMm(), it.rollingKm(), it.warmup(),
                    false, it.startTime(), it.endTime()));
        }

        List<RollWindowRequest> windows = request.windows() == null ? List.of()
                : request.windows().stream()
                        .sorted(java.util.Comparator.comparing(RollWindowRequest::startTime))
                        .toList();
        if (!windows.isEmpty()) {
            IdAllocator.IdRange windowIds = idAllocator.nextIds(windows.size());
            long windowId = windowIds.start();
            int campaignNo = 1;
            for (var window : windows) {
                repo.insertWindow(new WindowRow(windowId++, orderId, campaignNo++, request.lineCode(),
                        window.startTime(), window.endTime()));
            }
        }

        ScheduleView view = buildView(order);
        saveIdempotent(request.requestId(), BIZ_SCHEDULE, fp, 200, FingerprintUtil.toJson(view));
        return view;
    }

    // ---------------- 校验 ----------------

    @Transactional
    public ScheduleView validate(Long orderId) {
        OrderRow order = requireOrder(orderId);
        if (order.status().equals(ScheduleStatus.DRAFT.name())
                || order.status().equals(ScheduleStatus.VALIDATED.name())) {
            LocalDateTime now = LocalDateTime.now(clock);
            repo.updateStatus(orderId, ScheduleStatus.VALIDATED.name(), now);
        } else {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "当前状态 " + order.status() + " 不允许校验");
        }
        return buildView(requireOrder(orderId));
    }

    // ---------------- 下达 ----------------

    @Transactional
    public ScheduleView issue(Long orderId, IssueRequest request) {
        String fp = FingerprintUtil.fingerprint(new IssueFingerprint(request.requestId(), orderId));
        Object replay = replayIfSame(request.requestId(), BIZ_ISSUE, fp);
        if (replay != null) {
            return (ScheduleView) replay;
        }

        OrderRow order = requireOrder(orderId);
        if (!order.status().equals(ScheduleStatus.VALIDATED.name())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "仅校验通过的排产单可下达，当前状态为 " + order.status());
        }

        LocalDateTime now = LocalDateTime.now(clock);
        boolean won;
        if (order.baseVersionId() == null) {
            // 首版：插入生效版本行，主键冲突即并发下达失败。
            won = repo.tryInsertEffective(order.lineCode(), orderId, now);
        } else {
            // 修改版：仅当生效版本仍为基线版本时替换，数据库裁决并发。
            won = repo.tryReplaceEffective(order.lineCode(), orderId, order.baseVersionId(), now);
        }
        if (!won) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "产线 " + order.lineCode() + " 同时刻只允许一个生效版本，本次下达未抢到生效位");
        }

        repo.updateStatus(orderId, ScheduleStatus.ISSUED.name(), now);
        repo.updateEffectiveFlag(orderId, true, now);
        if (order.baseVersionId() != null) {
            repo.updateEffectiveFlag(order.baseVersionId(), false, now);
        }
        ScheduleView view = buildView(requireOrder(orderId));
        saveIdempotent(request.requestId(), BIZ_ISSUE, fp, 200, FingerprintUtil.toJson(view));
        return view;
    }

    // ---------------- 实绩回传 ----------------

    @Transactional
    public ActualResultView reportActual(ActualResultRequest request) {
        String fp = FingerprintUtil.fingerprint(request);
        Object replay = replayIfSame(request.requestId(), BIZ_ACTUAL, fp);
        if (replay != null) {
            return (ActualResultView) replay;
        }

        // 按“合同号＋道次”去重：已存在的实绩按首次结果幂等返回。
        ActualRow existing = repo.findActual(request.contractNo(), request.passNo());
        if (existing != null) {
            return dedupActualView(request.requestId(), fp, existing);
        }

        // 定位合同归属的当前生效版本。
        OrderRow order = findOrderByLineAndContract(request.lineCode(), request.contractNo());
        if (order == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND,
                    "合同 " + request.contractNo() + " 在产线 " + request.lineCode()
                            + " 的生效排产版本中不存在");
        }
        ItemRow item = repo.findItemByContract(order.id(), request.contractNo());

        LocalDateTime now = LocalDateTime.now(clock);

        // 冻结终态：迟到实绩只记迟到事件，不改已冻结里程与换辊结论。
        if (order.status().equals(ScheduleStatus.FROZEN.name())) {
            long eventId = idAllocator.nextId();
            repo.insertLateEvent(eventId, order.id(), request.requestId(), request.contractNo(),
                    request.passNo(), request.rollingKm(), request.recordedAt(), now);
            ActualResultView lateView = new ActualResultView(request.contractNo(), request.passNo(),
                    null, false, true, item.campaignNo(),
                    repo.sumActualKmForCampaign(order.id(), item.campaignNo()),
                    order.locked(), order.rollChangeRequired(), request.recordedAt());
            saveIdempotent(request.requestId(), BIZ_ACTUAL, fp, 200,
                    FingerprintUtil.toJson(lateView));
            return lateView;
        }

        long actualId = idAllocator.nextId();
        try {
            repo.insertActual(new ActualRow(actualId, order.id(), request.contractNo(),
                    request.passNo(), request.rollingKm(), request.recordedAt(),
                    request.requestId(), fp, now));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发回传同一“合同号＋道次”：唯一约束裁决后按首次实绩幂等返回。
            ActualRow winner = repo.findActual(request.contractNo(), request.passNo());
            if (winner != null) {
                return dedupActualView(request.requestId(), fp, winner);
            }
            throw e;
        }

        // 首次实绩回传后版本进入执行态（ISSUED → EXECUTING）。
        if (order.status().equals(ScheduleStatus.ISSUED.name())) {
            repo.updateStatus(order.id(), ScheduleStatus.EXECUTING.name(), now);
        }

        // 实绩按道次归位到换辊周期并累计里程；恰达阈值即锁定后续排程并提示换辊，不放行超限轧制。
        BigDecimal campaignTotal = repo.sumActualKmForCampaign(order.id(), item.campaignNo());
        boolean hitThreshold = campaignTotal.compareTo(ROLL_CHANGE_KM_THRESHOLD) >= 0;
        if (hitThreshold && !order.locked()) {
            repo.markRollChangeLock(order.id(), now);
            // 恰达阈值的当前块是本周期最后合法轧制位；锁定其后所有作业（含同周期后续块与后续周期），
            // 换辊完成前不得静默放行。
            for (ItemRow later : repo.findItemsByOrderId(order.id())) {
                if (later.seq() > item.seq()) {
                    repo.lockItem(later.id());
                }
            }
        }

        OrderRow refreshed = requireOrder(order.id());
        ActualResultView view = new ActualResultView(request.contractNo(), request.passNo(),
                request.rollingKm(), true, false, item.campaignNo(), campaignTotal,
                refreshed.locked(), refreshed.rollChangeRequired(), request.recordedAt());
        saveIdempotent(request.requestId(), BIZ_ACTUAL, fp, 200, FingerprintUtil.toJson(view));
        return view;
    }

    // ---------------- 日终冻结 ----------------

    @Transactional
    public ScheduleView freeze(Long orderId, FreezeRequest request) {
        String fp = FingerprintUtil.fingerprint(new FreezeFingerprint(request.requestId(), orderId));
        Object replay = replayIfSame(request.requestId(), BIZ_FREEZE, fp);
        if (replay != null) {
            return (ScheduleView) replay;
        }

        OrderRow order = requireOrder(orderId);
        if (order.status().equals(ScheduleStatus.FROZEN.name())) {
            // 终态重复冻结：幂等成功，不重复写状态。
            ScheduleView view = buildView(order);
            saveIdempotent(request.requestId(), BIZ_FREEZE, fp, 200, FingerprintUtil.toJson(view));
            return view;
        }
        if (!order.status().equals(ScheduleStatus.ISSUED.name())
                && !order.status().equals(ScheduleStatus.EXECUTING.name())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "仅下达/执行中的排产单可日终冻结，当前状态为 " + order.status());
        }

        LocalDateTime now = LocalDateTime.now(clock);
        repo.markFrozen(orderId, now);
        ScheduleView view = buildView(requireOrder(orderId));
        saveIdempotent(request.requestId(), BIZ_FREEZE, fp, 200, FingerprintUtil.toJson(view));
        return view;
    }

    @Transactional(readOnly = true)
    public ScheduleView get(Long orderId) {
        return buildView(requireOrder(orderId));
    }

    // ---------------- 内部方法 ----------------

    private OrderRow requireOrder(long orderId) {
        OrderRow order = repo.findOrderById(orderId);
        if (order == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "排产单不存在: " + orderId);
        }
        return order;
    }

    private OrderRow findOrderByLineAndContract(String lineCode, String contractNo) {
        Long effectiveId = repo.findEffectiveOrderId(lineCode);
        if (effectiveId != null) {
            ItemRow item = repo.findItemByContract(effectiveId, contractNo);
            if (item != null) {
                return repo.findOrderById(effectiveId);
            }
        }
        return null;
    }

    /**
     * 构造“合同号＋道次”重复回传的幂等视图并登记本次 requestId 的幂等记录。
     */
    private ActualResultView dedupActualView(String requestId, String fp, ActualRow existing) {
        OrderRow order = requireOrder(existing.orderId());
        ItemRow item = repo.findItemByContract(existing.orderId(), existing.contractNo());
        BigDecimal total = item == null ? null
                : repo.sumActualKmForCampaign(existing.orderId(), item.campaignNo());
        ActualResultView view = new ActualResultView(existing.contractNo(), existing.passNo(),
                existing.rollingKm(), false, false, item == null ? null : item.campaignNo(),
                total, order.locked(), order.rollChangeRequired(), existing.recordedAt());
        saveIdempotent(requestId, BIZ_ACTUAL, fp, 200, FingerprintUtil.toJson(view));
        return view;
    }

    /**
     * 同键重放：指纹一致返回首次响应；同键异参抛 409 且不产生任何写入。
     *
     * @return 首次响应对象；null 表示该 requestId 首次出现
     */
    private Object replayIfSame(String requestId, String bizType, String fingerprint) {
        IdempotentRow row = repo.findIdempotent(requestId);
        if (row == null) {
            return null;
        }
        if (!row.fingerprint().equals(fingerprint) || !row.bizType().equals(bizType)) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "requestId " + requestId + " 已用于不同业务参数的请求，拒绝执行且不产生新版本");
        }
        return switch (bizType) {
            case BIZ_ACTUAL -> FingerprintUtil.fromJson(row.responseBody(), ActualResultView.class);
            default -> FingerprintUtil.fromJson(row.responseBody(), ScheduleView.class);
        };
    }

    private void saveIdempotent(String requestId, String bizType, String fingerprint,
                                int httpStatus, String body) {
        boolean inserted = repo.tryInsertIdempotent(requestId, bizType, fingerprint, httpStatus, body,
                LocalDateTime.now(clock));
        if (!inserted) {
            // 并发同键首调竞争：唯一约束裁决后本事务所有写入将随异常回滚，
            // 不产生新版本；客户端用相同 requestId 重放即可取得首次结果。
            throw new ConflictException(HttpStatus.CONFLICT,
                    "requestId " + requestId + " 正在被并发处理，唯一记录已存在，请用相同参数重放");
        }
    }

    private ScheduleView buildView(OrderRow order) {
        List<ItemView> items = repo.findItemsByOrderId(order.id()).stream()
                .map(i -> new ItemView(i.seq(), i.campaignNo(), i.seqInCampaign(), i.contractNo(),
                        i.widthMm(), i.rollingKm(), i.warmup(), i.locked(), i.startTime(), i.endTime()))
                .toList();
        List<WindowView> windows = repo.findWindowsByOrderId(order.id()).stream()
                .map(w -> new WindowView(w.campaignNo(), w.startTime(), w.endTime()))
                .toList();
        return new ScheduleView(order.id(), order.lineCode(), order.unitCode(), order.versionNo(),
                order.baseVersionId(), order.status(), order.effective(), order.locked(),
                order.rollChangeRequired(), order.frozenAt(), items, windows);
    }

    /** 下达指纹载体：覆盖 requestId 与目标排产单。 */
    private record IssueFingerprint(String requestId, Long orderId) {
    }

    /** 冻结指纹载体：覆盖 requestId 与目标排产单。 */
    private record FreezeFingerprint(String requestId, Long orderId) {
    }
}
