package com.example.starter.audit.engine;

import java.util.Map;

/**
 * 阶段执行结果：指标、产物内容与相对路径、产物 SHA-256 与可重放命令。
 *
 * @param metrics         关键指标（指标名 -> 数值）
 * @param artifactContent 产物字节内容（本平台以内存/本地文件模拟 rosbag 处理产物）
 * @param artifactPath    产物相对路径（相对产物根目录，使用正斜杠）
 * @param artifactSha256  产物字节内容实际 SHA-256
 * @param replayCommand   可重放命令（含绑定版本、种子与上游摘要链）
 */
public record StageExecutionResult(
        Map<String, Double> metrics,
        byte[] artifactContent,
        String artifactPath,
        String artifactSha256,
        String replayCommand) {
}
