

<h1 align="center">LinguaFlow</h1>
<p align="center">实时同声传译系统 · Real-time Simultaneous Interpretation</p>

---

<p align="center">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?style=flat-square&logo=spring" alt="Spring Boot"/>
  <img src="https://img.shields.io/badge/Java-17-orange?style=flat-square&logo=java" alt="Java"/>
  <img src="https://img.shields.io/badge/WebSocket-bidirectional-blue?style=flat-square" alt="WebSocket"/>
  <img src="https://img.shields.io/badge/LangChain4j-AI-purple?style=flat-square" alt="LangChain4j"/>
  <img src="https://img.shields.io/badge/阿里云ASR-流式语音识别-critical?style=flat-square" alt="Aliyun ASR"/>
  <img src="https://img.shields.io/badge/Chrome_Extension-Manifest_V3-4285F4?style=flat-square&logo=googlechrome" alt="Chrome Extension"/>
</p>

## 概述

LinguaFlow 是一套基于 **WebSocket 全双工通信** 的实时同声传译解决方案，由 **Spring Boot 后端** 与 **Chrome 浏览器扩展** 两部分组成。系统通过**流式语音识别 (ASR)** 将音频实时转写为文本，经由 **LangChain4j 大语言模型** 完成跨语言翻译，最终通过 WebSocket 推送至 Chrome 扩展浮窗面板，实现端到端延迟低于 200ms 的同传体验。

### 整体架构

```
┌──────────────────┐          WebSocket          ┌─────────────────────┐
│  Chrome 扩展      │ ←──────────────────────────→│  Spring Boot 后端    │
│  · 浮窗翻译面板    │   Audio PCM / Translation    │  · WebSocket 消息分发 │
│  · TTS 语音朗读    │                              │  · 阿里云 ASR 流式识别│
│  · 快捷键控制      │                              │  · LangChain4j LLM   │
└──────────────────┘                              └─────────────────────┘
```

### 项目demo展示视频
通过网盘分享的文件：showcase_video.mp4
链接: https://pan.baidu.com/s/1QZkiSWN9SA7a9U9b6ZQ2JA?pwd=j37k 提取码: j37k

### 后端处理流水线

```
Audio PCM → 阿里云 ASR → Text → LangChain4j LLM → Translation → WebSocket Push
```

### 技术亮点

| 特性 | 说明 |
|:-----|:-----|
| **流式 ASR** | 阿里云智能语音交互，16kHz PCM 流式输入，逐字返回中间结果 |
| **LLM 翻译** | LangChain4j 统一抽象层，可切换任意 OpenAI 兼容模型 |
| **本地缓存** | 常用翻译结果内存缓存，重复查询响应时间 < 50ms |
| **优雅降级** | ASR 服务不可用时自动降级为纯文本模式，不影响核心功能 |
| **Chrome 扩展** | Manifest V3 浏览器扩展，浮窗翻译面板、TTS 语音朗读、快捷键控制 |
| **高可用设计** | 异步非阻塞处理（CompletableFuture + 线程池），单连接不阻塞其他会话 |

---

## 环境要求

| 组件 | 版本 |
|:-----|:-----|
| JDK | 17+ |
| Maven | 3.8+ |
| 浏览器 | Chrome / Edge (WebSocket + MediaRecorder API) |

---

## 快速部署

### 1. 克隆项目

```bash
git clone https://github.com/VibingzzZ/LinguaFlow.git
cd LinguaFlow
```

### 2. 配置密钥

编辑 `Backend/src/main/resources/application.yaml`，确认环境变量引用正确后，在 `Backend/` 目录下创建 `.env` 文件：

```env
# ══════════════════════════════════════
# AI 翻译服务 (七牛云 AIHubMix / OpenAI 兼容)
# ══════════════════════════════════════
AIHUBMIX_BASE_URL=https://api.aihubmix.com/v1
AIHUBMIX_API_KEY=sk-your-api-key-here
AIHUBMIX_MODEL=qwen-plus

# ══════════════════════════════════════
# 阿里云语音识别 (ASR)
# ══════════════════════════════════════
ALIYUN_ACCESS_KEY_ID=LTAI5txxxxxxxxxxxxxx
ALIYUN_ACCESS_KEY_SECRET=your-secret-key-here
ALIYUN_ASR_APP_KEY=your-asr-app-key-here
```

> **安全提示**: `.env` 文件已纳入 `.gitignore`，请勿将包含真实密钥的文件提交至版本控制。

> **账号一致性**: AccessKey ID、AccessKey Secret 与 AppKey 必须归属于**同一个阿里云主账号**或**同一个 RAM 用户**。混合使用会导致 `APPKEY_UID_MISMATCH` 错误。

### 3. 构建并启动

```bash
cd Backend && mvn spring-boot:run
```

启动成功后访问：
- 后端服务：`http://localhost:8080`
- 测试页面：`http://localhost:8080/test.html`

### 4. 安装 Chrome 扩展

1. 打开 Chrome 浏览器，进入 `chrome://extensions/`
2. 开启右上角「开发者模式」
3. 点击「加载已解压的扩展程序」，选择 `ChromeExtension/` 目录
4. 扩展安装后，访问任意网页即可看到右下角浮窗翻译面板
5. 快捷键 `Ctrl+Shift+Space` 可快速开始/停止录音翻译

---

## 项目结构

```
LinguaFlow/
├── Backend/                         # Spring Boot 后端
│   └── src/main/java/com/javaee/backend/
│       ├── config/                  #   配置层
│       │   ├── AsrConfig.java       #     ASR 连接参数配置
│       │   ├── CorsConfig.java      #     跨域策略
│       │   ├── WebSocketConfig.java #     WebSocket 端点 & 缓冲区调优
│       │   └── Result.java          #     统一响应封装
│       ├── controller/              #   REST 控制器
│       │   ├── TestController.java  #     健康检查 & 静态资源
│       │   └── TranlateController.java # 翻译 HTTP 接口
│       ├── service/                 #   业务逻辑层 (接口)
│       │   ├── AsrService.java      #     语音识别服务接口
│       │   ├── InterpretationService.java # 同传会话编排 (核心)
│       │   └── TranslationService.java # 翻译服务接口
│       ├── service/impl/            #   业务逻辑层 (实现)
│       │   ├── AsrServiceImpl.java  #     阿里云 ASR SDK 集成
│       │   └── TranslationServiceImpl.java # LangChain4j LLM 翻译
│       ├── websocket/               #   通信层
│       │   ├── EventType.java       #     事件类型枚举
│       │   └── InterpretationHandler.java # WebSocket 消息分发
│       └── BackendApplication.java  #   应用入口
│
├── ChromeExtension/                 # Chrome 浏览器扩展 (Manifest V3)
│   ├── background.js                #   后台 Service Worker (TTS 队列管理)
│   ├── content.js                   #   Content Script (浮窗面板 + 音频采集)
│   ├── popup.html                   #   扩展弹出设置面板
│   ├── popup.js                     #   设置面板交互逻辑
│   ├── styles/panel.css             #   浮窗面板样式
│   ├── icons/                       #   扩展图标 (16x16 / 48x48 / 128x128)
│   └── manifest.json                #   扩展清单
│
└── README.md                        # 项目文档
```

---

## 协议规范

### WebSocket 端点

```
ws://{host}:8080/api/ws/interpretation
```

### 消息协议 (JSON)

#### 客户端 → 服务端

```jsonc
// 音频数据包 (Base64 编码 PCM)
{ "type": "AUDIO_CHUNK", "data": "<base64-pcm>" }

// 文本翻译请求
{ "type": "TEXT_INPUT", "data": "hello world", "sourceLang": "en", "targetLang": "zh" }

// 录音停止信号
{ "type": "STOP_AUDIO" }
```

#### 服务端 → 客户端

```jsonc
// 连接确认
{ "type": "CONNECTED", "sessionId": "...", "sourceLang": "en", "targetLang": "zh" }

// ASR 中间结果 (流式)
{ "type": "ASR_INTERMEDIATE", "text": "hello", "isFinal": false }

// ASR 最终结果 (句子结束)
{ "type": "ASR_FINAL", "text": "hello world", "isFinal": true }

// 翻译完成
{ "type": "TRANSLATION_RESULT", "original": "hello world", "translated": "你好世界" }
```

---

<div align="center">

### 🎬 功能演示

![展示视频](docs/showcase.gif)

</div>

---

## 使用指南

### 操作流程

```
① 建立 WebSocket 连接        ② 选择源/目标语言对
        ↓                            ↓
   [已连接]                    [英语 → 中文]
        ↓                            ↓
③ 点击「开始录音」           ④ 对着麦克风说话
   (浏览器请求麦克风权限)       (波形条实时反馈音量)
        ↓                            ↓
⑤ 点击「停止录音」           ⑥ 查看翻译结果
   (触发 ASR 最终识别)         (左侧原文 / 右侧译文)
```

### 状态面板说明

| 指示器 | 状态 | 含义 |
|:------:|:----:|:-----|
| ASR 语音识别 | 🟢 已启用 | 流式语音识别正常运行 |
| | 🟡 已停止 | ASR 未启动或已降级为文本模式 |
| AI 翻译 | 🟢 就绪 | 翻译服务可用 |
| | 🟡 翻译中 | 正在等待 LLM 返回结果 |
| | 🔴 错误 | 模型未配置或调用异常 |
| 音频输入 | ■■■■□ 波形条 | 实时麦克风音量电平 |
| 已发送数据包 | N 包 | 累计发送的音频数据包数量 |

---

## 故障排查

| 错误信息 | 原因 | 解决方案 |
|:---------|:-----|:---------|
| `SignatureDoesNotMatch` | AccessKey Secret 配置错误或引用了错误的变量 | 检查 `.env` 中 `ALIYUN_ACCESS_KEY_SECRET` 是否正确填写，确认 `application.yaml` 中引用的是 `${ALIYUN_ACCESS_KEY_SECRET}` 而非其他变量 |
| `APPKEY_NOT_EXIST` | AppKey 在当前账号下不存在 | 登录 [NLS 控制台](https://nls-portal.console.aliyun.com/apalist) 确认项目存在且未过期 |
| `APPKEY_UID_MISMATCH` | AccessKey 与 AppKey 属于不同身份（主账号 vs RAM 用户） | 确保 AccessKey 和 AppKey 由**同一个身份**创建。在两个控制台右上角核对登录账号是否一致 |
| `STATE_CLOSED` | 向已关闭的 ASR 会话发送音频 | 已在代码层面修复：增加状态检查与静默跳过逻辑 |
| WebSocket 连接断开 | 音频消息超出默认 8KB 缓冲区限制 | 已在 `WebSocketConfig` 中将缓冲区扩容至 512KB / 1MB |
| `[AI未配置]` | LangChain4j ChatModel 注入失败 | 检查 Maven 依赖是否完整加载，确认 `.env` 中 AIHubMix 配置有效 |

---

## 开发路线图

- [x] WebSocket 实时双向通信
- [x] 阿里云 ASR 流式语音识别接入
- [x] LangChain4j LLM 翻译集成
- [x] 本地翻译缓存加速
- [x] ASR 不可用时优雅降级
- [x] TTS 语音合成输出（翻译结果朗读）
- [ ] 多会话并发管理与隔离
- [ ] 翻译历史持久化存储

---

## License

This project is licensed under the MIT License.