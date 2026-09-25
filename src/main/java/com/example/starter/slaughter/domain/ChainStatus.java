package com.example.starter.slaughter.domain;

/**
 * 同步检验链状态：同一批次同时刻只允许一条检验链。
 */
public enum ChainStatus {

    /** 检验链开启中，可继续登记头只检验结果。 */
    OPEN,
    /** 头只全部一一对应检验完毕，检验链关闭。 */
    COMPLETED
}
