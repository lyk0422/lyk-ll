package com.example.starter.arbitration.service;

import com.example.starter.arbitration.repo.ActiveClaimRepository;
import com.example.starter.arbitration.repo.CommandRepository;
import com.example.starter.arbitration.repo.EstopStateRepository;
import com.example.starter.arbitration.repo.LeaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 紧急停止：立即撤销所有写权限、清空待执行指令；显式恢复前拒绝普通申请。
 */
@Service
public class EstopService {

    /** 急停撤销原因标记。 */
    public static final String REASON_ESTOP = "ESTOP";

    private final EstopStateRepository estopStateRepository;
    private final LeaseRepository leaseRepository;
    private final ActiveClaimRepository activeClaimRepository;
    private final CommandRepository commandRepository;
    private final ControllableClock clock;

    public EstopService(EstopStateRepository estopStateRepository,
                        LeaseRepository leaseRepository,
                        ActiveClaimRepository activeClaimRepository,
                        CommandRepository commandRepository,
                        ControllableClock clock) {
        this.estopStateRepository = estopStateRepository;
        this.leaseRepository = leaseRepository;
        this.activeClaimRepository = activeClaimRepository;
        this.commandRepository = commandRepository;
        this.clock = clock;
    }

    /** 触发急停。重复触发幂等：仅第一次执行撤销与清空。 */
    @Transactional
    public void trigger() {
        long now = clock.now();
        if (estopStateRepository.triggerIfInactive(now)) {
            // 立即撤销所有写权限
            leaseRepository.deactivateAllActive(REASON_ESTOP);
            activeClaimRepository.deleteAll();
            // 清空待执行指令
            commandRepository.cancelAllPending(REASON_ESTOP, now);
        }
    }

    /** 显式恢复。未处于急停时恢复返回 409。 */
    @Transactional
    public void recover() {
        if (!estopStateRepository.recoverIfActive(clock.now())) {
            throw ApiException.conflict("ESTOP_NOT_ACTIVE", "系统当前未处于紧急停止状态，无需恢复");
        }
    }

    /** 当前是否急停中。 */
    public boolean isActive() {
        return estopStateRepository.get().isActive();
    }
}
