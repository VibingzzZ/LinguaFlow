package com.javaee.backend.websocket;

/**
 * websocket事件类型枚举
 */
public enum EventType {
    //客户端->服务端
    AUDIO_CHUNK,        //  Base64编码的音频数据
    CONNECTED,          //  连接成功
    SUBTITLE,           //  实时字幕翻译
    CORRECTION,         //  上下文修正
    COMPLET,            //  一句话完整翻译（ASR稳定后的终版）
    ERROR               //  错误
}
