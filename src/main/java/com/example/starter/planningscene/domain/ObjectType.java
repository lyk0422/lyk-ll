package com.example.starter.planningscene.domain;

/**
 * 碰撞对象归属集合：世界碰撞对象或机器人附着物，同一对象任一时刻只能属于其中之一。
 */
public enum ObjectType {
    /** 世界碰撞对象，通过 frameId 引用坐标系。 */
    WORLD,
    /** 附着物，通过 linkId 引用机器人 Link。 */
    ATTACHED
}
