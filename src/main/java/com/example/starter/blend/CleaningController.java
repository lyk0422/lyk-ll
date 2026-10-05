package com.example.starter.blend;

import com.example.starter.blend.BlendDtos.CleaningResponse;
import com.example.starter.blend.BlendDtos.CompleteCleaningRequest;
import com.example.starter.blend.BlendDtos.CreateCleaningRequest;
import com.example.starter.blend.BlendDtos.TankOccupancyView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 品种切换清洗控制接口：清洗发起、完成确认与罐占用视图。
 */
@RestController
public class CleaningController {

    private final CleaningService service;

    public CleaningController(CleaningService service) {
        this.service = service;
    }

    /**
     * 发起清洗（时间窗半开区间登记，同罐同时刻只允许一张执行中清洗单）。
     */
    @PostMapping("/api/cleaning-orders")
    public CleaningResponse create(@Valid @RequestBody CreateCleaningRequest request) {
        return service.createCleaning(request);
    }

    /**
     * 清洗完成确认（与罐状态复位同一事务）。
     */
    @PostMapping("/api/cleaning-orders/{cleaningId}/complete")
    public CleaningResponse complete(@PathVariable long cleaningId,
                                     @Valid @RequestBody CompleteCleaningRequest request) {
        return service.completeCleaning(cleaningId, request);
    }

    /**
     * 罐占用视图（罐品种状态、执行中调合单占用与清洗占用窗）。
     */
    @GetMapping("/api/blend-tanks/occupancy")
    public List<TankOccupancyView> occupancy() {
        return service.occupancyView();
    }
}
