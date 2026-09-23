package com.example.starter.ros.service;

import com.example.starter.ros.dto.ParamEvent;

import java.util.List;

/**
 * 参数事件订阅：订阅者 ACK 后保存位置，重连续传大于已确认游标的事件。
 */
public interface EventStreamService {

    /** 拉取大于订阅者已确认游标的事件（不自动 ACK）。 */
    List<ParamEvent> pull(String subscriberId, int limit);

    /** 确认游标。 */
    void ack(String subscriberId, long cursor);

    /** 查看订阅者已确认游标。 */
    long ackedCursor(String subscriberId);
}
