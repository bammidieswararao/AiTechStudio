/*
 * AiTech Studio — offline VFX / motion-design engine (Canvas 2D, zero dependencies)
 *
 * Every frame is a pure function of time:  render(ctx, cfg, t, W, H, opts)
 * The same function draws the live preview, every MP4 frame, and every strip of a
 * large print PNG, so what you preview is exactly what you export.
 *
 * Works in the Android WebView and in Node (for tests) — see module wrapper below.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.Engine = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  /* ------------------------------------------------------------------ utils */
  var clamp = function (v, a, b) { return v < a ? a : v > b ? b : v; };
  var lerp = function (a, b, t) { return a + (b - a) * t; };
  var prog = function (t, t0, d) { return clamp((t - t0) / d, 0, 1); };
  var easeOutCubic = function (t) { return 1 - Math.pow(1 - t, 3); };
  var easeInOut = function (t) { return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2; };
  var easeOutBack = function (t, s) { s = s || 1.70158; t = t - 1; return t * t * ((s + 1) * t + s) + 1; };
  var frac = function (v) { return ((v % 1) + 1) % 1; };

  // mulberry32: tiny seeded PRNG so particles / glitches are identical on every render
  function rng(seed) {
    var a = seed | 0;
    return function () {
      a = (a + 0x6D2B79F5) | 0;
      var t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  function hex(c) {
    c = String(c || '#000000').replace('#', '');
    if (c.length === 3) c = c[0] + c[0] + c[1] + c[1] + c[2] + c[2];
    var n = parseInt(c, 16);
    if (isNaN(n)) n = 0;
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  function rgba(c, a) { var h = hex(c); return 'rgba(' + h[0] + ',' + h[1] + ',' + h[2] + ',' + a + ')'; }
  function mixc(a, b, t) {
    var x = hex(a), y = hex(b);
    var f = function (i) { return Math.round(lerp(x[i], y[i], t)); };
    return '#' + [f(0), f(1), f(2)].map(function (v) { return ('0' + v.toString(16)).slice(-2); }).join('');
  }
  function isHex(s) { return /^#?([0-9a-f]{3}|[0-9a-f]{6})$/i.test(String(s || '')); }

  function rr(ctx, x, y, w, h, r) { // rounded rect path (avoids ctx.roundRect for older WebViews)
    r = Math.min(r, w / 2, h / 2);
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  function linGrad(ctx, W, H, c1, c2, deg) {
    var a = deg * Math.PI / 180, cx = W / 2, cy = H / 2;
    var r = Math.abs(W * Math.cos(a)) / 2 + Math.abs(H * Math.sin(a)) / 2;
    var g = ctx.createLinearGradient(cx - Math.cos(a) * r, cy - Math.sin(a) * r, cx + Math.cos(a) * r, cy + Math.sin(a) * r);
    g.addColorStop(0, c1); g.addColorStop(1, c2);
    return g;
  }
  function blob(ctx, x, y, r, color, alpha) {
    var g = ctx.createRadialGradient(x, y, 0, x, y, r);
    g.addColorStop(0, rgba(color, alpha)); g.addColorStop(1, rgba(color, 0));
    ctx.fillStyle = g; ctx.fillRect(x - r, y - r, r * 2, r * 2);
  }
  function bgRect(ctx, W, H) { ctx.fillRect(-W * 0.06, -H * 0.06, W * 1.12, H * 1.12); }

  /* ----------------------------------------------------------- palettes/fonts */
  var PALETTES = {
    neon:    { name: 'Neon',    bg1: '#0b1020', bg2: '#2a0f4a', a1: '#22d3ee', a2: '#e879f9', text: '#ffffff' },
    cyber:   { name: 'Cyber',   bg1: '#05060a', bg2: '#0d1b2a', a1: '#00ffd5', a2: '#ff2e93', text: '#ffffff' },
    synth:   { name: 'Synth',   bg1: '#12002b', bg2: '#4b0a6e', a1: '#00e5ff', a2: '#ff3ea5', text: '#ffffff' },
    sunset:  { name: 'Sunset',  bg1: '#1a0b2e', bg2: '#7a1f5c', a1: '#ff7a59', a2: '#ffd166', text: '#fff7ed' },
    royal:   { name: 'Royal',   bg1: '#0a0f2c', bg2: '#1e3a8a', a1: '#60a5fa', a2: '#fbbf24', text: '#f8fafc' },
    emerald: { name: 'Emerald', bg1: '#04160f', bg2: '#0b4d3a', a1: '#34d399', a2: '#a3e635', text: '#ecfdf5' },
    gold:    { name: 'Gold',    bg1: '#0d0a05', bg2: '#2b1d07', a1: '#f5c451', a2: '#ffefb0', text: '#fffaf0' },
    crimson: { name: 'Crimson', bg1: '#12040a', bg2: '#5b0f1f', a1: '#ff3d5a', a2: '#ffb199', text: '#fff1f2' },
    ice:     { name: 'Ice',     bg1: '#061622', bg2: '#0e4a68', a1: '#7dd3fc', a2: '#c4b5fd', text: '#ffffff' },
    saffron: { name: 'Saffron', bg1: '#7c2d12', bg2: '#ea580c', a1: '#fde047', a2: '#ffffff', text: '#fffbeb' },
    mono:    { name: 'Mono',    bg1: '#0a0a0a', bg2: '#262626', a1: '#ffffff', a2: '#a3a3a3', text: '#ffffff' }
  };

  var FONTS = {
    bold:      { name: 'Black',     css: '900 {s}px sans-serif' },
    condensed: { name: 'Condensed', css: '700 {s}px "sans-serif-condensed", sans-serif' },
    clean:     { name: 'Clean',     css: '600 {s}px sans-serif' },
    light:     { name: 'Light',     css: '300 {s}px "sans-serif-light", sans-serif' },
    serif:     { name: 'Serif',     css: '700 {s}px serif' },
    mono:      { name: 'Mono',      css: '700 {s}px monospace' },
    script:    { name: 'Script',    css: '400 {s}px cursive' }
  };

  var ANIMS = { pop: 'Pop', slide: 'Slide up', letters: 'Letters', tracking: 'Tracking', type: 'Typewriter', glitch: 'Glitch', fade: 'Fade' };

  function setFont(ctx, key, size, italic) {
    var f = FONTS[key] || FONTS.bold;
    ctx.font = (italic ? 'italic ' : '') + f.css.replace('{s}', Math.max(1, Math.round(size)));
  }

  var seg = (typeof Intl !== 'undefined' && Intl.Segmenter) ? new Intl.Segmenter(undefined, { granularity: 'grapheme' }) : null;
  function clusters(s) { // grapheme clusters, so Telugu / emoji never get split mid-glyph
    if (seg) { var out = []; var it = seg.segment(s)[Symbol.iterator](); var n; while (!(n = it.next()).done) out.push(n.value.segment); return out; }
    return Array.from(s);
  }

  function wrapLine(ctx, text, maxW) {
    var words = text.split(/\s+/).filter(Boolean), lines = [], cur = '';
    for (var i = 0; i < words.length; i++) {
      var test = cur ? cur + ' ' + words[i] : words[i];
      if (!cur || ctx.measureText(test).width <= maxW) cur = test; else { lines.push(cur); cur = words[i]; }
    }
    if (cur) lines.push(cur);
    return lines;
  }
  function fitText(ctx, text, font, italic, maxW, maxH, maxSize, maxLines, tracking) {
    var size = maxSize, lines = [text], w = 0;
    for (var it = 0; it < 48; it++) {
      setFont(ctx, font, size, italic);
      lines = [];
      text.split('\n').forEach(function (part) { wrapLine(ctx, part, maxW).forEach(function (l) { lines.push(l); }); });
      w = 0;
      lines.forEach(function (l) {
        var lw = ctx.measureText(l).width + (tracking || 0) * size * Math.max(0, clusters(l).length - 1);
        if (lw > w) w = lw;
      });
      if (lines.length <= maxLines && w <= maxW && lines.length * size * 1.14 <= maxH) break;
      size *= 0.94;
      if (size < maxSize * 0.16) break;
    }
    return { size: size, lines: lines, lh: size * 1.14, w: w };
  }

  /* ------------------------------------------------------------ text painting */
  // paints one run of text at (0,0) of the current transform: extrude -> shadow -> glow -> outline -> fill
  function paint(env, s, str, size, o) {
    var ctx = env.ctx;
    var alpha = o.alpha === undefined ? 1 : o.alpha;
    if (alpha <= 0.003) return;
    ctx.save();
    ctx.globalAlpha *= alpha;
    var fill = s.fill ? s.fill(ctx, size, env) : env.pal.text;
    if (s.extrude) {
      var e = s.extrude, n = e.steps || 10;
      ctx.fillStyle = e.color;
      for (var i = n; i >= 1; i--) ctx.fillText(str, e.dx * size * i / n, e.dy * size * i / n);
    }
    if (s.shadow) {
      ctx.save();
      ctx.shadowColor = s.shadow.color; ctx.shadowBlur = s.shadow.blur * size;
      ctx.shadowOffsetX = (s.shadow.dx || 0) * size; ctx.shadowOffsetY = (s.shadow.dy || 0) * size;
      ctx.fillStyle = fill; ctx.fillText(str, 0, 0);
      ctx.restore();
    }
    if (s.glow && env.fx.glow) {
      ctx.save();
      ctx.shadowColor = s.glowColor || env.pal.a1;
      ctx.shadowBlur = s.glow * size * (0.4 + env.I * 0.9);
      ctx.fillStyle = fill; ctx.fillText(str, 0, 0);
      ctx.restore();
    }
    if (o.chroma > 0.2) {
      ctx.save();
      ctx.globalCompositeOperation = 'lighter';
      ctx.globalAlpha *= 0.85;
      ctx.fillStyle = '#ff1744'; ctx.fillText(str, -o.chroma, 0);
      ctx.fillStyle = '#00e5ff'; ctx.fillText(str, o.chroma, 0);
      ctx.restore();
    }
    if (s.outline) {
      ctx.lineJoin = 'round'; ctx.miterLimit = 2;
      ctx.lineWidth = s.outline.w * size; ctx.strokeStyle = s.outline.color;
      ctx.strokeText(str, 0, 0);
    }
    ctx.fillStyle = fill;
    ctx.fillText(str, 0, 0);
    ctx.restore();
  }

  function glitchAmt(env, t) {
    var near = t >= env.sc.hit - 0.05 && t <= env.sc.hit + 0.35;
    var k = Math.floor(t * 18);
    var periodic = rng(env.cfg.seed * 131 + k)() < 0.12;
    return clamp(((near ? 1 : 0) + (periodic ? 0.8 : 0)) * env.I * 1.2, 0, 1.5);
  }

  // draws one line of a text block centered on (x,y) using the chosen animation style
  function drawLine(env, s, ln, x, y, size, t0, anim) {
    var ctx = env.ctx, t = env.t, K = env.K;
    ctx.save();
    ctx.translate(x, y);
    ctx.textBaseline = 'middle';
    setFont(ctx, s.font, size, s.italic);

    if (anim === 'letters' || anim === 'tracking' || anim === 'type') {
      ctx.textAlign = 'left';
      var cl = clusters(ln), ws = cl.map(function (c) { return ctx.measureText(c).width; });
      var gap = (s.tracking || 0) * size;
      if (anim === 'tracking') gap += size * 0.55 * (1 - easeOutCubic(prog(t, t0, 1.2)));
      var total = ws.reduce(function (a, b) { return a + b; }, 0) + gap * Math.max(0, cl.length - 1);
      var cx = -total / 2;
      var shown = cl.length;
      if (anim === 'type') shown = Math.floor(prog(t, t0, Math.max(0.5, cl.length * 0.07)) * cl.length + 0.0001);
      for (var i = 0; i < cl.length; i++) {
        if (anim === 'type' && i >= shown) { cx += ws[i] + gap; continue; }
        var a = 1, dy = 0, sc = 1;
        if (anim === 'letters') {
          var pi = prog(t, t0 + i * 0.05, 0.5);
          sc = 0.55 + 0.45 * easeOutBack(pi, 1.9); a = clamp(pi * 2.5, 0, 1); dy = (1 - easeOutBack(pi, 1.9)) * size * 0.55;
        } else if (anim === 'tracking') {
          a = easeOutCubic(prog(t, t0, 0.9));
        }
        ctx.save();
        ctx.translate(cx + ws[i] / 2, dy);
        ctx.scale(sc, sc);
        ctx.textAlign = 'center';
        paint(env, s, cl[i], size, { alpha: a, chroma: 0 });
        ctx.restore();
        cx += ws[i] + gap;
      }
      if (anim === 'type') {
        var finishedAt = t0 + Math.max(0.5, cl.length * 0.07);
        if (t < finishedAt + 1.6 && Math.floor(t * 2.2) % 2 === 0) {
          ctx.fillStyle = env.pal.a1; ctx.fillRect(cx + size * 0.05, -size * 0.38, Math.max(2 * K, size * 0.07), size * 0.76);
        }
      }
      ctx.restore();
      return;
    }

    ctx.textAlign = 'center';
    var alpha = 1, sc2 = 1, dy2 = 0, dx2 = 0, chroma = 0;
    if (anim === 'pop') {
      var p = prog(t, t0, 0.6); sc2 = Math.max(0.001, easeOutBack(p, 2.4)); alpha = clamp(p * 4, 0, 1);
    } else if (anim === 'slide') {
      var p2 = prog(t, t0, 0.7), e2 = easeOutCubic(p2); dy2 = (1 - e2) * size * 0.8; alpha = e2;
    } else if (anim === 'fade') {
      alpha = easeOutCubic(prog(t, t0, 0.9));
    } else if (anim === 'glitch') {
      var p3 = prog(t, t0, 0.5);
      alpha = p3 < 1 ? (Math.floor(t * 40) % 3 === 0 ? 0.2 : 1) * clamp(p3 * 5, 0, 1) : 1;
      var g = glitchAmt(env, t);
      chroma = g * size * 0.06;
      var rr2 = rng(env.cfg.seed * 71 + Math.floor(t * 24));
      dx2 = g > 0.4 ? (rr2() - 0.5) * size * 0.12 * g : 0;
    }
    if (alpha <= 0.003) { ctx.restore(); return; }
    ctx.translate(dx2, dy2);
    ctx.scale(sc2, sc2);
    paint(env, s, ln, size, { alpha: alpha, chroma: chroma });
    if (anim === 'glitch' && chroma > 0.5 * size * 0.06 * 0.4) { // displaced slices
      var r = rng(env.cfg.seed * 17 + Math.floor(t * 20));
      for (var b = 0; b < 3; b++) {
        var by = (r() - 0.5) * size * 0.9, bh = size * (0.06 + r() * 0.16), off = (r() - 0.5) * size * 0.35;
        ctx.save();
        ctx.beginPath(); ctx.rect(-env.W, by, env.W * 2, bh); ctx.clip();
        ctx.translate(off, 0);
        paint(env, s, ln, size, { alpha: alpha, chroma: chroma });
        ctx.restore();
      }
    }
    ctx.restore();
  }

  function drawBlock(env, s, fit, cy, t0, anim) {
    if (!fit) return;
    var top = cy - (fit.lines.length * fit.lh) / 2 + fit.lh / 2;
    for (var li = 0; li < fit.lines.length; li++) {
      drawLine(env, s, fit.lines[li], env.W / 2, top + li * fit.lh, fit.size, t0 + li * 0.12, anim);
    }
  }

  /* -------------------------------------------------------------- text stack */
  // lays out logo / title / subtitle / tagline / contact as one vertically-centred stack
  function drawTexts(env, th) {
    var ctx = env.ctx, W = env.W, H = env.H, cfg = env.cfg;
    var portrait = H > W * 1.15, wide = W > H * 1.6;
    var maxW = W * (th.maxWFrac || 0.86);
    var tm = th.times;
    var clean = function (s) { return (s || '').replace(/\r/g, '').trim(); };
    var up = function (s, u) { return u ? s.toUpperCase() : s; };
    var items = [];

    var logo = (cfg.logo && env.logo) ? env.logo : null;
    if (logo) {
      var lw = logo.naturalWidth || logo.width, lh = logo.naturalHeight || logo.height;
      if (lw > 0 && lh > 0) {
        var box = Math.min(W, H) * (wide ? 0.38 : 0.22) * (th.logoK || 1);
        var sc = Math.min(W * 0.4 / lw, box / lh);
        items.push({ kind: 'logo', w: lw * sc, h: lh * sc, gap: Math.min(W, H) * 0.035 });
      }
    }
    var tText = up(clean(cfg.title), th.title.upper);
    var tFit = null, sFit = null, gFit = null, cFit = null;
    var tMax = Math.min(W * 0.17, H * (wide ? 0.28 : portrait ? 0.15 : 0.16)) * (th.titleK || 1);
    if (tText) {
      tFit = fitText(ctx, tText, th.title.font, th.title.italic, maxW, H * (wide ? 0.46 : portrait ? 0.28 : 0.32), tMax, portrait ? 3 : 2, th.title.tracking);
      items.push({ kind: 'title', h: tFit.lines.length * tFit.lh, gap: tFit.size * 0.3 });
    }
    var tSize = tFit ? tFit.size : tMax;
    var sText = up(clean(cfg.subtitle), th.sub.upper);
    if (sText) {
      sFit = fitText(ctx, sText, th.sub.font, th.sub.italic, maxW * (th.sub.plate ? 0.9 : 1), H * 0.16, Math.min(tSize * 0.45, W * 0.07), 2, th.sub.tracking);
      items.push({ kind: 'sub', h: sFit.lines.length * sFit.lh + (th.sub.plate ? sFit.size * 0.5 : 0), gap: sFit.size * 0.45 });
    }
    var gText = up(clean(cfg.tagline), th.tag.upper);
    if (gText) {
      gFit = fitText(ctx, gText, th.tag.font, th.tag.italic, maxW, H * 0.1, Math.min(tSize * 0.3, W * 0.05), 2, th.tag.tracking);
      items.push({ kind: 'tag', h: gFit.lines.length * gFit.lh, gap: gFit.size * 0.5 });
    }
    var cText = th.contactInStack ? clean(cfg.contact) : '';
    if (cText) {
      cFit = fitText(ctx, cText, th.tag.font, false, maxW, H * 0.08, Math.min(tSize * 0.26, W * 0.04), 1, 0);
      items.push({ kind: 'contact', h: cFit.lh, gap: 0 });
    }
    if (!items.length) return;
    var total = 0;
    items.forEach(function (it, i) { total += it.h + (i < items.length - 1 ? it.gap : 0); });
    var y = H * th.cy - total / 2;
    var anim = cfg.anim;

    items.forEach(function (it) {
      var cy = y + it.h / 2;
      if (it.kind === 'logo') {
        var p = prog(env.t, tm.logo, 0.7), e = Math.max(0.001, easeOutBack(p, 2));
        ctx.save();
        ctx.globalAlpha *= clamp(p * 3, 0, 1);
        ctx.translate(W / 2, cy); ctx.scale(e, e);
        if (env.fx.glow) { ctx.shadowColor = env.pal.a1; ctx.shadowBlur = it.h * 0.2; }
        ctx.drawImage(logo, -it.w / 2, -it.h / 2, it.w, it.h);
        ctx.restore();
      } else if (it.kind === 'title') {
        drawBlock(env, th.title, tFit, cy, tm.title, anim);
      } else if (it.kind === 'sub') {
        if (th.sub.plate) {
          var pp = easeOutCubic(prog(env.t, tm.sub, 0.6));
          var pw = sFit.w + sFit.size * 1.4, ph = it.h;
          ctx.save();
          ctx.globalAlpha *= pp;
          ctx.translate(0, (1 - pp) * sFit.size * 0.6);
          ctx.fillStyle = th.sub.plate.fill;
          rr(ctx, W / 2 - pw / 2, cy - ph / 2, pw, ph, ph * 0.5);
          ctx.fill();
          ctx.restore();
        }
        drawBlock(env, th.sub, sFit, cy, tm.sub, th.sub.anim || 'slide');
      } else if (it.kind === 'tag') {
        drawBlock(env, th.tag, gFit, cy, tm.tag, 'fade');
      } else if (it.kind === 'contact') {
        drawBlock(env, th.tag, cFit, cy, tm.contact, 'fade');
      }
      y += it.h + it.gap;
    });
  }

  /* ------------------------------------------------------------ shared VFX */
  function drawParticles(env) {
    var ctx = env.ctx, W = env.W, H = env.H, K = env.K, t = env.t, pal = env.pal, I = env.I;
    var n = clamp(Math.round(70 * (W * H) / (1080 * 1920) * (0.4 + I)), 28, 320);
    var r = rng(env.cfg.seed * 97 + 11);
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    for (var i = 0; i < n; i++) {
      var x0 = r(), y0 = r(), sp = 0.015 + r() * 0.05, dr = (r() - 0.5) * 0.02, sz = 0.8 + r() * 2.6, ph = r() * 6.28, ci = r();
      var px = frac(x0 + dr * t) * W, py = frac(y0 - sp * t) * H;
      var tw = 0.5 + 0.5 * Math.sin(t * 2.2 + ph);
      var rad = sz * K * (0.8 + tw * 0.6) * (env.still ? 1.5 : 1);
      var col = ci < 0.4 ? pal.a1 : ci < 0.75 ? pal.a2 : '#ffffff';
      ctx.globalAlpha = (0.25 + 0.6 * tw) * Math.min(1, 0.4 + I);
      if (i % 3 === 0) { ctx.shadowColor = col; ctx.shadowBlur = rad * 5; } else { ctx.shadowBlur = 0; }
      ctx.fillStyle = col;
      ctx.beginPath(); ctx.arc(px, py, rad, 0, 6.2832); ctx.fill();
    }
    ctx.restore();
  }

  function drawBurst(env) {
    var ctx = env.ctx, W = env.W, H = env.H, K = env.K, t = env.t, pal = env.pal, I = env.I, sc = env.sc;
    var p = env.still ? 0.85 : t - sc.hit;
    if (p < 0 || p > 4.2) return;
    var n = Math.round(70 + 120 * I), r = rng(env.cfg.seed * 53 + 3), R = Math.min(W, H);
    var ox = W / 2, oy = H * (sc.originY || 0.46), kd = 2.3, g = H * 0.45;
    var confetti = !!sc.confetti;
    var cols = [pal.a1, pal.a2, '#ffffff', mixc(pal.a1, pal.a2, 0.5)];
    ctx.save();
    if (!confetti) ctx.globalCompositeOperation = 'lighter';
    ctx.lineCap = 'round';
    for (var i = 0; i < n; i++) {
      // every random value is drawn up-front so each particle is stable whatever branch is taken
      var a = r() * 6.2832, v = (0.25 + r() * 0.85) * R * 1.35, life = 2.2 + r() * 1.6;
      var w1 = r(), h1 = r(), rot = r(), flip = r(), thick = r();
      var al = clamp(1 - (p - life * 0.55) / (life * 0.45), 0, 1);
      if (al <= 0) continue;
      var d = v * (1 - Math.exp(-kd * p)) / kd;
      var x = ox + Math.cos(a) * d, y = oy + Math.sin(a) * d + 0.5 * g * p * p * (confetti ? 1 : 0.35);
      var col = cols[i % 4];
      ctx.globalAlpha = al;
      if (confetti) {
        var w = (7 + w1 * 10) * K, hh = w * (0.45 + h1 * 0.3);
        ctx.save();
        ctx.translate(x, y); ctx.rotate(rot * 6.28 + p * (3 + flip * 6));
        ctx.scale(1, Math.max(0.08, Math.abs(Math.cos(p * (4 + h1 * 5) + flip * 6))));
        ctx.fillStyle = col; ctx.fillRect(-w / 2, -hh / 2, w, hh);
        ctx.restore();
      } else {
        var vx = Math.cos(a) * v * Math.exp(-kd * p), vy = Math.sin(a) * v * Math.exp(-kd * p) + g * 0.35 * p;
        var spd = Math.sqrt(vx * vx + vy * vy) || 1, len = Math.min(spd * 0.04, 60 * K) + 2 * K;
        ctx.strokeStyle = col; ctx.lineWidth = (2 + thick * 2.5) * K;
        ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x - vx / spd * len, y - vy / spd * len); ctx.stroke();
      }
    }
    ctx.restore();
  }

  function drawRings(env) {
    if (env.still) return;
    var ctx = env.ctx, W = env.W, H = env.H, K = env.K, t = env.t, pal = env.pal, I = env.I, sc = env.sc;
    var times = [sc.hit, sc.hit + 4, sc.hit + 8, sc.hit + 12, sc.hit + 16, sc.hit + 20, sc.hit + 24];
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    times.forEach(function (h, idx) {
      if (h > env.cfg.duration) return;
      for (var k = 0; k < 2; k++) {
        var p = (t - h - k * 0.12) / 1.0;
        if (p <= 0 || p >= 1) continue;
        var strength = idx === 0 ? 1 : 0.45;
        ctx.globalAlpha = Math.pow(1 - p, 1.5) * 0.75 * strength * (0.4 + I * 0.6);
        ctx.strokeStyle = k === 0 ? pal.a1 : pal.a2;
        ctx.lineWidth = (10 * (1 - p) + 2) * K;
        ctx.beginPath(); ctx.arc(W / 2, H * (sc.originY || 0.46), easeOutCubic(p) * Math.max(W, H) * 0.7 * (idx === 0 ? 1 : 0.7), 0, 6.2832); ctx.stroke();
      }
    });
    ctx.restore();
  }

  function drawSweep(env) {
    if (env.still) return;
    var ctx = env.ctx, W = env.W, H = env.H, t = env.t, I = env.I, sc = env.sc;
    var t0 = sc.hit + 0.15, period = 3.6, dur = 0.95;
    if (t < t0) return;
    var p = ((t - t0) % period) / dur;
    if (p >= 1) return;
    var x = lerp(-0.45 * W, 1.45 * W, easeInOut(p)), bw = W * 0.34;
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    ctx.translate(x, 0);
    ctx.transform(1, 0, -0.4, 1, 0, 0);
    var g = ctx.createLinearGradient(-bw / 2, 0, bw / 2, 0);
    g.addColorStop(0, 'rgba(255,255,255,0)');
    g.addColorStop(0.5, 'rgba(255,255,255,' + (0.30 * (0.3 + I * 0.7)).toFixed(3) + ')');
    g.addColorStop(1, 'rgba(255,255,255,0)');
    ctx.fillStyle = g;
    ctx.fillRect(-bw / 2, -H * 0.1, bw, H * 1.2);
    ctx.restore();
  }

  function shakeOffset(env) {
    if (env.still || !env.fx.shake) return [0, 0];
    var p = env.t - env.sc.hit;
    if (p <= 0 || p >= 0.5) return [0, 0];
    var amp = Math.pow(1 - p / 0.5, 2) * 18 * env.K * env.I;
    return [Math.sin(p * 70) * amp, Math.cos(p * 83) * amp * 0.8];
  }

  function post(env) {
    var ctx = env.ctx, W = env.W, H = env.H, K = env.K, t = env.t, I = env.I, fx = env.fx;
    if (fx.scan || env.sc.forceScan) {
      ctx.save();
      ctx.fillStyle = 'rgba(0,0,0,' + (0.16 * (0.4 + I * 0.8)).toFixed(3) + ')';
      var step = Math.max(2, Math.round(4 * K)), off = env.still ? 0 : Math.floor(t * 30 * K) % step;
      for (var y = off; y < H; y += step) ctx.fillRect(0, y, W, Math.max(1, step / 2));
      ctx.restore();
    }
    if (fx.grain) {
      var gr = rng(env.cfg.seed * 7 + Math.floor(t * 24));
      var n = clamp(Math.round(W * H / 1200), 300, 7000), sz = Math.max(1, Math.round(1.4 * K));
      ctx.save();
      for (var i = 0; i < n; i++) {
        var gx = gr() * W, gy = gr() * H, gv = gr();
        ctx.fillStyle = gv > 0.5 ? 'rgba(255,255,255,' + (0.05 * I + 0.02).toFixed(3) + ')' : 'rgba(0,0,0,' + (0.07 * I + 0.02).toFixed(3) + ')';
        ctx.fillRect(gx, gy, sz, sz);
      }
      ctx.restore();
    }
    if (fx.vignette) {
      var vg = ctx.createRadialGradient(W / 2, H / 2, Math.min(W, H) * 0.35, W / 2, H / 2, Math.max(W, H) * 0.78);
      vg.addColorStop(0, 'rgba(0,0,0,0)'); vg.addColorStop(1, 'rgba(0,0,0,' + (0.62 * (0.4 + I * 0.6)).toFixed(3) + ')');
      ctx.fillStyle = vg; ctx.fillRect(0, 0, W, H);
    }
    if (fx.flash && !env.still) {
      var fp = (t - env.sc.hit) / 0.28;
      if (fp >= 0 && fp < 1) {
        ctx.fillStyle = 'rgba(255,255,255,' + (Math.pow(1 - fp, 3) * 0.55 * (0.4 + I * 0.6)).toFixed(3) + ')';
        ctx.fillRect(0, 0, W, H);
      }
    }
  }

  var _scratch = null;
  function bloom(env, opts) {
    var ctx = env.ctx, W = env.W, H = env.H, cv = ctx.canvas;
    if (!env.fx.bloom || opts.offsetY || !cv || cv.width !== W || cv.height !== H) return;
    try {
      var sw = Math.max(8, Math.ceil(W / 4)), sh = Math.max(8, Math.ceil(H / 4));
      if (!_scratch || _scratch.width !== sw || _scratch.height !== sh) {
        _scratch = opts.createCanvas ? opts.createCanvas(sw, sh)
          : (typeof OffscreenCanvas !== 'undefined' ? new OffscreenCanvas(sw, sh) : document.createElement('canvas'));
        _scratch.width = sw; _scratch.height = sh;
      }
      var sctx = _scratch.getContext('2d');
      sctx.setTransform(1, 0, 0, 1, 0, 0);
      sctx.clearRect(0, 0, sw, sh);
      sctx.drawImage(cv, 0, 0, W, H, 0, 0, sw, sh);
      ctx.save();
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.globalCompositeOperation = 'lighter';
      ctx.globalAlpha = 0.5 * (0.3 + env.I * 0.7);
      ctx.filter = 'blur(' + Math.round(10 * env.K) + 'px) brightness(1.25)';
      ctx.drawImage(_scratch, 0, 0, sw, sh, 0, 0, W, H);
      ctx.restore();
    } catch (e) { /* bloom is optional; never fail a frame because of it */ }
  }

  /* --------------------------------------------------------------- captions */
  function groupCaptions(words) {
    var phrases = [], cur = null;
    (words || []).forEach(function (w, i) {
      var prev = i > 0 ? words[i - 1] : null;
      var start = !cur || cur.words.length >= 4 || cur.chars > 24 || (prev && w.s - prev.e > 0.5) || (prev && /[.!?]$/.test(prev.w));
      if (start) { cur = { words: [], chars: 0, s: w.s, e: w.e }; phrases.push(cur); }
      cur.words.push(w); cur.chars += w.w.length + 1; cur.e = w.e;
    });
    return phrases;
  }

  function quickCaptions(script, dur, start) {
    var words = String(script || '').trim().split(/\s+/).filter(Boolean);
    if (!words.length) return [];
    var wt = words.map(function (w) { return Math.max(2, Array.from(w).length) + (/[.,!?;:।]$/.test(w) ? 3 : 0); });
    var total = wt.reduce(function (a, b) { return a + b; }, 0), acc = 0, out = [];
    for (var i = 0; i < words.length; i++) {
      var s = (start || 0) + dur * acc / total; acc += wt[i];
      out.push({ w: words[i], s: s, e: (start || 0) + dur * acc / total });
    }
    return out;
  }

  function drawCaptions(env) {
    var cfg = env.cfg;
    if (!cfg.captionsOn || !cfg.captions || !cfg.captions.length) return;
    if (!cfg._phr) cfg._phr = groupCaptions(cfg.captions);
    var ctx = env.ctx, W = env.W, H = env.H, t = env.t, pal = env.pal;
    var ph = null;
    for (var i = 0; i < cfg._phr.length; i++) { var p = cfg._phr[i]; if (t >= p.s - 0.05 && t <= p.e + 0.25) { ph = p; break; } }
    if (!ph) return;
    var size = Math.min(W * 0.062, H * 0.05), y = H * (H > W ? 0.80 : 0.86);
    var style = cfg.captionStyle;
    ctx.save();
    ctx.textBaseline = 'middle'; ctx.textAlign = 'left';
    setFont(ctx, 'bold', size);
    var texts = ph.words.map(function (w) { return w.w; });
    var gap = size * 0.28;
    var ws = texts.map(function (s) { return ctx.measureText(s).width; });
    var total = ws.reduce(function (a, b) { return a + b; }, 0) + gap * (texts.length - 1);
    if (total > W * 0.9) { size *= (W * 0.9) / total; setFont(ctx, 'bold', size); ws = texts.map(function (s) { return ctx.measureText(s).width; }); total = ws.reduce(function (a, b) { return a + b; }, 0) + gap * (texts.length - 1); }
    var fadeIn = clamp((t - (ph.s - 0.05)) / 0.12, 0, 1);
    ctx.globalAlpha = fadeIn * clamp((ph.e + 0.25 - t) / 0.15, 0, 1);

    if (style === 'bar') {
      var bw = total + size * 1.2, bh = size * 1.7;
      ctx.fillStyle = 'rgba(0,0,0,0.62)'; rr(ctx, W / 2 - bw / 2, y - bh / 2, bw, bh, bh * 0.3); ctx.fill();
    }
    if (style === 'pop') { // one big word at a time
      var cur = ph.words[0];
      ph.words.forEach(function (w) { if (t >= w.s) cur = w; });
      var pp = easeOutBack(prog(t, cur.s, 0.25), 2.2), big = size * 1.5;
      setFont(ctx, 'bold', big);
      ctx.textAlign = 'center'; ctx.translate(W / 2, y); ctx.scale(Math.max(0.01, pp), Math.max(0.01, pp));
      ctx.lineJoin = 'round'; ctx.lineWidth = big * 0.14; ctx.strokeStyle = 'rgba(0,0,0,0.85)'; ctx.strokeText(cur.w, 0, 0);
      ctx.fillStyle = pal.a2; ctx.fillText(cur.w, 0, 0);
      ctx.restore();
      return;
    }
    var x = W / 2 - total / 2;
    ph.words.forEach(function (w, i) {
      var active = t >= w.s && t < w.e + 0.02, spoken = t >= w.s;
      ctx.save();
      ctx.translate(x + ws[i] / 2, y);
      var sc = active ? 1 + 0.12 * Math.sin(prog(t, w.s, 0.2) * Math.PI) : 1;
      ctx.scale(sc, sc); ctx.textAlign = 'center';
      ctx.lineJoin = 'round'; ctx.lineWidth = size * 0.16; ctx.strokeStyle = 'rgba(0,0,0,0.85)';
      if (style !== 'bar') ctx.strokeText(w.w, 0, 0);
      ctx.fillStyle = active ? pal.a2 : spoken ? '#ffffff' : 'rgba(255,255,255,0.55)';
      ctx.fillText(w.w, 0, 0);
      ctx.restore();
      x += ws[i] + gap;
    });
    ctx.restore();
  }

  /* ------------------------------------------------- imported video / image bg */
  // Draws an imported clip frame (<video>, <img> or canvas) "cover"-fitted, then dims it so text stays readable.
  // Returns false when the source has no size yet so the caller can fall back to the scene backdrop.
  function drawBgMedia(env, src) {
    var sw = src.videoWidth || src.naturalWidth || src.width, sh = src.videoHeight || src.naturalHeight || src.height;
    if (!sw || !sh) return false;
    var ctx = env.ctx, W = env.W, H = env.H;
    var s = Math.max(W / sw, H / sh) * 1.06; // slight overscan keeps edges covered during camera shake
    var dw = sw * s, dh = sh * s;
    ctx.drawImage(src, (W - dw) / 2, (H - dh) / 2, dw, dh);
    var dim = clamp(env.cfg.bgDim === undefined ? 0.35 : +env.cfg.bgDim, 0, 0.9);
    if (dim > 0) {
      var g = ctx.createLinearGradient(0, 0, 0, H);
      g.addColorStop(0, 'rgba(0,0,0,' + (dim * 0.8).toFixed(3) + ')');
      g.addColorStop(0.5, 'rgba(0,0,0,' + dim.toFixed(3) + ')');
      g.addColorStop(1, 'rgba(0,0,0,' + Math.min(0.95, dim * 1.15).toFixed(3) + ')');
      ctx.fillStyle = g; ctx.fillRect(-W * 0.06, -H * 0.06, W * 1.12, H * 1.12);
    }
    return true;
  }

  /* ----------------------------------------------------------------- scenes */
  var solid =function (c) { return function () { return c; }; };
  var vGrad = function (c1, c2, c3) {
    return function (ctx, size) {
      var g = ctx.createLinearGradient(0, -size * 0.5, 0, size * 0.5);
      g.addColorStop(0, c1); g.addColorStop(1, c2);
      if (c3) { g.addColorStop(0.5, c3); }
      return g;
    };
  };
  var textTheme = function (o) { // merge helper with sane defaults
    var def = function (x, d) { return x || d; };
    return {
      cy: o.cy || 0.47, maxWFrac: o.maxWFrac, titleK: o.titleK, logoK: o.logoK, times: o.times,
      contactInStack: o.contactInStack !== false,
      title: def(o.title, {}), sub: def(o.sub, {}), tag: def(o.tag, {})
    };
  };

  var SCENES = {
    neon: {
      name: 'Neon Pulse', desc: 'Glowing title, orbit rings, light sweep',
      defaults: { font: 'bold', anim: 'pop', palette: 'neon' },
      hit: 0.95, hero: 3.2, times: { logo: 0.25, title: 0.7, sub: 1.45, tag: 1.9, contact: 2.2 },
      bg: function (env) {
        var ctx = env.ctx, W = env.W, H = env.H, K = env.K, t = env.t, pal = env.pal, I = env.I, R = Math.max(W, H);
        ctx.fillStyle = linGrad(ctx, W, H, pal.bg1, pal.bg2, 125); bgRect(ctx, W, H);
        ctx.save(); ctx.globalCompositeOperation = 'lighter';
        blob(ctx, W * (0.25 + 0.18 * Math.sin(t * 0.5)), H * (0.25 + 0.12 * Math.cos(t * 0.4)), R * 0.55, pal.a1, 0.30 * (0.5 + I * 0.5));
        blob(ctx, W * (0.78 + 0.15 * Math.cos(t * 0.45)), H * (0.70 + 0.12 * Math.sin(t * 0.55)), R * 0.6, pal.a2, 0.28 * (0.5 + I * 0.5));
        blob(ctx, W * 0.5, H * (0.5 + 0.05 * Math.sin(t)), R * 0.35, mixc(pal.a1, pal.a2, 0.5), 0.14);
        ctx.restore();
        ctx.save(); ctx.translate(W / 2, H * 0.47); ctx.strokeStyle = rgba(pal.a1, 0.22); ctx.lineWidth = 2 * K;
        for (var i = 0; i < 3; i++) {
          var r = Math.min(W, H) * (0.34 + i * 0.17);
          ctx.setLineDash([r * 0.08, r * 0.05 + i * r * 0.02]);
          ctx.lineDashOffset = -t * (40 + i * 25) * K * (i % 2 ? -1 : 1);
          ctx.beginPath(); ctx.arc(0, 0, r, 0, 6.2832); ctx.stroke();
        }
        ctx.restore();
      },
      theme: function (env) {
        var p = env.pal;
        return textTheme({
          cy: 0.47, times: this.times,
          title: { font: env.cfg.font, upper: false, glow: 0.35, glowColor: p.a1, fill: solid(p.text), tracking: 0 },
          sub: { font: 'clean', fill: solid(rgba(p.text, 0.88)), tracking: 0.02 },
          tag: { font: 'light', fill: solid(p.a2), tracking: 0.12, upper: true }
        });
      }
    },

    glitch: {
      name: 'Cyber Glitch', desc: 'RGB split, data grid, scanlines',
      defaults: { font: 'condensed', anim: 'glitch', palette: 'cyber' },
      hit: 0.8, hero: 2.6, forceScan: true, times: { logo: 0.2, title: 0.6, sub: 1.4, tag: 1.8, contact: 2.1 },
      bg: function (env) {
        var ctx = env.ctx, W = env.W, H = env.H, K = env.K, t = env.t, pal = env.pal, I = env.I;
        ctx.fillStyle = linGrad(ctx, W, H, mixc(pal.bg1, '#000000', 0.5), pal.bg2, 100); bgRect(ctx, W, H);
        var s = 72 * K, off = env.still ? 0 : (t * 30 * K) % s;
        ctx.save(); ctx.strokeStyle = rgba(pal.a1, 0.10); ctx.lineWidth = Math.max(1, K);
        ctx.beginPath();
        for (var x = 0; x <= W; x += s) { ctx.moveTo(x, 0); ctx.lineTo(x, H); }
        for (var y = -s + off; y <= H; y += s) { ctx.moveTo(0, y); ctx.lineTo(W, y); }
        ctx.stroke(); ctx.restore();
        var k = Math.floor(t * 12), r = rng(env.cfg.seed * 29 + k);
        var near = t >= env.sc.hit - 0.05 && t <= env.sc.hit + 0.4;
        var cnt = near ? 7 : (r() < 0.25 ? 3 : 0);
        ctx.save();
        for (var i = 0; i < cnt; i++) {
          ctx.fillStyle = rgba(i % 2 ? pal.a1 : pal.a2, 0.10 + 0.14 * I);
          ctx.fillRect(r() * W * 0.7, r() * H, W * (0.15 + r() * 0.5), (6 + r() * 34) * K);
        }
        ctx.restore();
        blob(ctx, W * 0.5, H * 0.47, Math.max(W, H) * 0.5, pal.a2, 0.10);
      },
      theme: function (env) {
        var p = env.pal;
        return textTheme({
          cy: 0.47, times: this.times,
          title: { font: env.cfg.font, upper: true, tracking: 0.02, fill: solid(p.text), glow: 0.18, glowColor: p.a1 },
          sub: { font: 'mono', fill: solid(p.a1), tracking: 0.04, upper: false },
          tag: { font: 'mono', fill: solid(p.a2), tracking: 0.1, upper: true }
        });
      }
    },

    cinema: {
      name: 'Cinematic', desc: 'Letterbox, anamorphic flare, wide tracking',
      defaults: { font: 'serif', anim: 'tracking', palette: 'gold' },
      hit: 1.2, hero: 3.4, times: { logo: 0.5, title: 0.9, sub: 2.0, tag: 2.5, contact: 2.8 },
      bg: function (env) {
        var ctx = env.ctx, W = env.W, H = env.H, t = env.t, pal = env.pal, I = env.I;
        var z = 1 + 0.05 * clamp(t / env.cfg.duration, 0, 1);
        ctx.save();
        ctx.translate(W / 2, H / 2); ctx.scale(z, z); ctx.translate(-W / 2, -H / 2);
        ctx.fillStyle = linGrad(ctx, W, H, pal.bg1, pal.bg2, 90); bgRect(ctx, W, H);
        ctx.globalCompositeOperation = 'lighter';
        blob(ctx, W / 2, H * 0.40, Math.max(W, H) * 0.7, pal.a2, 0.20 * (0.5 + I * 0.5));
        blob(ctx, W * 0.15, H * 0.9, Math.max(W, H) * 0.5, pal.a1, 0.10);
        ctx.restore();
      },
      over: function (env) {
        var ctx = env.ctx, W = env.W, H = env.H, K = env.K, t = env.t, pal = env.pal, I = env.I;
        var open = env.still ? 1 : easeOutCubic(prog(t, 0, 1.0));
        var bar = H * 0.095 * open;
        ctx.fillStyle = '#000'; ctx.fillRect(-2, -2, W + 4, bar + 2); ctx.fillRect(-2, H - bar, W + 4, bar + 2);
        var fp = env.still ? 0.5 : (t - env.sc.hit) / 2.2;
        if (fp > -0.05 && fp < 1) { // anamorphic flare streak
          var a = Math.sin(clamp(fp + 0.05, 0, 1.05) * Math.PI) * 0.8 * (0.3 + I * 0.7);
          ctx.save(); ctx.globalCompositeOperation = 'lighter'; ctx.globalAlpha = Math.max(0, a);
          var g = ctx.createLinearGradient(0, 0, W, 0);
          g.addColorStop(0, rgba(pal.a2, 0)); g.addColorStop(0.5, rgba(pal.a2, 0.95)); g.addColorStop(1, rgba(pal.a2, 0));
          var fy = H * 0.47, th = Math.max(2, 5 * K);
          ctx.fillStyle = g; ctx.fillRect(0, fy - th / 2, W, th);
          var g2 = ctx.createLinearGradient(0, 0, W, 0);
          g2.addColorStop(0, rgba(pal.a1, 0)); g2.addColorStop(0.5, rgba('#ffffff', 0.35)); g2.addColorStop(1, rgba(pal.a1, 0));
          ctx.fillStyle = g2; ctx.fillRect(0, fy - th * 4, W, th * 8);
          ctx.restore();
        }
      },
      theme: function (env) {
        var p = env.pal;
        return textTheme({
          cy: 0.47, times: this.times, titleK: 0.9,
          title: { font: env.cfg.font, upper: true, tracking: 0.07, fill: vGrad(p.text, p.a1), glow: 0.12, glowColor: p.a2 },
          sub: { font: 'light', upper: true, tracking: 0.22, fill: solid(rgba(p.text, 0.9)), anim: 'fade' },
          tag: { font: 'serif', italic: true, fill: solid(p.a1), tracking: 0.04 }
        });
      }
    },

    burst: {
      name: 'Pop Burst', desc: 'Confetti, sunburst, outlined 3D title',
      defaults: { font: 'bold', anim: 'pop', palette: 'sunset' },
      hit: 0.75, hero: 2.6, confetti: true, times: { logo: 0.2, title: 0.5, sub: 1.2, tag: 1.6, contact: 1.9 },
      bg: function (env) {
        var ctx = env.ctx, W = env.W, H = env.H, t = env.t, pal = env.pal, K = env.K;
        ctx.fillStyle = linGrad(ctx, W, H, mixc(pal.bg2, pal.a1, 0.38), mixc(pal.bg1, pal.bg2, 0.5), 120); bgRect(ctx, W, H);
        var cx = W / 2, cy = H * 0.46, R = Math.max(W, H) * 1.2, n = 18, rot = env.still ? 0.3 : t * 0.12;
        ctx.save(); ctx.fillStyle = 'rgba(255,255,255,0.07)';
        for (var i = 0; i < n; i++) {
          var a0 = rot + (i * 2) * Math.PI / n, a1 = a0 + Math.PI / n;
          ctx.beginPath(); ctx.moveTo(cx, cy); ctx.lineTo(cx + Math.cos(a0) * R, cy + Math.sin(a0) * R); ctx.lineTo(cx + Math.cos(a1) * R, cy + Math.sin(a1) * R); ctx.closePath(); ctx.fill();
        }
        ctx.restore();
        var r = rng(env.cfg.seed * 41 + 5);
        for (var j = 0; j < 7; j++) {
          var bx = r(), by = r(), br = (0.06 + r() * 0.12) * Math.min(W, H), sp = 0.15 + r() * 0.2, ph = r() * 6;
          ctx.fillStyle = rgba(j % 2 ? pal.a1 : pal.a2, 0.18);
          ctx.beginPath(); ctx.arc(bx * W, by * H + Math.sin(t * sp * 4 + ph) * 18 * K, br, 0, 6.2832); ctx.fill();
        }
      },
      theme: function (env) {
        var p = env.pal, dark = mixc(p.bg1, '#000000', 0.55);
        return textTheme({
          cy: 0.47, times: this.times, titleK: 1.05,
          title: { font: env.cfg.font, upper: false, fill: solid(p.text), outline: { w: 0.1, color: dark }, extrude: { dx: 0.015, dy: 0.055, color: dark, steps: 10 }, glow: 0 },
          sub: { font: 'bold', fill: solid(p.a2), outline: { w: 0.14, color: dark }, tracking: 0.01 },
          tag: { font: 'clean', fill: solid(p.text), tracking: 0.04 }
        });
      }
    },

    aurora: {
      name: 'Aurora Flow', desc: 'Soft flowing light, elegant type',
      defaults: { font: 'clean', anim: 'slide', palette: 'ice' },
      hit: 1.2, hero: 3.4, times: { logo: 0.3, title: 0.8, sub: 1.6, tag: 2.0, contact: 2.3 },
      bg: function (env) {
        var ctx = env.ctx, W = env.W, H = env.H, t = env.t, pal = env.pal, I = env.I, R = Math.max(W, H);
        ctx.fillStyle = linGrad(ctx, W, H, pal.bg1, pal.bg2, 90); bgRect(ctx, W, H);
        ctx.save(); ctx.globalCompositeOperation = 'lighter';
        var cols = [pal.a1, pal.a2, mixc(pal.a1, pal.a2, 0.5), pal.a1, pal.a2];
        for (var b = 0; b < 5; b++) {
          for (var s = -1; s <= 12; s++) {
            var x = W * s / 11;
            var y = H * (0.2 + 0.14 * b) + Math.sin(x / W * 5 + t * (0.5 + b * 0.12) + b * 1.7) * H * 0.07 + Math.sin(x / W * 11 - t * 0.7 + b) * H * 0.025;
            blob(ctx, x, y, R * 0.16, cols[b], 0.075 * (0.5 + I * 0.8));
          }
        }
        ctx.restore();
      },
      theme: function (env) {
        var p = env.pal;
        return textTheme({
          cy: 0.47, times: this.times,
          title: { font: env.cfg.font, upper: false, fill: solid('#ffffff'), glow: 0.16, glowColor: p.a1, tracking: 0.005 },
          sub: { font: 'light', fill: solid(rgba('#ffffff', 0.85)), tracking: 0.06 },
          tag: { font: 'light', fill: solid(p.a2), tracking: 0.14, upper: true }
        });
      }
    },

    retro: {
      name: 'Retro Wave', desc: 'Synth sun, grid horizon, chrome title',
      defaults: { font: 'bold', anim: 'slide', palette: 'synth' },
      hit: 1.0, hero: 3.0, originY: 0.34, times: { logo: 0.3, title: 0.8, sub: 1.5, tag: 1.9, contact: 2.2 },
      bg: function (env) {
        var ctx = env.ctx, W = env.W, H = env.H, K = env.K, t = env.t, pal = env.pal;
        var hy = H * 0.62;
        var sky = ctx.createLinearGradient(0, 0, 0, hy);
        sky.addColorStop(0, pal.bg1); sky.addColorStop(0.65, pal.bg2); sky.addColorStop(1, mixc(pal.bg2, pal.a2, 0.55));
        ctx.fillStyle = sky; ctx.fillRect(-W * 0.06, -H * 0.06, W * 1.12, hy + H * 0.06);
        var r = rng(env.cfg.seed * 13 + 2);
        ctx.fillStyle = '#ffffff';
        for (var i = 0; i < 60; i++) { var sx = r() * W, sy = r() * hy * 0.8, tw = 0.4 + 0.6 * Math.abs(Math.sin(t * 1.5 + i)); ctx.globalAlpha = 0.5 * tw; ctx.fillRect(sx, sy, 2 * K, 2 * K); }
        ctx.globalAlpha = 1;
        var sr = Math.min(W, H) * 0.30, scy = hy - sr * (0.35 + 0.2 * (env.still ? 1 : easeOutCubic(prog(t, 0, 2.0))));
        ctx.save(); ctx.beginPath(); ctx.rect(-W, -H, W * 3, hy + H); ctx.clip();
        var sg = ctx.createLinearGradient(0, scy - sr, 0, scy + sr);
        sg.addColorStop(0, pal.a2); sg.addColorStop(1, pal.a1);
        ctx.shadowColor = pal.a2; ctx.shadowBlur = sr * 0.35;
        ctx.fillStyle = sg; ctx.beginPath(); ctx.arc(W / 2, scy, sr, 0, 6.2832); ctx.fill();
        ctx.shadowBlur = 0;
        ctx.fillStyle = sky; // stripes cut the sun with the sky gradient so it reads as a slatted sun
        for (var k = 1; k <= 7; k++) { var yy = scy + sr * (0.02 + 0.13 * k); ctx.fillRect(W / 2 - sr - 2, yy, sr * 2 + 4, Math.max(1, (0.8 + k * 0.55) * K * 2)); }
        ctx.restore();
        var fg = ctx.createLinearGradient(0, hy, 0, H);
        fg.addColorStop(0, mixc(pal.bg2, '#000000', 0.2)); fg.addColorStop(1, mixc(pal.bg1, '#000000', 0.5));
        ctx.fillStyle = fg; ctx.fillRect(-W * 0.06, hy, W * 1.12, H - hy + H * 0.06);
        ctx.save(); ctx.strokeStyle = pal.a1; ctx.lineWidth = 2 * K; ctx.shadowColor = pal.a1; ctx.shadowBlur = 8 * K;
        ctx.beginPath();
        var N = 14, sh = env.still ? 0.3 : (t * 0.6) % 1;
        for (var h = 0; h < N; h++) { var q = Math.pow((h + sh) / N, 2.2); var gy = hy + (H - hy) * q; ctx.moveTo(0, gy); ctx.lineTo(W, gy); }
        for (var j = -12; j <= 12; j++) { ctx.moveTo(W / 2, hy); ctx.lineTo(W / 2 + j * W * 0.16, H); }
        ctx.stroke(); ctx.restore();
        ctx.save(); ctx.globalCompositeOperation = 'lighter'; blob(ctx, W / 2, hy, Math.max(W, H) * 0.5, pal.a2, 0.18); ctx.restore();
      },
      theme: function (env) {
        var p = env.pal;
        var chrome = function (ctx, size) {
          var g = ctx.createLinearGradient(0, -size * 0.5, 0, size * 0.5);
          g.addColorStop(0, '#ffffff'); g.addColorStop(0.46, mixc(p.a1, '#ffffff', 0.55));
          g.addColorStop(0.5, mixc(p.a1, '#000000', 0.35)); g.addColorStop(1, p.a2);
          return g;
        };
        var lift = { color: 'rgba(8,0,24,0.85)', blur: 0.14, dy: 0.05 }; // dark halo keeps text readable over the bright sun
        return textTheme({
          cy: 0.36, times: this.times, titleK: 0.95,
          title: { font: env.cfg.font, italic: true, upper: true, fill: chrome, glow: 0.22, glowColor: p.a2, extrude: { dx: 0.01, dy: 0.04, color: mixc(p.a2, '#000000', 0.45), steps: 8 } },
          sub: { font: 'bold', italic: true, upper: true, fill: solid(mixc(p.a1, '#ffffff', 0.7)), tracking: 0.12, shadow: lift },
          tag: { font: 'clean', fill: solid('#ffffff'), tracking: 0.12, upper: true, shadow: lift }
        });
      }
    },

    banner: {
      name: 'Flex Banner', desc: 'Print-ready shop banner with contact bar',
      defaults: { font: 'bold', anim: 'pop', palette: 'saffron', vfx: { particles: false, burst: false, rings: false, sweep: false, flash: false, shake: false, grain: false, bloom: false, vignette: false } },
      hit: 0.5, hero: 2.0, times: { logo: 0.1, title: 0.2, sub: 0.7, tag: 1.0, contact: 1.2 },
      bg: function (env) {
        var ctx = env.ctx, W = env.W, H = env.H, K = env.K, pal = env.pal;
        ctx.fillStyle = linGrad(ctx, W, H, pal.bg1, pal.bg2, 135); bgRect(ctx, W, H);
        ctx.save();
        ctx.fillStyle = rgba(pal.a1, 0.16);
        ctx.beginPath(); ctx.moveTo(W * 0.56, 0); ctx.lineTo(W * 0.80, 0); ctx.lineTo(W * 0.40, H); ctx.lineTo(W * 0.16, H); ctx.closePath(); ctx.fill();
        ctx.fillStyle = rgba(pal.a2, 0.14);
        ctx.beginPath(); ctx.moveTo(W * 0.84, 0); ctx.lineTo(W * 0.90, 0); ctx.lineTo(W * 0.50, H); ctx.lineTo(W * 0.44, H); ctx.closePath(); ctx.fill();
        var s = 46 * K, maxD = Math.max(W, H) * 0.42;
        ctx.fillStyle = rgba(pal.a2, 0.22);
        for (var y = s / 2; y < H; y += s) for (var x = s / 2; x < W; x += s) {
          var d = Math.hypot(W - x, y), w = 1 - d / maxD;
          if (w > 0.05) { ctx.beginPath(); ctx.arc(x, y, s * 0.36 * w, 0, 6.2832); ctx.fill(); }
        }
        ctx.restore();
        var bh = H * 0.13;
        ctx.fillStyle = 'rgba(0,0,0,0.38)'; ctx.fillRect(0, H - bh, W, bh);
        ctx.fillStyle = pal.a1; ctx.fillRect(0, H - bh - Math.max(2, H * 0.012), W, Math.max(2, H * 0.012));
        ctx.strokeStyle = rgba(pal.a2, 0.75); ctx.lineWidth = Math.max(2, Math.min(W, H) * 0.006);
        var inset = Math.min(W, H) * 0.025; ctx.strokeRect(inset, inset, W - inset * 2, H - inset * 2);
      },
      fg2: function (env) { // contact line lives in the bottom bar
        var ctx = env.ctx, W = env.W, H = env.H, pal = env.pal, cfg = env.cfg;
        var txt = (cfg.contact || '').trim();
        if (!txt) return;
        var bh = H * 0.13, fit = fitText(ctx, txt, 'bold', false, W * 0.86, bh * 0.62, bh * 0.5, 1, 0.01);
        ctx.save(); ctx.textBaseline = 'middle'; ctx.textAlign = 'left';
        ctx.translate(W / 2, H - bh / 2);
        setFont(ctx, 'bold', fit.size);
        ctx.textAlign = 'center'; ctx.fillStyle = pal.text; ctx.fillText(fit.lines[0], 0, 0);
        ctx.restore();
      },
      theme: function (env) {
        var p = env.pal, dark = mixc(p.bg1, '#000000', 0.5);
        return textTheme({
          cy: 0.43, times: this.times, titleK: 1.05, contactInStack: false, maxWFrac: 0.84,
          title: { font: env.cfg.font, upper: false, fill: solid(p.text), outline: { w: 0.09, color: dark }, extrude: { dx: 0.014, dy: 0.05, color: dark, steps: 9 } },
          sub: { font: 'bold', fill: solid(dark), plate: { fill: p.a1 }, tracking: 0.01 },
          tag: { font: 'clean', fill: solid(p.text), tracking: 0.03 }
        });
      }
    }
  };

  /* -------------------------------------------------------------- public API */
  var DEFAULT_VFX = { particles: true, burst: true, rings: true, sweep: true, glow: true, chroma: false, scan: false, grain: true, vignette: true, flash: true, shake: true, bloom: true };
  var DEFAULT_CFG = {
    scene: 'neon', title: 'Your Title Here', subtitle: 'A short subtitle goes here', tagline: '', contact: '',
    palette: 'neon', custom: null, font: null, anim: null, vfx: DEFAULT_VFX, intensity: 0.7, duration: 6, seed: 7,
    sfxPack: 'cinematic', captions: [], captionsOn: false, captionStyle: 'karaoke', logo: false, transparent: false,
    bgVideo: false, bgDim: 0.35
  };

  function normalize(c) {
    c = c || {};
    var sc = SCENES[c.scene] ? c.scene : DEFAULT_CFG.scene;
    var d = SCENES[sc].defaults;
    var out = {};
    Object.keys(DEFAULT_CFG).forEach(function (k) { out[k] = c[k] !== undefined && c[k] !== null ? c[k] : DEFAULT_CFG[k]; });
    out.scene = sc;
    out.font = FONTS[out.font] ? out.font : d.font;
    out.anim = ANIMS[out.anim] ? out.anim : d.anim;
    out.palette = (PALETTES[out.palette] || out.palette === 'custom') ? out.palette : d.palette;
    out.vfx = {}; Object.keys(DEFAULT_VFX).forEach(function (k) { out.vfx[k] = (c.vfx && c.vfx[k] !== undefined) ? !!c.vfx[k] : DEFAULT_VFX[k]; });
    out.intensity = clamp(+out.intensity, 0, 1);
    out.bgVideo = !!out.bgVideo; out.bgDim = clamp(+out.bgDim, 0, 0.9);
    out.duration = clamp(+out.duration || 6, 2, 60);
    out.seed = (out.seed | 0) || 7;
    out.title = String(out.title === undefined ? '' : out.title);
    out.subtitle = String(out.subtitle || ''); out.tagline = String(out.tagline || ''); out.contact = String(out.contact || '');
    out._n = true;
    return out;
  }

  function resolvePalette(cfg) {
    if (cfg.palette === 'custom' && cfg.custom) {
      var base = PALETTES.neon, o = {};
      Object.keys(base).forEach(function (k) { o[k] = isHex(cfg.custom[k]) ? '#' + String(cfg.custom[k]).replace('#', '') : base[k]; });
      return o;
    }
    return PALETTES[cfg.palette] || PALETTES.neon;
  }

  function heroTime(cfg) { var sc = SCENES[cfg.scene] || SCENES.neon; return Math.min(sc.hero, (cfg.duration || 6) - 0.05); }

  function render(ctx, cfgIn, t, W, H, opts) {
    opts = opts || {};
    var cfg = cfgIn && cfgIn._n ? cfgIn : normalize(cfgIn);
    var sc = SCENES[cfg.scene];
    var still = !!opts.still;
    if (still) t = opts.t !== undefined ? opts.t : heroTime(cfg);
    var transparent = !!opts.transparent;
    var env = {
      ctx: ctx, W: W, H: H, K: Math.min(W, H) / 1080, t: t, cfg: cfg, sc: sc, still: still,
      pal: resolvePalette(cfg), I: cfg.intensity, fx: cfg.vfx, logo: opts.logo || null
    };

    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, ctx.canvas ? ctx.canvas.width : W, ctx.canvas ? ctx.canvas.height : H);
    ctx.save();
    if (opts.offsetY) ctx.translate(0, -opts.offsetY);

    var sh = shakeOffset(env);
    ctx.save();
    ctx.translate(sh[0], sh[1]);
    if (!transparent) {
      if (cfg.bgVideo && opts.bgImage && drawBgMedia(env, opts.bgImage)) { /* imported clip replaces the scene backdrop */ }
      else sc.bg(env);
    }
    drawTexts(env, sc.theme(env));
    if (sc.fg2) sc.fg2(env);
    if (env.fx.particles) drawParticles(env);
    if (env.fx.burst) drawBurst(env);
    if (env.fx.rings) drawRings(env);
    if (env.fx.sweep) drawSweep(env);
    ctx.restore();

    if (sc.over && !transparent) sc.over(env);
    drawCaptions(env);
    if (!transparent) {
      post(env);
      bloom(env, opts);
      if (!still) { // fade in from / out to black
        var fi = 1 - prog(t, 0, 0.3), fo = prog(t, cfg.duration - 0.4, 0.4), a = Math.max(fi, fo);
        if (a > 0.002) { ctx.fillStyle = 'rgba(0,0,0,' + a.toFixed(3) + ')'; ctx.fillRect(0, 0, W, H); }
      }
    }
    ctx.restore();
  }

  // sound-effect cues, derived from the same timeline as the visuals so audio always lines up
  function cues(cfgIn) {
    var cfg = cfgIn && cfgIn._n ? cfgIn : normalize(cfgIn);
    var sc = SCENES[cfg.scene], tm = sc.times, hit = sc.hit, D = cfg.duration, out = [];
    var pack = cfg.sfxPack, add = function (t, type, o) { if (t >= 0 && t < D) { var c = { t: +t.toFixed(3), type: type }; for (var k in o) c[k] = o[k]; out.push(c); } };
    if (pack === 'none') return out;
    var titleLen = Array.from((cfg.title || '').trim()).length;
    if (pack === 'cinematic') {
      add(0, 'pad', { dur: D, gain: 0.22 });
      var rs = Math.max(0, hit - 1.1);
      add(rs, 'riser', { dur: hit - rs, gain: 0.7 });
      add(hit, 'impact', { gain: 1.0 });
      add(hit + 0.12, 'sparkle', { gain: 0.6 });
      add(tm.sub, 'ding', { freq: 659, gain: 0.45 });
      add(D - 0.9, 'whoosh', { dur: 0.7, up: false, gain: 0.5 });
    } else if (pack === 'tech') {
      add(Math.max(0, tm.title - 0.25), 'whoosh', { dur: 0.45, up: true, gain: 0.7 });
      add(hit, 'zap', { gain: 0.9 });
      add(hit + 0.35, 'zap', { gain: 0.5 });
      if (cfg.anim === 'type') { var n = Math.min(titleLen, 60), step = Math.max(0.5, titleLen * 0.07) / Math.max(1, titleLen); for (var i = 0; i < n; i++) add(tm.title + i * step, 'tick', { gain: 0.5 }); }
      add(tm.sub, 'ding', { freq: 988, gain: 0.4 });
      add(tm.tag, 'tick', { gain: 0.6 });
      add(D - 0.7, 'whoosh', { dur: 0.5, up: false, gain: 0.45 });
    } else if (pack === 'pop') {
      add(Math.max(0, tm.title - 0.15), 'whoosh', { dur: 0.3, up: true, gain: 0.55 });
      add(hit, 'pop', { gain: 1.0 });
      add(hit + 0.05, 'sparkle', { gain: 0.7 });
      add(tm.sub, 'pop', { gain: 0.6 });
      add(tm.tag, 'ding', { freq: 784, gain: 0.4 });
    }
    return out;
  }

  function info() {
    return {
      scenes: Object.keys(SCENES).map(function (id) { var s = SCENES[id]; return { id: id, name: s.name, desc: s.desc, defaults: s.defaults, hit: s.hit, hero: s.hero, times: s.times }; }),
      palettes: Object.keys(PALETTES).map(function (id) { return { id: id, name: PALETTES[id].name, colors: PALETTES[id] }; }),
      fonts: Object.keys(FONTS).map(function (id) { return { id: id, name: FONTS[id].name }; }),
      anims: Object.keys(ANIMS).map(function (id) { return { id: id, name: ANIMS[id] }; })
    };
  }

  return {
    render: render, normalize: normalize, cues: cues, info: info, heroTime: heroTime,
    quickCaptions: quickCaptions, groupCaptions: groupCaptions, PALETTES: PALETTES, FONTS: FONTS,
    resolvePalette: resolvePalette, isHex: isHex, defaultVfx: function () { return JSON.parse(JSON.stringify(DEFAULT_VFX)); }, version: '1.0.0'
  };
});
