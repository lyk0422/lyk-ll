package com.example.starter.ros.domain;

/**
 * 资源/生命周期变更事件类型。
 */
public final class EventTypes {

    /** 节点声明新资源 */
    public static final String RESOURCE_DECLARED = "RESOURCE_DECLARED";
    /** 资源被释放（注销或崩溃清理） */
    public static final String RESOURCE_RELEASED = "RESOURCE_RELEASED";
    /** 节点异常退出，全部资源已在同一事务中清理（每节点仅一次） */
    public static final String NODE_CRASH_CLEANUP = "NODE_CRASH_CLEANUP";
    /** 生命周期相邻迁移 */
    public static final String LIFECYCLE_TRANSITION = "LIFECYCLE_TRANSITION";

    private EventTypes() {
    }
}
