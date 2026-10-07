package com.example.starter.radio.domain;

/**
 * 未发药整批撤回单状态机（合成数据软件验证，不用于真实给药决策）：
 * 申请只创建 PENDING（冻结全清单，不立即作废）；
 * 两名不同人员批准且最终事务重查全清单全部合法后，一起进入 APPROVED（终态），
 * 同事务把全部分装转为 {@link DispensingStatus#VOID}、终结其全部未生效时刻候选、使核对不再有效；
 * PENDING 可由申请人整体撤销进入 CANCELLED（终态）并释放全部分装占用；已批准不能撤销。
 */
public enum WithdrawalStatus {

    /** 已冻结全清单，等待两名不同人员批准；期间原合法发药与候选批准仍可先行。 */
    PENDING,

    /** 双人批准通过且最终事务重查全清单全部合法，全部分装已一起 VOID，终态。 */
    APPROVED,

    /** 未批准即整体撤销，全部分装占用已释放，终态。 */
    CANCELLED
}
