package com.example.starter.pesticide;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 药剂库存与采购建议查询接口。
 */
@RestController
@RequestMapping("/api")
public class PesticideController {

    private final PesticideService service;

    public PesticideController(PesticideService service) {
        this.service = service;
    }

    /**
     * 药剂库存列表：GET /api/pesticides
     */
    @GetMapping("/pesticides")
    public List<PesticideService.PesticideResponse> list() {
        return service.list();
    }

    /**
     * 采购建议列表：GET /api/purchase-suggestions
     */
    @GetMapping("/purchase-suggestions")
    public List<PesticideService.PurchaseSuggestionResponse> listSuggestions() {
        return service.listSuggestions();
    }
}
