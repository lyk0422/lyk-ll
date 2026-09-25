package com.example.starter.bonded.service;

import com.example.starter.bonded.domain.OrderPo;
import com.example.starter.bonded.domain.TransferStatus;
import com.example.starter.bonded.repo.EnterpriseRepository;
import com.example.starter.bonded.repo.HistoryRepository;
import com.example.starter.bonded.repo.OrderRepository;
import com.example.starter.bonded.support.TimeProvider;
import com.example.starter.bonded.web.ApiBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 超时守卫：把"恰满 48 小时未核满 -> 异常"的标记放在独立事务中提交，
 * 使业务操作（核销/离港）即使随后以 409 拒绝，异常标记与企业冻结也已持久化。
 */
@Service
public class TimeoutGuard {

    private final OrderRepository orders;
    private final HistoryRepository history;
    private final EnterpriseRepository enterprises;
    private final TimeProvider clock;

    public TimeoutGuard(OrderRepository orders, HistoryRepository history,
                        EnterpriseRepository enterprises, TimeProvider clock) {
        this.orders = orders;
        this.history = history;
        this.enterprises = enterprises;
        this.clock = clock;
    }

    /**
     * 独立事务（REQUIRES_NEW）：若单据已超时则标记异常并冻结企业（随本事务独立提交，
     * 不受调用方事务回滚影响），返回刷新后的状态。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransferStatus refreshAndGetStatus(String requestId) {
        OrderPo order = orders.findByRequestId(requestId)
                .orElseThrow(() -> new ApiBusinessException(HttpStatus.NOT_FOUND, "转关单不存在: " + requestId));
        OrderPo locked = orders.findByIdForUpdate(order.id())
                .orElseThrow(() -> new ApiBusinessException(HttpStatus.NOT_FOUND, "转关单不存在: " + requestId));
        markIfExpirable(locked, clock.now());
        return orders.findById(locked.id())
                .orElseThrow(() -> new ApiBusinessException(HttpStatus.NOT_FOUND, "转关单不存在: " + requestId))
                .status();
    }

    /**
     * 单据是否已超时（在途/已核注且当前时间已达到或超过核销截止时间）。
     */
    public boolean isExpirable(OrderPo o, Instant now) {
        return (o.status() == TransferStatus.ENDORSED || o.status() == TransferStatus.IN_TRANSIT)
                && o.deadline() != null && !now.isBefore(o.deadline());
    }

    /**
     * 若单据已超时则置为异常，并冻结企业及其全部在途单。须在持有该行锁的事务内调用；
     * 无 @Transactional 注解，加入调用方事务。
     *
     * @return 是否执行了异常标记
     */
    public boolean markIfExpirable(OrderPo locked, Instant now) {
        if (!isExpirable(locked, now)) {
            return false;
        }
        if (orders.updateStatusIfCurrent(locked.id(), locked.status(), TransferStatus.ABNORMAL, now) == 0) {
            throw new ApiBusinessException(HttpStatus.CONFLICT, "异常标记与并发操作冲突，单据状态已变化");
        }
        history.append(locked.id(), locked.status(), TransferStatus.ABNORMAL, now,
                "核注后满 48 小时未核满，自动转异常");
        enterprises.setFrozen(locked.enterpriseId(), true);
        for (OrderPo sibling : orders.findByEnterpriseAndStatusIn(locked.enterpriseId(),
                List.of(TransferStatus.ENDORSED, TransferStatus.IN_TRANSIT))) {
            OrderPo lockedSibling = orders.findByIdForUpdate(sibling.id()).orElse(null);
            if (lockedSibling == null
                    || (lockedSibling.status() != TransferStatus.ENDORSED
                    && lockedSibling.status() != TransferStatus.IN_TRANSIT)) {
                continue;
            }
            if (orders.updateStatusIfCurrent(lockedSibling.id(), lockedSibling.status(),
                    TransferStatus.ABNORMAL, now) > 0) {
                history.append(lockedSibling.id(), lockedSibling.status(), TransferStatus.ABNORMAL, now,
                        "企业存在超时异常单，全部在途单一并冻结");
            }
        }
        return true;
    }
}
