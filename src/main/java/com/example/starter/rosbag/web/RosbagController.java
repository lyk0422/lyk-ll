package com.example.starter.rosbag.web;

import com.example.starter.rosbag.dto.*;
import com.example.starter.rosbag.service.RosbagService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * rosbag 风格时序存储 HTTP 接口（骨架）。
 */
@RestController
@RequestMapping("/api/rosbag")
public class RosbagController {

    private final RosbagService service;

    public RosbagController(RosbagService service) {
        this.service = service;
    }

    @PostMapping("/messages")
    public WriteResult append(@Valid @RequestBody WriteMessageRequest request) {
        return service.append(request);
    }

    @PostMapping("/messages/query")
    public List<MessageView> query(@RequestBody QueryRequest request) {
        return service.query(request);
    }

    @PostMapping("/replays")
    public ReplayResult replay(@Valid @RequestBody ReplayRequest request) {
        return service.replay(request);
    }

    @PostMapping("/recovery/{topic}")
    public RecoveryResult recover(@PathVariable String topic) {
        return service.recover(topic);
    }

    /**
     * 故障注入：破坏指定消息负载，模拟写入中断造成的不完整消息。
     */
    @PostMapping("/faults/{topic}/message/{seq}/corrupt")
    public java.util.Map<String, Object> corruptMessage(@PathVariable String topic,
                                                        @PathVariable long seq) {
        boolean changed = service.simulateCorruptedMessage(topic, seq);
        return java.util.Map.of("topic", topic, "seq", seq, "corrupted", changed);
    }

    /**
     * 故障注入：删除指定消息的索引行，模拟索引损坏。
     */
    @PostMapping("/faults/{topic}/message/{seq}/index-corrupt")
    public java.util.Map<String, Object> corruptIndex(@PathVariable String topic,
                                                      @PathVariable long seq) {
        int removed = service.simulateCorruptedIndex(topic, seq);
        return java.util.Map.of("topic", topic, "seq", seq, "indexRowsRemoved", removed);
    }

    @GetMapping("/segments/{topic}")
    public List<SegmentView> segments(@PathVariable String topic) {
        return service.segments(topic);
    }
}
