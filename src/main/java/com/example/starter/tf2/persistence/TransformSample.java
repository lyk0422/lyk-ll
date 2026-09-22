package com.example.starter.tf2.persistence;

import com.example.starter.tf2.domain.EdgeType;
import com.example.starter.tf2.domain.Quat;
import com.example.starter.tf2.domain.Vec3;

import java.time.Instant;

/**
 * 一条变换样本记录（持久化行的内存表示）。
 *
 * @param id          自增主键
 * @param parentFrame 父坐标系名称（边的方向：parent -> child）
 * @param childFrame  子坐标系名称
 * @param type        静态/动态
 * @param stampNanos  样本时间戳（纳秒，单调时钟语义；静态边为 0 且忽略）
 * @param translation 平移分量（米）
 * @param rotation    旋转四元数（x,y,z,w）
 * @param source      样本来源标识，用于同参重放幂等判定
 * @param createdAt   入库时间（审计用）
 */
public record TransformSample(
        Long id,
        String parentFrame,
        String childFrame,
        EdgeType type,
        long stampNanos,
        Vec3 translation,
        Quat rotation,
        String source,
        Instant createdAt
) {
}
