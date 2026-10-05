package com.example.starter.deicing.service;

import com.example.starter.deicing.db.DeicingFluidPo;
import com.example.starter.deicing.db.DeicingRequestPo;
import com.example.starter.deicing.db.FluidBatchPo;
import com.example.starter.deicing.db.FluidBorrowRepository;
import com.example.starter.deicing.db.FluidRepository;
import com.example.starter.deicing.db.FluidReservationPo;
import com.example.starter.deicing.db.FluidReservationRepository;
import com.example.starter.deicing.domain.DeicingType;
import com.example.starter.deicing.domain.ReservationStatus;
import com.example.starter.deicing.web.ApiException;
import com.example.starter.deicing.web.dto.FluidLedgerView;
import com.example.starter.deicing.web.dto.InboundRequest;
import com.example.starter.deicing.web.dto.InboundView;
import com.example.starter.deicing.web.dto.LedgerResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 除冰液库存与配比管理：两步法受理同事务预占、一步法作业完成同事务结算、
 * 补料入库批次 FEFO 扣减、库存台账复算。
 * 所有库存写操作先锁定 deicing_fluid 行串行化，预占/结算与业务记录同事务。
 */
@Service
public class InventoryService {

    /** 一步法除冰液（合成配比）。 */
    public static final String FLUID_ONE_STEP = "TYPE-I";
    /** 两步法防冰液（合成配比）。 */
    public static final String FLUID_TWO_STEP = "TYPE-II";

    private final FluidRepository fluidRepository;
    private final FluidReservationRepository reservationRepository;
    private final FluidBorrowRepository borrowRepository;
    private final TimeSource timeSource;

    public InventoryService(FluidRepository fluidRepository,
                            FluidReservationRepository reservationRepository,
                            FluidBorrowRepository borrowRepository,
                            TimeSource timeSource) {
        this.fluidRepository = fluidRepository;
        this.reservationRepository = reservationRepository;
        this.borrowRepository = borrowRepository;
        this.timeSource = timeSource;
    }

    /**
     * 两步法申请受理时预占库存：按车位占用时长（作业时长）× 配比，
     * 与受理在同一事务内完成。可用量只计未过期批次并扣除 ACTIVE 预占；
     * 不足（含效期耗尽）抛 {@link InsufficientFluidException}，由调用方转人工确认。
     */
    @Transactional
    public void reserveForTwoStep(String requestId, int durationMinutes) {
        Instant now = timeSource.now();
        DeicingFluidPo fluid = mustLockFluid(FLUID_TWO_STEP);
        BigDecimal need = fluid.getConsumptionPerMinuteLiters()
                .multiply(BigDecimal.valueOf(durationMinutes));
        BigDecimal available = availableForReservation(FLUID_TWO_STEP, now);
        if (available.compareTo(need) < 0) {
            BigDecimal totalRemaining = fluidRepository.sumTotalRemaining(FLUID_TWO_STEP)
                    .subtract(reservationRepository.sumActiveByFluid(FLUID_TWO_STEP));
            String detail;
            if (totalRemaining.compareTo(need) >= 0) {
                detail = "除冰液 " + FLUID_TWO_STEP + " 可用批次效期耗尽：需 " + need
                        + " 升，未过期可用 " + available + " 升";
            } else {
                detail = "除冰液 " + FLUID_TWO_STEP + " 库存不足：需 " + need
                        + " 升，可预占 " + available + " 升";
            }
            throw new InsufficientFluidException(detail);
        }
        FluidReservationPo po = new FluidReservationPo();
        po.setRequestId(requestId);
        po.setFluidCode(FLUID_TWO_STEP);
        po.setQuantityLiters(need);
        po.setStatus(ReservationStatus.ACTIVE);
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        reservationRepository.insert(po);
    }

    /**
     * 作业完成结算，与完成记录（起飞回报）在同一事务内完成：
     * 两步法按预占量转消耗；一步法按实际作业时长（实际开始至起飞回报，向上取整分钟）结算。
     */
    @Transactional
    public void settleOnCompletion(DeicingRequestPo po, Instant actualTakeoffTime) {
        Instant now = timeSource.now();
        if (po.getType() == DeicingType.TWO_STEP) {
            FluidReservationPo reservation = reservationRepository.findByRequestId(po.getRequestId());
            BigDecimal quantity;
            if (reservation != null && reservation.getStatus() == ReservationStatus.ACTIVE) {
                quantity = reservation.getQuantityLiters();
                reservationRepository.transitionActive(po.getRequestId(),
                        ReservationStatus.SETTLED, now);
            } else {
                // 防御：预占缺失时按车位占用时长与配比结算，保证台账完整
                quantity = mustLockFluid(FLUID_TWO_STEP).getConsumptionPerMinuteLiters()
                        .multiply(BigDecimal.valueOf(po.getDurationMinutes()));
            }
            deductAndRecord(po.getRequestId(), FLUID_TWO_STEP, quantity, po.getDurationMinutes(), now);
        } else {
            Instant start = po.getActualStart() != null ? po.getActualStart() : po.getScheduledStart();
            long seconds = Math.max(0, Duration.between(start, actualTakeoffTime).getSeconds());
            int minutes = (int) Math.max(1, (seconds + 59) / 60);
            BigDecimal quantity = mustLockFluid(FLUID_ONE_STEP).getConsumptionPerMinuteLiters()
                    .multiply(BigDecimal.valueOf(minutes));
            deductAndRecord(po.getRequestId(), FLUID_ONE_STEP, quantity, minutes, now);
        }
    }

    /**
     * 释放 ACTIVE 预占（取消等未完成作业的路径），幂等：非 ACTIVE 预占不受影响。
     */
    @Transactional
    public void releaseReservation(String requestId) {
        reservationRepository.transitionActive(requestId, ReservationStatus.RELEASED,
                timeSource.now());
    }

    /**
     * 补料入库：按批次登记效期；效期已过的批次拒绝入库。
     */
    @Transactional
    public InboundView registerInbound(InboundRequest request) {
        Instant now = timeSource.now();
        if (fluidRepository.lockFluid(request.fluidCode()) == null) {
            throw ApiException.badRequest("未知除冰液编码: " + request.fluidCode());
        }
        if (!request.expiryTime().isAfter(now)) {
            throw ApiException.badRequest("批次 " + request.batchNo() + " 效期已过，拒绝入库");
        }
        FluidBatchPo po = new FluidBatchPo();
        po.setBatchNo(request.batchNo());
        po.setFluidCode(request.fluidCode());
        po.setSource("INBOUND");
        po.setQuantityLiters(request.quantityLiters());
        po.setRemainingLiters(request.quantityLiters());
        po.setExpiryTime(request.expiryTime());
        po.setCreatedAt(now);
        try {
            fluidRepository.insertBatch(po);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("批次号 " + request.batchNo() + " 已存在");
        }
        return new InboundView(po.getBatchNo(), po.getFluidCode(), po.getQuantityLiters(),
                po.getExpiryTime(), po.getCreatedAt());
    }

    /**
     * 库存台账：现存 = 期初 + 入库 - 消耗 - 借出（未归还）；可复算、重复查询稳定。
     */
    @Transactional(readOnly = true)
    public LedgerResponse ledger() {
        Instant now = timeSource.now();
        List<FluidLedgerView> fluids = new ArrayList<>();
        for (DeicingFluidPo fluid : fluidRepository.findAllFluids()) {
            String code = fluid.getFluidCode();
            BigDecimal opening = fluid.getOpeningLiters();
            BigDecimal inbound = fluidRepository.sumInbound(code);
            BigDecimal consumed = fluidRepository.sumConsumed(code);
            BigDecimal borrowed = borrowRepository.sumOpenByFluid(code);
            BigDecimal reserved = reservationRepository.sumActiveByFluid(code);
            BigDecimal current = opening.add(inbound).subtract(consumed).subtract(borrowed);
            BigDecimal available = availableForReservation(code, now);
            fluids.add(new FluidLedgerView(code, fluid.getFluidName(), opening, inbound,
                    consumed, borrowed, reserved, current, available));
        }
        return new LedgerResponse(now, fluids);
    }

    /**
     * 可预占量 = 未过期批次剩余合计 - ACTIVE 预占。
     */
    BigDecimal availableForReservation(String fluidCode, Instant now) {
        return fluidRepository.sumUsableRemaining(fluidCode, now)
                .subtract(reservationRepository.sumActiveByFluid(fluidCode));
    }

    /**
     * 物理扣减：先到期先出（FEFO），仅扣未过期批次；可用批次耗尽时差额计入
     * 效期最晚批次（剩余量转负，表示断供透支），保证台账可复算。
     * 调用前须已锁定对应 deicing_fluid 行。
     */
    void deductFefo(String fluidCode, BigDecimal quantity, Instant now) {
        BigDecimal remaining = quantity;
        for (FluidBatchPo batch : fluidRepository.lockUsableBatchesFefo(fluidCode, now)) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal take = batch.getRemainingLiters().min(remaining);
            fluidRepository.updateBatchRemaining(batch.getId(),
                    batch.getRemainingLiters().subtract(take));
            remaining = remaining.subtract(take);
        }
        if (remaining.signum() > 0) {
            FluidBatchPo last = fluidRepository.lockLatestExpiryBatch(fluidCode);
            fluidRepository.updateBatchRemaining(last.getId(),
                    last.getRemainingLiters().subtract(remaining));
        }
    }

    /**
     * 归还回补：计入效期最晚批次。调用前须已锁定对应 deicing_fluid 行。
     */
    void creditBack(String fluidCode, BigDecimal quantity) {
        FluidBatchPo last = fluidRepository.lockLatestExpiryBatch(fluidCode);
        fluidRepository.updateBatchRemaining(last.getId(),
                last.getRemainingLiters().add(quantity));
    }

    DeicingFluidPo mustLockFluid(String fluidCode) {
        DeicingFluidPo fluid = fluidRepository.lockFluid(fluidCode);
        if (fluid == null) {
            throw ApiException.badRequest("未知除冰液编码: " + fluidCode);
        }
        return fluid;
    }

    private void deductAndRecord(String requestId, String fluidCode, BigDecimal quantity,
                                 int jobMinutes, Instant now) {
        mustLockFluid(fluidCode);
        deductFefo(fluidCode, quantity, now);
        fluidRepository.insertConsumption(requestId, fluidCode, quantity, jobMinutes, now);
    }
}
