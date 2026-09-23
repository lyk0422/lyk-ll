package com.example.starter.compiler.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 编译请求：入口 Xacro（相对受控工作目录）、参数覆盖与请求标识。
 *
 * @param entry      入口文件相对工作目录的路径，例如 {@code robots/arm/main.xacro}
 * @param parameters 参数覆盖（键为属性名，值为字符串字面量）；请求参数优先于文件默认值
 * @param requestId  调用方提供的请求标识，仅用于响应关联与幂等排查，不参与缓存键
 */
public record CompileRequest(
        @NotBlank String entry,
        @NotNull Map<String, String> parameters,
        @NotBlank String requestId) {
}
