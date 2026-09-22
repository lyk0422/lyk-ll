package com.example.starter.tf.persistence;

import com.example.starter.tf.math.Transform3d;

/**
 * 动态边采样行 tf_dynamic_sample（不含自增主键与落库时间，业务只按时间戳读取）。
 *
 * @param stampNanos 样本时间戳，epoch 纳秒
 * @param source     样本来源标识
 * @param tx         平移 X（米）
 * @param ty         平移 Y（米）
 * @param tz         平移 Z（米）
 * @param qx         四元数 X
 * @param qy         四元数 Y
 * @param qz         四元数 Z
 * @param qw         四元数 W
 */
public record SampleRow(long stampNanos,
                        String source,
                        double tx, double ty, double tz,
                        double qx, double qy, double qz, double qw) {

    /** 转换为领域刚体变换。 */
    public Transform3d toTransform() {
        return new Transform3d(tx, ty, tz, qx, qy, qz, qw);
    }
}
