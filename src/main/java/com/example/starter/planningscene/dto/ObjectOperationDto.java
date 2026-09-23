package com.example.starter.planningscene.dto;

import com.example.starter.planningscene.domain.ObjectOperationType;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 批处理中的单个对象操作。不同类型按需取字段：
 * ADD 需要 frameId 或 linkId 二选一；UPDATE 可改几何/位姿/坐标系；
 * RENAME 需要 newId；ATTACH 需要 linkId；DETACH 需要 frameId。
 */
public record ObjectOperationDto(
        @NotNull ObjectOperationType type,
        String objectId,
        String newId,
        String frameId,
        String linkId,
        String geometry,
        PoseDto pose,
        List<String> touchLinks) {
}
