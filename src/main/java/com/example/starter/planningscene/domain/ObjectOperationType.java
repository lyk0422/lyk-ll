package com.example.starter.planningscene.domain;

/**
 * 批量提交中针对单个碰撞对象的操作类型。
 */
public enum ObjectOperationType {
    /** 新增对象（默认进入世界集合；linkId 非空时直接附着）。 */
    ADD,
    /** 修改已存在对象的几何、位姿或引用坐标系。 */
    UPDATE,
    /** 删除对象；被其他对象以坐标系引用时级联删除引用方。 */
    DELETE,
    /** 重命名对象 ID；引用方坐标系同步改写。 */
    RENAME,
    /** 将世界对象附着到指定机器人 Link。 */
    ATTACH,
    /** 将附着物摘除回世界集合，需给出世界坐标系。 */
    DETACH
}
