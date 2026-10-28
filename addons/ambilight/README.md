# Ambient light

Makes a cheap Bluetooth LED strip behind the TV follow what's on screen, like Philips Ambilight, with the music
riding on top. About 20 times a second it takes one colour from the picture (edges weighted, black bars
skipped), shows it at full brightness, flashes it towards white on each bass beat of what the TV is playing,
and sends it to the strip over Bluetooth LE. Open it to switch it on (opening it again keeps it on); the
separate "Ambient light off" entry switches it off. It comes back on by itself, if it was on, after a restart
(about 10 s after the TV finishes starting), after an update, and when the TV wakes from standby.

It drives **LEDDMX** controllers: strips sold with the "LED LAMP" app, which show up over Bluetooth as
`LEDDMX-00-…`, `LEDDMX-01-…` or `LEDDMX-03-…` (many USB "neon" strips on Amazon India are these). They are
single-zone, so the whole strip shows one colour. On connect it sets the controller's own brightness to 100%.

Tested on a Xiaomi MiTV-MOOR2 (Google TV / Android 11, MediaTek m7632, 4K panel) with an LEDDMX-03 strip. Checked with
photos of the wall: standby and back, power off and on with the remote, a full shutdown and start, and reboots;
the strip comes back on by itself each time.

## Where the picture comes from

1. **The TV's own colour taking**, on Xiaomi MediaTek TVs that have it (the MiTV-MOOR2 does). Xiaomi's TvService
   system service can ask the picture engine for a pixel report of a screen area: the average colour of the
   final output, video and protected apps (Hotstar, Netflix) included. The add-on calls it directly (binder
   `mitv.internal.ITvService`, transaction 87 `getScreenColors`, after 89 `supportColorTaking`). Measured with
   known colours on screen: channels are 6-bit (0..63), and over a large area the blue sum overflows (blue 48 on
   the whole 4K panel reads as 15), so the screen is read as a 3×2 grid, one area about every 75 ms (the whole
   grid about twice a second; each read costs the service about 60 ms, so it stays free the rest of the time),
   and the **dominant** colour is taken: similar areas are grouped, vivid ones count a bit more, black ones
   don't vote. Nothing runs while the screen is off. No root, no ADB at run time.
2. **A USB webcam pointed at the screen**, on TVs without colour taking. Tell the add-on where the screen sits in
   its view (fractions of the camera image): `--es crop 0.15,0.2,0.85,0.8`.
3. **Screen capture** otherwise. On many TV chips video plays on a hardware plane that never reaches screen
   capture (even from the ADB shell), so this sees the home screen and menus but black where a film is.
4. When the picture is black, or frozen behind an app's controls while sound plays (screen capture only), the
   strip follows the **music**: hue from the last picture, drifting faster the louder it gets, jumping on beats,
   leaning warm or cool with how bass-heavy the sound is, at high brightness. Sound the add-on can't hear (below)
   makes the last colour **breathe**; black and silent fades to a dim glow; a short black keeps the last colour.

Before it goes to the strip, each colour is corrected for the LEDs (`out = 255 · gain · (in/255)^gamma` per
channel, default gains 1, 1, 0.92 and gamma 2.2): pixel values are gamma-encoded while an LED's light is linear,
so without it orange came out yellow; the channels stay balanced so whites, greys and pastels stay neutral (a
first calibration from camera photos cut blue to 0.6, which the camera's auto white balance hid but which turned
whites yellow and pastel blues green). Override with `--es led r,g,b,gamma`. For calibrating another strip:
`am start -n io.cleanyourtv.ambilight/.CalibrateActivity --es color FF8000` holds a colour on screen, and
`am start -n io.cleanyourtv.ambilight/.AmbiActivity --es test 663300 --ei test_ms 20000 [--ez corrected true]`
holds one on the strip.

Whatever the source, a normal or bright picture is shown at full brightness, dark scenes stay dim in their own
colour, and a black picture turns the strip off. No saturation boost by default (`--ef saturation 1.3` adds one,
0.5 to 2), and vivid areas get only a small nudge when choosing the dominant colour, so white, grey and pastel
scenes stay soft. Each bass beat the TV plays flashes the colour towards white.

On a TV with colour taking the add-on never starts screen capture: a capture display appearing while Prime Video
plays a protected film stops it with an HDCP "HDMI connection" error.

## Limits

- **Without colour taking or a webcam, no film picture** on TVs that play video on a hardware plane (above): the
  strip follows the menus and the music, not the film.
- **Dolby/Atmos sound sent straight to the TV's decoder can't be heard.** Hotstar and Netflix send E-AC3 (and
  Atmos) through a compressed offload output that bypasses the mixer the add-on listens to, so during them the
  strip shows the picture without beat flashes (and, with screen capture only, breathes). YouTube, Spotify and most other apps play PCM and are heard.
  Playing through a Bluetooth speaker turns everything into PCM through the mixer, so then Hotstar is heard too.
- **Standby and power-up.** Many TVs cut USB power to the strip in standby. The add-on switches the strip off
  and lets go of it when the screen goes off, and connects again as soon as it comes back on. Switching on from
  off takes the controller a moment: anything sent right behind "power on" cancels it and the strip stays dark
  (measured: power on, brightness and a colour back to back stay off; with 0.4 s after power on it lights), so
  nothing else is sent for 0.4 s after a power frame. Colour frames alone don't switch it on, so "power on" is
  also sent again 1, 3 and 6 s after connecting and every 15 s after that. A connection attempt that never
  answers is dropped after 35 s, a link that refuses writes for 3 s is replaced, and Bluetooth being switched off
  and on, or Android stopping the app for memory, start it over.
- One Bluetooth client at a time: while the add-on is connected, the strip's phone app cannot connect, and the
  other way round. Close the phone app first.
- A 5 m strip at full brightness can draw more than a TV USB port gives; if it flickers, power it from a
  5 V / 2 A charger (this also frees the TV's USB port for a webcam).

## Install

Build and install it as in [the add-ons README](../README.md), then grant it, once:

```bash
P=io.cleanyourtv.ambilight
tools/tv.py "appops set $P PROJECT_MEDIA allow"         # screen capture without the "Start recording?" prompt
tools/tv.py "appops set $P SYSTEM_ALERT_WINDOW allow"   # lets it start itself after the TV boots
tools/tv.py "pm grant $P android.permission.RECORD_AUDIO" # music mode: read the TV's own sound
tools/tv.py "pm grant $P android.permission.CAMERA"       # camera mode, if you plug in a USB webcam
```

The first start scans for the strip and remembers its address. On Android 11 and older a Bluetooth scan
needs location, so for that first start only:

```bash
tools/tv.py "pm grant $P android.permission.ACCESS_FINE_LOCATION; settings put secure location_mode 3"
tools/tv.py "am start -n $P/.AmbiActivity"              # wait until the strip lights up
tools/tv.py "settings put secure location_mode 0"
```

Or skip the scan by giving it the address yourself:
`am start -n $P/.AmbiActivity --es address AA:BB:CC:DD:EE:FF`.

Other extras: `--ei brightness 60` (percent, kept), `--ez forget true` (scan again for a new strip),
`--es crop l,t,r,b` (camera mode), `--ef saturation 1.0` (less colour boost), `--ez off true` (switch it off). Extras sent while it is on change the setting without switching it off.
Logs: `logcat -s Ambilight`.

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (Android 11 and older), `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` | Find the strip once and talk to it |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` (Android 11 and older) | Android needs them for that one Bluetooth scan; the app never reads a location |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Screen capture and the strip connection run in a foreground service |
| `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS` | Music mode: Visualizer reads the TV's own output mix (audio session 0) as a spectrum. Not the microphone; nothing is recorded or kept |
| `CAMERA`, `FOREGROUND_SERVICE_CAMERA` | Camera mode: read a USB webcam pointed at the screen, shrunk to 64×36 in memory; nothing is saved |
| `RECEIVE_BOOT_COMPLETED`, `SYSTEM_ALERT_WINDOW` | Switch back on after a restart or an update, if it was on |

No `INTERNET` permission: nothing leaves the TV except the colour sent to the strip. Captured and camera frames
are 64×36 pixels, kept in memory only for the moment it takes to average them.

## Credits

The LEDDMX frame format comes from the people who reverse-engineered the vendor app:
[wall-led](https://github.com/heditrigui/wall-led), [LED_UNION](https://github.com/chameleonbr/LED_UNION) and
[LEDDMX-00](https://github.com/user154lt/LEDDMX-00).
