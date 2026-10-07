package com.example.starter.tank;

import com.example.starter.idempotent.IdempotencyService;
import com.example.starter.idempotent.ReplayResult;
import com.example.starter.web.BusinessRejectedException;
import com.example.starter.web.ResourceNotFoundException;
import com.example.starter.web.VersionConflictException;
import com.example.starter.web.dto.TankTransferApplyRequest;
import com.example.starter.web.dto.TankTransferApplyResponse;
import com.example.starter.web.dto.TankTransferCancelRequest;
import com.example.starter.web.dto.TankTransferCancelResponse;
import com.example.starter.web.dto.TankTransferConfirmRequest;
import com.example.starter.web.dto.TankTransferConfirmResponse;
import com.example.starter.web.dto.TankTransferRecomputeView;
import com.example.starter.web.dto.TankTransferView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 跨罐调拨：同品种两罐间调拨加臭剂，源罐扣了但目标罐装不下就不算成功。
 *
 * <ul>
 *   <li>申请：两罐须不同且同品种（均已登记不可变品种号），调拨量为正 kg、至多三位小数，
 *       超精度拒绝而非舍入；源可用量扣调拨预占后不得低于安全储备（恰等允许），
 *       目标余额加全部接收预占不得超容量（恰满允许）；任一侧不满足则全单零预占；</li>
 *   <li>原子性：两罐按 ID 升序行锁串行裁决，源预占与目标空间预占同事务，
 *       与原加注计划、补液共享两侧约束（补液不得占用调拨接收预占空间，
 *       未入账接收预占不计入源可用量）；</li>
 *   <li>执行：两名不同交接人（申请时登记）确认实际量等于申请量及合成凭据，
 *       同事务扣源余额、加目标余额并释放双侧预占；运输批号全局唯一，
 *       不能以另一个 requestId 再次入账；</li>
 *   <li>撤销：仅未执行可撤销，释放双侧预占且仅一次；已执行不得撤回；
 *       撤销与最后确认竞争经调拨单行锁串行，只能全撤销或全调拨；</li>
 *   <li>守恒：双侧台账使用同一调拨单标识，合计变化为 0；
 *       可从调拨单复算双侧预占、余额及调拨流量；</li>
 *   <li>版本：调拨单版本从 1 开始，确认/撤销携带 expectedVersion、成功加 1、旧版 409；
 *       计量、双侧台账、版本与幂等结果同事务提交，失败回滚不占幂等键。</li>
 * </ul>
 */
@Service
public class TankTransferService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final TankRepository tankRepository;
    private final TankTransferRepository transferRepository;
    private final TankLedgerRepository ledgerRepository;
    private final IdempotencyService idempotencyService;

    public TankTransferService(TankRepository tankRepository,
                               TankTransferRepository transferRepository,
                               TankLedgerRepository ledgerRepository,
                               IdempotencyService idempotencyService) {
        this.tankRepository = tankRepository;
        this.transferRepository = transferRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyService = idempotencyService;
    }

    // ---------------------------------------------------------------- 申请

    public TankTransferApplyResponse apply(TankTransferApplyRequest request) {
        if (request.sourceTankCode().equals(request.targetTankCode())) {
            throw new BusinessRejectedException(
                    "transfer requires two different tanks: " + request.sourceTankCode());
        }
        Tank source = tankRepository.findByCode(request.sourceTankCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "tank not found: " + request.sourceTankCode()));
        tankRepository.findByCode(request.targetTankCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "tank not found: " + request.targetTankCode()));
        ReplayResult<TankTransferApplyResponse> replay = idempotencyService.run(
                request.requestId(), source.stationId(), "TANK_TRANSFER_APPLY", request, 200,
                () -> doApply(request), TankTransferApplyResponse.class);
        return replay.result();
    }

    private TankTransferApplyResponse doApply(TankTransferApplyRequest request) {
        BigDecimal quantity = requireScale3(request.quantityKg(), "quantityKg");
        String firstOperator = request.operators().get(0);
        String secondOperator = request.operators().get(1);
        if (firstOperator.equals(secondOperator)) {
            throw new BusinessRejectedException(
                    "transfer requires two different handover operators");
        }

        Tank source = tankRepository.findByCode(request.sourceTankCode()).orElseThrow();
        Tank target = tankRepository.findByCode(request.targetTankCode()).orElseThrow();
        Tank[] locked = lockBoth(source.id(), target.id());
        source = locked[0];
        target = locked[1];
        if (source.varietyNo() == null || target.varietyNo() == null) {
            throw new BusinessRejectedException(
                    "both tanks must be registered with an immutable variety number for transfer");
        }
        if (!source.varietyNo().equals(target.varietyNo())
                || !source.varietyNo().equals(request.varietyNo())) {
            throw new BusinessRejectedException(
                    "variety mismatch: request=" + request.varietyNo()
                            + ", source=" + source.varietyNo() + ", target=" + target.varietyNo());
        }

        // 源侧：可用量扣调拨预占后不得低于安全储备，恰等允许
        if (source.availableKg().subtract(quantity).compareTo(source.safetyReserveKg()) < 0) {
            throw new BusinessRejectedException(
                    "source available would fall below safety reserve: "
                            + source.availableKg().subtract(quantity).toPlainString()
                            + " < " + source.safetyReserveKg().toPlainString());
        }
        // 目标侧：余额加全部接收预占（含本单）不得超容量，恰满允许
        BigDecimal targetOccupied = target.balanceKg().add(target.incomingReservedKg()).add(quantity);
        if (targetOccupied.compareTo(target.capacityKg()) > 0) {
            throw new BusinessRejectedException(
                    "target balance plus incoming reservations would exceed capacity: "
                            + targetOccupied.toPlainString() + " > "
                            + target.capacityKg().toPlainString());
        }

        LocalDateTime appliedAt = utc(request.appliedAt());
        Long transferId;
        try {
            transferId = transferRepository.insert(request.requestId(), source.id(), target.id(),
                    request.varietyNo(), quantity, request.transportBatchNo(),
                    firstOperator, secondOperator, appliedAt);
        } catch (DuplicateKeyException duplicate) {
            throw new BusinessRejectedException(
                    "transport batch already posted: " + request.transportBatchNo());
        }

        // 原子预占：源数量 + 目标空间，同一事务；任一侧失败整体回滚，全单零预占
        BigDecimal sourceReservedAfter = source.reservedKg().add(quantity);
        long sourceVersion = bumpTank(source, source.balanceKg(), sourceReservedAfter,
                source.incomingReservedKg());
        BigDecimal targetIncomingAfter = target.incomingReservedKg().add(quantity);
        long targetVersion = bumpTank(target, target.balanceKg(), target.reservedKg(),
                targetIncomingAfter);

        ledgerRepository.insert(source.id(), "TRANSFER_RESERVE", ZERO, quantity, ZERO,
                source.balanceKg(), sourceReservedAfter, source.incomingReservedKg(),
                "TRANSFER", transferId, request.requestId(), appliedAt);
        ledgerRepository.insert(target.id(), "TRANSFER_IN_RESERVE", ZERO, ZERO, quantity,
                target.balanceKg(), target.reservedKg(), targetIncomingAfter,
                "TRANSFER", transferId, request.requestId(), appliedAt);

        return new TankTransferApplyResponse(transferId, source.id(), target.id(),
                request.varietyNo(), quantity, request.transportBatchNo(), "RESERVED",
                sourceReservedAfter, targetIncomingAfter, sourceVersion, targetVersion, 1L, false);
    }

    // ---------------------------------------------------------------- 执行确认

    public TankTransferConfirmResponse confirm(TankTransferConfirmRequest request) {
        TankTransfer transfer = transferRepository.findById(request.transferId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "transfer not found: " + request.transferId()));
        Tank source = tankRepository.findById(transfer.sourceTankId()).orElseThrow();
        ReplayResult<TankTransferConfirmResponse> replay = idempotencyService.run(
                request.requestId(), source.stationId(), "TANK_TRANSFER_CONFIRM", request, 200,
                () -> doConfirm(request), TankTransferConfirmResponse.class);
        return replay.result();
    }

    private TankTransferConfirmResponse doConfirm(TankTransferConfirmRequest request) {
        BigDecimal actual = requireScale3(request.actualQtyKg(), "actualQtyKg");
        TankTransfer transfer = transferRepository.findByIdForUpdate(request.transferId())
                .orElseThrow();

        switch (transfer.status()) {
            case "EXECUTED" -> {
                // 重复确认（同量同凭据）：返回当前状态，不重复计量、不变更版本
                if (actual.compareTo(transfer.actualQtyKg()) == 0
                        && request.credentialNo().equals(transfer.credentialNo())) {
                    Tank source = tankRepository.findById(transfer.sourceTankId()).orElseThrow();
                    Tank target = tankRepository.findById(transfer.targetTankId()).orElseThrow();
                    return new TankTransferConfirmResponse(transfer.id(), "EXECUTED",
                            transfer.actualQtyKg(), transfer.credentialNo(),
                            source.balanceKg(), target.balanceKg(), source.version(),
                            target.version(), transfer.version(), false);
                }
                throw new BusinessRejectedException(
                        "transfer already executed with quantity "
                                + transfer.actualQtyKg().toPlainString());
            }
            case "CANCELLED" -> throw new BusinessRejectedException(
                    "transfer already cancelled, cannot confirm");
            default -> {
                // RESERVED：版本裁决后同事务扣源余额、加目标余额并释放双侧预占
                checkVersion(transfer, request.expectedVersion());
                if (actual.compareTo(transfer.quantityKg()) != 0) {
                    throw new BusinessRejectedException(
                            "actual quantity must equal applied quantity: "
                                    + actual.toPlainString() + " != "
                                    + transfer.quantityKg().toPlainString());
                }
                Tank[] locked = lockBoth(transfer.sourceTankId(), transfer.targetTankId());
                Tank source = locked[0];
                Tank target = locked[1];
                BigDecimal qty = transfer.quantityKg();

                BigDecimal sourceBalanceAfter = source.balanceKg().subtract(qty);
                BigDecimal sourceReservedAfter = source.reservedKg().subtract(qty);
                long sourceVersion = bumpTank(source, sourceBalanceAfter, sourceReservedAfter,
                        source.incomingReservedKg());
                BigDecimal targetBalanceAfter = target.balanceKg().add(qty);
                BigDecimal targetIncomingAfter = target.incomingReservedKg().subtract(qty);
                long targetVersion = bumpTank(target, targetBalanceAfter, target.reservedKg(),
                        targetIncomingAfter);

                long newVersion = transfer.version() + 1;
                int updated = transferRepository.markExecuted(transfer.id(), actual,
                        request.credentialNo(), utc(request.confirmedAt()), newVersion,
                        transfer.version());
                if (updated != 1) {
                    throw new VersionConflictException("transfer " + transfer.id()
                            + " concurrent modification detected");
                }
                LocalDateTime executedAt = utc(request.confirmedAt());
                ledgerRepository.insert(source.id(), "TRANSFER_OUT", qty.negate(), qty.negate(),
                        ZERO, sourceBalanceAfter, sourceReservedAfter, source.incomingReservedKg(),
                        "TRANSFER", transfer.id(), request.requestId(), executedAt);
                ledgerRepository.insert(target.id(), "TRANSFER_IN", qty, ZERO, qty.negate(),
                        targetBalanceAfter, target.reservedKg(), targetIncomingAfter,
                        "TRANSFER", transfer.id(), request.requestId(), executedAt);
                return new TankTransferConfirmResponse(transfer.id(), "EXECUTED", actual,
                        request.credentialNo(), sourceBalanceAfter, targetBalanceAfter,
                        sourceVersion, targetVersion, newVersion, false);
            }
        }
    }

    // ---------------------------------------------------------------- 撤销

    public TankTransferCancelResponse cancel(TankTransferCancelRequest request) {
        TankTransfer transfer = transferRepository.findById(request.transferId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "transfer not found: " + request.transferId()));
        Tank source = tankRepository.findById(transfer.sourceTankId()).orElseThrow();
        ReplayResult<TankTransferCancelResponse> replay = idempotencyService.run(
                request.requestId(), source.stationId(), "TANK_TRANSFER_CANCEL", request, 200,
                () -> doCancel(request), TankTransferCancelResponse.class);
        return replay.result();
    }

    private TankTransferCancelResponse doCancel(TankTransferCancelRequest request) {
        TankTransfer transfer = transferRepository.findByIdForUpdate(request.transferId())
                .orElseThrow();

        switch (transfer.status()) {
            case "CANCELLED" -> {
                // 重复撤销：预占已释放，返回当前状态，不重复返还
                return new TankTransferCancelResponse(transfer.id(), "CANCELLED", ZERO,
                        transfer.version(), false);
            }
            case "EXECUTED" -> throw new BusinessRejectedException(
                    "transfer already executed, cannot cancel");
            default -> {
                // RESERVED：只释放尚未执行的双侧预占，且仅一次
                checkVersion(transfer, request.expectedVersion());
                Tank[] locked = lockBoth(transfer.sourceTankId(), transfer.targetTankId());
                Tank source = locked[0];
                Tank target = locked[1];
                BigDecimal qty = transfer.quantityKg();

                BigDecimal sourceReservedAfter = source.reservedKg().subtract(qty);
                bumpTank(source, source.balanceKg(), sourceReservedAfter,
                        source.incomingReservedKg());
                BigDecimal targetIncomingAfter = target.incomingReservedKg().subtract(qty);
                bumpTank(target, target.balanceKg(), target.reservedKg(), targetIncomingAfter);

                long newVersion = transfer.version() + 1;
                int updated = transferRepository.markCancelled(transfer.id(),
                        utc(request.cancelledAt()), newVersion, transfer.version());
                if (updated != 1) {
                    throw new VersionConflictException("transfer " + transfer.id()
                            + " concurrent modification detected");
                }
                LocalDateTime cancelledAt = utc(request.cancelledAt());
                ledgerRepository.insert(source.id(), "TRANSFER_RELEASE", ZERO, qty.negate(), ZERO,
                        source.balanceKg(), sourceReservedAfter, source.incomingReservedKg(),
                        "TRANSFER", transfer.id(), request.requestId(), cancelledAt);
                ledgerRepository.insert(target.id(), "TRANSFER_IN_RELEASE", ZERO, ZERO,
                        qty.negate(), target.balanceKg(), target.reservedKg(), targetIncomingAfter,
                        "TRANSFER", transfer.id(), request.requestId(), cancelledAt);
                return new TankTransferCancelResponse(transfer.id(), "CANCELLED", qty,
                        newVersion, false);
            }
        }
    }

    // ---------------------------------------------------------------- 查询与复算

    /**
     * 调拨单视图。
     */
    public TankTransferView transferView(Long transferId) {
        return toView(loadTransfer(transferId));
    }

    /**
     * 指定罐（作为源或目标）参与的全部调拨单。
     */
    public List<TankTransferView> transfers(String tankCode) {
        Tank tank = tankRepository.findByCode(tankCode)
                .orElseThrow(() -> new ResourceNotFoundException("tank not found: " + tankCode));
        return transferRepository.listByTank(tank.id()).stream().map(this::toView).toList();
    }

    /**
     * 从调拨单复算双侧预占、余额及调拨流量：全部数值由台账分录重新合计，
     * 双侧余额变动合计为 0（守恒）。
     */
    public TankTransferRecomputeView recompute(Long transferId) {
        TankTransfer transfer = loadTransfer(transferId);
        BigDecimal sourceReservedDelta = ledgerRepository.sumDeltaByTransfer(
                transfer.sourceTankId(), transferId, "reserved_delta_kg");
        BigDecimal targetIncomingDelta = ledgerRepository.sumDeltaByTransfer(
                transfer.targetTankId(), transferId, "incoming_reserved_delta_kg");
        BigDecimal netBalanceDelta = ledgerRepository.sumDeltaByTransfer(
                transfer.sourceTankId(), transferId, "balance_delta_kg")
                .add(ledgerRepository.sumDeltaByTransfer(
                        transfer.targetTankId(), transferId, "balance_delta_kg"));
        BigDecimal sourceBalance = ledgerRepository.sumDeltaByTank(
                transfer.sourceTankId(), "balance_delta_kg");
        BigDecimal targetBalance = ledgerRepository.sumDeltaByTank(
                transfer.targetTankId(), "balance_delta_kg");
        BigDecimal sourceReserved = ledgerRepository.sumDeltaByTank(
                transfer.sourceTankId(), "reserved_delta_kg");
        BigDecimal targetIncoming = ledgerRepository.sumDeltaByTank(
                transfer.targetTankId(), "incoming_reserved_delta_kg");
        BigDecimal transferred = "EXECUTED".equals(transfer.status())
                ? transfer.actualQtyKg() : ZERO;
        return new TankTransferRecomputeView(transfer.id(), transfer.status(),
                transfer.quantityKg(), transferred, transfer.sourceTankId(),
                transfer.targetTankId(), sourceReservedDelta, targetIncomingDelta,
                netBalanceDelta, sourceBalance, targetBalance, sourceReserved, targetIncoming);
    }

    // ---------------------------------------------------------------- 内部

    private TankTransfer loadTransfer(Long transferId) {
        return transferRepository.findById(transferId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "transfer not found: " + transferId));
    }

    private TankTransferView toView(TankTransfer transfer) {
        return new TankTransferView(transfer.id(), transfer.sourceTankId(), transfer.targetTankId(),
                transfer.varietyNo(), transfer.quantityKg(), transfer.transportBatchNo(),
                transfer.status(), transfer.version(), transfer.firstOperator(),
                transfer.secondOperator(), transfer.actualQtyKg(), transfer.credentialNo(),
                transfer.appliedAt().atOffset(ZoneOffset.UTC).toInstant(),
                transfer.executedAt() == null ? null
                        : transfer.executedAt().atOffset(ZoneOffset.UTC).toInstant(),
                transfer.cancelledAt() == null ? null
                        : transfer.cancelledAt().atOffset(ZoneOffset.UTC).toInstant());
    }

    /**
     * 两罐按 ID 升序行锁，与对向调拨共用同一加锁顺序，避免死锁；
     * 返回数组按传入顺序（sourceId, targetId）回填锁定后的罐。
     */
    private Tank[] lockBoth(Long firstTankId, Long secondTankId) {
        Tank first = tankRepository.findByIdForUpdate(
                Math.min(firstTankId, secondTankId)).orElseThrow();
        Tank second = tankRepository.findByIdForUpdate(
                Math.max(firstTankId, secondTankId)).orElseThrow();
        return first.id().equals(firstTankId)
                ? new Tank[]{first, second} : new Tank[]{second, first};
    }

    private void checkVersion(TankTransfer transfer, long expectedVersion) {
        if (transfer.version() != expectedVersion) {
            throw new VersionConflictException("transfer " + transfer.id()
                    + " version mismatch: expected=" + expectedVersion
                    + ", current=" + transfer.version());
        }
    }

    /**
     * 储罐余额/预占/接收预占变更并递增版本；行锁内版本条件更新必中，否则为并发缺陷。
     */
    private long bumpTank(Tank tank, BigDecimal balanceAfter, BigDecimal reservedAfter,
                          BigDecimal incomingReservedAfter) {
        long newVersion = tank.version() + 1;
        int updated = tankRepository.updateBalanceReservedVersion(tank.id(), balanceAfter,
                reservedAfter, incomingReservedAfter, newVersion, tank.version());
        if (updated != 1) {
            throw new VersionConflictException("tank " + tank.tankCode()
                    + " concurrent modification detected");
        }
        return newVersion;
    }

    /**
     * 千克数量至多三位小数；合法值原样返回，比较一律按规范值（compareTo），超精度拒绝而非舍入。
     */
    private BigDecimal requireScale3(BigDecimal value, String field) {
        if (value.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException(field + " supports at most 3 decimal places");
        }
        return value;
    }

    /**
     * 调拨业务时刻按 UTC 存储。
     */
    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
