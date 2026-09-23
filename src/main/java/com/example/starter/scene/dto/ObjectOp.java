package com.example.starter.scene.dto;

/**
 * 单个碰撞对象批处理操作。type 取值：ADD、MODIFY、DELETE、RENAME。
 *
 * @param type 操作类型
 * @param objectId DELETE/RENAME 的目标对象 ID
 * @param newObjectId RENAME 的新对象 ID，必须全局未占用
 * @param object ADD/MODIFY 携带的对象主体
 */
public record ObjectOp(String type, String objectId, String newObjectId, CollisionObjectBody object) {

    /** 新增对象操作类型。 */
    public static final String ADD = "ADD";
    /** 修改对象操作类型。 */
    public static final String MODIFY = "MODIFY";
    /** 删除对象操作类型。 */
    public static final String DELETE = "DELETE";
    /** 重命名对象操作类型。 */
    public static final String RENAME = "RENAME";
}
