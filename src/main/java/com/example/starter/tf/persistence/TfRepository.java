package com.example.starter.tf.persistence;

import com.example.starter.tf.domain.EdgeType;

import java.util.List;
import java.util.Optional;

/**
 * TF 缓存的数据访问边界；所有 SQL 均为 H2（MODE=MySQL）兼容的参数化语句。
 */
public interface TfRepository {

    /** 注册坐标系；已存在时返回 false。 */
    boolean insertFrameIfAbsent(String frameName, long nowNanos);

    /** 坐标系是否存在。 */
    boolean frameExists(String frameName);

    /** 读取全部边（森林拓扑快照）。 */
    List<EdgeRow> findAllEdges();

    /** 按子坐标系查找唯一入边。 */
    Optional<EdgeRow> findEdgeByChild(String childFrame);

    /** 创建一条边，返回新主键。 */
    long insertEdge(String parentFrame, String childFrame, EdgeType edgeType,
                    String source, long nowNanos);

    /** 读取静态边位姿。 */
    Optional<SampleRow> findStaticPose(long edgeId);

    /** 写入静态边唯一位姿。 */
    void insertStaticPose(long edgeId, String source, SampleRow pose, long nowNanos);

    /** 按 (边, 时间戳) 读取已存动态样本（每条边每时刻至多一个样本）。 */
    Optional<SampleRow> findDynamicSample(long edgeId, long stampNanos);

    /** 插入动态样本；并发撞唯一键时抛出 {@code DuplicateKeyException}。 */
    void insertDynamicSample(long edgeId, SampleRow sample, long nowNanos);

    /** 单调下修动态边历史最早样本水位 first_sample_nanos（取已有值与新时间戳的最小值）。 */
    void touchFirstSample(long edgeId, long stampNanos);

    /** 读取动态边最新样本。 */
    Optional<SampleRow> findLastSample(long edgeId);

    /** 读取包围 stamp 的相邻样本：不晚于 stamp 的最新样本与不早于 stamp 的最早样本。 */
    Optional<SampleRow> findSampleAtOrBefore(long edgeId, long stampNanos);

    Optional<SampleRow> findSampleAtOrAfter(long edgeId, long stampNanos);

    /**
     * 淘汰严格早于 cutoffNanos 的历史样本。
     *
     * @return 淘汰结果（删除条数与被删样本最高时间戳）；没有删除时返回 empty
     */
    Optional<EvictionOutcome> deleteSamplesOlderThan(long edgeId, long cutoffNanos);

    /** 推进边的淘汰水位 evicted_upto_nanos（单调不降）。 */
    void updateEvictedUpto(long edgeId, long evictedUptoNanos);
}
