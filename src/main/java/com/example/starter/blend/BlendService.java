package com.example.starter.blend;

import com.example.starter.blend.BlendCalculator.ComponentSnapshot;
import com.example.starter.blend.BlendCalculator.Judgment;
import com.example.starter.blend.BlendCalculator.SpecLimits;
import com.example.starter.blend.BlendDtos.BatchView;
import com.example.starter.blend.BlendDtos.ComponentAmount;
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
import com.example.starter.blend.BlendRepository.BatchComponentRow;
import com.example.starter.blend.BlendRepository.BatchRow;
import com.example.starter.blend.BlendRepository.BlendTankRow;
import com.example.starter.blend.BlendRepository.ComponentTankRow;
import com.example.starter.blend.BlendRepository.OrderRow;
import com.example.starter.blend.BlendRepository.SpecRow;
import com.example.starter.blend.BlendRepository.TankStateRow;
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

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BlendRepository repository;
    private final BlendCalculator calculator;
    private final IdempotencyExecutor idempotency;
    private final CleaningService cleaningService;
    private final Clock clock;
    private final BlendService self;

    public BlendService(BlendRepository repository, BlendCalculator calculator,
                        IdempotencyExecutor idempotency, CleaningService cleaningService,
                        Clock clock, @Lazy BlendService self) {
        this.repository = repository;
        this.calculator = calculator;
        this.idempotency = idempotency;
        this.cleaningService = cleaningService;
        this.clock = clock;
        this.self = self;
    }

    /**
     * 下达调合单：requestId 幂等；校验罐余量，组分切罐与库存扣减在同一事务内完成；
     * 任一组分不足则整单不下达且库存无变动；同一调合罐同时刻只允许一张执行中调合单；
     * 跨品种切换须先完成清洗确认。逾期清洗先在独立事务内自动释放，避免随本单回滚。
     */
    public OrderResponse createOrder(CreateOrderRequest request) {
        if (request.blendTankId() != null) {
            cleaningService.expireOverdue(request.blendTankId(), LocalDateTime.now(clock));
        }
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
        BlendTankRow blendTank = repository.findBlendTank(request.blendTankId())
                .orElseThrow(() -> BlendException.notFound("调合罐不存在: " + request.blendTankId()));
        LocalDateTime now = LocalDateTime.now(clock);
        // 品种切换清洗控制：逾期清洗先自动释放；清洗占用窗内新单不得下达
        cleaningService.expireOverdue(request.blendTankId(), now);
        cleaningService.assertNoCleaningWindowConflict(request.blendTankId(), now);
        TankStateRow tankState = cleaningService.tankState(request.blendTankId(), now);
        if (CleaningService.TANK_PENDING_CONFIRMATION.equals(tankState.status())) {
            throw BlendException.conflict("调合罐" + blendTank.code()
                    + "清洗逾期未完成，罐状态待确认，重新占用须重新发起清洗");
        }
        String currentProduct = tankState.currentProduct();
        if (currentProduct != null && !currentProduct.equals(request.productCode())
                && !request.productCode().equals(tankState.cleanForProduct())) {
            throw BlendException.unprocessable("跨品种切换未清洗确认，需清洗罐号明细：" + blendTank.code()
                    + "（罐内品种" + currentProduct + "，目标品种" + request.productCode() + "）");
        }
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
        // 登记罐内品种并消费该罐未使用的清洗确认（同事务）
        cleaningService.onOrderCreated(request.blendTankId(), request.productCode(), now);
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

    private String formatTime(LocalDateTime time) {
        return time == null ? null : TIME_FORMAT.format(time);
    }
}
