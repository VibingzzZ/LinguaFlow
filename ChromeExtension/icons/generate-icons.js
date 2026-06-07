// 生成占位图标脚本 - 在浏览器控制台运行此代码可生成图标
// 或者使用在线工具生成简单PNG图标

function generateIcon(size, color1, color2) {
  const canvas = document.createElement('canvas');
  canvas.width = size;
  canvas.height = size;
  const ctx = canvas.getContext('2d');
  
  // 渐变背景
  const gradient = ctx.createLinearGradient(0, 0, size, size);
  gradient.addColorStop(0, color1);
  gradient.addColorStop(1, color2);
  ctx.fillStyle = gradient;
  ctx.fillRect(0, 0, size, size);
  
  // 绘制L字母
  ctx.fillStyle = '#fff';
  ctx.font = `bold ${size * 0.6}px Arial`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText('L', size / 2, size / 2);
  
  // 下载
  canvas.toBlob((blob) => {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `icon${size}.png`;
    a.click();
  });
}

// 生成三个尺寸的图标
generateIcon(16, '#e94560', '#c0392b');
generateIcon(48, '#e94560', '#c0392b');
generateIcon(128, '#e94560', '#c0392b');