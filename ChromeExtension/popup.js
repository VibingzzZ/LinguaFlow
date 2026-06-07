// LinguaFlow Chrome Extension - Popup Script

const btnConnect = document.getElementById('btnConnect');
const btnRecord = document.getElementById('btnRecord');
const statusDot = document.getElementById('statusDot');
const statusText = document.getElementById('statusText');
const sourceLang = document.getElementById('sourceLang');
const targetLang = document.getElementById('targetLang');
const audioSource = document.getElementById('audioSource');
const ttsToggle = document.getElementById('ttsToggle');

let isConnected = false;
let isRecording = false;
let ttsEnabled = true;

// 初始化
chrome.runtime.sendMessage({ type: 'GET_STATUS' }, (response) => {
  if (chrome.runtime.lastError) {
    console.debug('[LinguaFlow] Background 未响应:', chrome.runtime.lastError.message);
    return;
  }
  if (response) {
    isConnected = response.connected;
    isRecording = response.recording;
    ttsEnabled = response.ttsEnabled !== false;
    updateUI();
  }
});

// 连接/断开
btnConnect.addEventListener('click', () => {
  if (!isConnected) {
    chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
      if (tabs[0]) {
        chrome.tabs.sendMessage(tabs[0].id, { type: 'CONNECT_WS' });
        isConnected = true;
        updateUI();
      }
    });
  } else {
    chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
      if (tabs[0]) {
        chrome.tabs.sendMessage(tabs[0].id, { type: 'DISCONNECT_WS' });
        isConnected = false;
        isRecording = false;
        updateUI();
      }
    });
  }
});

// 录音
btnRecord.addEventListener('click', () => {
  if (!isRecording) {
    chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
      if (tabs[0]) {
        chrome.tabs.sendMessage(tabs[0].id, {
          type: 'START_RECORDING',
          config: {
            sourceLang: sourceLang.value,
            targetLang: targetLang.value,
            audioSource: audioSource.value,
            ttsEnabled: ttsEnabled
          }
        }, (response) => {
          if (chrome.runtime.lastError) {
            alert('录音失败: ' + chrome.runtime.lastError.message);
            return;
          }
          if (response && response.success) {
            isRecording = true;
            updateUI();
          } else {
            alert('录音失败: ' + (response?.error || '未知错误'));
          }
        });
      }
    });
  } else {
    chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
      if (tabs[0]) {
        chrome.tabs.sendMessage(tabs[0].id, { type: 'STOP_RECORDING' });
        isRecording = false;
        updateUI();
      }
    });
  }
});

// 语言选择
sourceLang.addEventListener('change', () => {
  chrome.runtime.sendMessage({
    type: 'UPDATE_CONFIG',
    config: { sourceLang: sourceLang.value }
  });
});

targetLang.addEventListener('change', () => {
  chrome.runtime.sendMessage({
    type: 'UPDATE_CONFIG',
    config: { targetLang: targetLang.value }
  });
});

// 音频源选择
audioSource.addEventListener('change', () => {
  chrome.runtime.sendMessage({
    type: 'UPDATE_CONFIG',
    config: { audioSource: audioSource.value }
  });
});

// TTS开关
ttsToggle.addEventListener('change', () => {
  ttsEnabled = ttsToggle.checked;
  chrome.runtime.sendMessage({
    type: 'UPDATE_CONFIG',
    config: { ttsEnabled: ttsEnabled }
  });
});

// 更新UI
function updateUI() {
  if (isConnected) {
    btnConnect.textContent = '断开连接';
    btnConnect.style.background = '#e94560';
    statusDot.className = 'status-dot status-connected';
    statusText.textContent = '已连接';
    btnRecord.disabled = false;
  } else {
    btnConnect.textContent = '连接服务';
    btnConnect.style.background = '#0f3460';
    statusDot.className = 'status-dot';
    statusText.textContent = '未连接';
    btnRecord.disabled = true;
  }

  if (isRecording) {
    btnRecord.textContent = '⏹ 停止录音';
    btnRecord.style.background = '#ff4757';
    statusDot.className = 'status-dot status-recording';
    statusText.textContent = '录音中...';
  } else {
    btnRecord.textContent = '🎤 开始录音';
    btnRecord.style.background = '#e94560';
    if (isConnected) {
      statusDot.className = 'status-dot status-connected';
      statusText.textContent = '已连接';
    }
  }
}