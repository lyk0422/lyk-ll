package com.example.starter.trajectory.service;

import com.example.starter.trajectory.domain.RuleConfig;
import com.example.starter.trajectory.domain.RuleStatus;
import com.example.starter.trajectory.persistence.RuleConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 版本化规则管理：配置发布（PUBLISHED）后不可原地修改，
 * 调整阈值必须新建 ruleVersion；同一轨迹可分别按不同版本复核。
 */
@Service
public class RuleConfigService {

    private final RuleConfigRepository repository;

    public RuleConfigService(RuleConfigRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public RuleConfig createDraft(RuleConfig config) {
        validateThresholds(config);
        if (repository.existsByVersion(config.ruleVersion())) {
            throw new DuplicateRuleVersionException(config.ruleVersion());
        }
        RuleConfig draft = withStatus(config, RuleStatus.DRAFT);
        repository.insert(draft);
        return draft;
    }

    @Transactional
    public RuleConfig updateDraft(String ruleVersion, RuleConfig config) {
        validateThresholds(config);
        RuleConfig existing = repository.findByVersion(ruleVersion)
                .orElseThrow(() -> new RuleVersionNotFoundException(ruleVersion));
        if (existing.status() == RuleStatus.PUBLISHED) {
            throw new PublishedRuleImmutableException(ruleVersion);
        }
        RuleConfig updated = new RuleConfig(
                ruleVersion,
                config.maxTimeGapSeconds(),
                config.maxSpeed(),
                config.maxAcceleration(),
                config.maxCurvature(),
                config.roadTolerance(),
                config.collisionDistance(),
                RuleStatus.DRAFT);
        repository.updateDraft(updated);
        return updated;
    }

    @Transactional
    public RuleConfig publish(String ruleVersion) {
        RuleConfig existing = repository.findByVersion(ruleVersion)
                .orElseThrow(() -> new RuleVersionNotFoundException(ruleVersion));
        if (existing.status() == RuleStatus.PUBLISHED) {
            return existing;
        }
        repository.updateStatus(ruleVersion, RuleStatus.PUBLISHED.name());
        return withStatus(existing, RuleStatus.PUBLISHED);
    }

    @Transactional(readOnly = true)
    public RuleConfig get(String ruleVersion) {
        return repository.findByVersion(ruleVersion)
                .orElseThrow(() -> new RuleVersionNotFoundException(ruleVersion));
    }

    @Transactional(readOnly = true)
    public List<RuleConfig> list() {
        return repository.findAll();
    }

    /**
     * 取已发布规则；不存在或未发布均视为不可用。
     */
    @Transactional(readOnly = true)
    public RuleConfig getPublished(String ruleVersion) {
        RuleConfig config = get(ruleVersion);
        if (config.status() != RuleStatus.PUBLISHED) {
            throw new RuleVersionNotFoundException(ruleVersion);
        }
        return config;
    }

    private RuleConfig withStatus(RuleConfig config, RuleStatus status) {
        return new RuleConfig(
                config.ruleVersion(),
                config.maxTimeGapSeconds(),
                config.maxSpeed(),
                config.maxAcceleration(),
                config.maxCurvature(),
                config.roadTolerance(),
                config.collisionDistance(),
                status);
    }

    private void validateThresholds(RuleConfig config) {
        if (!(config.maxTimeGapSeconds() > 0)) {
            throw new IllegalArgumentException("maxTimeGapSeconds must be positive");
        }
        if (config.maxSpeed() < 0 || config.maxAcceleration() < 0
                || config.maxCurvature() < 0 || config.roadTolerance() < 0
                || config.collisionDistance() < 0) {
            throw new IllegalArgumentException("thresholds must not be negative");
        }
    }
}
