package com.example.starter.rosbag.exception;

/**
 * Topic 内序列号倒退（新消息 seq 不大于当前最大有效 seq）。
 */
public class SequenceRegressionException extends RosbagException {

    public SequenceRegressionException(String topic, long requestedSeq, long maxSeq) {
        super("topic " + topic + " 序列号倒退：请求 seq=" + requestedSeq + "，当前最大 seq=" + maxSeq);
    }
}
