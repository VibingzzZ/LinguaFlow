package com.javaee.backend.po.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;




@Data
@AllArgsConstructor
@NoArgsConstructor
public class TranslateResponse {
    private String sourceLang;          // 源语言
    private String targetLang;          // 目标语言
    private String sourceText;          // 源文本
    private String targetText;          // 目标文本
    private long processingTimeMs;      // 处理时间（毫秒）
}
