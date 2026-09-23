package com.example.starter.audit.model;

/**
 * 任务绑定的不可变版本集合，任一版本均为必填。
 *
 * @param codeVersion   代码版本（提交哈希或版本标签）
 * @param configVersion 配置版本或内容摘要
 * @param mapVersion    地图版本或内容摘要
 * @param rosbagVersion 输入 rosbag 版本或内容摘要
 * @param seed          随机种子，保证相同绑定时可确定性重放
 */
public record Bindings(
        String codeVersion,
        String configVersion,
        String mapVersion,
        String rosbagVersion,
        long seed
) {
}
