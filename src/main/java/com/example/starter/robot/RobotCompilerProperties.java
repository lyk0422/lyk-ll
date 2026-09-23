package com.example.starter.robot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 机器人模型编译服务配置。
 *
 * @param workspaceRoot 受控工作目录根路径，入口 Xacro 及其全部传递依赖必须位于该目录内
 */
@ConfigurationProperties(prefix = "robot.compiler")
public record RobotCompilerProperties(Path workspaceRoot) {

    public RobotCompilerProperties {
        if (workspaceRoot == null) {
            workspaceRoot = Path.of("robot-workspace");
        }
    }
}
