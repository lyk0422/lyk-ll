package com.example.starter.tf.domain;

import com.example.starter.tf.math.Transform3d;

import java.util.List;

/**
 * TF2 风格坐标系森林的核心领域服务：
 * 环检测、静态/动态写入幂等与冲突、多跳刚体组合、相邻样本插值、
 * 缓存窗口淘汰，以及读写锁保证的查询快照一致性。
 */
public interface TfService {

    /** 注册坐标系；已存在时幂等返回。 */
    void registerFrame(String frame);

    /** 写入或幂等重放静态变换。 */
    void writeStatic(String parentFrame, String childFrame, String source, Transform3d pose);

    /** 写入或乱序补录动态样本；同参重放幂等，异参冲突 409。 */
    void writeDynamic(String parentFrame, String childFrame, String source,
                      long stampNanos, Transform3d pose);

    /** 查询 stampNanos 时刻 source 在 target 坐标系中的位姿。 */
    LookupResult lookup(String source, String target, long stampNanos);

    /** 对所有动态边执行一次窗口淘汰；返回被删除样本总数。 */
    int evictExpired();
}
