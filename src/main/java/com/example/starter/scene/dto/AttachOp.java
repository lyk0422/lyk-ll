package com.example.starter.scene.dto;

/**
 * 单个附着/解除附着操作。type 取值：ATTACH、DETACH。
 *
 * @param type 操作类型
 * @param objectId 目标对象 ID
 * @param linkName ATTACH 的目标机器人 Link，必须已存在
 * @param frameId DETACH 后对象回到世界时使用的坐标系，为空时默认 world
 */
public record AttachOp(String type, String objectId, String linkName, String frameId) {

    /** 附着操作类型。 */
    public static final String ATTACH = "ATTACH";
    /** 解除附着操作类型。 */
    public static final String DETACH = "DETACH";
}
