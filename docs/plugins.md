# Plugins

Filum runs plugins written in JavaScript. A plugin can:

- **change what the app sends**: G-code commands, slicing requests, and the sliced G-code file before it goes to
  the printer;
- **block things**: commands or print starts;
- **react** to print events and printer state;
- **add its own controls** (buttons, switches, sliders, text fields) to the Control tab;
- **send notifications** and call web services.

Plugins run inside the app while it is open (not in the background notification service).

Open the plugin manager with the puzzle icon in the top bar. Install a plugin from a file, from a URL, or from the
bundled examples.

## A minimal plugin

```js
// @plugin      light-on-print
// @name        Light on while printing
// @version     1.0
// @author      you
// @description Turns the chamber light on when a print starts and off 5 minutes after it ends.
// @grant       gcode

filum.on('print.started', () => filum.printer.gcode('SET_PIN PIN=LED VALUE=1'))
filum.on('print.finished', () => filum.after(5 * 60 * 1000, () => filum.printer.gcode('SET_PIN PIN=LED VALUE=0')))
```

## Header

The file must start with `// @key value` lines:

| Key | Required | Meaning |
|---|---|---|
| `@plugin` | yes | id: `a-z`, `0-9`, `.`, `_`, `-`, 2–64 chars. A file with the same id replaces the installed plugin |
| `@name` | | shown in the manager |
| `@version`, `@author` | | shown in the manager |
| `@description` | | may repeat; the lines are joined |
| `@grant` | | permission; repeat or comma-separate. See below |

### Permissions

| Grant | Allows |
|---|---|
| *(none)* | read the printer state, show controls on the Control tab, toasts, own storage, log |
| `gcode` | `filum.printer.gcode()` and the hooks that change or block data: `gcode.send`, `print.start`, `slice`, `gcode.file` |
| `http` | `filum.http.get/post` |
| `notify` | `filum.notify()` |
| `device` | `filum.device.register()`: a device type that can be selected like a printer |

The manager shows the permissions before installing. A call without its permission throws an error.

## Language and sandbox

- **Engine.** JavaScript runs on Mozilla Rhino (interpreted). It supports ES5 plus ES6 arrow functions,
  `let`/`const`, template strings and `for…of`. Classes and modules are not available.
- **Sandbox.** Each plugin has its own global scope. There is no access to Java classes, files or Android APIs:
  only what `filum.*` offers.
- **Threading.** All plugins run on one background thread, so they never run in parallel and need no locking.
  The API calls are synchronous.
- **Time limits.** Loading may take 5 s and a hook or event handler 2 s; `gcode.file` gets 60 s. A plugin that runs
  longer is stopped.
- **Errors.** An error or timeout in a hook is logged, and the data passes on unchanged. An error while loading
  disables the plugin. Errors are shown in the manager (Log) and in the console (`#` lines).

## API

### Events — `filum.on(event, handler)`

| Event | Handler gets | Return |
|---|---|---|
| `load` | — | |
| `unload` | — (plugin disabled, deleted or updated) | |
| `state` | state object (below), on every update | |
| `print.started` | file name | |
| `print.finished`, `print.cancelled` | file name | |
| `print.paused`, `print.resumed` | file name | |
| `print.error` | printer message | |
| **`gcode.send`** 🔒 | G-code script the app is about to send (console, buttons, jog, temperatures…) | a string to send instead, `false` to block, nothing to keep it |
| **`print.start`** 🔒 | path of the file about to start (Files tab) | `false` to block |
| **`slice`** 🔒 | request to the slicer server: `{model_id, printer, process, filaments, plate, arrange, cfs, printer_host, …}` | the changed object, or nothing |
| **`gcode.file`** 🔒 | `(text, info)`: the whole sliced G-code and `{file, plate, job, model, process, start}` | the new text, or nothing |

🔒 needs `@grant gcode`.

- **Order.** Hooks of several plugins run in installation order; each gets the result of the previous one.
- **How `gcode.file` works.** Without this hook, the slicer server uploads the G-code to the printer itself. When a
  plugin has the hook, the app downloads the G-code instead, passes it through the plugins and uploads the result
  to Moonraker.
- **Plugins' own commands.** `filum.printer.gcode()` from a plugin does not go through `gcode.send`, so a plugin
  can't loop on itself.

### Printer

| Call | Description |
|---|---|
| `filum.printer.state()` | state object |
| `filum.printer.query(['extruder', 'heater_bed'])` | raw Klipper objects (`printer.objects.query`), or `null` without a connection (waits up to 5 s) |
| `filum.printer.gcode(script)` 🔒 | send G-code; several commands separated by `\n` |

The state object has these fields:
- `connected`, `klippyState`;
- `printState` (`standby` / `printing` / `paused` / `complete` / `cancelled` / `error`), `printMessage`,
  `fileName`;
- `progress` (%), `layer`, `totalLayers`, `printTime`, `timeLeft` (s);
- `nozzleTemp`, `nozzleTarget`, `bedTemp`, `bedTarget`, `chamberTemp`;
- `nozzleDiameter`, `zOffset`, `speedPct`, `flowPct`, `light`, `hostname`;
- `device`: id of the driver plugin when a device such as a CNC is selected, otherwise `null`.

### Interface

```js
const id = filum.ui.section('Title', [
  { type: 'text',   text: 'Any text' },
  { type: 'button', label: 'Home', onClick: () => filum.printer.gcode('G28') },
  { type: 'switch', label: 'Enabled', value: true, onChange: on => … },
  { type: 'slider', label: 'Fan %', min: 0, max: 100, step: 5, value: 50, onChange: v => … },
  { type: 'input',  label: 'URL', value: '', button: 'Save', onSubmit: text => … },
])
filum.ui.update(id, items[, title])   // replace the items (e.g. to refresh text or a switch)
filum.ui.remove(id)
filum.ui.toast('Done')
```

- **Where sections appear.** On the Control tab. Consecutive buttons are laid out in one row.
- **When handlers run.** A slider reports its value when it is released.

### Timers

| Call | Description |
|---|---|
| `filum.every(ms, fn)` | repeat, at least every 1000 ms; returns an id |
| `filum.after(ms, fn)` | once; returns an id |
| `filum.cancel(id)` | stop a timer |

Timers stop when the plugin is unloaded.

### Storage, network, notifications, log

| Call | Description |
|---|---|
| `filum.storage.get(key, default)` / `filum.storage.set(key, value)` | values are stored as JSON per plugin; `set(key, undefined)` removes. Deleting the plugin deletes its data |
| `filum.http.get(url)` 🌐 | returns `{status, body}`; 5 s connect / 15 s read timeouts; network errors throw an `Error` you can catch, body up to 1 MB |
| `filum.http.post(url, body[, contentType])` 🌐 | an object body is sent as JSON |
| `filum.notify(title, text)` 🔔 | |
| `filum.log(…)`, `console.log/info/warn/error(…)` | to the plugin log and the app console |
| `filum.id`, `filum.version` | plugin id, API version (`1`) |

🌐 needs `@grant http`, 🔔 needs `@grant notify`.

## Devices: drivers for machines other than Klipper printers

A plugin can register a **device type**. The user then adds it in the printer list like a printer: Add printer →
Device type → the plugin's type, plus an address. When it is selected, the app talks to the plugin instead of
Moonraker:

| App | Driver method |
|---|---|
| Home tab: status, progress, Pause / Resume / Stop | `filum.device.update(state)`; `pause()`, `resume()`, `stop()` |
| Control tab: jog, Home | `jog(axis, distance, feed)`, `home(axes)` |
| Files tab: list and start | `files()` → `[{path, size, modified}]`, `start(path)` |
| Console and `filum.printer.gcode` | `command(script)` |

```js
// @grant http
// @grant device
filum.device.register({
  name: 'My machine',              // shown as the device type
  addressHint: '192.168.0.50:8080',
  connect(address) { … },          // selected: start polling, call filum.device.update()
  disconnect() { … },              // another printer selected
  command(script) { … },
  jog(axis, distance, feed) { … },
  home(axes) { … },                // '' — all axes
  pause() { … }, resume() { … }, stop() { … },
  files() { return [{ path: 'part.nc', size: 1234, modified: 1730000000 }] },
  start(path) { … },
})

filum.device.update({
  connected: true,
  state: 'printing',               // standby / printing / paused / complete / cancelled / error
  status: 'Running',               // optional: your own status text
  message: '…',                    // shown when not connected
  file: 'part.nc', progress: 41, printTime: 62, timeLeft: 90,
  position: [10, 20, -1.5],
  camera: 'http://…/stream.mjpg',  // optional MJPEG stream for the Home tab
})
filum.device.log('!', 'text')      // a line in the app console
```

- **Grants.** Registering a device needs `@grant device`.
- **What is hidden.** For a device the app hides what only Klipper printers have: heaters, fans, extruder,
  speed/flow, CFS, Z-offset, the slicer and timelapse tabs, and the background notification service.
- **Other plugins.** Sections of plugins with the `gcode` grant (printer plugins) are hidden too. The printer state
  that plugins get has `device` set to the driver's plugin id.
- **When there is no driver.** If the driver plugin is disabled or missing, the device shows as not connected and
  reconnects as soon as the plugin loads.

## Examples

These are bundled in the app (manager → Examples) and live in
[`android/app/src/main/assets/plugins`](../android/app/src/main/assets/plugins):

| Plugin | Shows |
|---|---|
| `pa-per-object.js` | `gcode.file`: a pressure advance test, where every object named `PA_0.05…` gets its own `SET_PRESSURE_ADVANCE` |
| `temperature-guard.js` | `gcode.send`: blocks too-high temperatures; a slider; storage |
| `quick-actions.js` | buttons, slider, a text line refreshed by a timer from `printer.state()` |
| `print-webhook.js` | print events, `http.post`, `notify`, an input field |
| `cnc-pi.js` | a device driver: a GRBL CNC through [cncd](https://github.com/fu1se/cnc-pi) selected like a printer — status, jog, files, jobs, plus unlock / reset / zeroing on the Control tab |

## Testing

`PluginHostTest` runs the engine on the JVM with a fake printer: `./gradlew testDebugUnitTest`. To try a plugin
quickly, install it from a file and watch its log in the manager or in the console.
