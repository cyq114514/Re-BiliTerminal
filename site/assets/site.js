/* 官网脚本：版本信息、下载链接、镜像切换、SHA 复制、滚动动画
 *
 * 版本数据来源优先 /api/update.json（Netlify 反向代理到 GitHub Release 资产，
 * 绕开浏览器跨域）；代理不可用时回落到下方 FALLBACK，保证页面不会空白。
 */
(function () {
  'use strict';

  var REPO = 'cyq114514/Re-BiliTerminal';

  // 兜底值：与最新一次 Release 的资产保持一致（本地预览与函数失效时使用）。
  // 注意：fileName 必须与 GitHub Release 上的资产名完全一致，否则下载 404。
  var FALLBACK = {
    versionName: '1.2.3',
    size: 9571482,
    sha256: '00acd04bf80960fa721b1a89d008227469acf8016c795161cd888ffd2007a1d9',
    fileName: 'Re-BiliTerminal-v1.2.3-release.apk'
  };

  // 第三方 GitHub 加速前缀（社区维护，可能随时失效；仅重写 github.com 直链）
  var MIRRORS = [
    { name: 'ghfast.top', prefix: 'https://ghfast.top/' },
    { name: 'gh-proxy.com', prefix: 'https://gh-proxy.com/' }
  ];

  function $(id) { return document.getElementById(id); }

  function humanSize(bytes) {
    if (!bytes) return '—';
    return (bytes / 1048576).toFixed(1) + ' MB';
  }

  /** 本地预览环境：没有 /api/update.json 代理，兜底属预期行为 */
  function isLocalPreview() {
    var host = location.hostname;
    return location.protocol === 'file:'
      || host === 'localhost'
      || host === '127.0.0.1'
      || host === '';
  }

  function apply(info, stale) {
    var name = info.versionName || FALLBACK.versionName;
    var file = info.fileName || FALLBACK.fileName;
    var direct = 'https://github.com/' + REPO + '/releases/latest/download/' + file;

    var verEl = $('dlVersion');
    if (verEl) verEl.textContent = 'v' + name;
    var heroVer = $('heroVer');
    if (heroVer) heroVer.textContent = 'v' + name;

    var sizeEl = $('dlSize');
    if (sizeEl) sizeEl.textContent = humanSize(info.size) + ' · 单一安装包（armeabi-v7a + x86）';

    var main = $('dlMain');
    if (main) main.href = direct;

    var all = $('dlAll');
    if (all) all.href = info.htmlUrl || ('https://github.com/' + REPO + '/releases');

    var shaEl = $('dlSha');
    if (shaEl) shaEl.textContent = info.sha256 || FALLBACK.sha256;

    var mirrorBox = $('dlMirrors');
    if (mirrorBox) {
      mirrorBox.innerHTML = '';
      MIRRORS.forEach(function (m) {
        var a = document.createElement('a');
        a.className = 'btn ghost';
        a.href = m.prefix + direct;
        a.textContent = '镜像 · ' + m.name;
        a.rel = 'nofollow noopener';
        a.target = '_blank';
        mirrorBox.appendChild(a);
      });
    }

    var hint = $('dlHint');
    if (hint) {
      //本地预览（file:// 或 localhost）没有 Netlify 反代，必然走兜底值，不必提示
      hint.textContent = (stale && !isLocalPreview())
        ? '（版本信息取自页面内置快照，可能滞后；以 GitHub Releases 为准）'
        : '';
    }
  }

  function loadVersion() {
    if (!$('dlVersion')) return;
    fetch('/api/update.json', { cache: 'no-store' })
      .then(function (r) {
        if (!r.ok) throw new Error('proxy ' + r.status);
        return r.json();
      })
      .then(function (info) { apply(info, false); })
      .catch(function () { apply(FALLBACK, true); });
  }

  function bindCopy() {
    var btn = $('copySha');
    var sha = $('dlSha');
    if (!btn || !sha) return;
    btn.addEventListener('click', function () {
      var text = sha.textContent.trim();
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function () {
          btn.textContent = '已复制';
          setTimeout(function () { btn.textContent = '复制'; }, 1500);
        });
      }
    });
  }

  // ---------------------------------------------------------------- 视觉交互

  /** 顶栏：滚动后加深背景与描边 */
  function bindNav() {
    var nav = document.querySelector('.nav');
    if (!nav) return;
    var onScroll = function () {
      nav.classList.toggle('scrolled', window.scrollY > 12);
    };
    window.addEventListener('scroll', onScroll, { passive: true });
    onScroll();
  }

  /** 滚动进场：给 .reveal 元素按顺序加延迟，进入视口后显示 */
  function bindReveal() {
    var items = document.querySelectorAll('.reveal');
    if (!items.length) return;

    //不支持 IntersectionObserver 时直接显示，不能让内容消失
    if (!('IntersectionObserver' in window)) {
      Array.prototype.forEach.call(items, function (el) { el.classList.add('in'); });
      return;
    }

    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        var el = entry.target;
        //同组元素按 DOM 顺序错开 70ms，营造层次
        var delay = Number(el.getAttribute('data-delay') || 0);
        setTimeout(function () { el.classList.add('in'); }, delay);
        observer.unobserve(el);
      });
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.12 });

    //同一父容器内的元素自动分配延迟
    var groups = {};
    Array.prototype.forEach.call(items, function (el) {
      var key = el.parentNode ? (el.parentNode.className || 'root') : 'root';
      groups[key] = (groups[key] || 0) + 1;
      if (!el.hasAttribute('data-delay')) {
        el.setAttribute('data-delay', String(Math.min(groups[key] - 1, 5) * 70));
      }
      observer.observe(el);
    });
  }

  /** 回到顶部：滚动超过一屏后出现 */
  function bindToTop() {
    var btn = document.createElement('button');
    btn.className = 'to-top';
    btn.type = 'button';
    btn.setAttribute('aria-label', '回到顶部');
    btn.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" ' +
      'stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
      '<path d="M12 19V5M5 12l7-7 7 7"/></svg>';
    btn.addEventListener('click', function () {
      window.scrollTo({ top: 0, behavior: 'smooth' });
    });
    document.body.appendChild(btn);

    window.addEventListener('scroll', function () {
      btn.classList.toggle('show', window.scrollY > window.innerHeight * 0.9);
    }, { passive: true });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }

  function init() {
    loadVersion();
    bindCopy();
    bindNav();
    bindReveal();
    bindToTop();
  }
})();
