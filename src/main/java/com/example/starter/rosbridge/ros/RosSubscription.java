package com.example.starter.rosbridge.ros;

/**
 * 上游 Topic 订阅句柄；关闭即向真实 ROS 注销订阅并释放资源。
 */
public interface RosSubscription extends AutoCloseable {

    @Override
    void close();
}
