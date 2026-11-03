// @plugin      pa-per-object
// @name        PA per object
// @version     1.0
// @author      Filum
// @description Pressure advance test: objects named PA_0.04, PA_0.05… each get their own SET_PRESSURE_ADVANCE. Edits the sliced G-code before it goes to the printer.
// @grant       gcode

// Hook gcode.file: the whole G-code as text, plus info about the file. Return the new text.
filum.on('gcode.file', (text, info) => {
  let count = 0
  const out = text.split('\n').map(line => {
    // OrcaSlicer labels objects: EXCLUDE_OBJECT_START NAME=PA_0.05.stl_id_1_copy_0
    const m = /^EXCLUDE_OBJECT_START NAME=PA_(\d+\.\d+)/.exec(line)
    if (!m) return line
    count++
    return line + '\nSET_PRESSURE_ADVANCE ADVANCE=' + m[1] + ' ; pa-per-object'
  })
  if (count === 0) return undefined   // not a PA test: leave the file as it is
  filum.log(`${info.file}: PA set for ${count} object starts`)
  return out.join('\n')
})
