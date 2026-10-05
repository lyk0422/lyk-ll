package com.example.starter.deicing.service;

import com.example.starter.deicing.db.BorrowOrderPo;
import com.example.starter.deicing.db.FluidBatchPo;
import com.example.starter.deicing.db.InventoryRepository;
import com.example.starter.deicing.db.InventoryRepository.MixtureRatio;
import com.example.starter.deicing.db.InventoryReservationPo;
import com.example.starter.deicing.db.LatchRepository;
import com.example.starter.deicing.domain.BorrowStatus;
import com.example.starter.deicing.domain.DeicingType;
import com.example.starter.deicing.domain.ReservationStatus;
import com.example.starter.deicing.web.ApiException;
import com.example.starter.deicing.web.dto.BatchView;
import com.example.starter.deicing.web.dto.BorrowOrderView;
import com.example.starter.deicing.web.dto.BorrowRequest;
import com.example.starter.deicing.web.dto.BorrowResponse;
import com.example.starter.deicing.web.dto.FluidLedgerView;
import com.example.starter.deicing.web.dto.InboundRequest;
import com.example.starter.deicing.web.dto.LedgerResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 除冰液库存与配比管理：两步法受理预占、一步法作业结算、跨班组借用与台账复算。
 * 预占/结算/借用均由全局库存闩锁串行化；预占量 = 所需车位数 × 作业分钟 × 配比（升）。
 */
@Service
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final LatchRepository latchRepository;
    private final TimeSource timeSource;

    public InventoryService(InventoryRepository inventoryRepository,
                            LatchRepository latchRepository,
                            TimeSource timeSource) {
        this.inventoryRepository = inventoryRepository;
        this.latchRepository = latchRepository;
        this.timeSource = timeSource;
    }

    // ---- 预占（两步法受理/人工确认，在调用方事务内执行） ------------------------

    /**
     * 尝试为两步法申请预占库存：库存闩锁内检查并登记，与受理同事务。
     *
     * @return true=预占成功；false=库存不足（调用方转人工确认队列，不得静默降级）
     */
    public boolean tryReserve(String requestId, int requiredBays, int durationMinutes) {
        List<MixtureRatio> mixture = mixtureOf(DeicingType.TWO_STEP);
        Instant now = timeSource.now();
        latchRepository.lockInventory();
        for (MixtureRatio ratio : mixture) {
            long required = requiredLiters(ratio, requiredBays, durationMinutes);
            if (reservableLiters(ratio.fluidCode(), now) < required) {
                return false;
            }
        }
        for (MixtureRatio ratio : mixture) {
            InventoryReservationPo po = new InventoryReservationPo();
            po.setRequestId(requestId);
            po.setFluidCode(ratio.fluidCode());
            po.setQuantity((int) requiredLiters(ratio, requiredBays, durationMinutes));
            po.setStatus(ReservationStatus.ACTIVE);
            po.setCreatedAt(now);
            po.setUpdatedAt(now);
            inventoryRepository.insertReservation(po);
        }
        return true;
    }

    /**
     * 库存不足提示：含已效期耗尽批次量，供人工确认失败时返回。
     */
    public String insufficientHint(int requiredBays, int durationMinutes) {
        Instant now = timeSource.now();
        List<String> parts = new ArrayList<>();
        for (MixtureRatio ratio : mixtureOf(DeicingType.TWO_STEP)) {
            long required = requiredLiters(ratio, requiredBays, durationMinutes);
            long reservable = reservableLiters(ratio.fluidCode(), now);
            if (reservable < required) {
                int expired = inventoryRepository.sumExpiredAvailable(ratio.fluidCode(), now);
                parts.add(ratio.fluidCode() + " 需 " + required + " 升、可预占 " + reservable
                        + " 升" + (expired > 0 ? "（另有 " + expired + " 升已效期耗尽，不可用）" : ""));
            }
        }
        return parts.isEmpty() ? "库存不足" : String.join("；", parts);
    }

    // ---- 结算与预占流转（在调用方事务内执行） ------------------------------------

    /**
     * 一步法作业结算：按实际作业时长 × 车位数 × 配比 FEFO 扣减批次并登记消耗台账，
     * 与作业完成记录在同一事务内完成；库存不足则整体回滚。
     */
    public void settleOneStep(String requestId, int requiredBays, long actualMinutes) {
        Instant now = timeSource.now();
        latchRepository.lockInventory();
        for (MixtureRatio ratio : mixtureOf(DeicingType.ONE_STEP)) {
            long required = ratio.litersPerMinute() * actualMinutes * requiredBays;
            deductFefo(requestId, ratio.fluidCode(), required, now);
        }
    }

    /**
     * 两步法预占转消耗：作业完成/起飞判定/作业开始后取消时调用，
     * 按预占量 FEFO 扣减批次并登记消耗台账。无生效预占时为空操作。
     */
    public void consumeReservations(String requestId) {
        Instant now = timeSource.now();
        latchRepository.lockInventory();
        List<InventoryReservationPo> actives =
                inventoryRepository.findActiveReservationsByRequest(requestId);
        for (InventoryReservationPo reservation : actives) {
            deductFefo(requestId, reservation.getFluidCode(), reservation.getQuantity(), now);
            inventoryRepository.updateReservationStatus(
                    reservation.getId(), ReservationStatus.CONSUMED, now);
        }
    }

    /**
     * 释放生效中的预占（作业开始前取消）。无生效预占时为空操作。
     */
    public void releaseReservations(String requestId) {
        Instant now = timeSource.now();
        latchRepository.lockInventory();
        for (InventoryReservationPo reservation
                : inventoryRepository.findActiveReservationsByRequest(requestId)) {
            inventoryRepository.updateReservationStatus(
                    reservation.getId(), ReservationStatus.RELEASED, now);
        }
    }

    // ---- 补料入库 --------------------------------------------------------------

    /**
     * 补料入库：按批次登记效期；批次号重复拒绝（409），未知除冰液拒绝（400）。
     */
    @Transactional
    public BatchView inbound(InboundRequest request) {
        if (!inventoryRepository.fluidExists(request.fluidCode())) {
            throw ApiException.badRequest("未知除冰液编码: " + request.fluidCode());
        }
        FluidBatchPo po = new FluidBatchPo();
        po.setBatchNo(request.batchNo());
        po.setFluidCode(request.fluidCode());
        po.setQuantityIn(request.quantity());
        po.setQuantityAvailable(request.quantity());
        po.setExpiryTime(request.expiryTime());
        po.setCreatedAt(timeSource.now());
        try {
            inventoryRepository.insertBatch(po);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("批次号 " + request.batchNo() + " 已登记，拒绝重复入库");
        }
        FluidBatchPo stored = inventoryRepository.findBatchByBatchNo(request.batchNo());
        return toBatchView(stored);
    }

    // ---- 跨班组借用 --------------------------------------------------------------

    /**
     * 借用登记：requestId 幂等（同键同参返回首次结果，同键异参 409）；
     * 同一液罐同时刻仅允许一张未归还借用单；借出量不得超过可预占量。
     */
    @Transactional
    public BorrowResponse borrow(BorrowRequest request) {
        String fingerprint = Fingerprints.sha256Borrow(request.requestId(), request.tankId(),
                request.fluidCode(), request.quantity(), request.fromTeam(), request.toTeam());

        BorrowOrderPo existing = inventoryRepository.findBorrowByRequestId(request.requestId());
        if (existing != null) {
            return borrowReplayOrConflict(existing, fingerprint);
        }
        if (!inventoryRepository.fluidExists(request.fluidCode())) {
            throw ApiException.badRequest("未知除冰液编码: " + request.fluidCode());
        }

        Instant now = timeSource.now();
        latchRepository.lockInventory();
        existing = inventoryRepository.findBorrowByRequestId(request.requestId());
        if (existing != null) {
            return borrowReplayOrConflict(existing, fingerprint);
        }
        try {
            inventoryRepository.insertTankOpen(request.tankId(), request.requestId(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("液罐 " + request.tankId()
                    + " 已存在未归还借用单，同一液罐同时刻仅允许一张未归还借用单");
        }
        long reservable = reservableLiters(request.fluidCode(), now);
        if (reservable < request.quantity()) {
            throw ApiException.conflict("除冰液 " + request.fluidCode() + " 可借出量 "
                    + reservable + " 升，不足借出 " + request.quantity() + " 升");
        }

        BorrowOrderPo po = new BorrowOrderPo();
        po.setRequestId(request.requestId());
        po.setTankId(request.tankId());
        po.setFluidCode(request.fluidCode());
        po.setQuantity(request.quantity());
        po.setFromTeam(request.fromTeam());
        po.setToTeam(request.toTeam());
        po.setStatus(BorrowStatus.OPEN);
        po.setFingerprint(fingerprint);
        po.setCreatedAt(now);
        try {
            inventoryRepository.insertBorrow(po);
        } catch (DuplicateKeyException e) {
            BorrowOrderPo raced = inventoryRepository.findBorrowByRequestId(request.requestId());
            if (raced != null) {
                return borrowReplayOrConflict(raced, fingerprint);
            }
            throw e;
        }
        return new BorrowResponse(toBorrowView(
                inventoryRepository.findBorrowByRequestId(request.requestId())), false);
    }

    /**
     * 归还登记：按 requestId 幂等，重复归还返回首次结果。
     */
    @Transactional
    public BorrowResponse returnBorrow(String requestId) {
        BorrowOrderPo po = inventoryRepository.findBorrowByRequestId(requestId);
        if (po == null) {
            throw ApiException.notFound("借用单不存在: " + requestId);
        }
        if (po.getStatus() == BorrowStatus.RETURNED) {
            return new BorrowResponse(toBorrowView(po), true);
        }
        Instant now = timeSource.now();
        latchRepository.lockInventory();
        inventoryRepository.markBorrowReturned(requestId, now);
        inventoryRepository.deleteTankOpen(po.getTankId());
        return new BorrowResponse(toBorrowView(
                inventoryRepository.findBorrowByRequestId(requestId)), false);
    }

    // ---- 库存台账 --------------------------------------------------------------

    /**
     * 库存台账：现存量 = 期初 + 入库 - 消耗 - 借出（未归还），按除冰液编码排序，重复查询稳定。
     */
    @Transactional(readOnly = true)
    public LedgerResponse ledger() {
        Instant now = timeSource.now();
        List<FluidLedgerView> fluids = new ArrayList<>();
        for (String fluidCode : inventoryRepository.findAllFluidCodes()) {
            int opening = 0;
            int inbound = inventoryRepository.sumInbound(fluidCode);
            int consumed = inventoryRepository.sumConsumption(fluidCode);
            int lentOut = inventoryRepository.sumOpenBorrow(fluidCode);
            int reserved = inventoryRepository.sumActiveReservation(fluidCode);
            fluids.add(new FluidLedgerView(
                    fluidCode,
                    opening,
                    inbound,
                    consumed,
                    lentOut,
                    opening + inbound - consumed - lentOut,
                    reserved,
                    (int) reservableLiters(fluidCode, now)));
        }
        return new LedgerResponse(now, fluids);
    }

    // ---- 内部辅助 --------------------------------------------------------------

    private List<MixtureRatio> mixtureOf(DeicingType type) {
        List<MixtureRatio> mixture = inventoryRepository.findMixture(type.name());
        if (mixture.isEmpty()) {
            throw ApiException.badRequest("配比表缺少工艺 " + type.name() + " 的单位作业消耗");
        }
        return mixture;
    }

    private static long requiredLiters(MixtureRatio ratio, int requiredBays, long minutes) {
        return (long) ratio.litersPerMinute() * minutes * requiredBays;
    }

    /**
     * 可预占量 = 未到期批次剩余 - 未归还借出 - 生效预占。效期耗尽批次不参与。
     */
    private long reservableLiters(String fluidCode, Instant now) {
        return inventoryRepository.sumAvailableNonExpired(fluidCode, now)
                - inventoryRepository.sumOpenBorrow(fluidCode)
                - inventoryRepository.sumActiveReservation(fluidCode);
    }

    /**
     * FEFO 扣减：先到期先出，批次扣减与消耗台账登记同事务；不足则抛错整体回滚。
     */
    private void deductFefo(String requestId, String fluidCode, long quantity, Instant now) {
        long remaining = quantity;
        for (FluidBatchPo batch : inventoryRepository.findConsumableBatches(fluidCode, now)) {
            if (remaining == 0) {
                break;
            }
            int take = (int) Math.min(batch.getQuantityAvailable(), remaining);
            if (take <= 0) {
                continue;
            }
            int updated = inventoryRepository.decreaseBatch(batch.getId(), take);
            if (updated != 1) {
                throw ApiException.conflict("批次 " + batch.getBatchNo()
                        + " 剩余量不足，库存扣减冲突");
            }
            inventoryRepository.insertConsumption(requestId, fluidCode, batch.getId(), take, now);
            remaining -= take;
        }
        if (remaining > 0) {
            int expired = inventoryRepository.sumExpiredAvailable(fluidCode, now);
            throw ApiException.conflict("除冰液 " + fluidCode + " 可用库存不足，缺口 " + remaining
                    + " 升" + (expired > 0 ? "（另有 " + expired + " 升已效期耗尽，不可用）" : ""));
        }
    }

    private BorrowResponse borrowReplayOrConflict(BorrowOrderPo existing, String fingerprint) {
        if (!existing.getFingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId " + existing.getRequestId()
                    + " 已存在但业务参数不一致，拒绝覆盖");
        }
        return new BorrowResponse(toBorrowView(existing), true);
    }

    private static BatchView toBatchView(FluidBatchPo po) {
        return new BatchView(po.getBatchNo(), po.getFluidCode(), po.getQuantityIn(),
                po.getQuantityAvailable(), po.getExpiryTime());
    }

    private static BorrowOrderView toBorrowView(BorrowOrderPo po) {
        return new BorrowOrderView(po.getRequestId(), po.getTankId(), po.getFluidCode(),
                po.getQuantity(), po.getFromTeam(), po.getToTeam(), po.getStatus(),
                po.getCreatedAt(), po.getReturnedAt());
    }
}
