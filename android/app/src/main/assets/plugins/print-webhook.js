// @plugin      print-webhook
// @name        Print webhook
// @version     1.0
// @author      Filum
// @description Sends a POST with JSON to your URL (Home Assistant, ntfy, n8n…) when a print starts, finishes, is cancelled or fails, and shows a notification.
// @grant       http
// @grant       notify

let url = filum.storage.get('url', '')

function send(event, file) {
  const s = filum.printer.state()
  const body = { event, file, progress: s.progress, printTime: s.printTime, printer: s.hostname }
  filum.notify('Print ' + event, file || '')
  if (!url) return
  try {
    const r = filum.http.post(url, body)
    filum.log(`${event}: HTTP ${r.status}`)
  } catch (e) {
    filum.log('webhook failed: ' + e.message)
  }
}

filum.on('print.started', f => send('started', f))
filum.on('print.finished', f => send('finished', f))
filum.on('print.cancelled', f => send('cancelled', f))
filum.on('print.error', msg => send('error', msg))

filum.ui.section('Print webhook', [
  { type: 'input', label: 'Webhook URL', value: url, button: 'Save',
    onSubmit: v => { url = v.trim(); filum.storage.set('url', url); filum.ui.toast('Saved') } },
])
