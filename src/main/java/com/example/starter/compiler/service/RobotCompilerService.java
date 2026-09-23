package com.example.starter.compiler.service;

import com.example.starter.compiler.api.CompileRequest;
import com.example.starter.compiler.api.CompileResult;

/**
 * 机器人模型编译服务。
 */
public interface RobotCompilerService {

    /**
     * 编译入口 Xacro：展开、校验、规范化并走依赖感知缓存。
     */
    CompileResult compile(CompileRequest request);
}
