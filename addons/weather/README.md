# Weather

A full-screen forecast for one city (current conditions and the next seven days), shown on the
launcher as a module card labelled **Weather** with the caption "Bengaluru forecast". A card's text is
fixed in its XML (spec 1), so the tile itself shows no live temperature: open it for the forecast.

Tested on: Xiaomi MiTV-MOOR2, Google TV / Android 11, firmware RTM5.220609.003.2674.

## Change the city

Edit `CITY`, `LATITUDE` and `LONGITUDE` in `app/build.gradle`, and the caption in
`app/src/main/res/xml/module_card.xml`, then rebuild. A changed caption doesn't re-prompt; a changed
label does.

## Build and install

```bash
cd addons/weather && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease
TV_HOST=<tv ip> tools/venv/bin/python -I tools/b64push.py addons/weather/app/build/outputs/apk/release/app-release.apk /data/local/tmp/w.apk
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py 'pm install -r /data/local/tmp/w.apk; rm -f /data/local/tmp/w.apk'
```

The launcher asks once whether to show the card. Hold OK on Settings, then Module cards, to change it.

## Permissions

- `android.permission.INTERNET`: fetches the forecast from `api.open-meteo.com` (free, no account,
  no key). The request carries only the city's coordinates.

Network and background use: it fetches only while the forecast screen is open, on open and then
hourly. There is no background service, no analytics and no ads. The last answer is kept in the
app's private storage, so the screen can show it offline.
