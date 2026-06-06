package com.javaee.backend.controller;

import com.javaee.backend.config.Result;
import com.javaee.backend.po.vo.TranslateResponse;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.io.File;
import java.util.Map;


@RestController
@RequestMapping("/api")
public class TranlateController {
    /**
     * 翻译接口
     * @param sourceLang 源语言，默认en
     * @param targetLang 目标语言，默认zh
     * @param file 待翻译文本，音频文件
     * @return 翻译结果
     */

    @PostMapping("/translate/blocking")
    public Result<TranslateResponse> tranRequestParamInBlocking(
            File file,
            @RequestParam(value="sourceLang",defaultValue="auto") String sourceLang,
            @RequestParam(value="targetLang") String targetLang){
        //TODO：接入Langchain4j的翻译后的文本
        TranslateResponse response = new TranslateResponse();
        response.setSourceLang(sourceLang);
        response.setTargetLang(targetLang);
        // TODO: 替换为实际的翻译结果
        response.setSourceText("待翻译文本");
        response.setTargetText("翻译后的文本");
        response.setProcessingTimeMs(0L);

        return Result.success(response);

    }

    @PostMapping("/translate/nonblocking")
    public Flux<ServerSentEvent<?>> translateInNonBlocking(File file,
                                                           @RequestParam(value="sourceLang",defaultValue="auto") String sourceLang,
                                                           @RequestParam(value="targetLang") String targetLang) {
        return Flux.defer(() -> {
            ServerSentEvent<?> startEvent = ServerSentEvent.builder()
                    .event("meta")
                    .data(Map.of("code", 200, "message", "streaming start"))
                    .build();

            // 后续事件：流式返回翻译结果
            Flux<String> translationStream = callLangchain4jStreaming(file, sourceLang, targetLang)
                    .map(chunk -> ServerSentEvent.builder()
                            .event("chunk")
                            .data(chunk)
                            .build());

            // 最后一个事件：发送"完成"信号
            ServerSentEvent<?> endEvent = ServerSentEvent.builder()
                    .event("done")
                    .data(Map.of("processingTimeMs", 1234L))
                    .build();

            return Flux.concat(
                    Flux.just(startEvent),
                    translationStream,
                    Flux.just(endEvent)
        });
    }
}