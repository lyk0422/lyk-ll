package com.example.starter.flood.repo;

import java.math.BigDecimal;

/**
 * 尾矿库档案 PO。
 */
public class PondPO {
    public Long id;
    public String pondCode;
    public String pondName;
    /** 下游安全泄量，立方米每秒。 */
    public BigDecimal downstreamSafeRelease;
    /** 干滩长度安全下限，米。 */
    public BigDecimal dryBeachLimit;
    /** 浸润线埋深安全下限，米。 */
    public BigDecimal phreaticLimit;
}
