package com.example.starter.outbound;

import com.example.starter.common.ApiException;
import com.example.starter.common.IdempotentExecutor;
import com.example.starter.pest.PestCaseService;
import com.example.starter.pest.Zone;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

/**
 * 出库登记服务：分区拦截——仅禁止处于处置状态（已确认未解除）库区的出库登记，
 * 其它库区借阅不受影响；仅上报未确认的库区不拦截。
 */
@Service
public class OutboundService {

    private final JdbcClient jdbc;
    private final IdempotentExecutor idempotent;
    private final PestCaseService pestCaseService;
    private final Clock clock;

    public OutboundService(JdbcClient jdbc, IdempotentExecutor idempotent,
            PestCaseService pestCaseService, Clock clock) {
        this.jdbc = jdbc;
        this.idempotent = idempotent;
        this.pestCaseService = pestCaseService;
        this.clock = clock;
    }

    /**
     * 出库登记：库区处于处置状态时拒绝（409），否则登记成功。
     */
    public OutboundDtos.OutboundResponse register(String zoneCode, OutboundDtos.OutboundRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("zoneCode", zoneCode);
        payload.put("itemName", request.itemName());
        payload.put("borrower", request.borrower());
        return idempotent.execute(request.requestId(), payload, OutboundDtos.OutboundResponse.class, () -> {
            Zone zone = pestCaseService.zoneByCode(zoneCode);
            long blocked = jdbc.sql("SELECT COUNT(*) FROM zone_active_case ac"
                            + " JOIN pest_case c ON ac.case_id = c.id"
                            + " WHERE ac.zone_id = ? AND c.status <> ?")
                    .param(zone.id()).param(PestCaseService.STATUS_REPORTED)
                    .query(Long.class).single();
            if (blocked > 0) {
                throw ApiException.conflict("库区 " + zoneCode + " 处于虫情处置状态，禁止出库登记");
            }
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.sql("INSERT INTO outbound_record (zone_id, item_name, borrower, created_at)"
                            + " VALUES (?, ?, ?, ?)")
                    .param(zone.id()).param(request.itemName()).param(request.borrower())
                    .param(LocalDateTime.now(clock))
                    .update(keyHolder, "id");
            return new OutboundDtos.OutboundResponse(keyHolder.getKey().longValue(), zoneCode);
        });
    }
}
