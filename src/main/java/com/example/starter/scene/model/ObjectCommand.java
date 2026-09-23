package com.example.starter.scene.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

/**
 * 单个碰撞对象操作。
 *
 * @param op          操作类型
 * @param objectId    目标对象 ID
 * @param newObjectId RENAME 时的新 ID
 * @param frameId     ADD/UPDATE/DETACH-隐含场景下对象在世界中的参考坐标系（Link 名）
 * @param payload     对象几何等属性，JSON 原样持久化；可为空
 */
public record ObjectCommand(
        @NotNull ObjectOp op,
        String objectId,
        String newObjectId,
        String frameId,
        JsonNode payload) {
}
