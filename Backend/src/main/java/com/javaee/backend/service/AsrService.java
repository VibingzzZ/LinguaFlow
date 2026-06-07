package com.javaee.backend.service;

/**
 * 语音识别服务接口
 */
public interface AsrService {

    /**
     * 启动实时语音识别会话
     * @param sessionId 会话ID
     * @param callback 识别结果回调
     * @return 是否启动成功
     */
    boolean startRecognition(String sessionId, RecognitionCallback callback);

    /**
     * 发送音频数据
     * @param sessionId 会话ID
     * @param audioData 音频字节数据（PCM/WAV格式）
     */
    void sendAudio(String sessionId, byte[] audioData);

    /**
     * 停止识别会话
     * @param sessionId 会话ID
     */
    void stopRecognition(String sessionId);

    /**
     * 识别结果回调接口
     */
    interface RecognitionCallback {
        /**
         * ASR连接就绪（WebSocket已建立，可以发送音频）
         */
        default void onReady() {}

        /**
         * 收到识别结果
         * @param text 识别文本
         * @param isFinal 是否为最终结果（true=终句，false=中间结果）
         */
        void onResult(String text, boolean isFinal);

        /**
         * 发生错误
         * @param errorMessage 错误信息
         */
        default void onError(String errorMessage) {}
    }
}
