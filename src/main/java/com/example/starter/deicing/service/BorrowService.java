package com.example.starter.deicing.service;

import com.example.starter.deicing.db.FluidBorrowPo;
import com.example.starter.deicing.db.FluidBorrowRepository;
import com.example.starter.deicing.domain.BorrowStatus;
import com.example.starter.deicing.web.ApiException;
import com.example.starter.deicing.web.dto.BorrowRequest;
import com.example.starter.deicing.web.dto.BorrowResponse;
import com.example.starter.deicing.web.dto.BorrowView;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 跨班组借用：借用单按 requestId 幂等（指纹覆盖全部业务字段，同键异参 409）；
 * 同一液罐同时刻只允许一张未归还借用单；归还须登记。
 */
@Service
public class BorrowService {

    private final FluidBorrowRepository borrowRepository;
    private final InventoryService inventoryService;
    private final TimeSource timeSource;

    public BorrowService(FluidBorrowRepository borrowRepository,
                         InventoryService inventoryService,
                         TimeSource timeSource) {
        this.borrowRepository = borrowRepository;
        this.inventoryService = inventoryService;
        this.timeSource = timeSource;
    }

    /**
     * 借用登记：同键同参返回首次结果，同键异参 409；
     * 液罐存在未归还借用单或可借库存不足时 409。
     */
    @Transactional
    public BorrowResponse borrow(BorrowRequest request) {
        Instant now = timeSource.now();
        String fingerprint = Fingerprints.sha256(request.requestId(), request.fluidCode(),
                request.tankCode(), request.quantityLiters().toPlainString(),
                request.fromTeam(), request.toTeam());

        FluidBorrowPo existing = borrowRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return replayOrConflict(existing, fingerprint);
        }

        // 锁定除冰液行，串行化同液的借用/预占/结算，保证液罐唯一性与库存检查的原子性
        inventoryService.mustLockFluid(request.fluidCode());
        if (borrowRepository.existsOpenByTank(request.tankCode())) {
            throw ApiException.conflict("液罐 " + request.tankCode()
                    + " 存在未归还借用单，须先归还登记");
        }
        BigDecimal available = inventoryService.availableForReservation(request.fluidCode(), now);
        if (available.compareTo(request.quantityLiters()) < 0) {
            throw ApiException.conflict("除冰液 " + request.fluidCode() + " 可借库存不足：需 "
                    + request.quantityLiters() + " 升，可借 " + available + " 升");
        }
        inventoryService.deductFefo(request.fluidCode(), request.quantityLiters(), now);

        FluidBorrowPo po = new FluidBorrowPo();
        po.setRequestId(request.requestId());
        po.setFluidCode(request.fluidCode());
        po.setTankCode(request.tankCode());
        po.setQuantityLiters(request.quantityLiters());
        po.setFromTeam(request.fromTeam());
        po.setToTeam(request.toTeam());
        po.setStatus(BorrowStatus.OPEN);
        po.setFingerprint(fingerprint);
        po.setCreatedAt(now);
        try {
            borrowRepository.insert(po);
        } catch (DuplicateKeyException e) {
            FluidBorrowPo raced = borrowRepository.findByRequestId(request.requestId());
            if (raced != null) {
                return replayOrConflict(raced, fingerprint);
            }
            throw e;
        }
        return new BorrowResponse(toView(borrowRepository.findByRequestId(request.requestId())),
                false);
    }

    /**
     * 归还登记：OPEN → RETURNED 并回补库存；重复归还幂等返回首次结果。
     */
    @Transactional
    public BorrowResponse returnBorrow(String requestId) {
        FluidBorrowPo po = borrowRepository.findByRequestId(requestId);
        if (po == null) {
            throw ApiException.notFound("借用单不存在: " + requestId);
        }
        if (po.getStatus() == BorrowStatus.RETURNED) {
            return new BorrowResponse(toView(po), true);
        }
        Instant now = timeSource.now();
        inventoryService.mustLockFluid(po.getFluidCode());
        inventoryService.creditBack(po.getFluidCode(), po.getQuantityLiters());
        borrowRepository.markReturned(requestId, now);
        return new BorrowResponse(toView(borrowRepository.findByRequestId(requestId)), false);
    }

    private BorrowResponse replayOrConflict(FluidBorrowPo existing, String fingerprint) {
        if (!existing.getFingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId " + existing.getRequestId()
                    + " 已存在但业务参数不一致，拒绝覆盖");
        }
        return new BorrowResponse(toView(existing), true);
    }

    private static BorrowView toView(FluidBorrowPo po) {
        return new BorrowView(po.getRequestId(), po.getFluidCode(), po.getTankCode(),
                po.getQuantityLiters(), po.getFromTeam(), po.getToTeam(), po.getStatus(),
                po.getCreatedAt(), po.getReturnedAt());
    }
}
