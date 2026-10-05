package com.example.starter.blend;

import com.example.starter.blend.BlendCalculator.ComponentSnapshot;
import com.example.starter.blend.BlendCalculator.Judgment;
import com.example.starter.blend.BlendCalculator.SpecLimits;
import com.example.starter.blend.BlendDtos.BatchView;
import com.example.starter.blend.BlendDtos.CleaningResponse;
import com.example.starter.blend.BlendDtos.CompleteCleaningRequest;
import com.example.starter.blend.BlendDtos.CompleteCleaningResponse;
import com.example.starter.blend.BlendDtos.ComponentAmount;
import com.example.starter.blend.BlendDtos.CreateCleaningRequest;
import com.example.starter.blend.BlendDtos.CreateOrderRequest;
import com.example.starter.blend.BlendDtos.Metrics;
import com.example.starter.blend.BlendDtos.OrderDetail;
import com.example.starter.blend.BlendDtos.OrderResponse;
import com.example.starter.blend.BlendDtos.ReblendRequest;
import com.example.starter.blend.BlendDtos.ReblendResponse;
import com.example.starter.blend.BlendDtos.ReleaseRequest;
import com.example.starter.blend.BlendDtos.ReleaseResponse;
import com.example.starter.blend.BlendDtos.SampleRequest;
import com.example.starter.blend.BlendDtos.SampleResponse;
import com.example.starter.blend.BlendDtos.SampleView;
import com.example.starter.blend.BlendDtos.TankOccupancyResponse;
import com.example.starter.blend.BlendDtos.TankOccupancyView;
import com.example.starter.blend.BlendRepository.BatchComponentRow;
import com.example.starter.blend.BlendRepository.BatchRow;
import com.example.starter.blend.BlendRepository.CleaningRow;
import com.example.starter.blend.BlendRepository.ComponentTankRow;
import com.example.starter.blend.BlendRepository.OrderRow;
import com.example.starter.blend.BlendRepository.OrderWindowRow;
import com.example.starter.blend.BlendRepository.SpecRow;
import com.example.starter.blend.BlendRepository.TankOccupancyRow;
import com.example.starter.blend.BlendRepository.TankStatusRow;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 调合业务服务：配方下达、组分切罐、在线调和、化验判定、放行/返调。
 * 公开方法负责幂等包装，内部 doXxx 方法在单一事务内完成状态变更与库存扣减。
 */
@Service
public class BlendService {

    /** 调合单状态：执行中。 */
    public static final String ORDER_EXECUTING = "EXECUTING";
    /** 调合单状态：已放行封账（终态）。 */
    public static final String ORDER_RELEASED = "RELEASED";
    /** 调合单状态：不合格品（终态）。 */
    public static final String ORDER_UNQUALIFIED = "UNQUALIFIED";

    /** 批次状态：调和化验中。 */
    public static final String BATCH_OPEN = "OPEN";
    /** 批次状态：首样合格，待次样。 */
    public static final String BATCH_SAMPLE1_PASSED = "SAMPLE1_PASSED";
    /** 批次状态：首样不合格，允许返调一次。 */
    public static final String BATCH_SAMPLE1_FAILED = "SAMPLE1_FAILED";
    /** 批次状态：双采样均合格。 */
    public static final String BATCH_PASSED = "PASSED";
    /** 批次状态：判定不合格。 */
    public static final String BATCH_FAILED = "FAILED";

    /** 清洗单状态：执行中。 */
    public static final String CLEAN_EXECUTING = "EXECUTING";
    /** 清洗单状态：已完成确认（终态）。 */
    public static final String CLEAN_COMPLETED = "COMPLETED";
    /** 清洗单状态：逾期自动释放（终态）。 */
    public static final String CLEAN_EXPIRED = "EXPIRED";

    /** 罐状态：正常。 */
    public static final String TANK_IDLE = "IDLE";
    /** 罐状态：待确认（清洗逾期释放后须重新发起清洗方可占用）。 */
    public static final String TANK_PENDING_CONFIRM = "PENDING_CONFIRM";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BlendRepository repository;
    private final BlendCalculator calculator;
    private final IdempotencyExecutor idempotency;
    private final Clock clock;
    private final BlendService self;

    public BlendService(BlendRepository repository, BlendCalculator calculator,
                        IdempotencyExecutor idempotency, Clock clock, @Lazy BlendService self) {
        this.repository = repository;
        this.calculator = calculator;
        this.idempotency = idempotency;
        this.clock = clock;
        this.self = self;
    }

    /**
     * 下达调合单：requestId 幂等；校验罐余量，组分切罐与库存扣减在同一事务内完成；
     * 任一组分不足则整单不下达且库存无变动；同一调合罐同时刻只允许一张执行中调合单。
     */
    public OrderResponse createOrder(CreateOrderRequest request) {
        self.expireOverdueCleaningsNow();
        String fingerprint = idempotency.fingerprint(request.requestId(), "CREATE_ORDER",
                request.productCode(), request.blendTankId(), canonicalComponents(request.components()));
        return idempotency.execute("CREATE_ORDER", request.requestId(), fingerprint,
                OrderResponse.class, () -> self.doCreateOrder(request));
    }

    /**
     * 提交化验：requestId 幂等；双采样点均合格方可放行；首样不合格允许返调一次；
     * 封账后迟到数据只记迟到事件，不改判定结论。
     */
    public SampleResponse submitSample(long orderId, SampleRequest request) {
        String fingerprint = idempotency.fingerprint(request.requestId(), "SAMPLE",
                orderId, request.samplePoint());
        return idempotency.execute("SAMPLE", request.requestId(), fingerprint,
                SampleResponse.class, () -> self.doSubmitSample(orderId, request));
    }

    /**
     * 返调：requestId 幂等；仅首样不合格后允许一次，生成新批次并引用原批次，重走双采样判定。
     */
    public ReblendResponse reblend(long orderId, ReblendRequest request) {
        String fingerprint = idempotency.fingerprint(request.requestId(), "REBLEND",
                orderId, canonicalComponents(request.components()));
        return idempotency.execute("REBLEND", request.requestId(), fingerprint,
                ReblendResponse.class, () -> self.doReblend(orderId, request));
    }

    /**
     * 放行封账：requestId 幂等；终态，重复封账返回幂等成功。
     */
    public ReleaseResponse release(long orderId, ReleaseRequest request) {
        String fingerprint = idempotency.fingerprint(request.requestId(), "RELEASE", orderId);
        return idempotency.execute("RELEASE", request.requestId(), fingerprint,
                ReleaseResponse.class, () -> self.doRelease(orderId, request));
    }

    /**
     * 发起品种切换清洗确认：requestId 幂等，指纹覆盖 requestId 与全部业务字段；
     * 清洗任务按罐号唯一，占用时间窗按半开区间登记，与调合单占用冲突时不得下达。
     */
    public CleaningResponse createCleaning(CreateCleaningRequest request) {
        self.expireOverdueCleaningsNow();
        String fingerprint = idempotency.fingerprint(request.requestId(), "CREATE_CLEANING",
                request.blendTankId(), request.toProduct(), request.windowStart(), request.windowEnd());
        return idempotency.execute("CREATE_CLEANING", request.requestId(), fingerprint,
                CleaningResponse.class, () -> self.doCreateCleaning(request));
    }

    /**
     * 清洗完成确认：requestId 幂等；清洗确认与罐状态复位在同一事务内完成。
     */
    public CompleteCleaningResponse completeCleaning(long cleaningId, CompleteCleaningRequest request) {
        self.expireOverdueCleaningsNow();
        String fingerprint = idempotency.fingerprint(request.requestId(), "COMPLETE_CLEANING", cleaningId);
        return idempotency.execute("COMPLETE_CLEANING", request.requestId(), fingerprint,
                CompleteCleaningResponse.class, () -> self.doCompleteCleaning(cleaningId, request));
    }

    /**
     * 逾期未完成清洗单自动释放：独立事务提交，释放结果不随后续业务成败回滚。
     */
    @Transactional
    public void expireOverdueCleaningsNow() {
        expireOverdueCleanings(LocalDateTime.now(clock));
    }

    /**
     * 罐占用视图：执行中调合单、执行中清洗占用窗、当前品种与罐状态；
     * 查询前对逾期未完成清洗单执行自动释放并标记罐待确认。
     */
    @Transactional
    public TankOccupancyResponse occupancyView() {
        expireOverdueCleanings(LocalDateTime.now(clock));
        List<TankOccupancyView> tanks = repository.findTankOccupancy().stream()
                .map(r -> new TankOccupancyView(r.blendTankId(), r.tankCode(),
                        r.state() == null ? TANK_IDLE : r.state(), r.currentProduct(),
                        r.executingOrderId(), r.executingCleaningId(),
                        formatTime(r.cleaningWindowStart()), formatTime(r.cleaningWindowEnd())))
                .toList();
        return new TankOccupancyResponse(tanks);
    }

    /**
     * 查询调合单详情：指标、卡边率与批次链均由组分快照复算，重复查询结果稳定。
     */
    @Transactional(readOnly = true)
    public OrderDetail getOrder(long orderId) {
        OrderRow order = repository.findOrder(orderId)
                .orElseThrow(() -> BlendException.notFound("调合单不存在: " + orderId));
        List<BatchRow> batches = repository.findBatches(orderId);
        List<BatchView> batchViews = new ArrayList<>();
        for (BatchRow batch : batches) {
            List<SampleView> samples = repository.findSamples(batch.id()).stream()
                    .map(s -> new SampleView(s.id(), s.samplePoint(), s.qualified(),
                            new Metrics(s.octane(), s.sulfur(), s.vaporPressure()),
                            splitHints(s.hints())))
                    .toList();
            batchViews.add(new BatchView(batch.id(), batch.seq(), batch.originBatchId(), batch.status(), samples));
        }
        BatchRow latest = batches.get(batches.size() - 1);
        Metrics metrics = computeBatchMetrics(latest.id());
        Judgment judgment = judgeOrder(order.productCode(), metrics);
        return new OrderDetail(order.id(), order.orderNo(), order.productCode(), order.blendTankId(),
                order.status(), order.totalVolume(), order.reblendCount(), metrics, judgment.marginHints(),
                batchViews, repository.findLateEvents(orderId));
    }

    /**
     * 配方下达事务体：校验、切罐、扣量、占锁同事务，任一步失败整体回滚。
     */
    @Transactional
    public OrderResponse doCreateOrder(CreateOrderRequest request) {
        SpecRow spec = repository.findSpec(request.productCode())
                .orElseThrow(() -> BlendException.notFound("成品规格不存在: " + request.productCode()));
        if (!repository.blendTankExists(request.blendTankId())) {
            throw BlendException.notFound("调合罐不存在: " + request.blendTankId());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // 品种切换控制：逾期清洗已在入口事务释放，此处校验清洗占用窗与罐品种状态
        checkTankSwitchAllowed(request.blendTankId(), request.productCode(), now);
        // 先逐罐校验余量（行锁），任一组分不足则整单拒绝，不产生任何库存变动
        List<ComponentTankRow> tanks = new ArrayList<>();
        for (ComponentAmount component : request.components()) {
            ComponentTankRow tank = repository.findComponentTankForUpdate(component.tankId())
                    .orElseThrow(() -> BlendException.notFound("组分罐不存在: " + component.tankId()));
            if (tank.remainingVolume().compareTo(component.volume()) < 0) {
                throw BlendException.unprocessable("组分罐" + tank.code() + "余量不足，整单不予下达，库存无变动");
            }
            tanks.add(tank);
        }
        BigDecimal totalVolume = request.components().stream()
                .map(ComponentAmount::volume)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long orderId = repository.insertOrder("TMP-" + request.requestId(), request.requestId(),
                request.productCode(), request.blendTankId(), ORDER_EXECUTING, totalVolume, now);
        String orderNo = String.format("BO%08d", orderId);
        repository.updateOrderNo(orderId, orderNo);
        // 同一调合罐同时刻只允许一张执行中调合单
        try {
            repository.acquireTankLock(request.blendTankId(), orderId, now);
        } catch (DuplicateKeyException e) {
            throw BlendException.conflict("调合罐" + request.blendTankId() + "存在执行中调合单，本次下达被拒绝");
        }
        // 罐品种台账随调合单下达登记当前品种
        repository.updateTankStatus(request.blendTankId(), request.productCode(), TANK_IDLE, now);
        long batchId = repository.insertBatch(orderId, 1, null, BATCH_OPEN, now);
        List<ComponentSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < tanks.size(); i++) {
            ComponentTankRow tank = tanks.get(i);
            BigDecimal volume = request.components().get(i).volume();
            repository.insertBatchComponent(batchId, tank.id(), tank.code(), volume,
                    tank.octane(), tank.sulfur(), tank.vaporPressure());
            if (repository.deductComponentVolume(tank.id(), volume) != 1) {
                throw BlendException.unprocessable("组分罐" + tank.code() + "余量不足，整单不予下达，库存无变动");
            }
            snapshots.add(new ComponentSnapshot(volume, tank.octane(), tank.sulfur(), tank.vaporPressure()));
        }
        Metrics metrics = calculator.computeMetrics(snapshots);
        Judgment judgment = calculator.judge(metrics, toLimits(spec));
        return new OrderResponse(orderId, orderNo, ORDER_EXECUTING, batchId, totalVolume,
                metrics, judgment.marginHints());
    }

    /**
     * 化验提交事务体。
     */
    @Transactional
    public SampleResponse doSubmitSample(long orderId, SampleRequest request) {
        OrderRow order = repository.findOrder(orderId)
                .orElseThrow(() -> BlendException.notFound("调合单不存在: " + orderId));
        BatchRow batch = repository.findLatestBatch(orderId)
                .orElseThrow(() -> BlendException.conflict("调合单无调合批次"));
        if (ORDER_RELEASED.equals(order.status())) {
            // 封账终态：迟到化验数据只记迟到事件，不改判定结论
            repository.insertLateEvent(orderId, batch.id(), request.samplePoint(), request.requestId(),
                    "封账后迟到化验数据（采样点" + request.samplePoint() + "），仅记录不改判定结论",
                    LocalDateTime.now(clock));
            return new SampleResponse(null, batch.id(), request.samplePoint(), false,
                    computeBatchMetrics(batch.id()), List.of(), ORDER_RELEASED, true);
        }
        if (ORDER_UNQUALIFIED.equals(order.status())) {
            throw BlendException.conflict("调合单已转不合格品终态，不再接受化验数据");
        }
        return switch (request.samplePoint()) {
            case 1 -> submitFirstSample(order, batch, request.requestId());
            case 2 -> submitSecondSample(order, batch, request.requestId());
            default -> throw BlendException.conflict("采样点仅支持1或2");
        };
    }

    /**
     * 返调事务体：新批次引用原批次，追加组分切罐扣量与状态变更同事务。
     */
    @Transactional
    public ReblendResponse doReblend(long orderId, ReblendRequest request) {
        OrderRow order = repository.findOrder(orderId)
                .orElseThrow(() -> BlendException.notFound("调合单不存在: " + orderId));
        if (!ORDER_EXECUTING.equals(order.status())) {
            throw BlendException.conflict("调合单状态为" + order.status() + "，不允许返调");
        }
        if (order.reblendCount() > 0) {
            throw BlendException.conflict("返调仅限一次，本单已返调过");
        }
        BatchRow origin = repository.findLatestBatch(orderId)
                .orElseThrow(() -> BlendException.conflict("调合单无调合批次"));
        if (!BATCH_SAMPLE1_FAILED.equals(origin.status())) {
            throw BlendException.conflict("仅首样不合格后允许返调");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<ComponentTankRow> tanks = new ArrayList<>();
        for (ComponentAmount component : request.components()) {
            ComponentTankRow tank = repository.findComponentTankForUpdate(component.tankId())
                    .orElseThrow(() -> BlendException.notFound("组分罐不存在: " + component.tankId()));
            if (tank.remainingVolume().compareTo(component.volume()) < 0) {
                throw BlendException.unprocessable("组分罐" + tank.code() + "余量不足，返调不予执行，库存无变动");
            }
            tanks.add(tank);
        }
        long newBatchId = repository.insertBatch(orderId, origin.seq() + 1, origin.id(), BATCH_OPEN, now);
        // 新批次继承原批次全部组分（罐内油仍留存），再追加返调组分
        List<ComponentSnapshot> snapshots = new ArrayList<>();
        for (BatchComponentRow old : repository.findBatchComponents(origin.id())) {
            repository.insertBatchComponent(newBatchId, old.tankId(), old.tankCode(), old.volume(),
                    old.octane(), old.sulfur(), old.vaporPressure());
            snapshots.add(new ComponentSnapshot(old.volume(), old.octane(), old.sulfur(), old.vaporPressure()));
        }
        BigDecimal addedVolume = BigDecimal.ZERO;
        for (int i = 0; i < tanks.size(); i++) {
            ComponentTankRow tank = tanks.get(i);
            BigDecimal volume = request.components().get(i).volume();
            repository.insertBatchComponent(newBatchId, tank.id(), tank.code(), volume,
                    tank.octane(), tank.sulfur(), tank.vaporPressure());
            if (repository.deductComponentVolume(tank.id(), volume) != 1) {
                throw BlendException.unprocessable("组分罐" + tank.code() + "余量不足，返调不予执行，库存无变动");
            }
            snapshots.add(new ComponentSnapshot(volume, tank.octane(), tank.sulfur(), tank.vaporPressure()));
            addedVolume = addedVolume.add(volume);
        }
        repository.markReblended(orderId, addedVolume);
        Metrics metrics = calculator.computeMetrics(snapshots);
        SpecRow spec = repository.findSpec(order.productCode())
                .orElseThrow(() -> BlendException.notFound("成品规格不存在: " + order.productCode()));
        Judgment judgment = calculator.judge(metrics, toLimits(spec));
        return new ReblendResponse(orderId, newBatchId, origin.id(), origin.seq() + 1,
                metrics, judgment.marginHints(), ORDER_EXECUTING);
    }

    /**
     * 放行封账事务体：终态；重复封账返回幂等成功。
     */
    @Transactional
    public ReleaseResponse doRelease(long orderId, ReleaseRequest request) {
        OrderRow order = repository.findOrder(orderId)
                .orElseThrow(() -> BlendException.notFound("调合单不存在: " + orderId));
        if (ORDER_RELEASED.equals(order.status())) {
            return new ReleaseResponse(orderId, ORDER_RELEASED, formatTime(order.releasedAt()));
        }
        if (ORDER_UNQUALIFIED.equals(order.status())) {
            throw BlendException.conflict("调合单已转不合格品终态，不能放行");
        }
        BatchRow batch = repository.findLatestBatch(orderId)
                .orElseThrow(() -> BlendException.conflict("调合单无调合批次"));
        if (!BATCH_PASSED.equals(batch.status())) {
            throw BlendException.conflict("双采样未全部合格，不能放行封账");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        repository.updateOrderStatus(orderId, ORDER_RELEASED, now);
        repository.releaseTankLock(order.blendTankId());
        return new ReleaseResponse(orderId, ORDER_RELEASED, formatTime(now));
    }

    /**
     * 清洗单发起事务体：占用窗半开区间与调合单占用冲突校验、罐级清洗锁占用同事务。
     */
    @Transactional
    public CleaningResponse doCreateCleaning(CreateCleaningRequest request) {
        String tankCode = repository.findBlendTankCode(request.blendTankId())
                .orElseThrow(() -> BlendException.notFound("调合罐不存在: " + request.blendTankId()));
        LocalDateTime windowStart = parseTime(request.windowStart());
        LocalDateTime windowEnd = parseTime(request.windowEnd());
        if (!windowEnd.isAfter(windowStart)) {
            throw new BlendException(400, "清洗占用窗终点必须晚于起点");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // 清洗占用窗[windowStart, windowEnd)与调合单占用窗[createdAt, releasedAt)冲突校验，端点相接不算冲突
        for (OrderWindowRow window : repository.findOrderWindowsForTank(request.blendTankId())) {
            boolean overlaps = window.createdAt().isBefore(windowEnd)
                    && (window.releasedAt() == null || windowStart.isBefore(window.releasedAt()));
            if (overlaps) {
                throw BlendException.conflict("清洗占用窗与调合罐" + tankCode + "的调合单" + window.orderId()
                        + "占用冲突，新清洗单不得下达");
            }
        }
        repository.ensureTankStatus(request.blendTankId(), now);
        TankStatusRow status = repository.findTankStatus(request.blendTankId())
                .orElseThrow(() -> BlendException.notFound("调合罐不存在: " + request.blendTankId()));
        long cleaningId = repository.insertCleaning("TMP-" + request.requestId(), request.requestId(),
                request.blendTankId(), status.currentProduct(), request.toProduct(), CLEAN_EXECUTING,
                windowStart, windowEnd, now);
        String cleanNo = String.format("CL%08d", cleaningId);
        repository.updateCleaningNo(cleaningId, cleanNo);
        // 清洗任务按罐号唯一：同罐同时刻只允许一张执行中清洗单
        try {
            repository.acquireCleaningLock(request.blendTankId(), cleaningId, now);
        } catch (DuplicateKeyException e) {
            throw BlendException.conflict("调合罐" + tankCode + "存在执行中清洗单，同罐同时刻只允许一张执行中清洗单");
        }
        return new CleaningResponse(cleaningId, cleanNo, request.blendTankId(), CLEAN_EXECUTING,
                status.currentProduct(), request.toProduct(), formatTime(windowStart), formatTime(windowEnd));
    }

    /**
     * 清洗完成确认事务体：清洗单状态流转、罐级锁释放与罐状态复位在同一事务内完成。
     */
    @Transactional
    public CompleteCleaningResponse doCompleteCleaning(long cleaningId, CompleteCleaningRequest request) {
        repository.findCleaningForUpdate(cleaningId)
                .orElseThrow(() -> BlendException.notFound("清洗单不存在: " + cleaningId));
        LocalDateTime now = LocalDateTime.now(clock);
        // 逾期未完成已在入口事务自动释放：占用窗终点已过（半开区间端点相接即结束）的清洗单不能再确认
        CleaningRow cleaning = repository.findCleaning(cleaningId)
                .orElseThrow(() -> BlendException.notFound("清洗单不存在: " + cleaningId));
        if (CLEAN_EXPIRED.equals(cleaning.status())) {
            throw BlendException.conflict("清洗单已逾期自动释放，罐已标记待确认，须重新发起清洗");
        }
        if (!CLEAN_EXECUTING.equals(cleaning.status())) {
            throw BlendException.conflict("清洗单状态为" + cleaning.status() + "，不能完成确认");
        }
        repository.updateCleaningStatus(cleaningId, CLEAN_COMPLETED, now);
        repository.releaseCleaningLock(cleaning.blendTankId());
        // 罐状态复位：清洗确认后罐空置，可接任意品种
        repository.ensureTankStatus(cleaning.blendTankId(), now);
        repository.updateTankStatus(cleaning.blendTankId(), null, TANK_IDLE, now);
        return new CompleteCleaningResponse(cleaningId, CLEAN_COMPLETED, formatTime(now),
                cleaning.blendTankId(), TANK_IDLE);
    }

    private SampleResponse submitFirstSample(OrderRow order, BatchRow batch, String requestId) {
        if (BATCH_SAMPLE1_PASSED.equals(batch.status())) {
            throw BlendException.conflict("首样已提交且合格，请提交次样");
        }
        if (BATCH_SAMPLE1_FAILED.equals(batch.status())) {
            throw BlendException.conflict("首样已判定不合格，请执行返调");
        }
        if (!BATCH_OPEN.equals(batch.status())) {
            throw BlendException.conflict("批次状态为" + batch.status() + "，不接受首样");
        }
        Metrics metrics = computeBatchMetrics(batch.id());
        Judgment judgment = judgeOrder(order.productCode(), metrics);
        long sampleId = insertSampleGuarded(batch.id(), 1, requestId, metrics, judgment);
        String orderStatus = order.status();
        if (judgment.qualified()) {
            repository.updateBatchStatus(batch.id(), BATCH_SAMPLE1_PASSED);
        } else if (order.reblendCount() == 0) {
            repository.updateBatchStatus(batch.id(), BATCH_SAMPLE1_FAILED);
        } else {
            // 返调批次首样仍不合格：整罐转不合格品终态，不得再次返调
            repository.updateBatchStatus(batch.id(), BATCH_FAILED);
            repository.updateOrderStatus(order.id(), ORDER_UNQUALIFIED, null);
            repository.releaseTankLock(order.blendTankId());
            orderStatus = ORDER_UNQUALIFIED;
        }
        return new SampleResponse(sampleId, batch.id(), 1, judgment.qualified(),
                metrics, judgment.marginHints(), orderStatus, false);
    }

    private SampleResponse submitSecondSample(OrderRow order, BatchRow batch, String requestId) {
        if (!BATCH_SAMPLE1_PASSED.equals(batch.status())) {
            throw BlendException.conflict("首样未合格，不能提交次样");
        }
        Metrics metrics = computeBatchMetrics(batch.id());
        Judgment judgment = judgeOrder(order.productCode(), metrics);
        long sampleId = insertSampleGuarded(batch.id(), 2, requestId, metrics, judgment);
        String orderStatus = order.status();
        if (judgment.qualified()) {
            repository.updateBatchStatus(batch.id(), BATCH_PASSED);
        } else {
            // 次样不合格不允许返调，整罐转不合格品终态
            repository.updateBatchStatus(batch.id(), BATCH_FAILED);
            repository.updateOrderStatus(order.id(), ORDER_UNQUALIFIED, null);
            repository.releaseTankLock(order.blendTankId());
            orderStatus = ORDER_UNQUALIFIED;
        }
        return new SampleResponse(sampleId, batch.id(), 2, judgment.qualified(),
                metrics, judgment.marginHints(), orderStatus, false);
    }

    private long insertSampleGuarded(long batchId, int samplePoint, String requestId,
                                     Metrics metrics, Judgment judgment) {
        try {
            return repository.insertSample(batchId, samplePoint, requestId,
                    metrics.octane(), metrics.sulfur(), metrics.vaporPressure(),
                    judgment.qualified(), String.join(";", judgment.marginHints()),
                    LocalDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            throw BlendException.conflict("采样点" + samplePoint + "已提交化验结果");
        }
    }

    private Metrics computeBatchMetrics(long batchId) {
        List<ComponentSnapshot> snapshots = repository.findBatchComponents(batchId).stream()
                .map(c -> new ComponentSnapshot(c.volume(), c.octane(), c.sulfur(), c.vaporPressure()))
                .toList();
        return calculator.computeMetrics(snapshots);
    }

    private Judgment judgeOrder(String productCode, Metrics metrics) {
        SpecRow spec = repository.findSpec(productCode)
                .orElseThrow(() -> BlendException.notFound("成品规格不存在: " + productCode));
        return calculator.judge(metrics, toLimits(spec));
    }

    private SpecLimits toLimits(SpecRow spec) {
        return new SpecLimits(spec.octaneLimit(), spec.sulfurLimit(), spec.vaporPressureLimit(),
                spec.octaneMargin(), spec.sulfurMargin(), spec.vaporPressureMargin());
    }

    private String canonicalComponents(List<ComponentAmount> components) {
        return components.stream()
                .sorted(Comparator.comparing(ComponentAmount::tankId))
                .map(c -> c.tankId() + ":" + c.volume().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining(","));
    }

    private List<String> splitHints(String hints) {
        if (hints == null || hints.isEmpty()) {
            return List.of();
        }
        return List.of(hints.split(";"));
    }

    /**
     * 逾期未完成清洗单自动释放：释放占用窗与罐级锁，并把罐标记为待确认；重新占用须重新发起清洗。
     */
    private void expireOverdueCleanings(LocalDateTime now) {
        for (CleaningRow overdue : repository.findOverdueExecutingCleanings(now)) {
            repository.updateCleaningStatus(overdue.id(), CLEAN_EXPIRED, null);
            repository.releaseCleaningLock(overdue.blendTankId());
            repository.ensureTankStatus(overdue.blendTankId(), now);
            TankStatusRow status = repository.findTankStatus(overdue.blendTankId()).orElse(null);
            String product = status == null ? null : status.currentProduct();
            repository.updateTankStatus(overdue.blendTankId(), product, TANK_PENDING_CONFIRM, now);
        }
    }

    /**
     * 品种切换校验：清洗占用窗未结束时新调合单不得下达（半开区间，端点相接不算冲突）；
     * 罐待确认或跨品种且未清洗确认时直接拒绝，并给出需清洗的罐号明细。
     */
    private void checkTankSwitchAllowed(long blendTankId, String productCode, LocalDateTime now) {
        String tankCode = repository.findBlendTankCode(blendTankId).orElse(String.valueOf(blendTankId));
        repository.findExecutingCleaningForTank(blendTankId).ifPresent(cleaning -> {
            if (now.isBefore(cleaning.windowEnd())) {
                throw BlendException.conflict("调合罐" + tankCode + "存在执行中清洗单" + cleaning.cleanNo()
                        + "，占用窗[" + formatTime(cleaning.windowStart()) + ", "
                        + formatTime(cleaning.windowEnd()) + ")未结束，新调合单不得下达");
            }
        });
        repository.ensureTankStatus(blendTankId, now);
        TankStatusRow status = repository.findTankStatus(blendTankId)
                .orElseThrow(() -> BlendException.notFound("调合罐不存在: " + blendTankId));
        if (TANK_PENDING_CONFIRM.equals(status.state())) {
            throw BlendException.unprocessable("调合罐" + tankCode + "清洗逾期待确认，需清洗罐号：" + tankCode
                    + "；须重新发起清洗确认后方可占用");
        }
        if (status.currentProduct() != null && !status.currentProduct().equals(productCode)) {
            throw BlendException.unprocessable("调合罐" + tankCode + "当前品种" + status.currentProduct()
                    + "，与目标品种" + productCode + "不同，未清洗确认不得跨品种下单；需清洗罐号：" + tankCode);
        }
    }

    private LocalDateTime parseTime(String text) {
        try {
            return LocalDateTime.parse(text, TIME_FORMAT);
        } catch (Exception e) {
            throw new BlendException(400, "时间格式必须为yyyy-MM-dd HH:mm:ss: " + text);
        }
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? null : TIME_FORMAT.format(time);
    }
}
