package app.filum.ui

import app.filum.R
import app.filum.tr

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.filum.CameraMode
import java.net.URI

/**
 * Камера через WebView.
 * GO2RTC: своя страница с плеером go2rtc (video-stream.js), WebRTC с откатом на MSE.
 * WEBRTC: K2-серия отдаёт видео по WebRTC — SDP-offer отправляется POST-запросом
 * на /call/webrtc_local (base64 от JSON). Страница грузится с базовым адресом
 * самого принтера, поэтому fetch остаётся same-origin и CORS не мешает.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CameraView(mode: CameraMode, url: String, reloadKey: Int, modifier: Modifier = Modifier) {
    val key = "$mode|$url|$reloadKey"
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
            }
        },
        update = { web ->
            if (web.tag != key) {
                web.tag = key
                load(web, mode, url)
            }
        },
        onRelease = { web ->
            web.loadUrl("about:blank")
            web.destroy()
        },
    )
}

private fun load(web: WebView, mode: CameraMode, url: String) {
    when (mode) {
        CameraMode.OFF, CameraMode.AUTO -> web.loadUrl("about:blank")
        CameraMode.PAGE -> web.loadUrl(url)
        CameraMode.GO2RTC -> {
            val (base, src, streamMode) = go2rtcParams(url)
            web.loadDataWithBaseURL(base, go2rtcHtml(src, streamMode), "text/html", "utf-8", null)
        }
        CameraMode.MJPEG -> web.loadDataWithBaseURL(baseOf(url), mjpegHtml(url), "text/html", "utf-8", null)
        CameraMode.WEBRTC -> web.loadDataWithBaseURL(baseOf(url), webrtcHtml(url), "text/html", "utf-8", null)
    }
}

private fun baseOf(url: String): String = runCatching {
    val u = URI(url)
    "${u.scheme}://${u.authority}/"
}.getOrDefault(url)

/** Из адреса вида http://host:1984/stream.html?src=camera&mode=webrtc,mse достаём базу, поток и режим. */
private fun go2rtcParams(url: String): Triple<String, String, String> = runCatching {
    val u = URI(url)
    val q = (u.rawQuery ?: "").split('&').mapNotNull {
        val kv = it.split('=', limit = 2)
        if (kv.size == 2) kv[0] to java.net.URLDecoder.decode(kv[1], "UTF-8") else null
    }.toMap()
    val dir = (u.rawPath ?: "/").substringBeforeLast('/') + "/"
    Triple("${u.scheme}://${u.authority}$dir", q["src"] ?: "", q["mode"] ?: "webrtc,mse")
}.getOrDefault(Triple(url, "", "webrtc,mse"))

private fun jsString(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

// В WebView высота CSS-вьюпорта бывает нулевой (100% и 100vh дают 0),
// поэтому контейнер #b растягивается из JS в пикселях по innerWidth/innerHeight.
private const val CSS = """
html,body{margin:0;background:#000;overflow:hidden}
#b{position:fixed;left:0;top:0;width:100px;height:100px}
video,img,video-stream{width:100%;height:100%;object-fit:contain;display:block}
#s{position:absolute;top:8px;left:8px;right:8px;color:#bbb;font:13px sans-serif}
.info{display:none!important}
"""

private const val FIT_JS = """
function fit(){const b=document.getElementById('b');b.style.width=innerWidth+'px';b.style.height=innerHeight+'px';}
fit();addEventListener('resize',fit);setInterval(fit,1000);
"""

private fun go2rtcHtml(src: String, mode: String) = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>$CSS</style></head><body>
<div id="b"></div>
<script>$FIT_JS</script>
<script type="module" src="video-stream.js"></script>
<script type="module">
let src=${jsString(src)};
// поток не задан — берём первый из настроенных в go2rtc
if(!src){try{src=Object.keys(await (await fetch('api/streams')).json())[0]||'';}catch(e){}}
const el=document.createElement('video-stream');
el.mode=${jsString(mode)};
el.src=new URL('api/ws?src='+encodeURIComponent(src), location.href);
document.getElementById('b').appendChild(el);
</script></body></html>
"""

private fun mjpegHtml(url: String) = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>$CSS</style></head><body>
<div id="b"><img id="i"><div id="s">${tr(R.string.camera_connecting)}</div></div>
<script>$FIT_JS</script>
<script>
const URL_=${jsString(url)};
const i=document.getElementById('i'), s=document.getElementById('s');
function go(){ i.src=URL_+(URL_.includes('?')?'&':'?')+'_t='+Date.now(); }
i.onload=()=>{s.style.display='none';i.style.visibility='visible'};
i.onerror=()=>{i.style.visibility='hidden';s.style.display='block';s.textContent=${jsString(tr(R.string.camera_unavailable_retry))};setTimeout(go,3000)};
go();
</script></body></html>
"""

private fun webrtcHtml(url: String) = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>$CSS</style></head><body>
<div id="b"><video id="v" autoplay playsinline muted></video><div id="s">${tr(R.string.camera_connecting)}</div></div>
<script>$FIT_JS</script>
<script>
const URL_=${jsString(url)};
const s=document.getElementById('s'), v=document.getElementById('v');
let pc=null, retry=null;
function fail(msg){
  s.style.display='block'; s.textContent=msg;
  if(pc){try{pc.close()}catch(e){} pc=null}
  clearTimeout(retry); retry=setTimeout(start,4000);
}
async function start(){
  s.style.display='block'; s.textContent=${jsString(tr(R.string.camera_connecting))};
  try{
    pc=new RTCPeerConnection({iceServers:[]});
    pc.addTransceiver('video',{direction:'recvonly'});
    pc.ontrack=e=>{ v.srcObject=e.streams[0]||new MediaStream([e.track]); s.style.display='none'; };
    pc.onconnectionstatechange=()=>{
      if(pc && (pc.connectionState==='failed'||pc.connectionState==='disconnected')) fail(${jsString(tr(R.string.camera_stream_lost))});
    };
    await pc.setLocalDescription(await pc.createOffer());
    await new Promise(r=>{
      if(pc.iceGatheringState==='complete') return r();
      pc.onicegatheringstatechange=()=>{ if(pc.iceGatheringState==='complete') r(); };
      setTimeout(r,2000);
    });
    const body=btoa(JSON.stringify({type:'offer',sdp:pc.localDescription.sdp}));
    const resp=await fetch(URL_,{method:'POST',headers:{'Content-Type':'plain/text'},body:body});
    if(!resp.ok) throw new Error('HTTP '+resp.status);
    const txt=(await resp.text()).trim();
    let ans; try{ ans=JSON.parse(atob(txt)); }catch(e){ ans=JSON.parse(txt); }
    await pc.setRemoteDescription({type:'answer',sdp:ans.sdp});
  }catch(e){ fail(${jsString(tr(R.string.camera_unavailable))}+': '+e.message+'. '+${jsString(tr(R.string.camera_retry))}); }
}
start();
</script></body></html>
"""
