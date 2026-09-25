package com.example.starter.api;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.ApiResponses.LedgerEntry;
import com.example.starter.repo.ArrivalDao;
import com.example.starter.repo.LogbookDao;
import com.example.starter.repo.QuotaDao;
import com.example.starter.repo.SettlementDao;
import com.example.starter.repo.VoyageDao;
import com.example.starter.repo.VoyageDao.VoyageRow;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 台账与航次明细查询：配额台账、日志链与核销明细均可复算。
 */
@RestController
@RequestMapping("/api/v1")
public class QueryController {

    /** 航次明细：状态流转、日志链、卸渔与核销明细。 */
    public record VoyageDetail(long voyageId, String voyageNo, long vesselId, String status,
                               Instant departureTime, Instant arrivalTime, Instant closedAt,
                               List<LogLine> logs, List<QuantityLine> arrivalItems,
                               List<QuantityLine> settlementItems) {
    }

    /** 日志链条目。 */
    public record LogLine(long logId, LocalDate logDate, String fishingArea, boolean bycatchFlagged) {
    }

    /** 分鱼种数量条目（kg）。 */
    public record QuantityLine(String speciesCode, BigDecimal quantityKg) {
    }

    private final QuotaDao quotaDao;
    private final VoyageDao voyageDao;
    private final LogbookDao logbookDao;
    private final ArrivalDao arrivalDao;
    private final SettlementDao settlementDao;

    public QueryController(QuotaDao quotaDao, VoyageDao voyageDao, LogbookDao logbookDao,
                           ArrivalDao arrivalDao, SettlementDao settlementDao) {
        this.quotaDao = quotaDao;
        this.voyageDao = voyageDao;
        this.logbookDao = logbookDao;
        this.arrivalDao = arrivalDao;
        this.settlementDao = settlementDao;
    }

    /** 查询渔船某年度（UTC）配额台账。 */
    @GetMapping("/vessels/{vesselId}/ledger")
    public List<LedgerEntry> ledger(@PathVariable long vesselId, @RequestParam int year) {
        return quotaDao.listLedger(vesselId, year).stream()
                .map(l -> new LedgerEntry(l.speciesCode(), l.quotaYear(), l.totalQuota(),
                        l.usedQuota(), l.remaining(), l.frozen()))
                .toList();
    }

    /** 查询航次明细（日志链、卸渔与核销明细）。 */
    @GetMapping("/voyages/{voyageId}")
    public VoyageDetail voyage(@PathVariable long voyageId) {
        VoyageRow voyage = voyageDao.findVoyage(voyageId)
                .orElseThrow(() -> ApiException.notFound("航次不存在: " + voyageId));
        List<LogLine> logs = logbookDao.listLogsForVoyage(voyageId).stream()
                .map(l -> new LogLine(l.id(), l.logDate(), l.fishingArea(), l.bycatchFlagged()))
                .toList();
        List<QuantityLine> arrivalItems = arrivalDao.findByVoyage(voyageId)
                .map(a -> arrivalDao.itemsOf(a.id()).stream()
                        .map(i -> new QuantityLine(i.speciesCode(), i.unloadedKg()))
                        .toList())
                .orElse(List.of());
        List<QuantityLine> settlementItems = settlementDao.findByVoyage(voyageId)
                .map(s -> settlementDao.itemsOf(s.id()).stream()
                        .map(i -> new QuantityLine(i.speciesCode(), i.quantityKg()))
                        .toList())
                .orElse(List.of());
        return new VoyageDetail(voyage.id(), voyage.voyageNo(), voyage.vesselId(),
                voyage.status().name(), voyage.departureTime(), voyage.arrivalTime(),
                voyage.closedAt(), logs, arrivalItems, settlementItems);
    }
}
