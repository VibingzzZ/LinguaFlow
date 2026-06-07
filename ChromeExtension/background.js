// LinguaFlow Chrome Extension - Background Service Worker
// 完整版：TTS队列管理、配置同步、消息转发

// 配置
let config = {
  sourceLang: 'en',
  targetLang: 'zh',
  audioSource: 'system',
  ttsEnabled: true,
  ttsRate: 1.0,
  ttsPitch: 1.0
};

// TTS 队列管理
let ttsQueue = [];
let isSpeaking = false;
let currentUtterance = null;

// 语言代码映射
const LANG_MAP = {
  'zh': 'zh-CN',
  'en': 'en-US',
  'ja': 'ja-JP',
  'ko': 'ko-KR',
  'fr': 'fr-FR',
  'de': 'de-DE',
  'es': 'es-ES',
  'ru': 'ru-RU'
};

function getVoiceLang(langCode) {
  return LANG_MAP[langCode] || 'zh-CN';
}

// TTS 队列处理
function processTTSQueue() {
  if (!config.ttsEnabled || ttsQueue.length === 0 || isSpeaking) {
    return;
  }

  const item = ttsQueue.shift();
  isSpeaking = true;

  chrome.tts.speak(item.text, {
    lang: item.lang || getVoiceLang(config.targetLang),
    rate: config.ttsRate,
    pitch: config.ttsPitch,
    onEvent: function(event) {
      console.log('[LinguaFlow] TTS Event:', event.type);
      if (event.type === 'end' || event.type === 'interrupted' || event.type === 'cancelled' || event.type === 'error') {
        isSpeaking = false;
        currentUtterance = null;
        // 继续处理队列
        setTimeout(() => processTTSQueue(), 100);
      }
    }
  });

  currentUtterance = item;
  console.log('[LinguaFlow] TTS开始朗读:', item.text.substring(0, 50) + '...');
}

function addToTTSQueue(text, lang) {
  if (!text || !text.trim()) return;
  
  ttsQueue.push({
    text: text.trim(),
    lang: lang || getVoiceLang(config.targetLang),
    timestamp: Date.now()
  });

  // 限制队列长度，防止内存泄漏
  if (ttsQueue.length > 10) {
    ttsQueue = ttsQueue.slice(-10);
  }

  processTTSQueue();
}

function stopTTS() {
  chrome.tts.stop();
  isSpeaking = false;
  currentUtterance = null;
  ttsQueue = [];
  console.log('[LinguaFlow] TTS已停止');
}

// 监听来自popup和content script的消息
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  switch (message.type) {
    case 'UPDATE_CONFIG':
      config = { ...config, ...message.config };
      sendResponse({ success: true });
      break;

    case 'WS_CONNECTED':
      chrome.action.setBadgeText({ text: 'ON' });
      chrome.action.setBadgeBackgroundColor({ color: '#4caf50' });
      break;

    case 'WS_DISCONNECTED':
      chrome.action.setBadgeText({ text: 'OFF' });
      chrome.action.setBadgeBackgroundColor({ color: '#f44336' });
      break;

    case 'TTS_SPEAK':
      if (message.text && config.ttsEnabled) {
        addToTTSQueue(message.text, message.lang);
      }
      sendResponse({ success: true });
      break;

    case 'TTS_STOP':
      stopTTS();
      sendResponse({ success: true });
      break;

    case 'TTS_GET_STATUS':
      sendResponse({
        isSpeaking: isSpeaking,
        queueLength: ttsQueue.length,
        currentText: currentUtterance?.text || ''
      });
      break;

    case 'GET_STATUS':
      sendResponse({
        connected: false,
        recording: false,
        ttsEnabled: config.ttsEnabled,
        config: config
      });
      break;
  }
});

// 键盘快捷键
chrome.commands.onCommand.addListener((command) => {
  chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
    if (tabs[0]) {
      chrome.tabs.sendMessage(tabs[0].id, {
        type: command === 'start-recording' ? 'START_RECORDING' : 'TOGGLE_PANEL',
        config: config
      });
    }
  });
});

console.log('[LinguaFlow] Background Service Worker已加载');