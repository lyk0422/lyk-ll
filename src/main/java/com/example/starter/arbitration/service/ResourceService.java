package com.example.starter.arbitration.service;

import com.example.starter.arbitration.api.HeartbeatRequest;
import com.example.starter.arbitration.api.RegisterResourceRequest;
import com.example.starter.arbitration.domain.ResourcePo;
import com.example.starter.arbitration.domain.SafetyAction;
import com.example.starter.arbitration.repo.ControllerRepository;
import com.example.starter.arbitration.repo.ResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 资源登记与控制器心跳。
 */
@Service
public class ResourceService {

    private final ResourceRepository resourceRepository;
    private final ControllerRepository controllerRepository;
    private final ControllableClock clock;

    public ResourceService(ResourceRepository resourceRepository,
                           ControllerRepository controllerRepository,
                           ControllableClock clock) {
        this.resourceRepository = resourceRepository;
        this.controllerRepository = controllerRepository;
        this.clock = clock;
    }

    /** 登记/更新资源定义（位置边界、限速限加速度、安全动作）。 */
    @Transactional
    public void register(RegisterResourceRequest request) {
        List<String> violations = new ArrayList<>();
        if (request.minPosition() > request.maxPosition()) {
            violations.add("INVALID_BOUNDS: 位置下限 " + request.minPosition()
                    + " 大于上限 " + request.maxPosition());
        }
        if (request.maxVelocity() == null || request.maxVelocity() < 0) {
            violations.add("INVALID_MAX_VELOCITY: 最大速度必须不小于 0");
        }
        if (request.maxAcceleration() == null || request.maxAcceleration() < 0) {
            violations.add("INVALID_MAX_ACCELERATION: 最大加速度必须不小于 0");
        }
        SafetyAction action;
        try {
            action = SafetyAction.valueOf(request.safetyAction());
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("INVALID_SAFETY_ACTION", List.of(
                    "UNKNOWN_SAFETY_ACTION: 不支持的安全动作 " + request.safetyAction()
                            + "，可选 HOLD/ZERO_VELOCITY/PRESET_POSITION"));
        }
        if (action == SafetyAction.PRESET_POSITION) {
            double preset = request.presetPosition() != null ? request.presetPosition() : 0.0;
            if (preset < request.minPosition() || preset > request.maxPosition()) {
                violations.add("PRESET_OUT_OF_BOUNDS: 预设位置 " + preset
                        + " 超出边界 [" + request.minPosition() + ", " + request.maxPosition() + "]");
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.badRequest("INVALID_RESOURCE", violations);
        }

        ResourcePo po = new ResourcePo();
        po.setResourceName(request.resourceName());
        po.setMinPosition(request.minPosition());
        po.setMaxPosition(request.maxPosition());
        po.setMaxVelocity(request.maxVelocity());
        po.setMaxAcceleration(request.maxAcceleration());
        po.setSafetyAction(action.name());
        po.setPresetPosition(action == SafetyAction.PRESET_POSITION
                ? (request.presetPosition() != null ? request.presetPosition() : 0.0)
                : null);
        resourceRepository.upsert(po);
    }

    /** 控制器心跳：刷新心跳时间；若此前被判定失联，心跳恢复后清除失联标记。 */
    @Transactional
    public void heartbeat(HeartbeatRequest request) {
        controllerRepository.heartbeat(request.controllerId(), clock.now());
    }
}
