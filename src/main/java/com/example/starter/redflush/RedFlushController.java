package com.example.starter.redflush;

import com.example.starter.redflush.RedFlushService.ConfirmRequest;
import com.example.starter.redflush.RedFlushService.ConfirmResponse;
import com.example.starter.redflush.RedFlushService.CreateRedFlushRequest;
import com.example.starter.redflush.RedFlushService.CreateRedFlushResponse;
import com.example.starter.redflush.RedFlushService.RedFlushRequestView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 红冲接口：红字信息表申请、购销双方确认与详情查询。
 */
@RestController
@RequestMapping("/api/red-flush/requests")
public class RedFlushController {

    private final RedFlushService redFlushService;

    public RedFlushController(RedFlushService redFlushService) {
        this.redFlushService = redFlushService;
    }

    @PostMapping
    public CreateRedFlushResponse createRequest(@RequestBody CreateRedFlushRequest request) {
        return redFlushService.createRequest(request);
    }

    @PostMapping("/{requestNo}/confirm")
    public ConfirmResponse confirm(@PathVariable String requestNo, @RequestBody ConfirmRequest request) {
        return redFlushService.confirm(requestNo, request);
    }

    @GetMapping("/{requestNo}")
    public RedFlushRequestView getRequest(@PathVariable String requestNo) {
        return redFlushService.getRequest(requestNo);
    }
}
