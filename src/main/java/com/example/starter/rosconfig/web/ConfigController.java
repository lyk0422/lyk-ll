package com.example.starter.rosconfig.web;

import com.example.starter.rosconfig.dto.ConfigView;
import com.example.starter.rosconfig.dto.TransactionRequest;
import com.example.starter.rosconfig.dto.TransactionResponse;
import com.example.starter.rosconfig.service.ConfigQueryService;
import com.example.starter.rosconfig.service.EventService;
import com.example.starter.rosconfig.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ROS 参数配置中心 REST 入口。
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final TransactionService transactionService;
    private final ConfigQueryService configQueryService;
    private final EventService eventService;

    public ConfigController(TransactionService transactionService,
                            ConfigQueryService configQueryService,
                            EventService eventService) {
        this.transactionService = transactionService;
        this.configQueryService = configQueryService;
        this.eventService = eventService;
    }

    /** 读取节点合并配置视图（默认值 + 已提交值）。 */
    @GetMapping("/nodes/{node}")
    public ConfigView view(@PathVariable String node) {
        return configQueryService.view(node);
    }

    /** 跨节点事务式热更新。 */
    @PostMapping("/transactions")
    public TransactionResponse transact(@Valid @RequestBody TransactionRequest request) {
        return transactionService.execute(request);
    }

    /** 创建待重启静态参数配置（静态参数修改的唯一合法入口，重启后才生效）。 */
    @PostMapping("/nodes/{node}/pending/{name}")
    public ConfigView stagePending(@PathVariable String node, @PathVariable String name,
                                   @RequestBody Map<String, Object> body) {
        return configQueryService.stagePending(node, name, body.get("value"));
    }

    /**
     * 订阅者拉取事件：不传 afterCursor 时按已 ACK 位置断线续传，只返回严格大于该游标的事件。
     */
    @GetMapping("/events")
    public List<EventService.Event> poll(@RequestParam String subscriberId,
                                         @RequestParam(required = false) Long afterCursor,
                                         @RequestParam(defaultValue = "100") int limit) {
        return eventService.poll(subscriberId, afterCursor, limit);
    }

    /** 订阅者确认已消费游标。 */
    @PostMapping("/events/ack")
    public Map<String, Object> ack(@RequestParam String subscriberId, @RequestParam long cursor) {
        eventService.ack(subscriberId, cursor);
        return Map.of("subscriberId", subscriberId, "ackedCursor", eventService.ackedCursor(subscriberId));
    }
}
