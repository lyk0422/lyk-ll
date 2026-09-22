package com.example.starter.arbitration.service;

import com.example.starter.arbitration.config.ArbitrationProperties;
import com.example.starter.arbitration.domain.ActiveClaimPo;
import com.example.starter.arbitration.domain.CommandPo;
import com.example.starter.arbitration.domain.ControllerPo;
import com.example.starter.arbitration.domain.LeasePo;
import com.example.starter.arbitration.repo.ActiveClaimRepository;
import com.example.starter.arbitration.repo.CommandRepository;
import com.example.starter.arbitration.repo.ControllerRepository;
import com.example.starter.arbitration.repo.LeaseRepository;
import com.example.starter.arbitration.repo.ResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 一次性安全回收：租约到期、控制器失联、到时指令执行、队列指令超时。
 * 每个触发源由租约 id/指令 id 绑定唯一安全事件键，重复扫描不会重复执行降级动作。
 */
@Service
public class SafetyReaper {

    private final ActiveClaimRepository activeClaimRepository;
    private final LeaseRepository leaseRepository;
    private final ControllerRepository controllerRepository;
    private final CommandRepository commandRepository;
    private final ResourceRepository resourceRepository;
    private final SafetyService safetyService;
    private final ArbitrationProperties properties;
    private final ControllableClock clock;

    public SafetyReaper(ActiveClaimRepository activeClaimRepository,
                        LeaseRepository leaseRepository,
                        ControllerRepository controllerRepository,
                        CommandRepository commandRepository,
                        ResourceRepository resourceRepository,
                        SafetyService safetyService,
                        ArbitrationProperties properties,
                        ControllableClock clock) {
        this.activeClaimRepository = activeClaimRepository;
        this.leaseRepository = leaseRepository;
        this.controllerRepository = controllerRepository;
        this.commandRepository = commandRepository;
        this.resourceRepository = resourceRepository;
        this.safetyService = safetyService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 执行一轮完整回收（定时任务与测试共用同一入口）。
     * 顺序：先回收失效写权限，再处理队列；队列先做超时看门狗判定，后执行到时指令，
     * 避免时钟跳变后把早已过期的陈旧指令当作新目标执行。
     */
    public void reapOnce() {
        reapExpiredLeases();
        reapLostControllers();
        reapTimedOutCommands();
        executeDueCommands();
    }

    /** 租约到期：撤销写权限、清理该持有者对该资源的待执行指令、一次性安全降级。 */
    @Transactional
    public void reapExpiredLeases() {
        long now = clock.now();
        List<ActiveClaimPo> expired = activeClaimRepository.findExpiredForUpdate(now);
        for (ActiveClaimPo claim : expired) {
            LeasePo lease = leaseRepository.findById(claim.getLeaseId()).orElse(null);
            if (lease == null || !lease.isActive()) {
                activeClaimRepository.deleteByLeaseId(claim.getLeaseId());
                continue;
            }
            if (leaseRepository.deactivateIfActive(lease.getId(), SafetyService.REASON_LEASE_EXPIRED) > 0) {
                activeClaimRepository.deleteByLeaseId(lease.getId());
                commandRepository.cancelPendingByHolder(lease.getControllerId(), lease.getResourceName(),
                        SafetyService.REASON_LEASE_EXPIRED, now);
                safetyService.applyForLeaseLoss(SafetyService.REASON_LEASE_EXPIRED, lease, now);
            }
        }
    }

    /** 控制器失联：撤销其全部写权限，逐资源一次性安全降级。 */
    @Transactional
    public void reapLostControllers() {
        long now = clock.now();
        List<ControllerPo> lost = controllerRepository.findTimedOutForUpdate(
                now, properties.getController().getHeartbeatTimeoutMs());
        for (ControllerPo controller : lost) {
            if (controllerRepository.markLost(controller.getControllerId()) == 0) {
                continue;
            }
            List<ActiveClaimPo> claims = activeClaimRepository.findByController(controller.getControllerId());
            for (ActiveClaimPo claim : claims) {
                LeasePo lease = leaseRepository.findById(claim.getLeaseId()).orElse(null);
                if (lease == null || !lease.isActive()) {
                    activeClaimRepository.deleteByLeaseId(claim.getLeaseId());
                    continue;
                }
                if (leaseRepository.deactivateIfActive(lease.getId(),
                        SafetyService.REASON_CONTROLLER_LOST) > 0) {
                    activeClaimRepository.deleteByLeaseId(lease.getId());
                    commandRepository.cancelPendingByHolder(lease.getControllerId(), lease.getResourceName(),
                            SafetyService.REASON_CONTROLLER_LOST, now);
                    safetyService.applyForLeaseLoss(SafetyService.REASON_CONTROLLER_LOST, lease, now);
                }
            }
        }
    }

    /** 到时指令执行：模拟硬件应用目标，记录最近目标位置与时戳。 */
    @Transactional
    public void executeDueCommands() {
        long now = clock.now();
        List<CommandPo> due = commandRepository.findDuePendingForUpdate(now);
        for (CommandPo command : due) {
            if (commandRepository.markExecutedIfPending(command.getId(), now) > 0) {
                resourceRepository.updateLastTarget(command.getResourceName(),
                        command.getTargetPosition(), command.getTimestampMs());
            }
        }
    }

    /** 队列指令超时：置 TIMED_OUT 并对目标资源执行一次性安全降级。 */
    @Transactional
    public void reapTimedOutCommands() {
        long now = clock.now();
        List<CommandPo> overdue = commandRepository.findOverduePendingForUpdate(
                now, properties.getCommand().getQueueTimeoutMs());
        for (CommandPo command : overdue) {
            if (commandRepository.markTimedOutIfPending(command.getId(), now) > 0) {
                safetyService.applyForCommandTimeout(command, now);
            }
        }
    }
}
