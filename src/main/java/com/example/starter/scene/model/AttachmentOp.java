package com.example.starter.scene.model;

/**
 * 附着物操作类型。
 */
public enum AttachmentOp {
    /** 将世界对象附着到指定机器人 Link（对象移出世界集合）。 */
    ATTACH,
    /** 将附着物取回世界集合，需指定放回的参考坐标系。 */
    DETACH
}
