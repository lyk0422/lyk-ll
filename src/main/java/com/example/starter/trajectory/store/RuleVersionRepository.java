package com.example.starter.trajectory.store;

import com.example.starter.trajectory.dto.RuleConfig;

/**
 * 规则版本持久化：版本发布后不可原地修改。
 */
public interface RuleVersionRepository {

    /**
     * 发布新规则版本；版本号重复时抛出
     * {@link com.example.starter.trajectory.exception.ConflictException}。
     */
    void save(String ruleVersion, RuleConfig config);

    /** 按版本号读取不可变配置，不存在返回 {@code null}。 */
    RuleConfig findByVersion(String ruleVersion);
}
