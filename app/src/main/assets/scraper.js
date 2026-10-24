/*
 * XTract thread collector. Injected into an x.com status page by ThreadScraper.java.
 *
 * X virtualises its timeline (posts that scroll off screen are removed from the DOM),
 * so posts are harvested on every scroll step and accumulated in a map keyed by
 * their permalink. Inline "Show more replies" / "Show probable spam" style buttons
 * are clicked as they appear. Results are reported through the XTractBridge
 * JavaScript interface.
 */
(function (token) {
  'use strict';
  var bridge = window.XTractBridge;
  if (!bridge) return;
  if (window.__xtractActive) window.__xtractStop = true; // a previous run is still going
  window.__xtractActive = token;
  window.__xtractStop = false;

  var STEP_DELAY_MS = 900;
  var MAX_STEPS = 2500;
  var IDLE_STEPS_AT_BOTTOM = 6;
  var EXPAND_RE = /^(show (more|additional|probable|hidden)\b|show replies$|show spam$|show$)/i;

  var seen = {};
  var order = 0;
  var expanded = 0;

  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  function stopped() { return window.__xtractStop || window.__xtractActive !== token; }

  // innerText drops emoji (X renders them as <img alt="..">), so walk the tree.
  function textOf(el) {
    if (!el) return '';
    var out = '';
    (function walk(node) {
      for (var c = node.firstChild; c; c = c.nextSibling) {
        if (c.nodeType === 3) out += c.nodeValue;
        else if (c.nodeType === 1) {
          var tag = c.tagName;
          if (tag === 'IMG') out += c.getAttribute('alt') || '';
          else if (tag === 'BR') out += '\n';
          else walk(c);
        }
      }
    })(el);
    return out.replace(/ /g, ' ').trim();
  }

  function column() {
    return document.querySelector('[data-testid="primaryColumn"]') || document.querySelector('main') || document.body;
  }

  // X appends unrelated recommendations under a "Discover more" heading after the replies.
  function discoverHeading() {
    var hs = column().querySelectorAll('h2, [role="heading"]');
    for (var i = 0; i < hs.length; i++) {
      if (/^discover more/i.test((hs[i].innerText || '').trim())) return hs[i];
    }
    return null;
  }

  function isAfter(ref, el) {
    return ref && (ref.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING);
  }

  function insideQuote(el, article) {
    var q = el.closest('div[role="link"]');
    return q && q !== article && article.contains(q);
  }

  function harvest() {
    var stopAt = discoverHeading();
    var articles = column().querySelectorAll('article[data-testid="tweet"]');
    for (var i = 0; i < articles.length; i++) {
      var a = articles[i];
      if (isAfter(stopAt, a)) continue;

      var url = null, time = '';
      var times = a.querySelectorAll('a[href*="/status/"] time');
      for (var t = 0; t < times.length; t++) {
        if (!insideQuote(times[t], a)) {
          url = times[t].closest('a').href.split('?')[0];
          time = times[t].getAttribute('datetime') || '';
          break;
        }
      }
      if (!url) continue; // ads and placeholders have no permalink

      var text = '', quote = '';
      var texts = a.querySelectorAll('div[data-testid="tweetText"]');
      for (var k = 0; k < texts.length; k++) {
        if (insideQuote(texts[k], a)) { if (!quote) quote = textOf(texts[k]); }
        else if (!text) text = textOf(texts[k]);
      }

      var name = '', handle = '';
      var un = a.querySelector('div[data-testid="User-Name"]');
      if (un) {
        var m = (un.textContent || '').match(/@[A-Za-z0-9_]{1,15}/);
        handle = m ? m[0] : '';
        var nameLink = un.querySelector('a');
        name = textOf(nameLink).split('\n')[0].trim() ||
          textOf(un).split(/\n|@/)[0].trim();
        if (name.charAt(0) === '@') name = '';
      }

      var group = a.querySelector('div[role="group"][aria-label]');
      var rec = {
        url: url,
        name: name,
        handle: handle,
        time: time,
        text: text,
        quote: quote,
        stats: group ? group.getAttribute('aria-label') : '',
        media: a.querySelectorAll('div[data-testid="tweetPhoto"], div[data-testid="videoPlayer"]').length
      };
      var old = seen[url];
      if (!old) { rec.i = order++; seen[url] = rec; }
      else if ((!old.text && rec.text) || (!old.stats && rec.stats)) { rec.i = old.i; seen[url] = rec; }
    }
  }

  function expand() {
    var clicked = 0;
    var btns = column().querySelectorAll('button, div[role="button"]');
    for (var i = 0; i < btns.length; i++) {
      var b = btns[i];
      if (b.closest('article')) continue; // "Show more" inside a post navigates away
      var label = (b.innerText || '').trim().split('\n')[0];
      if (label && label.length < 60 && EXPAND_RE.test(label) && !b.__xtractClicked) {
        b.__xtractClicked = true;
        b.click();
        clicked++;
      }
    }
    expanded += clicked;
    return clicked;
  }

  function results() {
    var list = [];
    for (var k in seen) if (Object.prototype.hasOwnProperty.call(seen, k)) list.push(seen[k]);
    list.sort(function (x, y) { return x.i - y.i; });
    return list;
  }

  function loading() {
    return !!column().querySelector('[role="progressbar"]');
  }

  async function run() {
    window.scrollTo(0, 0);
    await sleep(800);
    var idle = 0;
    for (var step = 0; step < MAX_STEPS && !stopped(); step++) {
      var before = order;
      harvest();
      var clicks = expand();
      bridge.progress(token, order, expanded);

      var heading = discoverHeading();
      if (heading && heading.getBoundingClientRect().top < window.innerHeight) break;

      var atBottom = window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 8;
      if (order === before && clicks === 0 && atBottom && !loading()) {
        if (++idle >= IDLE_STEPS_AT_BOTTOM) break;
      } else {
        idle = 0;
      }
      window.scrollBy(0, Math.round(window.innerHeight * 0.8));
      await sleep(loading() || clicks ? STEP_DELAY_MS * 2 : STEP_DELAY_MS);
    }
    harvest();
    if (window.__xtractActive === token) window.__xtractActive = false;
    window.scrollTo(0, 0);
    bridge.done(token, JSON.stringify(results()));
  }

  run().catch(function (e) {
    if (window.__xtractActive === token) window.__xtractActive = false;
    bridge.error(token, String(e && e.message || e));
  });
})(__TOKEN__);
