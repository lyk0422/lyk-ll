package com.example.starter.robot;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 编译请求。
 *
 * @param entry      受控工作目录内的入口 Xacro 相对路径
 * @param parameters Xacro 参数覆盖，键为参数名，值为字符串形式的参数值；请求参数优先于文件默认值
 * @param requestId  请求标识，用于响应与错误体回溯
 */
public record CompileRequest(
        @NotBlank String entry,
        @NotNull Map<String, String> parameters,
        @NotBlank String requestId) {
}
