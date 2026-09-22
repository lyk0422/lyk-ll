package com.example.starter.arbitration.service;

import com.example.starter.arbitration.config.ArbitrationProperties;
import com.example.starter.arbitration.domain.CommandPo;
import com.example.starter.arbitration.domain.LeasePo;
import com.example.starter.arbitration.domain.ResourcePo;
import com.example.starter.arbitration.domain.SafetyAction;
import com.example.starter.arbitration.domain.SafetyEventPo;
import com.example.starter.arbitration.repo.CommandRepository;
import com.example.starter.arbitration.repo.ResourceRepository;
import com.example.starter.arbitration.repo.SafetyEventRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 安全降级：失联、租约到期、指令超时后让资源一次性进入可配置安全状态。
 * 事件键精确绑定触发源（租约 id / 指令 id）：
 * 同一触发源重复检测只产生一条事件、只执行一次降级；新一轮失效使用新租约/指令 id，可再次降级。
 */
@Service
public class SafetyService {

    /** 租约失效类原因（租约到期、控制器失联）。 */
    public static final String REASON_LEASE_EXPIRED = "LEASE_EXPIRED";
    public static final String REASON_CONTROLLER_LOST = "CONTROLLER_LOST";
    /** 指令执行超时原因。 */
    public static final String REASON_COMMAND_TIMEOUT = "COMMAND_TIMEOUT";

    private final SafetyEventRepository safetyEventRepository;
    private final ResourceRepository resourceRepository;
    private final CommandRepository commandRepository;
    private final ArbitrationProperties properties;

    public SafetyService(SafetyEventRepository safetyEventRepository,
                         ResourceRepository resourceRepository,
                         CommandRepository commandRepository,
                         ArbitrationProperties properties) {
        this.safetyEventRepository = safetyEventRepository;
        this.resourceRepository = resourceRepository;
        this.commandRepository = commandRepository;
        this.properties = properties;
    }

    /**
     * 租约失效（到期/失联）导致的一次性降级。必须在已锁定资源与租约行的事务内调用。
     *
     * @return 本次是否实际执行了降级；false 表示该触发源此前已执行过
     */
    @Transactional
    public boolean applyForLeaseLoss(String reason, LeasePo lease, long nowMs) {
        String eventKey = reason + ":LEASE:" + lease.getId();
        return apply(eventKey, reason, lease.getResourceName(), lease.getControllerId(), nowMs);
    }

    /**
     * 指令执行超时导致的一次性降级。必须在已锁定指令行的事务内调用。
     *
     * @return 本次是否实际执行了降级；false 表示该指令此前已处理过
     */
    @Transactional
    public boolean applyForCommandTimeout(CommandPo command, long nowMs) {
        String eventKey = REASON_COMMAND_TIMEOUT + ":COMMAND:" + command.getId();
        return apply(eventKey, REASON_COMMAND_TIMEOUT, command.getResourceName(),
                command.getControllerId(), nowMs);
    }

    private boolean apply(String eventKey, String reason, String resourceName,
                          String controllerId, long nowMs) {
        if (safetyEventRepository.findByKey(eventKey).isPresent()) {
            return false;
        }
        ResourcePo resource = resourceRepository.findByNameForUpdate(resourceName).orElseThrow();
        SafetyAction action = resolveAction(resource);

        SafetyEventPo event = new SafetyEventPo();
        event.setEventKey(eventKey);
        event.setReason(reason);
        event.setResourceName(resourceName);
        event.setControllerId(controllerId);
        event.setAction(action.name());
        event.setEventMs(nowMs);

        switch (action) {
            case PRESET_POSITION -> {
                double target = resource.getPresetPosition() != null
                        ? resource.getPresetPosition()
                        : properties.getSafety().getPresetPosition();
                // 模拟硬件进入预设位置：记录最近目标
                resourceRepository.updateLastTarget(resourceName, target, nowMs);
                event.setTargetPosition(target);
            }
            case HOLD, ZERO_VELOCITY -> {
                // HOLD：保持最近目标不变；ZERO_VELOCITY：就地停车，位置不变、目标速度归零（模拟层记录动作即可）
                event.setTargetPosition(null);
            }
        }

        try {
            safetyEventRepository.insert(event);
        } catch (DuplicateKeyException alreadyApplied) {
            // 并发的重复检测已先插入：不重复执行降级
            return false;
        }
        return true;
    }

    private SafetyAction resolveAction(ResourcePo resource) {
        try {
            return SafetyAction.valueOf(resource.getSafetyAction());
        } catch (IllegalArgumentException | NullPointerException ex) {
            return SafetyAction.valueOf(properties.getSafety().getDefaultAction());
        }
    }
}
