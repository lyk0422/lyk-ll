package com.example.starter.specimen.web;

import com.example.starter.specimen.model.EntityType;
import com.example.starter.specimen.service.CustodyService;
import com.example.starter.specimen.web.dto.CustodyEventResponse;
import com.example.starter.specimen.web.dto.TransferRequest;
import com.example.starter.specimen.web.dto.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 保管接口：交接与交接事件查询。
 */
@RestController
@RequestMapping("/api/custody")
public class CustodyController {

    private final CustodyService service;

    public CustodyController(CustodyService service) {
        this.service = service;
    }

    /**
     * 交接：校验当前持有人、时间与版本；重复交接不新增事件。
     */
    @PostMapping("/transfers")
    public TransferResponse transfer(@Valid @RequestBody TransferRequest request) {
        return service.transfer(request);
    }

    /**
     * 查询指定实体的保管交接事件。
     */
    @GetMapping("/events")
    public List<CustodyEventResponse> events(@RequestParam EntityType entityType,
                                             @RequestParam String entityId) {
        return service.events(entityType, entityId);
    }
}
