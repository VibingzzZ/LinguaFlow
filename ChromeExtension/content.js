// LinguaFlow Chrome Extension - Content Script

(function() {
  'use strict';

  const WS_URL = 'ws://localhost:8080/api/ws/interpretation';
  const MAX_RECONNECT_ATTEMPTS = 5;
  const RECONNECT_DELAY = 2000;

  // 安全地向 background 发送消息（忽略错误）
  function sendToBackground(message) {
    try {
      chrome.runtime.sendMessage(message, function(response) {
        // 检查是否有错误（background 可能未加载）
        if (chrome.runtime.lastError) {
          console.debug('[LinguaFlow] Background 未响应:', chrome.runtime.lastError.message);
        }
      });
    } catch (e) {
      // 静默忽略 "Receiving end does not exist" 错误
      console.debug('[LinguaFlow] 无法发送到 background:', e.message);
    }
  }

  let ws = null;
  let isRecording = false;
  let audioContext = null;
  let processor = null;
  let audioBuffer = [];
  let sendInterval = null;
  let captureStream = null;
  let reconnectAttempts = 0;
  let reconnectTimer = null;

  let config = {
    sourceLang: 'en',
    targetLang: 'zh',
    audioSource: 'system',
    ttsEnabled: true
  };

  let panel = null;
  let sourceTextEl = null;
  let targetTextEl = null;
  let statusEl = null;
  let controlsEl = null;
  let langSelectEl = null;
  let ttsToggleEl = null;

  function createPanel() {
    if (panel) return;

    panel = document.createElement('div');
    panel.id = 'linguaflow-panel';
    panel.style.cssText = 'position:fixed;bottom:20px;right:20px;width:420px;background:white;border-radius:12px;box-shadow:0 8px 32px rgba(0,0,0,0.15);z-index:999999;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;overflow:hidden;';

    const header = document.createElement('div');
    header.id = 'lf-header';
    header.style.cssText = 'padding:12px 16px;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);color:white;display:flex;justify-content:space-between;align-items:center;cursor:move;';
    header.innerHTML = '<span style="font-weight:600;font-size:14px;">🌐 LinguaFlow 实时翻译</span><div><button id="lf-minimize" style="background:rgba(255,255,255,0.2);border:none;color:white;width:24px;height:24px;border-radius:4px;cursor:pointer;margin-right:4px;">-</button><button id="lf-close" style="background:rgba(255,255,255,0.2);border:none;color:white;width:24px;height:24px;border-radius:4px;cursor:pointer;">x</button></div>';

    const body = document.createElement('div');
    body.id = 'lf-body';
    body.style.cssText = 'padding:16px;';

    controlsEl = document.createElement('div');
    controlsEl.id = 'lf-controls';
    controlsEl.style.cssText = 'display:flex;gap:8px;margin-bottom:12px;align-items:center;flex-wrap:wrap;';
    controlsEl.innerHTML = `
      <select id="lf-source-lang" style="flex:1;padding:6px 8px;border:1px solid #ddd;border-radius:6px;font-size:12px;">
        <option value="en">English</option>
        <option value="ja">日本語</option>
        <option value="ko">한국어</option>
        <option value="fr">Français</option>
        <option value="de">Deutsch</option>
        <option value="es">Español</option>
        <option value="ru">Русский</option>
      </select>
      <span style="color:#999;">→</span>
      <select id="lf-target-lang" style="flex:1;padding:6px 8px;border:1px solid #ddd;border-radius:6px;font-size:12px;">
        <option value="zh">中文</option>
        <option value="en">English</option>
        <option value="ja">日本語</option>
        <option value="ko">한국어</option>
      </select>
      <button id="lf-tts-toggle" style="padding:6px 12px;border:1px solid #4caf50;background:#e8f5e9;color:#4caf50;border-radius:6px;font-size:12px;cursor:pointer;">🔊 TTS</button>
    `;

    statusEl = document.createElement('div');
    statusEl.id = 'lf-status';
    statusEl.style.cssText = 'padding:8px;background:#f0f0f0;border-radius:6px;margin-bottom:12px;font-size:12px;text-align:center;transition:all 0.3s;';
    statusEl.textContent = '等待连接...';

    sourceTextEl = document.createElement('div');
    sourceTextEl.id = 'lf-source';
    sourceTextEl.style.cssText = 'color:#666;font-size:14px;margin-bottom:8px;padding:10px;background:#f9f9f9;border-radius:6px;min-height:40px;line-height:1.5;';
    sourceTextEl.textContent = '原文将显示在这里';

    targetTextEl = document.createElement('div');
    targetTextEl.id = 'lf-target';
    targetTextEl.style.cssText = 'color:#333;font-size:16px;font-weight:500;padding:10px;background:linear-gradient(135deg,#e8f5e9 0%,#f1f8e9 100%);border-radius:6px;min-height:40px;line-height:1.5;';
    targetTextEl.textContent = '翻译结果将显示在这里';

    body.appendChild(controlsEl);
    body.appendChild(statusEl);
    body.appendChild(sourceTextEl);
    body.appendChild(targetTextEl);
    panel.appendChild(header);
    panel.appendChild(body);
    document.body.appendChild(panel);

    document.getElementById('lf-minimize').addEventListener('click', function() {
      var b = document.getElementById('lf-body');
      b.style.display = b.style.display === 'none' ? 'block' : 'none';
    });

    document.getElementById('lf-close').addEventListener('click', function() {
      panel.style.display = 'none';
    });

    document.getElementById('lf-source-lang').addEventListener('change', function(e) {
      config.sourceLang = e.target.value;
      sendToBackground({ type: 'UPDATE_CONFIG', config: { sourceLang: config.sourceLang } });
    });

    document.getElementById('lf-target-lang').addEventListener('change', function(e) {
      config.targetLang = e.target.value;
      sendToBackground({ type: 'UPDATE_CONFIG', config: { targetLang: config.targetLang } });
    });

    document.getElementById('lf-tts-toggle').addEventListener('click', function(e) {
      config.ttsEnabled = !config.ttsEnabled;
      e.target.style.background = config.ttsEnabled ? '#e8f5e9' : '#f5f5f5';
      e.target.style.color = config.ttsEnabled ? '#4caf50' : '#999';
      e.target.style.borderColor = config.ttsEnabled ? '#4caf50' : '#ddd';
      e.target.textContent = config.ttsEnabled ? '🔊 TTS' : '🔇 TTS';
      sendToBackground({ type: 'UPDATE_CONFIG', config: { ttsEnabled: config.ttsEnabled } });
    });

    initDrag();
    updateControlsFromConfig();
  }

  function updateControlsFromConfig() {
    var sourceSelect = document.getElementById('lf-source-lang');
    var targetSelect = document.getElementById('lf-target-lang');
    var ttsBtn = document.getElementById('lf-tts-toggle');

    if (sourceSelect) sourceSelect.value = config.sourceLang;
    if (targetSelect) targetSelect.value = config.targetLang;
    if (ttsBtn) {
      ttsBtn.style.background = config.ttsEnabled ? '#e8f5e9' : '#f5f5f5';
      ttsBtn.style.color = config.ttsEnabled ? '#4caf50' : '#999';
      ttsBtn.style.borderColor = config.ttsEnabled ? '#4caf50' : '#ddd';
      ttsBtn.textContent = config.ttsEnabled ? '🔊 TTS' : '🔇 TTS';
    }
  }

  function initDrag() {
    var header = document.getElementById('lf-header');
    var isDragging = false;
    var startX, startY, startLeft, startTop;

    header.addEventListener('mousedown', function(e) {
      isDragging = true;
      startX = e.clientX;
      startY = e.clientY;
      startLeft = panel.offsetLeft;
      startTop = panel.offsetTop;
      panel.style.transition = 'none';
      e.preventDefault();
    });

    document.addEventListener('mousemove', function(e) {
      if (!isDragging) return;
      panel.style.left = (startLeft + (e.clientX - startX)) + 'px';
      panel.style.top = (startTop + (e.clientY - startY)) + 'px';
      panel.style.right = 'auto';
      panel.style.bottom = 'auto';
    });

    document.addEventListener('mouseup', function() {
      isDragging = false;
      panel.style.transition = '';
    });
  }

  function updateStatus(text, type) {
    if (!statusEl) return;
    statusEl.textContent = text;
    statusEl.style.background = type === 'connected' ? '#e8f5e9' : type === 'error' ? '#ffebee' : type === 'active' ? '#e3f2fd' : '#f0f0f0';
    statusEl.style.color = type === 'connected' ? '#4caf50' : type === 'error' ? '#f44336' : type === 'active' ? '#2196f3' : '#666';
  }

  function connectWebSocket() {
    if (ws && ws.readyState === WebSocket.OPEN) return;

    if (ws) {
      ws.close();
      ws = null;
    }

    updateStatus('连接中...', 'active');

    ws = new WebSocket(WS_URL);

    ws.onopen = function() {
      console.log('[LinguaFlow] WebSocket connected');
      updateStatus('已连接', 'connected');
      reconnectAttempts = 0;
      sendToBackground({ type: 'WS_CONNECTED' });
    };

    ws.onmessage = function(event) {
      try {
        var msg = JSON.parse(event.data);
        console.log('[LinguaFlow] Message:', msg.type, msg);

        if (msg.type === 'CONNECTED') {
          updateStatus('已连接 - ' + (msg.message || ''), 'connected');
        } else if (msg.type === 'SUBTITLE') {
          updateStatus('识别中...', 'active');
          if (sourceTextEl) sourceTextEl.textContent = msg.sourceText || '';
          if (targetTextEl) targetTextEl.textContent = msg.targetText || '翻译中...';
        } else if (msg.type === 'COMPLET') {
          updateStatus('翻译完成', 'connected');
          if (sourceTextEl) sourceTextEl.textContent = msg.sourceText || '';
          if (targetTextEl) targetTextEl.textContent = msg.targetText || '';
        } else if (msg.type === 'ASR_INTERMEDIATE') {
          if (sourceTextEl) sourceTextEl.textContent = msg.text || '';
        } else if (msg.type === 'ASR_FINAL') {
          if (sourceTextEl) sourceTextEl.textContent = msg.text || '';
        } else if (msg.type === 'TRANSLATION_RESULT') {
          if (targetTextEl) targetTextEl.textContent = msg.translated || '';
        } else if (msg.type === 'ERROR') {
          updateStatus('错误: ' + msg.message, 'error');
        } else if (msg.type === 'TTS_SPEAK') {
          updateStatus('朗读中...', 'active');
          var ttsText = msg.text || msg.targetText;
          if (ttsText && config.ttsEnabled) {
            console.log('[LinguaFlow] TTS:', ttsText);
            sendToBackground({
              type: 'TTS_SPEAK',
              text: ttsText,
              lang: msg.language || config.targetLang
            });
          }
        } else if (msg.type === 'CORRECTION') {
          console.log('[LinguaFlow] 翻译修正:', msg.oldTranslation, '→', msg.newTranslation);
        }
      } catch (err) {
        console.error('[LinguaFlow] Parse error:', err);
      }
    };

    ws.onclose = function() {
      console.log('[LinguaFlow] WebSocket closed');
      updateStatus('已断开', 'error');
      sendToBackground({ type: 'WS_DISCONNECTED' });

      if (isRecording && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
        reconnectAttempts++;
        updateStatus('重连中... (' + reconnectAttempts + '/' + MAX_RECONNECT_ATTEMPTS + ')', 'active');
        reconnectTimer = setTimeout(function() {
          connectWebSocket();
        }, RECONNECT_DELAY);
      }
    };

    ws.onerror = function(err) {
      console.error('[LinguaFlow] WebSocket error:', err);
      updateStatus('连接失败', 'error');
    };
  }

  function uint8ToBase64(uint8Array) {
    var CHUNK_SIZE = 0x8000;
    var result = '';
    for (var i = 0; i < uint8Array.length; i += CHUNK_SIZE) {
      var chunk = uint8Array.subarray(i, i + CHUNK_SIZE);
      result += String.fromCharCode.apply(null, chunk);
    }
    return btoa(result);
  }

  function float32ToPCM16(floatData) {
    var pcm16 = new Int16Array(floatData.length);
    for (var i = 0; i < floatData.length; i++) {
      var s = Math.max(-1, Math.min(1, floatData[i]));
      pcm16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
    }
    return pcm16;
  }

  // 简单的降采样函数（从浏览器采样率降到 16kHz）
  function resampleTo16k(pcm16Data, originalSampleRate) {
    if (originalSampleRate === 16000) {
      return pcm16Data;
    }
    
    var ratio = originalSampleRate / 16000;
    var newLength = Math.floor(pcm16Data.length / ratio);
    var resampled = new Int16Array(newLength);
    
    for (var i = 0; i < newLength; i++) {
      var srcIndex = Math.floor(i * ratio);
      resampled[i] = pcm16Data[srcIndex];
    }
    
    return resampled;
  }

  function processAudio(stream) {
    audioContext = new (window.AudioContext || window.webkitAudioContext)();
    if (audioContext.state === 'suspended') {
      audioContext.resume();
    }

    var sourceSampleRate = audioContext.sampleRate;
    console.log('[LinguaFlow] 浏览器采样率:', sourceSampleRate, 'Hz');

    var source = audioContext.createMediaStreamSource(stream);
    var bufferSize = 4096;
    processor = audioContext.createScriptProcessor(bufferSize, 1, 1);

    audioBuffer = [];
    var chunkCount = 0;
    var totalSamples = 0;

    processor.onaudioprocess = function(e) {
      if (!isRecording) return;
      var floatData = e.inputBuffer.getChannelData(0);
      var pcm16 = float32ToPCM16(floatData);
      
      // 降采样到 16kHz（阿里云 ASR 要求）
      var resampled = resampleTo16k(pcm16, sourceSampleRate);

      for (var i = 0; i < resampled.length; i++) {
        var sample = resampled[i];
        var uSample = sample < 0 ? sample + 65536 : sample;
        audioBuffer.push(uSample & 0xFF);
        audioBuffer.push((uSample >> 8) & 0xFF);
      }
      chunkCount++;
      totalSamples += resampled.length;
    };

    source.connect(processor);
    processor.connect(audioContext.destination);

    isRecording = true;

    sendInterval = setInterval(function() {
      if (!isRecording || !ws || ws.readyState !== WebSocket.OPEN) return;

      // 16kHz 16bit 单声道：每秒 32000 字节
      // 每次发送约 0.1 秒的音频（3200 字节）
      var MIN_SAMPLES = 3200;
      if (audioBuffer.length < MIN_SAMPLES * 2) return;

      var chunkLen = Math.min(audioBuffer.length, 6400);
      var chunk = new Uint8Array(audioBuffer.splice(0, chunkLen));
      var base64 = uint8ToBase64(chunk);

      ws.send(JSON.stringify({
        type: 'AUDIO_CHUNK',
        data: base64,
        sourceLang: config.sourceLang,
        targetLang: config.targetLang
      }));
    }, 100);

    updateStatus('录音中...', 'active');
    console.log('[LinguaFlow] 录音开始, 浏览器采样率:', sourceSampleRate, 'Hz, 目标采样率: 16000 Hz');
  }

  async function startRecording() {
    if (isRecording) return;

    try {
      var stream;

      if (config.audioSource === 'system') {
        stream = await navigator.mediaDevices.getDisplayMedia({
          video: true,
          audio: true,
          preferCurrentTab: false,
          selfBrowserSurface: 'include',
          systemAudio: 'include'
        });

        var audioTracks = stream.getAudioTracks();
        if (audioTracks.length === 0) {
          throw new Error('未检测到音频轨道，请确保选择了"分享标签页音频"');
        }

        var videoTracks = stream.getVideoTracks();
        videoTracks.forEach(function(track) {
          track.stop();
          stream.removeTrack(track);
        });
      } else {
        stream = await navigator.mediaDevices.getUserMedia({
          audio: {
            echoCancellation: true,
            noiseSuppression: true,
            autoGainControl: true
          }
        });
      }

      captureStream = stream;

      stream.getTracks().forEach(function(track) {
        track.onended = function() {
          stopRecording();
        };
      });

      processAudio(stream);

    } catch (err) {
      console.error('[LinguaFlow] 录音失败:', err);
      updateStatus('失败: ' + err.message, 'error');
    }
  }

  function stopRecording() {
    isRecording = false;

    if (sendInterval) {
      clearInterval(sendInterval);
      sendInterval = null;
    }

    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'STOP_AUDIO' }));
    }

    if (processor) {
      processor.disconnect();
      processor = null;
    }

    if (audioContext) {
      audioContext.close();
      audioContext = null;
    }

    if (captureStream) {
      captureStream.getTracks().forEach(function(track) { track.stop(); });
      captureStream = null;
    }

    audioBuffer = [];
    updateStatus('已停止', 'connected');
    console.log('[LinguaFlow] 录音已停止');

    sendToBackground({ type: 'TTS_STOP' });
  }

  chrome.runtime.onMessage.addListener(function(message, sender, sendResponse) {
    console.log('[LinguaFlow] Received message:', message.type, message);

    if (message.type === 'CONNECT_WS') {
      connectWebSocket();
      createPanel();
      sendResponse({ success: true });
    } else if (message.type === 'DISCONNECT_WS') {
      if (reconnectTimer) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
      }
      reconnectAttempts = MAX_RECONNECT_ATTEMPTS;
      if (ws) {
        ws.close();
        ws = null;
      }
      sendResponse({ success: true });
    } else if (message.type === 'START_RECORDING') {
      config = Object.assign({}, config, message.config);
      connectWebSocket();
      createPanel();
      startRecording();
      sendResponse({ success: true });
    } else if (message.type === 'STOP_RECORDING') {
      stopRecording();
      sendResponse({ success: true });
    } else if (message.type === 'TOGGLE_PANEL') {
      if (panel) {
        panel.style.display = panel.style.display === 'none' ? 'block' : 'none';
      } else {
        createPanel();
      }
      sendResponse({ success: true });
    }
  });

  console.log('[LinguaFlow] Content Script loaded');
})();