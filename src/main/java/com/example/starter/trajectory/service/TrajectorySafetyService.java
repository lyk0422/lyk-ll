package com.example.starter.trajectory.service;

import com.example.starter.trajectory.dto.CreateRuleVersionRequest;
import com.example.starter.trajectory.dto.FailureItem;
import com.example.starter.trajectory.dto.RuleConfig;
import com.example.starter.trajectory.dto.ValidationRequest;
import com.example.starter.trajectory.dto.ValidationResult;
import com.example.starter.trajectory.engine.SafetyValidationEngine;
import com.example.starter.trajectory.exception.BadRequestException;
import com.example.starter.trajectory.exception.ConflictException;
import com.example.starter.trajectory.exception.NotFoundException;
import com.example.starter.trajectory.store.DeterministicHasher;
import com.example.starter.trajectory.store.RuleVersionRepository;
import com.example.starter.trajectory.store.ValidationResultRepository;
import com.example.starter.trajectory.store.ValidationResultRepository.StoredValidation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 轨迹安全校验应用服务：版本化规则管理、幂等请求处理与确定性结果输出。
 */
@Service
public class TrajectorySafetyService {

    private static final List<String> ALL_RULE_IDS = List.of("R1", "R2", "R3", "R4", "R5", "R6");

    private final RuleVersionRepository ruleVersionRepository;
    private final ValidationResultRepository resultRepository;
    private final SafetyValidationEngine engine;
    private final DeterministicHasher hasher;

    public TrajectorySafetyService(RuleVersionRepository ruleVersionRepository,
                                   ValidationResultRepository resultRepository,
                                   SafetyValidationEngine engine,
                                   DeterministicHasher hasher) {
        this.ruleVersionRepository = ruleVersionRepository;
        this.resultRepository = resultRepository;
        this.engine = engine;
        this.hasher = hasher;
    }

    /** 发布不可变规则版本；版本号重复抛出 409。 */
    @Transactional
    public void publishRuleVersion(CreateRuleVersionRequest request) {
        ruleVersionRepository.save(request.ruleVersion(), request.config());
    }

    /**
     * 执行轨迹安全校验。
     * 同键同参返回首次计算的原结果（含相同 resultHash）；同键异参返回 409。
     */
    @Transactional
    public ValidationResult validate(ValidationRequest request) {
        validateShape(request);

        RuleConfig config = ruleVersionRepository.findByVersion(request.ruleVersion());
        if (config == null) {
            throw new NotFoundException("规则版本不存在: " + request.ruleVersion());
        }

        String payloadHash = hasher.payloadHash(request);
        StoredValidation existing = resultRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return reconcile(existing, payloadHash, request.requestId());
        }

        List<FailureItem> failures = engine.validate(config, request);
        ValidationResult result = assembleResult(request, failures);
        boolean inserted = resultRepository.save(request.requestId(), request.ruleVersion(),
                payloadHash, hasher.toPayloadJsonUnchecked(request), result);
        if (!inserted) {
            // 并发提交：以数据库中首条记录为准
            StoredValidation concurrent = resultRepository.findByRequestId(request.requestId());
            return reconcile(concurrent, payloadHash, request.requestId());
        }
        return result;
    }

    /** 按幂等键读取已存结果，不存在抛出 404。 */
    @Transactional(readOnly = true)
    public ValidationResult getByRequestId(String requestId) {
        StoredValidation stored = resultRepository.findByRequestId(requestId);
        if (stored == null) {
            throw new NotFoundException("校验结果不存在: " + requestId);
        }
        return stored.result();
    }

    private ValidationResult reconcile(StoredValidation stored, String payloadHash, String requestId) {
        if (stored == null) {
            throw new ConflictException("请求并发冲突，请重试: " + requestId);
        }
        if (!stored.requestHash().equals(payloadHash)) {
            throw new ConflictException("requestId 已被不同参数占用，同键异参拒绝执行: " + requestId);
        }
        return stored.result();
    }

    private ValidationResult assembleResult(ValidationRequest request, List<FailureItem> failures) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String ruleId : ALL_RULE_IDS) {
            counts.put(ruleId, 0);
        }
        for (FailureItem failure : failures) {
            counts.merge(failure.ruleId(), 1, Integer::sum);
        }

        ValidationResult withoutHash = new ValidationResult(
                request.requestId(),
                request.ruleVersion(),
                failures.isEmpty(),
                failures.size(),
                Map.copyOf(counts),
                null,
                failures);
        String hash = hasher.resultHash(withoutHash);
        return new ValidationResult(
                withoutHash.requestId(),
                withoutHash.ruleVersion(),
                withoutHash.safe(),
                withoutHash.failureCount(),
                withoutHash.failuresByRule(),
                hash,
                withoutHash.failures());
    }

    /** 语义校验：障碍物 ID 在单个请求内唯一，障碍物预测点时间严格递增。 */
    private void validateShape(ValidationRequest request) {
        if (request.obstacles() != null) {
            Set<String> seen = new HashSet<>();
            for (var obstacle : request.obstacles()) {
                if (!seen.add(obstacle.id())) {
                    throw new BadRequestException("障碍物 ID 在请求内重复: " + obstacle.id());
                }
                for (int i = 1; i < obstacle.points().size(); i++) {
                    if (obstacle.points().get(i).t() <= obstacle.points().get(i - 1).t()) {
                        throw new BadRequestException(
                                "障碍物预测点时间必须严格递增: " + obstacle.id());
                    }
                }
            }
        }
    }
}
