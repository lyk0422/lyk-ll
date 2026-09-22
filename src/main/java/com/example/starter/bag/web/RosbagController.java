package com.example.starter.bag.web;

import com.example.starter.bag.service.RosbagStorageService;
import com.example.starter.bag.web.dto.CorruptSegmentRequest;
import com.example.starter.bag.web.dto.MessageView;
import com.example.starter.bag.web.dto.PlayRequest;
import com.example.starter.bag.web.dto.PlayResult;
import com.example.starter.bag.web.dto.RecoveryResult;
import com.example.starter.bag.web.dto.SegmentView;
import com.example.starter.bag.web.dto.WriteMessageRequest;
import com.example.starter.bag.web.dto.WriteResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * rosbag 风格时序存储 HTTP 入口。
 */
@RestController
@RequestMapping("/api/bag")
public class RosbagController {

    private final RosbagStorageService service;

    public RosbagController(RosbagStorageService service) {
        this.service = service;
    }

    /** 追加单条消息；同批次同参重试幂等，Topic 内序列号不得倒退。 */
    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public WriteResult write(@Valid @RequestBody WriteMessageRequest request) {
        return service.write(request);
    }

    /** 按 Topic + 纳秒时间范围 + 序列号范围组合查询，结果按 (topic, seq) 原序返回。 */
    @GetMapping("/messages")
    public List<MessageView> query(@RequestParam String topic,
                                   @RequestParam(required = false) Long startNs,
                                   @RequestParam(required = false) Long endNs,
                                   @RequestParam(required = false) Long startSeq,
                                   @RequestParam(required = false) Long endSeq) {
        return service.query(topic, startNs, endNs, startSeq, endSeq);
    }

    /** 查看分段及其边界、状态与段级校验。 */
    @GetMapping("/segments")
    public List<SegmentView> segments(@RequestParam String topic) {
        return service.segments(topic);
    }

    /** 测试入口：模拟写入中断或索引损坏。 */
    @PostMapping("/segments/corrupt")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void corrupt(@Valid @RequestBody CorruptSegmentRequest request) {
        service.corrupt(request);
    }

    /** 恢复全部受损分段：同一事务内保留完整前缀、隔离不完整消息并重建索引。 */
    @PostMapping("/recover")
    public RecoveryResult recover() {
        return service.recover();
    }

    /** 按原顺序重复回放；相同 playKey 的重复请求不重复创建回放记录。 */
    @PostMapping("/play")
    public PlayResult play(@Valid @RequestBody PlayRequest request) {
        return service.play(request);
    }
}
