package com.example.starter.hazmat.domain;

/**
 * 运单状态机：PENDING(申请) -&gt; APPROVED(路线核准) -&gt; IN_TRANSIT(发运在途)
 * -&gt; CLOSED(到达核销结案) / LOST(失联)；REJECTED 为核准拒绝终态。
 * CLOSED 与 LOST 均为终态，二者互斥。
 */
public enum WaybillStatus {
    PENDING,
    APPROVED,
    IN_TRANSIT,
    CLOSED,
    LOST,
    REJECTED
}
