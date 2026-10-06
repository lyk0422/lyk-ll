package com.example.starter.hazmat.domain;

/**
 * 运单状态机。
 *
 * <p>流转：APPLIED（已申请）→ ROUTE_APPROVED（路线核准）→ DISPATCHED（发运）
 * → IN_TRANSIT（在途打卡）→ COMPLETED（到达核销结案，终态）。
 * 路线驳回为 ROUTE_REJECTED（终态）；连续 2 点超时升级 LOST_CONTACT（失联终态，并冻结车辆）。
 */
public enum ShipmentStatus {

    /** 已申请，路线待核准。 */
    APPLIED(false),
    /** 路线核准驳回（终态）。 */
    ROUTE_REJECTED(true),
    /** 路线已核准，待发运。 */
    ROUTE_APPROVED(false),
    /** 已发运。 */
    DISPATCHED(false),
    /** 在途（已有打卡或监控扫描）。 */
    IN_TRANSIT(false),
    /** 到达核销结案（唯一正常终态）。 */
    COMPLETED(true),
    /** 失联（唯一异常终态，车辆冻结）。 */
    LOST_CONTACT(true),
    /** 夜间禁行冻结（未在禁行起点前完成押运换班，转处置，终态）。 */
    CURFEW_FROZEN(true);

    private final boolean terminal;

    ShipmentStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
