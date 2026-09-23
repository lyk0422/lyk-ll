package com.example.starter.trajectory.engine;

import com.example.starter.trajectory.domain.RuleConfig;
import com.example.starter.trajectory.domain.ValidationRequest;
import com.example.starter.trajectory.domain.Violation;

import java.util.List;

/**
 * 单类规则校验器。各实现无共享可变状态，可并行执行。
 */
public interface RuleChecker {

    /**
     * 对请求执行本类规则校验，同一（规则,对象）只返回首次触发证据。
     */
    List<Violation> check(ValidationRequest request, RuleConfig rules);
}
