// @plugin      temperature-guard
// @name        Temperature guard
// @version     1.0
// @author      Filum
// @description Blocks nozzle temperatures above a limit and bed temperatures above 110 °C sent from the app (console, buttons). The limit is set on the Control tab.
// @grant       gcode

let maxNozzle = filum.storage.get('maxNozzle', 300)

// Hook gcode.send: every command the app sends. Return a new command, false to block, or nothing to keep it.
filum.on('gcode.send', script => {
  const m = /^(M104|M109|M140|M190)\b.*\bS(\d+(\.\d+)?)/m.exec(script)
  if (!m) return
  const t = parseFloat(m[2])
  const limit = (m[1] === 'M140' || m[1] === 'M190') ? 110 : maxNozzle
  if (t > limit) {
    filum.ui.toast(`Blocked ${m[1]} S${t}: limit ${limit} °C`)
    return false
  }
})

filum.ui.section('Temperature guard', [
  { type: 'slider', label: 'Max nozzle °C', min: 200, max: 350, step: 5, value: maxNozzle,
    onChange: v => { maxNozzle = v; filum.storage.set('maxNozzle', v) } },
])
