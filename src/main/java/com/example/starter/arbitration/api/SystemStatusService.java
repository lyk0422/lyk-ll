package com.example.starter.arbitration.api;

import com.example.starter.arbitration.domain.ActiveClaimPo;
import com.example.starter.arbitration.domain.SafetyEventPo;
import com.example.starter.arbitration.repo.ActiveClaimRepository;
import com.example.starter.arbitration.repo.CommandRepository;
import com.example.starter.arbitration.repo.EstopStateRepository;
import com.example.starter.arbitration.repo.SafetyEventRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 系统状态查询。
 */
@Service
public class SystemStatusService {

    private final EstopStateRepository estopStateRepository;
    private final ActiveClaimRepository activeClaimRepository;
    private final CommandRepository commandRepository;
    private final SafetyEventRepository safetyEventRepository;

    public SystemStatusService(EstopStateRepository estopStateRepository,
                               ActiveClaimRepository activeClaimRepository,
                               CommandRepository commandRepository,
                               SafetyEventRepository safetyEventRepository) {
        this.estopStateRepository = estopStateRepository;
        this.activeClaimRepository = activeClaimRepository;
        this.commandRepository = commandRepository;
        this.safetyEventRepository = safetyEventRepository;
    }

    /** 组装当前系统快照。 */
    public SystemStatusResponse snapshot() {
        List<SystemStatusResponse.LeaseView> leases = activeClaimRepository.findAll().stream()
                .map(this::toLeaseView)
                .toList();
        List<SystemStatusResponse.SafetyEventView> events = safetyEventRepository.findAll().stream()
                .map(this::toEventView)
                .toList();
        return new SystemStatusResponse(
                estopStateRepository.get().isActive(),
                leases,
                commandRepository.countPending(),
                events);
    }

    private SystemStatusResponse.LeaseView toLeaseView(ActiveClaimPo claim) {
        return new SystemStatusResponse.LeaseView(claim.getControllerId(), claim.getResourceName(),
                claim.getRequestId(), claim.getPriority(), claim.getExpiresAtMs());
    }

    private SystemStatusResponse.SafetyEventView toEventView(SafetyEventPo event) {
        return new SystemStatusResponse.SafetyEventView(event.getEventKey(), event.getReason(),
                event.getResourceName(), event.getControllerId(), event.getAction(),
                event.getTargetPosition(), event.getEventMs());
    }
}
