package com.example.starter.trajectory.service;

import com.example.starter.trajectory.domain.RuleConfig;
import com.example.starter.trajectory.domain.ValidationRequest;
import com.example.starter.trajectory.domain.ValidationResult;
import com.example.starter.trajectory.engine.EvidenceJson;
import com.example.starter.trajectory.engine.Hashes;
import com.example.starter.trajectory.engine.TrajectoryValidationEngine;
import com.example.starter.trajectory.persistence.StoredValidation;
import com.example.starter.trajectory.persistence.ValidationRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 校验入口：幂等控制 + 规则版本加载 + 并行引擎执行。
 * requestId 同键同参返回原结果，同键异参返回 409；
 * 并发首次提交由 validation_record 主键约束保证只成功一次。
 */
@Service
public class TrajectoryValidationService {

    private final RuleConfigService ruleConfigService;
    private final TrajectoryValidationEngine engine;
    private final ValidationRecordRepository recordRepository;
    private final ObjectMapper objectMapper;

    public TrajectoryValidationService(RuleConfigService ruleConfigService,
                                       TrajectoryValidationEngine engine,
                                       ValidationRecordRepository recordRepository,
                                       ObjectMapper objectMapper) {
        this.ruleConfigService = ruleConfigService;
        this.engine = engine;
        this.recordRepository = recordRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行或复用一次轨迹安全校验。
     */
    public ValidationResult validate(ValidationRequest request) {
        validateShape(request);
        RuleConfig rules = ruleConfigService.getPublished(request.ruleVersion());
        String requestHash = requestParametersHash(request);

        StoredValidation existing = recordRepository.findByRequestId(request.requestId()).orElse(null);
        if (existing != null) {
            return resolveExisting(request.requestId(), requestHash, existing);
        }

        ValidationResult result = engine.validate(request, rules);
        StoredValidation record = new StoredValidation(
                request.requestId(),
                request.ruleVersion(),
                requestHash,
                EvidenceJson.write(objectMapper, result));
        boolean inserted = insertRecord(record);
        if (inserted) {
            return result;
        }
        StoredValidation concurrent = recordRepository.findByRequestId(request.requestId()).orElseThrow();
        return resolveExisting(request.requestId(), requestHash, concurrent);
    }

    /**
     * 插入幂等记录；JdbcTemplate 自动提交，主键冲突由仓储捕获并返回 false，
     * 随后重新读取已提交的记录，保证并发首次提交只有一个胜出者。
     */
    private boolean insertRecord(StoredValidation record) {
        return recordRepository.insert(record);
    }

    private ValidationResult resolveExisting(String requestId, String requestHash, StoredValidation stored) {
        if (!stored.requestHash().equals(requestHash)) {
            throw new ConflictingRequestException(requestId);
        }
        try {
            return objectMapper.readValue(stored.resultJson(), ValidationResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to read stored validation result: " + requestId, e);
        }
    }

    /**
     * 对 requestId 之外的全部请求参数计算稳定哈希。
     */
    private String requestParametersHash(ValidationRequest request) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("ruleVersion", request.ruleVersion());
        parameters.put("vehicle", request.vehicle());
        parameters.put("trajectory", request.trajectory());
        parameters.put("obstacles", request.obstacles());
        parameters.put("road", request.road());
        return Hashes.sha256Hex(EvidenceJson.write(objectMapper, parameters));
    }

    private void validateShape(ValidationRequest request) {
        if (request.trajectory().isEmpty()) {
            throw new IllegalArgumentException("trajectory must not be empty");
        }
        if (request.road().vertices().size() < 3) {
            throw new IllegalArgumentException("road boundary must contain at least 3 vertices");
        }
    }
}
