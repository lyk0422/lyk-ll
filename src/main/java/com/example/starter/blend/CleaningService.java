package com.example.starter.blend;

import com.example.starter.blend.BlendDtos.CleaningOccupancy;
import com.example.starter.blend.BlendDtos.CleaningResponse;
import com.example.starter.blend.BlendDtos.CompleteCleaningRequest;
import com.example.starter.blend.BlendDtos.CreateCleaningRequest;
import com.example.starter.blend.BlendDtos.OrderOccupancy;
import com.example.starter.blend.BlendDtos.TankOccupancyView;
import com.example.starter.blend.BlendRepository.BlendTankRow;
import com.example.starter.blend.BlendRepository.CleaningRow;
import com.example.starter.blend.BlendRepository.LockedCleaningRow;
import com.example.starter.blend.BlendRepository.LockedOrderRow;
import com.example.starter.blend.BlendRepository.TankStateRow;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 品种切换清洗控制服务：清洗单登记、完成确认、逾期自动释放与罐占用视图。
 * 清洗占用时间窗按半开区间[windowStart, windowEnd)登记，端点相接不算冲突；
 * 同罐同时刻只允许一张执行中清洗单（清洗占用锁唯一键保证）；
 * 清洗完成确认与罐状态复位在同一事务内完成；逾期未完成自动释放占用窗并把罐标记为待确认。
 */
@Service
public class CleaningService {

    /** 清洗单状态：执行中。 */
    public static final String CLEANING_EXECUTING = "EXECUTING";
    /** 清洗单状态：已完成确认（终态）。 */
    public static final String CLEANING_COMPLETED = "COMPLETED";
    /** 清洗单状态：逾期未完成，占用窗已自动释放（终态）。 */
    public static final String CLEANING_EXPIRED = "EXPIRED";

    /** 罐状态：正常。 */
    public static final String TANK_NORMAL = "NORMAL";
    /** 罐状态：清洗执行中。 */
    public static final String TANK_CLEANING = "CLEANING";
    /** 罐状态：清洗逾期未完成，待确认；重新占用须重新发起清洗。 */
    public static final String TANK_PENDING_CONFIRMATION = "PENDING_CONFIRMATION";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BlendRepository repository;
    private final IdempotencyExecutor idempotency;
    private final Clock clock;
    private final CleaningService self;

    public CleaningService(BlendRepository repository, IdempotencyExecutor idempotency, Clock clock,
                           @Lazy CleaningService self) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.clock = clock;
        this.self = self;
    }

    /**
     * 发起清洗：requestId 幂等，指纹覆盖 requestId 与全部业务字段；
     * 同键同参重放首次结果，同键异参 409 且无状态变化。
     * 逾期清洗先在独立事务内自动释放，避免随本次请求回滚。
     */
    public CleaningResponse createCleaning(CreateCleaningRequest request) {
        if (request.blendTankId() != null) {
            self.expireOverdue(request.blendTankId(), LocalDateTime.now(clock));
        }
        String fingerprint = idempotency.fingerprint(request.requestId(), "CLEANING_CREATE",
                request.blendTankId(), request.toProduct(), request.windowStart(), request.windowEnd());
        return idempotency.execute("CLEANING_CREATE", request.requestId(), fingerprint,
                CleaningResponse.class, () -> self.doCreateCleaning(request));
    }

    /**
     * 清洗完成确认：requestId 幂等；确认与罐状态复位在同一事务内完成。
     * 逾期清洗先在独立事务内自动释放，逾期单不得再确认。
     */
    public CleaningResponse completeCleaning(long cleaningId, CompleteCleaningRequest request) {
        repository.findCleaning(cleaningId)
                .ifPresent(c -> self.expireOverdue(c.blendTankId(), LocalDateTime.now(clock)));
        String fingerprint = idempotency.fingerprint(request.requestId(), "CLEANING_COMPLETE", cleaningId);
        return idempotency.execute("CLEANING_COMPLETE", request.requestId(), fingerprint,
                CleaningResponse.class, () -> self.doCompleteCleaning(cleaningId, request));
    }

    /**
     * 清洗发起事务体：校验时间窗与调合单占用冲突，登记清洗单并占用清洗锁，罐转清洗中。
     */
    @Transactional
    public CleaningResponse doCreateCleaning(CreateCleaningRequest request) {
        if (!request.windowEnd().isAfter(request.windowStart())) {
            throw BlendException.unprocessable("清洗时间窗终点必须晚于起点（半开区间）");
        }
        BlendTankRow tank = repository.findBlendTank(request.blendTankId())
                .orElseThrow(() -> BlendException.notFound("调合罐不存在: " + request.blendTankId()));
        repository.findSpec(request.toProduct())
                .orElseThrow(() -> BlendException.notFound("成品规格不存在: " + request.toProduct()));
        LocalDateTime now = LocalDateTime.now(clock);
        // 逾期清洗先自动释放，再登记新窗
        expireOverdue(tank.id(), now);
        if (repository.countOrderWindowConflicts(tank.id(), request.windowStart(), request.windowEnd()) > 0) {
            throw BlendException.conflict("调合罐" + tank.code() + "清洗时间窗与调合单占用冲突，不得登记");
        }
        TankStateRow state = repository.ensureTankState(tank.id(), now);
        long cleaningId = repository.insertCleaning(request.requestId(), tank.id(), state.currentProduct(),
                request.toProduct(), request.windowStart(), request.windowEnd(), CLEANING_EXECUTING, now);
        try {
            repository.acquireCleaningLock(tank.id(), cleaningId, now);
        } catch (DuplicateKeyException e) {
            throw BlendException.conflict("调合罐" + tank.code() + "存在执行中清洗单，同罐同时刻只允许一张");
        }
        repository.updateTankState(tank.id(), state.currentProduct(), null, TANK_CLEANING, now);
        return toResponse(repository.findCleaning(cleaningId).orElseThrow());
    }

    /**
     * 清洗完成确认事务体：状态流转、锁释放与罐状态复位在同一事务内完成；
     * 确认前先做逾期检查，逾期单不得再确认。
     */
    @Transactional
    public CleaningResponse doCompleteCleaning(long cleaningId, CompleteCleaningRequest request) {
        CleaningRow cleaning = repository.findCleaningForUpdate(cleaningId)
                .orElseThrow(() -> BlendException.notFound("清洗单不存在: " + cleaningId));
        LocalDateTime now = LocalDateTime.now(clock);
        expireOverdue(cleaning.blendTankId(), now);
        CleaningRow current = repository.findCleaning(cleaningId).orElseThrow();
        if (CLEANING_EXPIRED.equals(current.status())) {
            throw BlendException.conflict("清洗单已逾期，占用窗自动释放且罐标记待确认，需重新发起清洗");
        }
        if (CLEANING_COMPLETED.equals(current.status())) {
            throw BlendException.conflict("清洗单已完成确认，重复确认被拒绝");
        }
        repository.updateCleaningStatus(cleaningId, CLEANING_COMPLETED, now);
        repository.releaseCleaningLock(current.blendTankId());
        TankStateRow state = repository.ensureTankState(current.blendTankId(), now);
        // 罐状态复位：清洗确认登记目标品种，罐内品种保持历史记录供切换校验
        repository.updateTankState(current.blendTankId(), state.currentProduct(),
                current.toProduct(), TANK_NORMAL, now);
        return toResponse(repository.findCleaning(cleaningId).orElseThrow());
    }

    /**
     * 逾期清洗自动释放：占用窗终点不晚于当前时间仍未完成的清洗单，
     * 释放占用窗并把罐标记为待确认；重新占用须重新发起清洗。
     */
    @Transactional
    public void expireOverdue(long blendTankId, LocalDateTime now) {
        for (CleaningRow row : repository.findOverdueCleanings(blendTankId, now)) {
            expireCleaning(row, now);
        }
    }

    /**
     * 新单下达前校验：罐被清洗占用窗覆盖（半开区间，now &lt; windowEnd）时拒绝。
     */
    @Transactional
    public void assertNoCleaningWindowConflict(long blendTankId, LocalDateTime now) {
        Optional<CleaningRow> executing = repository.findExecutingCleaning(blendTankId);
        if (executing.isPresent() && now.isBefore(executing.get().windowEnd())) {
            CleaningRow cleaning = executing.get();
            throw BlendException.conflict("调合罐清洗占用窗[" + formatTime(cleaning.windowStart()) + ", "
                    + formatTime(cleaning.windowEnd()) + ")与调合单占用冲突，新单不得下达");
        }
    }

    /**
     * 读取罐品种状态（必要时初始化为NORMAL空罐）。
     */
    @Transactional
    public TankStateRow tankState(long blendTankId, LocalDateTime now) {
        return repository.ensureTankState(blendTankId, now);
    }

    /**
     * 调合单下达成功后登记罐内品种并消费该罐未使用的清洗确认（同事务）。
     */
    @Transactional
    public void onOrderCreated(long blendTankId, String productCode, LocalDateTime now) {
        repository.updateTankState(blendTankId, productCode, null, TANK_NORMAL, now);
        repository.consumeCleanConfirmations(blendTankId, now);
    }

    /**
     * 罐占用视图：先全局自动释放逾期清洗，再汇总罐状态、执行中调合单占用与清洗占用窗。
     */
    @Transactional
    public List<TankOccupancyView> occupancyView() {
        LocalDateTime now = LocalDateTime.now(clock);
        for (CleaningRow row : repository.findAllOverdueCleanings(now)) {
            expireCleaning(row, now);
        }
        Map<Long, TankStateRow> states = new HashMap<>();
        for (TankStateRow state : repository.findAllTankStates()) {
            states.put(state.blendTankId(), state);
        }
        Map<Long, LockedOrderRow> orders = new HashMap<>();
        for (LockedOrderRow row : repository.findLockedOrders()) {
            orders.put(row.blendTankId(), row);
        }
        Map<Long, LockedCleaningRow> cleanings = new HashMap<>();
        for (LockedCleaningRow row : repository.findLockedCleanings()) {
            cleanings.put(row.blendTankId(), row);
        }
        List<TankOccupancyView> views = new ArrayList<>();
        for (BlendTankRow tank : repository.findBlendTanks()) {
            TankStateRow state = states.get(tank.id());
            LockedOrderRow lockedOrder = orders.get(tank.id());
            LockedCleaningRow lockedCleaning = cleanings.get(tank.id());
            OrderOccupancy orderOccupancy = null;
            if (lockedOrder != null) {
                orderOccupancy = new OrderOccupancy(lockedOrder.order().id(), lockedOrder.order().orderNo(),
                        lockedOrder.order().productCode(), formatTime(lockedOrder.order().createdAt()), null);
            }
            CleaningOccupancy cleaningOccupancy = null;
            if (lockedCleaning != null) {
                CleaningRow c = lockedCleaning.cleaning();
                cleaningOccupancy = new CleaningOccupancy(c.id(), c.fromProduct(), c.toProduct(),
                        formatTime(c.windowStart()), formatTime(c.windowEnd()));
            }
            views.add(new TankOccupancyView(tank.id(), tank.code(),
                    state == null ? TANK_NORMAL : state.status(),
                    state == null ? null : state.currentProduct(),
                    state == null ? null : state.cleanForProduct(),
                    orderOccupancy, cleaningOccupancy));
        }
        return views;
    }

    private void expireCleaning(CleaningRow row, LocalDateTime now) {
        repository.updateCleaningStatus(row.id(), CLEANING_EXPIRED, null);
        repository.releaseCleaningLock(row.blendTankId());
        TankStateRow state = repository.ensureTankState(row.blendTankId(), now);
        repository.updateTankState(row.blendTankId(), state.currentProduct(), null,
                TANK_PENDING_CONFIRMATION, now);
    }

    private CleaningResponse toResponse(CleaningRow row) {
        return new CleaningResponse(row.id(), row.blendTankId(), row.fromProduct(), row.toProduct(),
                row.status(), formatTime(row.windowStart()), formatTime(row.windowEnd()),
                formatTime(row.completedAt()));
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? null : TIME_FORMAT.format(time);
    }
}
