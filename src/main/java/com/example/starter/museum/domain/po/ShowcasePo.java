package com.example.starter.museum.domain.po;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 展柜行。温湿度区间按材质设定：
 * tempMin/tempMax 单位摄氏度；humMin/hum_max 单位 %RH。
 * frozen=TRUE 表示紧急工单未处置，冻结该展柜新入藏。
 */
public class ShowcasePo {
    public Long id;
    public String showcaseNo;
    public Long hallId;
    public String material;
    public BigDecimal tempMin;
    public BigDecimal tempMax;
    public BigDecimal humMin;
    public BigDecimal humMax;
    public Boolean frozen;
    public String frozenReason;
    public Instant createdAt;
}
