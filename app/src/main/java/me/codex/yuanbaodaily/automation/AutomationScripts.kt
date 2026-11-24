package me.codex.yuanbaodaily.automation

/**
 * JS snippets injected into the host's WebView pages.
 *
 * The 福利中心 page is client-rendered: its `__NEXT_DATA__` is an empty shell and
 * the task counters arrive later over XHR, so the only way to read structured
 * completion data is to wrap the page's own `fetch`/`XMLHttpRequest` before its
 * scripts run. [SNIFFER_INSTALL] is idempotent and safe to inject repeatedly.
 */
internal object AutomationScripts {
    val SNIFFER_INSTALL = """
        (function(){
          try {
            if (window.__ybdSniffer) return 'already';
            window.__ybdHooked = [];
            function keep(url, body){
              try{
                if (!url) return;
                if (/\.(png|jpe?g|webp|gif|svg|css|js|woff2?|ttf|mp4|json\.js)/i.test(String(url))) return;
                if (/rumt-zh\.com|beacon|report|log|collect|sentry/i.test(String(url))) return;
                window.__ybdHooked.push({url:String(url).slice(0,300), body:String(body||'').slice(0,8000)});
                if (window.__ybdHooked.length > 20) window.__ybdHooked.shift();
              }catch(e){}
            }
            var of = window.fetch;
            if (of) {
              window.fetch = function(){
                return of.apply(this, arguments).then(function(res){
                  try{ res.clone().text().then(function(t){ keep(res.url, t); }); }catch(e){}
                  return res;
                });
              };
            }
            var ox = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(m, u){ this.__ybdUrl = u; return ox.apply(this, arguments); };
            var os = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.send = function(){
              var xhr = this;
              xhr.addEventListener('load', function(){ try{ keep(xhr.__ybdUrl, xhr.responseText); }catch(e){} });
              return os.apply(this, arguments);
            };
            window.__ybdSniffer = true;
            return 'installed';
          } catch(e) { return 'err:' + (e && e.message ? e.message : e); }
        })()
    """.trimIndent()

    val SNIFFER_READ = """
        (function(){
          try { return encodeURIComponent(JSON.stringify((window.__ybdHooked||[]).slice(-8))); }
          catch(e) { return ''; }
        })()
    """.trimIndent()
}
