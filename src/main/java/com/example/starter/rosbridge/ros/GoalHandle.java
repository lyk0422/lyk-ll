package com.example.starter.rosbridge.ros;

/**
 * 适配器侧 Action goal 句柄，用于向适配器发起取消。
 */
public interface GoalHandle {

    /** 请求适配器取消 goal；幂等，重复调用无副作用。 */
    void cancel();
}
