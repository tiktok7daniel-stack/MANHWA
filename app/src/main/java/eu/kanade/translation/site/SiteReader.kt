package eu.kanade.translation.site

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource

/**
 * MANHWA: MangaTek draws its own AI Arabic translation over its pages in its own reader, the extension only fetches
 * the bare images. Its chapters open in [SiteReaderActivity]: the site's reader itself, inside the app, one chapter
 * after the other. Only this source; every other one keeps the app's reader.
 */
object SiteReader {

    private const val SITE = "mangatek"

    fun handles(name: String, baseUrl: String?): Boolean =
        name.contains(SITE, ignoreCase = true) || baseUrl?.contains(SITE, ignoreCase = true) == true

    /** Only an installed source: a stub has no address to open. */
    fun handles(source: Source?): Boolean {
        val http = source as? HttpSource ?: return false
        return handles(http.name, runCatching { http.baseUrl }.getOrNull())
    }

    /**
     * Whether [source]'s chapters open in the site's reader: the reader's own choice for that source ([on] or
     * [off], source ids) first, else MangaTek only.
     */
    fun opensInSite(source: Source?, on: Set<String>, off: Set<String>): Boolean {
        if (source !is HttpSource) return false
        val id = source.id.toString()
        return choose(id in on, id in off, handles(source))
    }

    /** The reader's choice wins over the default; turned off wins over turned on. */
    fun choose(on: Boolean, off: Boolean, byDefault: Boolean): Boolean = when {
        off -> false
        on -> true
        else -> byDefault
    }

    /** The sets after the reader switched [sourceId] to [site] (only what differs from the default is kept). */
    fun switched(sourceId: Long, site: Boolean, byDefault: Boolean, on: Set<String>, off: Set<String>): Pair<Set<String>, Set<String>> {
        val id = sourceId.toString()
        val newOn = if (site && !byDefault) on + id else on - id
        val newOff = if (!site && byDefault) off + id else off - id
        return newOn to newOff
    }

    /** A page the reader may open: the site itself (its Cloudflare check included), nothing else. */
    fun allowed(host: String?, baseUrl: String): Boolean {
        val site = baseUrl.substringAfter("://").substringBefore('/').removePrefix("www.").lowercase()
        val h = host?.removePrefix("www.")?.lowercase() ?: return false
        return h == site || h.endsWith(".$site") || h == "challenges.cloudflare.com"
    }

    /**
     * Runs in the site's page and in each chapter put under it. When the end of the chapter comes near, the next one
     * is put under it in a frame (same site) so the reading goes on without leaving; past [DEPTH] frames, or when the
     * site refuses being framed, reaching the end opens the next chapter in the page. It tells the app which chapter
     * is being read and which one was finished. Comments and the footer are hidden.
     */
    val SCRIPT = """
(function () {
  if (window.__manhwaSite) return;
  window.__manhwaSite = true;
  var bridge = window.ManhwaSite || (window.top && window.top.ManhwaSite);
  if (!bridge) return;
  var index = typeof window.__manhwaIndex === 'number' ? window.__manhwaIndex : 0;
  var depth = typeof window.__manhwaDepth === 'number' ? window.__manhwaDepth : 0;
  var style = document.createElement('style');
  style.textContent = '.manhwa-next{display:block;width:100%;border:0;margin:0;background:#050507}' +
    '.manhwa-gap{padding:22px 0;text-align:center;font:800 15px sans-serif;color:#ff4d6d;background:#050507}';
  (document.head || document.documentElement).appendChild(style);
  // The app's look: only the pages and the translation on them, no bar, menu, footer or comments of the site
  var plain = document.createElement('style');
  plain.textContent = 'header,nav,footer,aside,[id*="comment" i],[class*="comment" i],[id*="disqus" i],.manhwa-hidden' +
    '{display:none!important}html,body{background:#050507!important;margin:0!important;padding:0!important}' +
    'img{max-width:100%!important;height:auto}';
  function hideBars() {
    if (!plain.isConnected) return;
    var all = document.body ? document.body.querySelectorAll('*') : [];
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el.classList.contains('manhwa-next') || el.classList.contains('manhwa-gap')) continue;
      var pos = getComputedStyle(el).position;
      if ((pos === 'fixed' || pos === 'sticky') && !el.querySelector('img,canvas')) el.classList.add('manhwa-hidden');
    }
  }
  function setPlain(on) {
    if (on) { (document.head || document.documentElement).appendChild(plain); hideBars(); }
    else if (plain.isConnected) plain.remove();
    var frames = document.querySelectorAll('iframe.manhwa-next');
    for (var i = 0; i < frames.length; i++) {
      try { frames[i].contentWindow.__manhwaSetPlain(on); } catch (err) {}
    }
  }
  window.__manhwaSetPlain = setPlain;
  setPlain(bridge.plain());
  setTimeout(hideBars, 1500);
  setTimeout(hideBars, 5000);
  var gap = null, inPage = false, finished = false, last = 0, pending = 0, reported = -1;
  function root() { return document.scrollingElement || document.documentElement; }
  function scroller(t) {
    if (!t || t === document || t === document.documentElement || t === document.body) return root();
    return t;
  }
  // The chapter on screen is worked out from the top page: the deepest chapter whose frame reached mid-screen
  function locate() {
    var i = window.__manhwaIndex || 0, doc = document, offset = 0, view = window.innerHeight;
    for (;;) {
      var frame = doc.querySelector('iframe.manhwa-next');
      if (!frame) break;
      var top = offset + frame.getBoundingClientRect().top;
      if (top >= view * 0.5) break;
      try { doc = frame.contentWindow.document; } catch (err) { break; }
      offset = top;
      i++;
    }
    if (i !== reported) { reported = i; bridge.now(i); }
  }
  if (depth === 0) window.__manhwaLocate = locate;
  function tell() {
    try { if (window.top.__manhwaLocate) window.top.__manhwaLocate(); } catch (err) {}
  }
  function addNext(s) {
    var next = bridge.urlAfter(index);
    if (!next) return;
    gap = document.createElement('div');
    gap.className = 'manhwa-gap';
    gap.textContent = bridge.titleAfter(index);
    var host = s === root() ? document.body : s;
    host.appendChild(gap);
    if (depth >= ${DEPTH - 1}) { inPage = true; return; }
    var frame = document.createElement('iframe');
    frame.className = 'manhwa-next';
    frame.style.height = window.innerHeight + 'px';
    frame.src = next;
    var ready = false;
    function framed() {
      try {
        var w = frame.contentWindow;
        if (!w.document.body || w.location.href === 'about:blank') return false;
        w.__manhwaIndex = index + 1;
        w.__manhwaDepth = depth + 1;
        w.eval(bridge.script());
        return true;
      } catch (err) { return false; }
    }
    function refused() {
      if (inPage) return;
      frame.remove();
      inPage = true;
      if (finished) bridge.go(index + 1);
    }
    frame.onload = function () {
      if (framed()) ready = true; else refused();
    };
    setTimeout(function () { if (!ready && !framed()) refused(); }, 20000);
    host.appendChild(frame);
  }
  // The chapter's pages are its big pictures, in order; the page read is the last one whose top passed mid-screen
  function pages() {
    var out = [], imgs = document.images, wide = window.innerWidth * 0.5;
    for (var i = 0; i < imgs.length; i++) {
      var r = imgs[i].getBoundingClientRect();
      if (r.width >= wide && r.height >= 100) out.push(imgs[i]);
    }
    return out;
  }
  var lastPage = -1;
  function readPage() {
    var list = pages(), view = window.innerHeight, page = 0;
    for (var i = 0; i < list.length; i++) {
      if (list[i].getBoundingClientRect().top < view * 0.5) page = i; else break;
    }
    if (list.length && page !== lastPage) { lastPage = page; bridge.page(index, page, list.length); }
  }
  function onScroll(e) {
    // At most every 200 ms, and once more after the scrolling stops (the end of a chapter is the last event)
    var now = Date.now();
    if (now - last < 200) {
      if (!pending) pending = setTimeout(function () { pending = 0; onScroll(e); }, 220);
      return;
    }
    last = now;
    var s = scroller(e && e.target);
    var view = s === root() ? window.innerHeight : s.clientHeight;
    if (s.scrollHeight < view * 2) return;
    if (!gap && s.scrollHeight - s.scrollTop - view < view * 3) addNext(s);
    var end = gap ? gap.getBoundingClientRect().top : Infinity;
    tell();
    readPage();
    var bottom = s.scrollHeight - s.scrollTop - view < 8;
    if (!finished && (end < view * 0.9 || bottom)) {
      finished = true;
      bridge.done(index);
      if (inPage) bridge.go(index + 1);
    }
  }
  document.addEventListener('scroll', onScroll, { capture: true, passive: true });
  if (depth === 0) {
    tell();
    // Back to the page the chapter was left at, while its pictures load, until the reader touches the page
    var want = bridge.startPage(index), touched = false, tries = 0;
    if (want > 0) {
      var stop = function () { touched = true; };
      window.addEventListener('touchstart', stop, { passive: true, once: true });
      window.addEventListener('wheel', stop, { passive: true, once: true });
      var timer = setInterval(function () {
        if (touched || ++tries > 40) { clearInterval(timer); return; }
        var list = pages();
        if (list.length > want) list[want].scrollIntoView({ block: 'start' });
      }, 500);
    }
  }
})();
""".trimIndent()

    /** Chapters nested in one page before the next one opens in the page instead (each frame holds a reader). */
    private const val DEPTH = 3
}
