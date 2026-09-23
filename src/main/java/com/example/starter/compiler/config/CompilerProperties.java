package com.example.starter.compiler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 编译服务配置。
 *
 * @param workspace 受控工作目录根；入口与 include 文件必须位于该目录之内
 */
@ConfigurationProperties(prefix = "robot.compiler")
public record CompilerProperties(String workspace) {
}
