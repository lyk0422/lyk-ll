package com.example.starter.blend;

import com.example.starter.blend.BlendDtos.CleaningResponse;
import com.example.starter.blend.BlendDtos.CompleteCleaningRequest;
import com.example.starter.blend.BlendDtos.CompleteCleaningResponse;
import com.example.starter.blend.BlendDtos.CreateCleaningRequest;
import com.example.starter.blend.BlendDtos.TankOccupancyResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 品种切换清洗确认与罐占用视图接口。
 */
@RestController
public class CleaningController {

    private final BlendService service;

    public CleaningController(BlendService service) {
        this.service = service;
    }

    /**
     * 发起清洗确认（按罐唯一，占用窗半开区间登记）。
     */
    @PostMapping("/api/cleaning-orders")
    public CleaningResponse create(@Valid @RequestBody CreateCleaningRequest request) {
        return service.createCleaning(request);
    }

    /**
     * 清洗完成确认（与罐状态复位同一事务）。
     */
    @PostMapping("/api/cleaning-orders/{cleaningId}/complete")
    public CompleteCleaningResponse complete(@PathVariable long cleaningId,
                                             @Valid @RequestBody CompleteCleaningRequest request) {
        return service.completeCleaning(cleaningId, request);
    }

    /**
     * 罐占用视图（执行中调合单、执行中清洗占用窗、当前品种与罐状态）。
     */
    @GetMapping("/api/blend-tanks/occupancy")
    public TankOccupancyResponse occupancy() {
        return service.occupancyView();
    }
}
