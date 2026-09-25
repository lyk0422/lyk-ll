package com.example.starter.slot;

import com.example.starter.slot.dto.PublicationView;
import com.example.starter.slot.dto.ReturnResultView;
import com.example.starter.slot.dto.SlotResultView;
import com.example.starter.slot.dto.SlotSeriesRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 航班时刻协调 REST 入口。
 */
@RestController
@RequestMapping("/api/slots")
public class SlotController {

    private final SlotService slotService;

    public SlotController(SlotService slotService) {
        this.slotService = slotService;
    }

    /**
     * 提交系列申请：历史优先权达标（>=80%）自动保留占用，否则进入公共池竞争。
     */
    @PostMapping("/requests")
    public ResponseEntity<SlotResultView> apply(@Valid @RequestBody SlotSeriesRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(slotService.apply(request));
    }

    /**
     * 查询系列申请状态。
     */
    @GetMapping("/requests/{requestId}")
    public SlotResultView getRequest(@PathVariable String requestId) {
        return slotService.getRequest(requestId);
    }

    /**
     * 退回系列，释放其占用的全部时刻。
     */
    @PostMapping("/requests/{requestId}/return")
    public ReturnResultView release(@PathVariable String requestId) {
        return slotService.release(requestId);
    }

    /**
     * 发布本期分配表快照，发布后本期冻结。
     */
    @PostMapping("/publications")
    public PublicationView publish() {
        return slotService.publish();
    }
}
