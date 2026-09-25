package com.example.starter.ventilation;

/**
 * 指令所处阶段：排烟联动（先阀后机）或复位（先风机后风阀的逆序）。
 */
public enum CommandPhase {
    PURGE,
    RESET
}
