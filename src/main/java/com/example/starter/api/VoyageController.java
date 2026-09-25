package com.example.starter.api;

import com.example.starter.api.dto.ApiRequests.ArrivalRequest;
import com.example.starter.api.dto.ApiRequests.ClosureRequest;
import com.example.starter.api.dto.ApiRequests.DepartureRequest;
import com.example.starter.api.dto.ApiRequests.LogbookRequest;
import com.example.starter.api.dto.ApiRequests.SettlementRequest;
import com.example.starter.api.dto.ApiResponses.ArrivalResponse;
import com.example.starter.api.dto.ApiResponses.ClosureResponse;
import com.example.starter.api.dto.ApiResponses.DepartureResponse;
import com.example.starter.api.dto.ApiResponses.LogbookResponse;
import com.example.starter.api.dto.ApiResponses.SettlementResponse;
import com.example.starter.service.SettlementService;
import com.example.starter.service.VoyageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 航次业务接口：出港申报 → 作业日志 → 进港报告 → 配额核销 → 结案。
 */
@RestController
@RequestMapping("/api/v1")
public class VoyageController {

    private final VoyageService voyageService;
    private final SettlementService settlementService;

    public VoyageController(VoyageService voyageService, SettlementService settlementService) {
        this.voyageService = voyageService;
        this.settlementService = settlementService;
    }

    /** 出港申报。 */
    @PostMapping("/departures")
    public DepartureResponse depart(@Valid @RequestBody DepartureRequest req) {
        return voyageService.depart(req);
    }

    /** 提交渔捞日志。 */
    @PostMapping("/voyages/{voyageId}/logs")
    public LogbookResponse submitLog(@PathVariable long voyageId,
                                     @Valid @RequestBody LogbookRequest req) {
        return voyageService.submitLog(voyageId, req);
    }

    /** 提交进港报告。 */
    @PostMapping("/voyages/{voyageId}/arrival")
    public ArrivalResponse submitArrival(@PathVariable long voyageId,
                                         @Valid @RequestBody ArrivalRequest req) {
        return voyageService.submitArrival(voyageId, req);
    }

    /** 配额核销。 */
    @PostMapping("/voyages/{voyageId}/settlement")
    public SettlementResponse settle(@PathVariable long voyageId,
                                     @Valid @RequestBody SettlementRequest req) {
        return settlementService.settle(voyageId, req);
    }

    /** 航次结案（终态）。 */
    @PostMapping("/voyages/{voyageId}/closure")
    public ClosureResponse close(@PathVariable long voyageId,
                                 @Valid @RequestBody ClosureRequest req) {
        return voyageService.close(voyageId, req);
    }
}
