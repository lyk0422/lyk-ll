package com.example.starter.asrun;

import com.example.starter.ackmodel.AckRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * as-run 查询与适配器 ACK 端点。
 */
@RestController
@RequestMapping("/api")
public class AsRunController {

    private final AsRunService asRunService;

    public AsRunController(AsRunService asRunService) {
        this.asRunService = asRunService;
    }

    @PostMapping("/as-run/ack")
    public AsRunRecord ack(@Valid @RequestBody AckRequest request) {
        return asRunService.ack(request);
    }

    @GetMapping("/channels/{channelId}/as-run")
    public List<AsRunRecord> query(@PathVariable String channelId,
                                   @RequestParam(required = false) Long fromMs,
                                   @RequestParam(required = false) Long toMs) {
        return asRunService.query(channelId, fromMs, toMs);
    }
}
