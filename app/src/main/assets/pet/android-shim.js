/*
 * Android shim —— dsh-pet 桌面助手（Electron 主进程 + 宿主管道）的安卓替代层。
 *
 * 三件事：
 *   1) 渲染：安卓 WebView 的 <video> 会丢掉 VP9-alpha 的透明通道（黑底），所以素材全部预转成
 *      「左半 RGB + 右半 alpha」的 H.264（见 tools/pack_alpha.sh），这里把 <video> 藏起来，
 *      用 canvas 每帧自己合成 RGBA 再上屏。透明因此不再依赖解码器。
 *   2) 窗口：渲染窗是全屏的透明悬浮窗（不可触摸），宠物在页面里用 transform 移动；
 *      触摸由另一块小窗（只覆盖宠物身体）转发成合成指针事件 —— 所以宠物周围不会挡点击。
 *   3) 宿主接口：window.petBridge + fetch 拦截（/state /whisper /chat /balance …），
 *      AI 走原生 AndroidPet.aiRequest。
 *
 * 加载顺序必须是第一个（在 shared-core / constants / sprite 之前）。
 */
(function () {
  'use strict';

  var BASE = location.origin + '/dsh-pet-7340';
  var FPS = (function () {
    var m = /[?&]fps=(\d+)/.exec(location.search);
    var v = m ? Number(m[1]) : 24;
    if (!isFinite(v) || v < 8) v = 24;
    return Math.max(8, Math.min(24, v));
  })();
  var FRAME_MS = 1000 / FPS;
  var PET_ID = 'main';
  var native = null;
  try {
    native = window.AndroidPet || null;
  } catch (e) {
    native = null;
  }

  // ---------- 状态存储（等价宿主的 S：sections + pets.<id>.leaf） ----------
  var state = { sections: {}, pets: {} };
  var counter = 0;
  function leaf(data) {
    counter += 1;
    return { counter: counter, data: data };
  }

  window.__petSay = function (petId, text, image) {
    if (!text) return;
    var data = { text: String(text) };
    if (image) data.image = String(image);
    if (!state.pets[petId]) state.pets[petId] = {};
    state.pets[petId].say = leaf(data);
  };
  window.__petBalanceUnavailable = function (message) {
    state.sections.balance = leaf({
      provider: 'android',
      ok: false,
      reason: 'unsupported',
      message: message || '手机端未接入余额查询',
      manual: true,
    });
  };
  window.__petDebug = function () {
    return { counter: counter, pets: Object.keys(state.pets), sections: Object.keys(state.sections) };
  };

  // ---------- 原生 AI 通道（Promise 化） ----------
  var pending = {};
  var pendingSeq = 0;
  function nativeCall(kind, payload) {
    return new Promise(function (resolve) {
      if (!native || !native.aiRequest) {
        resolve(null);
        return;
      }
      pendingSeq += 1;
      var id = 'a' + pendingSeq;
      pending[id] = resolve;
      try {
        native.aiRequest(id, kind, JSON.stringify(payload || {}));
      } catch (e) {
        delete pending[id];
        resolve(null);
      }
      setTimeout(function () {
        if (pending[id]) {
          delete pending[id];
          resolve(null);
        }
      }, 60000);
    });
  }
  window.__petNativeResult = function (id, json) {
    var resolve = pending[id];
    if (!resolve) return;
    delete pending[id];
    var value = null;
    try {
      value = JSON.parse(json);
    } catch (e) {
      value = null;
    }
    resolve(value);
  };

  // ---------- 指针捕获：合成事件没有真实 pointer，setPointerCapture 会抛 ----------
  try {
    Element.prototype.setPointerCapture = function () {};
    Element.prototype.releasePointerCapture = function () {};
    Element.prototype.hasPointerCapture = function () {
      return false;
    };
  } catch (e) {
    /* 老 WebView 没有这些方法也无所谓 */
  }

  // ---------- 宠物元素的屏幕定位（页面坐标 = 屏幕坐标，渲染窗全屏且原点在 0,0） ----------
  var hitBox = null; // {x,y,w,h} 相对 .pet-sprite，由 sprite 构造时写进 __dshPetDebug.hitRect
  var lastHit = null;
  var hitDirty = false;
  var hitFrozen = false;

  function spriteEl() {
    return document.querySelector('.pet-sprite');
  }

  function reportHit(left, top) {
    var h = window.__dshPetDebug && window.__dshPetDebug.hitRect;
    if (!h) return;
    hitBox = h;
    var rect = { x: left + h.x, y: top + h.y, w: h.w, h: h.h };
    if (
      lastHit &&
      Math.abs(rect.x - lastHit.x) < 0.6 &&
      Math.abs(rect.y - lastHit.y) < 0.6 &&
      Math.abs(rect.w - lastHit.w) < 0.6 &&
      Math.abs(rect.h - lastHit.h) < 0.6
    ) {
      return;
    }
    lastHit = rect;
    hitDirty = true;
  }

  // 10Hz 上报命中区（原生据此摆那块小触摸窗；触摸进行中冻结，避免窗口在手指下被移动）
  setInterval(function () {
    if (!hitDirty || hitFrozen || !lastHit) return;
    hitDirty = false;
    if (native && native.setHitRect) {
      try {
        native.setHitRect(lastHit.x, lastHit.y, lastHit.w, lastHit.h);
      } catch (e) {
        /* ignore */
      }
    }
  }, 60);

  // ---------- petBridge ----------
  window.petBridge = {
    setBounds: function (x, y, w, h, boxX, boxY, size, bottomPad, vx, vy, marginL, marginT) {
      var el = spriteEl();
      // x/y 是「窗口左上角」= 宠物包围盒 - 余量；+ margin 回到包围盒左上角（= 屏幕位置）
      var left = x + (marginL || 0);
      var top = y + (marginT || 0);
      if (el) {
        el.style.left = '0px';
        el.style.top = '0px';
        el.style.transform = 'translate3d(' + left + 'px,' + top + 'px,0)';
      }
      reportHit(left, top);
    },
    setInteractive: function () {},
    setInputBusy: function () {},
    setInputRegions: function () {},
    openDshSite: function (url) {
      if (native && native.openUrl) {
        try {
          native.openUrl(String(url || ''));
        } catch (e) {
          /* ignore */
        }
      }
    },
    reportFlight: function () {},
    onFlightStates: function () {},
    reportCollide: function () {},
    onPetHit: function () {},
    onDisplays: function () {},
  };

  // ---------- 合成触摸（原生小窗转发） ----------
  var touch = { down: false, x: 0, y: 0, t0: 0, target: null, moved: false, longTimer: null, longFired: false, id: 1 };

  function firePointer(target, type, x, y, extra) {
    var init = {
      bubbles: true,
      cancelable: true,
      composed: true,
      view: window,
      pointerId: touch.id,
      pointerType: 'touch',
      isPrimary: true,
      clientX: x,
      clientY: y,
      screenX: x,
      screenY: y,
      button: type === 'pointerup' || type === 'pointercancel' ? 0 : 0,
      buttons: type === 'pointerup' || type === 'pointercancel' ? 0 : 1,
      width: 2,
      height: 2,
      pressure: type === 'pointerup' ? 0 : 0.5,
    };
    if (extra) for (var k in extra) init[k] = extra[k];
    try {
      target.dispatchEvent(new PointerEvent(type, init));
    } catch (e) {
      /* 老内核没有 PointerEvent */
    }
  }

  function fireMouse(target, type, x, y, button, buttons, bubbles) {
    try {
      target.dispatchEvent(
        new MouseEvent(type, {
          bubbles: bubbles !== false,
          cancelable: true,
          composed: true,
          view: window,
          clientX: x,
          clientY: y,
          screenX: x,
          screenY: y,
          button: button,
          buttons: buttons,
        })
      );
    } catch (e) {
      /* ignore */
    }
  }

  function fireClick(target, x, y) {
    try {
      target.dispatchEvent(
        new MouseEvent('click', {
          bubbles: true,
          cancelable: true,
          composed: true,
          view: window,
          clientX: x,
          clientY: y,
          screenX: x,
          screenY: y,
          button: 0,
          buttons: 0,
        })
      );
    } catch (e) {
      /* ignore */
    }
  }

  function fireContextMenu(target, x, y) {
    try {
      target.dispatchEvent(
        new MouseEvent('contextmenu', {
          bubbles: true,
          cancelable: true,
          composed: true,
          view: window,
          clientX: x,
          clientY: y,
          screenX: x,
          screenY: y,
          button: 2,
          buttons: 0,
        })
      );
    } catch (e) {
      /* ignore */
    }
  }

  function targetAt(x, y) {
    var t = null;
    try {
      t = document.elementFromPoint(x, y);
    } catch (e) {
      t = null;
    }
    return t || document.querySelector('.pet-hit');
  }

  window.__petTouch = function (kind, x, y) {
    x = Number(x) || 0;
    y = Number(y) || 0;
    if (kind === 'down') {
      touch.down = true;
      touch.x = x;
      touch.y = y;
      touch.t0 = Date.now();
      touch.moved = false;
      touch.longFired = false;
      touch.target = targetAt(x, y);
      hitFrozen = true;
      if (!touch.target) return;
      if (touch.longTimer) clearTimeout(touch.longTimer);
      touch.longTimer = setTimeout(function () {
        touch.longTimer = null;
        if (!touch.down || touch.moved) return;
        touch.longFired = true;
        if (navigator.vibrate) {
          try {
            navigator.vibrate(12);
          } catch (e) {
            /* ignore */
          }
        }
        // 先中止这次按压（sprite 的 onContextMenu 会忽略「按压中」的状态），再弹菜单
        firePointer(touch.target, 'pointercancel', touch.x, touch.y);
        fireContextMenu(touch.target, touch.x, touch.y);
      }, 700);
      fireMouse(touch.target, 'mouseover', x, y, 0, 1);
      fireMouse(touch.target, 'mouseenter', x, y, 0, 1, false);
      fireMouse(touch.target, 'mousemove', x, y, 0, 1);
      firePointer(touch.target, 'pointerdown', x, y);
      fireMouse(touch.target, 'mousedown', x, y, 0, 1);
    } else if (kind === 'move') {
      if (!touch.down || !touch.target) return;
      if (Math.abs(x - touch.x) > 5 || Math.abs(y - touch.y) > 5) {
        touch.moved = true;
        if (touch.longTimer) {
          clearTimeout(touch.longTimer);
          touch.longTimer = null;
        }
      }
      firePointer(touch.target, 'pointermove', x, y);
    } else if (kind === 'up' || kind === 'cancel') {
      if (!touch.down) return;
      touch.down = false;
      hitFrozen = false;
      if (touch.longTimer) {
        clearTimeout(touch.longTimer);
        touch.longTimer = null;
      }
      if (!touch.target) return;
      if (!touch.longFired) firePointer(touch.target, kind === 'up' ? 'pointerup' : 'pointercancel', x, y);
      if (kind === 'up' && !touch.moved && !touch.longFired && Date.now() - touch.t0 < 600) {
        var t = targetAt(x, y);
        var field = null;
        try {
          field = t && t.closest ? t.closest('input,textarea,[contenteditable="true"]') : null;
        } catch (e) {
          field = null;
        }
        if (field) {
          try {
            field.focus();
          } catch (e) {
            /* ignore */
          }
        }
        fireClick(t, x, y);
      }
    }
  };

  // ---------- 渲染：WebGL 合成 RGB + alpha ----------
  // 素材是「左 480×270 彩色 + 右 240×135 灰度 alpha」的一张 H.264。这里每帧把视频帧上传成纹理，
  // 着色器用右半的亮度当 alpha 输出（canvas 本身保持 premultiplied alpha，所以透明区真的透明）。
  (function () {
    var style = document.createElement('style');
    style.textContent =
      /* 原视频只当解码器用：缩到 2×2 且几乎全透明，但仍然「可见」（Chromium 会对不可见视频降频/暂停解码）。 */
      '.pet-video{width:2px!important;height:2px!important;left:0!important;top:0!important;' +
      'right:auto!important;bottom:auto!important;opacity:.01!important;object-fit:fill!important}' +
      '.pet-canvas{position:absolute;left:0;top:0;width:100%;height:100%;pointer-events:none;z-index:0}' +
      '.dsh-pet-menu-column{max-height:calc(100vh - 8px)!important;overflow-y:auto!important;' +
      'overflow-x:hidden!important;-webkit-overflow-scrolling:touch!important}' +
      '.dsh-pet-menu-item{padding-top:3px!important;padding-bottom:3px!important}';
    document.head.appendChild(style);

    var VS = 'attribute vec2 aPos; varying vec2 vUv; void main(){ vUv = vec2(aPos.x*0.5+0.5, 0.5-aPos.y*0.5); gl_Position = vec4(aPos,0.0,1.0); }';
    var FS =
      'precision mediump float; varying vec2 vUv; uniform sampler2D uTex;' +
      'void main(){' +
      '  vec3 rgb = texture2D(uTex, vec2(vUv.x*0.6666667, vUv.y)).rgb;' +
      '  float a0 = texture2D(uTex, vec2(0.6666667 + vUv.x*0.3333333, vUv.y*0.5)).r;' +
      // 源素材边缘偏软，稍微压实一点（用户反馈"太透明"）
      '  float a = pow(clamp(a0, 0.0, 1.0), 0.78);' +
      '  gl_FragColor = vec4(rgb*a, a);' +
      '}';

    var canvas = null;
    var lastSig = '';
    var lastDrawAt = 0;
    var gl = null;
    var prog = null;
    var tex = null;
    var uTex = null;
    var buf = null;
    var failed = false;
    var dbg = { ok: false, failed: false, frames: 0, draws: 0, err: 0, vw: 0, ready: 0 };
    window.__petRenderDebug = dbg;

    function initGl(c) {
      try {
        gl = c.getContext('webgl', { alpha: true, premultipliedAlpha: true, antialias: false, depth: false, stencil: false });
      } catch (e) {
        gl = null;
      }
      if (!gl) { dbg.failed = true; return false; }
      dbg.ok = true;
      function sh(type, src) {
        var s = gl.createShader(type);
        gl.shaderSource(s, src);
        gl.compileShader(s);
        if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
          console.warn('[dsh-pet] shader: ' + gl.getShaderInfoLog(s));
          return null;
        }
        return s;
      }
      var vs = sh(gl.VERTEX_SHADER, VS);
      var fs = sh(gl.FRAGMENT_SHADER, FS);
      if (!vs || !fs) return false;
      prog = gl.createProgram();
      gl.attachShader(prog, vs);
      gl.attachShader(prog, fs);
      gl.linkProgram(prog);
      if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) return false;
      gl.useProgram(prog);
      buf = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, buf);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
      var aPos = gl.getAttribLocation(prog, 'aPos');
      gl.enableVertexAttribArray(aPos);
      gl.vertexAttribPointer(aPos, 2, gl.FLOAT, false, 0, 0);
      tex = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
      uTex = gl.getUniformLocation(prog, 'uTex');
      gl.uniform1i(uTex, 0);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      gl.clearColor(0, 0, 0, 0);
      return true;
    }

    function ensureCanvas(stage) {
      canvas = stage.querySelector('.pet-canvas');
      if (!canvas) {
        canvas = document.createElement('canvas');
        canvas.className = 'pet-canvas';
        stage.appendChild(canvas);
        gl = null;
      }
      if (!gl && !failed) {
        if (!initGl(canvas)) {
          failed = true;
          console.warn('[dsh-pet] WebGL 不可用，宠物透明合成失败');
        }
      }
    }

    function frame() {
      requestAnimationFrame(frame);
      var stage = document.querySelector('.pet-stage');
      if (!stage) return;
      if (!canvas || canvas.parentNode !== stage) ensureCanvas(stage);
      if (!gl) { dbg.failed = true; return; }
      dbg.frames++;
      var w = stage.clientWidth;
      var h = stage.clientHeight;
      if (!w || !h) return;
      var dpr = Math.min(2.5, window.devicePixelRatio || 1);
      var bw = Math.max(1, Math.round(w * dpr));
      var bh = Math.max(1, Math.round(h * dpr));
      if (canvas.width !== bw || canvas.height !== bh) {
        canvas.width = bw;
        canvas.height = bh;
      }
      var v = stage.querySelector('video.is-front') || stage.querySelector('video');
      // 朝向 / 落地挤压：sprite 把 transform 写在 video 上，镜像到 canvas
      var tr = v ? v.style.transform || '' : '';
      if (canvas.style.transform !== tr) canvas.style.transform = tr;
      var org = v ? v.style.transformOrigin || '' : '';
      if (canvas.style.transformOrigin !== org) canvas.style.transformOrigin = org;

      var sig = tr + '|' + bw + 'x' + bh + '|' + (v ? v.currentTime : -1) + '|' + (v && v.paused ? 'p' : 'r');
      if (sig === lastSig) return;
      // 帧率上限：素材只有 24fps，低端机可以把上传/绘制压到 12-20fps 省电
      var now = performance.now();
      if (now - lastDrawAt < FRAME_MS - 1) return;
      lastDrawAt = now;
      lastSig = sig;
      gl.viewport(0, 0, bw, bh);
      gl.clear(gl.COLOR_BUFFER_BIT);
      dbg.vw = v ? v.videoWidth : 0;
      dbg.ready = v ? v.readyState : 0;
      if (!v || v.readyState < 2 || v.videoWidth < 720) return;
      try {
        gl.bindTexture(gl.TEXTURE_2D, tex);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, v);
      } catch (e) {
        return;
      }
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      dbg.draws++;
    }
    requestAnimationFrame(frame);
    window.__petStopRender = function () {
      failed = true;
      gl = null;
    };
  })();

  // 兜底：没有手指按着时，命中区永远允许更新（防止手势事件丢失导致冻结）
  setInterval(function () {
    if (!touch.down) hitFrozen = false;
  }, 2000);

  // ---------- 菜单/对话的可视夹取矩形：安卓窗口 = 整块屏幕，不再是「宠物 + 外扩」那小块 ----------
  (function patchClamp() {
    try {
      if (typeof PetSprite === 'function' && !PetSprite.prototype.__androidClampPatched) {
        PetSprite.prototype.__androidClampPatched = true;
        PetSprite.prototype.visibleClampRect = function () {
          var a = null;
          try {
            a = window.PetShared.resolveRect(WORK_AREAS, this.pos.x + this.halfW, this.pos.y + this.halfH);
          } catch (e) {
            a = null;
          }
          if (!a) return { x: 0, y: 0, w: window.innerWidth, h: window.innerHeight };
          return { x: a.x, y: a.y, w: a.width, h: a.height };
        };
        return;
      }
    } catch (e) {
      /* sprite.js 还没执行（TDZ） */
    }
    setTimeout(patchClamp, 100);
  })();

  // ---------- 菜单/对话 → 原生把触摸窗放大到全屏 ----------
  (function () {
    var last = '';
    var lifted = 0;
    function sync() {
      var menu = !!document.querySelector('.dsh-pet-menu');
      var chat = !!document.querySelector('.dsh-pet-chat');
      var key = (menu ? 'm' : '') + (chat ? 'c' : '');
      if (key === last) return;
      last = key;
      // modal 结束：无论上一次手势有没有正常收尾，都要把「命中区冻结」解开，
      // 否则宠物挪了位置、触摸窗却停在原处，表现就是"拖不动了"
      if (!key) {
        hitFrozen = false;
        hitDirty = true;
      }
      // 对话：输入框在宠物旁边，宠物常常在屏幕下半区 → 整页上抬，免得被软键盘挡住
      try {
        if (chat) {
          var el = document.querySelector('.pet-sprite');
          var bottom = window.innerHeight;
          if (el) {
            var mm = /translate3d\(([-0-9.]+)px, ([-0-9.]+)px/.exec(el.style.transform || '');
            var hy = mm ? Number(mm[2]) : 0;
            var hb = window.__dshPetDebug && window.__dshPetDebug.hitRect;
            bottom = hy + (hb ? hb.y + hb.h : 0);
          }
          var h = window.innerHeight;
          lifted = bottom > h * 0.45 ? Math.round(h * 0.4) : 0;
          document.body.style.transform = lifted ? 'translateY(-' + lifted + 'px)' : '';
        } else if (lifted) {
          lifted = 0;
          document.body.style.transform = '';
        }
      } catch (e) {
        /* ignore */
      }
      if (native && native.setModal) {
        try {
          native.setModal(menu || chat, chat);
        } catch (e) {
          /* ignore */
        }
      }
    }
    var obs = new MutationObserver(sync);
    obs.observe(document.documentElement, { childList: true, subtree: true });
    setInterval(sync, 500);
  })();

  /** 原生看门狗用：当前是否真的有菜单/对话挂在 DOM 上（防止 modal 状态卡住整屏吃触摸） */
  window.__petModalAlive = function () {
    return !!document.querySelector('.dsh-pet-menu,.dsh-pet-chat');
  };

  // ---------- fetch 拦截 ----------
  function json(obj) {
    return new Response(JSON.stringify(obj), {
      status: 200,
      headers: { 'content-type': 'application/json; charset=utf-8' },
    });
  }

  var realFetch = window.fetch ? window.fetch.bind(window) : null;
  window.fetch = function (input, init) {
    var url = typeof input === 'string' ? input : (input && input.url) || '';
    if (url.indexOf(BASE + '/') === 0) {
      var path = url.slice(BASE.length).split('?')[0];
      var method = (init && init.method) || 'GET';
      var petId = PET_ID;
      var m = /[?&]pet=([^&]*)/.exec(url);
      if (m) {
        try {
          petId = decodeURIComponent(m[1]);
        } catch (e) {
          petId = PET_ID;
        }
      }

      if (path === '/state') return Promise.resolve(json(state));
      if (path === '/balance') {
        window.__petBalanceUnavailable('手机端未接入 DSH 余额接口');
        return Promise.resolve(json({ ok: true }));
      }
      if (path === '/whisper') {
        nativeCall('whisper', { petId: petId, manual: true }).then(function (r) {
          if (r && r.ok && r.text) window.__petSay(petId, r.text, r.image || null);
          else window.__petSay(petId, '（碎碎念失败：' + ((r && r.error) || '未配置 AI 端点') + '）', null);
        });
        return Promise.resolve(json({ ok: true }));
      }
      if (path === '/chat') {
        var text = '';
        try {
          text = (JSON.parse(String((init && init.body) || '{}')) || {}).text || '';
        } catch (e) {
          text = '';
        }
        if (!text) return Promise.resolve(json({ ok: false, reason: 'bad-request' }));
        nativeCall('chat', { petId: petId, text: text }).then(function (r) {
          if (r && r.ok && r.text) window.__petSay(petId, r.text, null);
          else if (r && r.error) window.__petSay(petId, '（对话失败：' + r.error + '）', null);
        });
        return Promise.resolve(json({ ok: true }));
      }
      if (
        path === '/pose/report' ||
        path === '/reload' ||
        path === '/pet-display' ||
        path === '/lock' ||
        path === '/lock/release' ||
        path === '/bubble/clear'
      ) {
        return Promise.resolve(json({ ok: true }));
      }
    }
    if (realFetch) return realFetch(input, init);
    return Promise.reject(new Error('fetch unavailable'));
  };
})();
