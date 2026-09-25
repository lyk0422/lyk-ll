package com.example.starter.feed.error;

/**
 * 业务前置条件不满足（如未找到配方版本、批次物种不存在、投产版本未批准等），映射为 400。
 */
public class FeedBusinessException extends RuntimeException {

    public FeedBusinessException(String message) {
        super(message);
    }
}
