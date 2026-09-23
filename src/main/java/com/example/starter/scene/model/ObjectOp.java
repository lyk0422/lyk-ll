package com.example.starter.scene.model;

/**
 * 碰撞对象操作类型。
 */
public enum ObjectOp {
    /** 新增对象到世界集合。 */
    ADD,
    /** 修改已存在的世界对象（坐标系或几何负载）。 */
    UPDATE,
    /** 删除对象；若处于附着状态则同步删除附着物引用。 */
    DELETE,
    /** 修改对象 ID；附着物引用同步改名。 */
    RENAME
}
