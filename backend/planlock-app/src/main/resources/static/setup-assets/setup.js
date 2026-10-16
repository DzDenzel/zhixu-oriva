'use strict';
const form = document.getElementById('setup-form');
const address = document.getElementById('accessUrl');
address.value = location.origin;
form.addEventListener('submit', async event => {
  event.preventDefault();
  const message = document.getElementById('message');
  const button = document.getElementById('submit');
  button.disabled = true; message.textContent = '正在创建你的实例…';
  const payload = Object.fromEntries(new FormData(form));
  try {
    const response = await fetch('/api/v1/setup', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload), cache: 'no-store', credentials: 'omit' });
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || '设置暂时无法完成，请稍后重试');
    form.reset(); form.hidden = true; document.getElementById('intro').hidden = true;
    const text = JSON.stringify(result.connection, null, 2);
    document.getElementById('connection').value = text;
    document.getElementById('qr').src = result.qrDataUrl;
    document.getElementById('download').href = result.qrDataUrl;
    document.getElementById('result').hidden = false;
  } catch (error) { message.textContent = error.message || '网络连接失败，请确认后端仍在运行'; }
  finally { payload.password = ''; payload.setupToken = ''; document.getElementById('setupToken').value = ''; document.getElementById('password').value = ''; button.disabled = false; }
});
document.getElementById('copy').addEventListener('click', async () => {
  const text = document.getElementById('connection');
  const status = document.getElementById('copy-status');
  try { await navigator.clipboard.writeText(text.value); status.textContent = '已复制连接配置'; }
  catch (_) { text.focus(); text.select(); status.textContent = '请长按或按 Ctrl+C 复制已选中的配置'; }
});
