package com.example.starter.playout.adapter;

/**
 * 播出适配器指令（发往虚构的下游播出设备）。
 *
 * @param channel      频道标识
 * @param asRunSequence 频道内单调递增的播出序号
 * @param content      指令内容描述（素材摘要或直播插播requestId）
 */
public record PlayoutCommand(String channel, long asRunSequence, String content) {
}
