package com.example.starter.api.dto;

/**
 * 读数批量上报结果。
 *
 * @param accepted   新入库读数条数
 * @param duplicated 因“测点＋采样时刻”重复而忽略的条数
 * @param late       方案已封存，仅登记迟到事件的条数
 */
public record ReadingIngestResult(int accepted, int duplicated, int late) {
}
