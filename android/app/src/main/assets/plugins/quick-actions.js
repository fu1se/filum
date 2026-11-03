// @plugin      quick-actions
// @name        Quick actions
// @version     1.0
// @author      Filum
// @description A section on the Control tab with preheat buttons, part cooling fan and live temperatures.
// @grant       gcode

const status = () => {
  const s = filum.printer.state()
  return `Nozzle ${Math.round(s.nozzleTemp || 0)}° / ${Math.round(s.nozzleTarget || 0)}°  ·  Bed ${Math.round(s.bedTemp || 0)}° / ${Math.round(s.bedTarget || 0)}°`
}

const items = () => [
  { type: 'text', text: status() },
  { type: 'button', label: 'PLA 210/60', onClick: () => filum.printer.gcode('M104 S210\nM140 S60') },
  { type: 'button', label: 'PETG 240/70', onClick: () => filum.printer.gcode('M104 S240\nM140 S70') },
  { type: 'button', label: 'Cool down', onClick: () => filum.printer.gcode('M104 S0\nM140 S0') },
  { type: 'slider', label: 'Part fan %', min: 0, max: 100, step: 10, value: 0,
    onChange: v => filum.printer.gcode(v > 0 ? 'M106 P0 S' + Math.round(v * 2.55) : 'M107 P0') },
]

const section = filum.ui.section('Quick actions', items())
// refresh the temperature line every 3 s
filum.every(3000, () => filum.ui.update(section, items()))
