package com.easycast.tv

/**
 * The page served at http://<tv>:8200/ : paste a video URL in a browser and push it to the TV.
 * Plain HTML/JS, no external resources. (No dollar signs in the script: this is a Kotlin raw string.)
 */
object WebPage {
    const val html = """<!doctype html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>简易投屏</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin: 0; font: 16px/1.5 system-ui, "Microsoft YaHei", sans-serif; background: #111; color: #eee; }
  .wrap { max-width: 720px; margin: 0 auto; padding: 24px 16px 48px; }
  h1 { font-size: 22px; margin: 0 0 4px; }
  h2 { font-size: 16px; margin: 28px 0 8px; color: #bbb; }
  .muted { color: #999; font-size: 14px; margin-bottom: 18px; }
  label { display: block; margin: 14px 0 6px; font-size: 14px; color: #bbb; }
  input, textarea { width: 100%; padding: 10px 12px; border-radius: 8px; border: 1px solid #333; background: #1c1c1c; color: #eee; font: inherit; }
  textarea { resize: vertical; word-break: break-all; }
  .row { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 14px; }
  button { padding: 10px 18px; border-radius: 8px; border: 0; background: #333; color: #fff; font: inherit; cursor: pointer; }
  button:hover { background: #444; }
  button.primary { background: #1e88e5; }
  button.primary:hover { background: #3b99ee; }
  button.danger { background: #8a2c2c; }
  #msg { min-height: 24px; margin-top: 12px; font-size: 14px; }
  .ok { color: #7bd88f; } .bad { color: #ff7b7b; }
  .item { display: flex; gap: 10px; align-items: flex-start; padding: 10px 12px; margin-bottom: 8px; background: #1c1c1c; border-radius: 8px; }
  .item .t { color: #8ab4f8; font-size: 13px; white-space: nowrap; }
  .item .u { flex: 1; word-break: break-all; font-size: 13px; color: #ccc; }
  .item button { padding: 6px 12px; font-size: 14px; }
</style>
</head>
<body>
<div class="wrap">
  <h1>简易投屏</h1>
  <div id="info" class="muted">连接中…</div>

  <label for="pin">口令（电视主界面上显示）</label>
  <input id="pin" inputmode="numeric" maxlength="8" placeholder="4 位口令" autocomplete="off">

  <label for="url">视频地址（http / https：直播流 FLV、m3u8，或 MP4 等文件）</label>
  <textarea id="url" rows="3" placeholder="例如 http://192.168.1.4:8000/电影.mp4"></textarea>

  <div class="row">
    <button id="play" class="primary">推送并播放</button>
    <button id="pause">暂停</button>
    <button id="resume">继续</button>
    <button id="stop" class="danger">停止</button>
  </div>
  <div id="msg"></div>

  <h2>最近投屏</h2>
  <div id="recent"></div>
</div>

<script>
var el = function (id) { return document.getElementById(id); };
var pin = el('pin');
try { pin.value = localStorage.getItem('pin') || ''; } catch (e) {}

function call(path, data) {
  data = data || {};
  data.pin = pin.value;
  try { localStorage.setItem('pin', pin.value); } catch (e) {}
  return fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(data).toString()
  }).then(function (r) {
    return r.json().then(function (j) { return { ok: r.ok, data: j }; });
  });
}

function say(text, bad) {
  var m = el('msg');
  m.textContent = text;
  m.className = bad ? 'bad' : 'ok';
}

function act(path, data, okText) {
  call(path, data).then(function (r) {
    say(r.ok ? okText : (r.data.error || '失败'), !r.ok);
    refresh();
  }).catch(function () { say('连不上电视：请检查网络，跨网段时要在路由器上转发 8200 端口', true); });
}

el('play').onclick = function () {
  var u = el('url').value.trim();
  if (!u) { say('请先填视频地址', true); return; }
  act('/api/play', { url: u }, '已推送到电视');
};
el('pause').onclick = function () { act('/api/pause', {}, '已暂停'); };
el('resume').onclick = function () { act('/api/resume', {}, '继续播放'); };
el('stop').onclick = function () { act('/api/stop', {}, '已停止'); };

var STATES = { PLAYING: '播放中', PAUSED_PLAYBACK: '已暂停', TRANSITIONING: '加载中', STOPPED: '空闲' };

function refresh() {
  call('/api/state').then(function (r) {
    if (!r.ok) { el('info').textContent = r.data.error || '口令不对'; return; }
    var s = r.data;
    el('info').textContent = '设备：' + s.device + '　状态：' + (STATES[s.state] || s.state);
  }).catch(function () { el('info').textContent = '连不上电视'; });

  call('/api/recent').then(function (r) {
    var box = el('recent');
    box.textContent = '';
    if (!r.ok) return;
    if (!r.data.length) { box.textContent = '还没有记录'; return; }
    r.data.forEach(function (it) {
      var row = document.createElement('div'); row.className = 'item';
      var t = document.createElement('div'); t.className = 't';
      t.textContent = new Date(it.time).toLocaleString();
      var u = document.createElement('div'); u.className = 'u'; u.textContent = it.url;
      var b = document.createElement('button'); b.textContent = '播放';
      b.onclick = function () { el('url').value = it.url; act('/api/play', { url: it.url }, '已推送到电视'); };
      row.appendChild(t); row.appendChild(u); row.appendChild(b);
      box.appendChild(row);
    });
  }).catch(function () {});
}

refresh();
setInterval(refresh, 3000);
</script>
</body>
</html>
"""
}
