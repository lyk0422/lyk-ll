package com.example.starter.settlement;

import com.example.starter.api.dto.DailySettlementResponse;
import java.time.LocalDate;

/**
 * 日结封存：封存为终态，重复封存幂等成功；封存后迟到读数只记迟到事件。
 */
public interface SettlementService {

    DailySettlementResponse seal(Long planId, LocalDate settleDate);
}
