package com.example.starter.tf.persistence;

import com.example.starter.tf.domain.EdgeType;

/**
 * 坐标系边表 tf_edge 的行记录。
 *
 * @param id               边主键
 * @param parentFrame      父坐标系
 * @param childFrame       子坐标系（全表唯一）
 * @param edgeType         STATIC / DYNAMIC
 * @param source           最近写入来源
 * @param createdAtNanos   边创建时间，epoch 纳秒
 * @param firstSampleNanos 动态边历史最早样本时间戳（乱序补录取最小值）；静态边为 null
 * @param evictedUptoNanos 动态边已淘汰到的最高样本时间戳；null 表示从未淘汰，静态边恒为 null
 */
public record EdgeRow(long id,
                      String parentFrame,
                      String childFrame,
                      EdgeType edgeType,
                      String source,
                      long createdAtNanos,
                      Long firstSampleNanos,
                      Long evictedUptoNanos) {
}
