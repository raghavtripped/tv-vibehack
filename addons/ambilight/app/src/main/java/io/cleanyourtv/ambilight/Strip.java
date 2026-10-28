package io.cleanyourtv.ambilight;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.util.Log;

/**
 * The Bluetooth link to one LEDDMX strip. The first time it scans for a device whose name starts with LEDDMX and
 * remembers its address; after that it connects to the address directly, which needs no scan (and so no location
 * mode). It reconnects on its own and only ever sends the newest frame: a colour that arrives while the previous
 * write is in flight replaces any colour still waiting.
 *
 * <p>While the TV sleeps ({@link #sleep}) it lets go of the strip and stops trying; {@link #wake} starts a fresh
 * connection. A connection attempt that never answers is given up after {@link #CONNECT_TIMEOUT_MS}, and Bluetooth
 * being switched off and on again starts over, so a stale link can't leave the strip dark.
 *
 * <p>The TV powers the strip from USB and cuts that power in standby. Straight after power comes back the strip's
 * Bluetooth answers before its LED controller listens, so a "power on" sent then is lost and colours alone don't
 * switch it on: "power on" is sent again a little after connecting and every {@link #KEEP_ON_MS} after that,
 * each time followed by the last colour.
 *
 * <p>Android allows one operation at a time per link and refuses writes until the previous one is acknowledged.
 * When that acknowledgement is lost (seen after the TV wakes, while its own remote reconnects too), the link
 * still says "connected" but refuses every write: after {@link #STUCK_MS} of refusals it is thrown away and a
 * fresh one is made.
 *
 * <p>Switching on from off takes the controller a moment: anything sent right behind "power on" cancels it and
 * the strip stays dark (measured: power on + brightness + colour back to back stays off; with 0.4 s after power
 * on it lights). So nothing is sent for {@link #POWER_GAP_MS} after a power frame.
 *
 * <p>Everything runs on the handler passed in, including the GATT callbacks' follow-up work.
 */
@SuppressLint("MissingPermission") // BLUETOOTH and BLUETOOTH_ADMIN are install-time on the API levels this runs on.
final class Strip {

    private static final String TAG = "Ambilight";
    static final String PREF_ADDRESS = "strip_address";
    private static final long SCAN_MS = 20_000, RETRY_MS = 5_000, WRITE_TIMEOUT_MS = 300;
    /** Android gives up on a direct connection after about 30 s; past this the stack has lost the attempt. */
    static final long CONNECT_TIMEOUT_MS = 35_000;
    static final long KEEP_ON_MS = 15_000;
    static final long STUCK_MS = 3_000;
    static final long POWER_GAP_MS = 400;
    private static final long[] SETTLE_MS = {1_000, 3_000, 6_000};

    private final Context context;
    private final Handler handler;
    private final SharedPreferences prefs;
    private final BluetoothAdapter adapter;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic channel;
    private boolean closed, writing, scanning, asleep;
    private byte[] pending, lastColor;
    /** Power and brightness frames go before colours and are never replaced by them. */
    private final java.util.ArrayDeque<byte[]> controls = new java.util.ArrayDeque<>();
    private long writeStarted, refusedSince = -1, quietUntil;

    Strip(Context context, Handler handler, SharedPreferences prefs) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.prefs = prefs;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
    }

    private final Runnable reconnect = this::connect;
    private final Runnable keepOn = new Runnable() {
        @Override
        public void run() {
            if (channel == null || asleep) return;
            switchOn();
            handler.postDelayed(this, KEEP_ON_MS);
        }
    };
    private final Runnable settle = this::switchOn;
    private final Runnable flushLater = this::flush;

    /** Queues "power on" and full brightness, then the last colour again. */
    private void switchOn() {
        if (channel == null || asleep) return;
        controls.add(Leddmx.power(true));
        controls.add(Leddmx.brightness(100));
        if (pending == null) pending = lastColor;
        flush();
    }
    private final Runnable connectTimeout = () -> {
        if (gatt == null || channel != null) return;
        Log.i(TAG, "connection attempt timed out");
        drop();
        retry();
    };

    private final BroadcastReceiver bluetooth = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            handler.post(() -> {
                if (state == BluetoothAdapter.STATE_ON) {
                    Log.i(TAG, "bluetooth on");
                    drop();
                    handler.removeCallbacks(reconnect);
                    connect();
                } else if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                    stopScan();
                    drop();
                }
            });
        }
    };

    void open() {
        context.registerReceiver(bluetooth, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), null, handler);
        handler.post(this::connect);
    }

    /** The TV is going to sleep: sends a last frame (if any), then lets go of the strip until {@link #wake}. */
    void sleep(byte[] last) {
        handler.post(() -> {
            asleep = true;
            handler.removeCallbacks(reconnect);
            handler.removeCallbacks(keepOn);
            handler.removeCallbacks(settle);
            stopScan();
            if (last != null && channel != null) write(last);
            handler.postDelayed(() -> {
                if (asleep) drop();
            }, 150);
        });
    }

    /** The TV woke up: connects again straight away, or switches the strip back on if the link survived. */
    void wake() {
        handler.post(() -> {
            asleep = false;
            handler.removeCallbacks(reconnect);
            if (channel != null) {
                ready();
                return;
            }
            drop();
            connect();
        });
    }

    /** Forgets the current link, if any, without scheduling another attempt. */
    private void drop() {
        handler.removeCallbacks(connectTimeout);
        handler.removeCallbacks(keepOn);
        handler.removeCallbacks(settle);
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        channel = null;
        writing = false;
        refusedSince = -1;
        controls.clear();
    }

    /** Queues one frame; it replaces any frame not yet sent. */
    void send(byte[] frame) {
        handler.post(() -> {
            pending = frame;
            flush();
        });
    }

    /** Queues a control frame (power, brightness): sent in order, ahead of colours. */
    void control(byte[] frame) {
        handler.post(() -> {
            controls.add(frame);
            flush();
        });
    }

    /** Sends a last frame (if any), then disconnects for good. */
    void close(byte[] last) {
        handler.post(() -> {
            if (last != null && channel != null) write(last);
            handler.postDelayed(() -> {
                closed = true;
                handler.removeCallbacks(reconnect);
                stopScan();
                drop();
                try {
                    context.unregisterReceiver(bluetooth);
                } catch (IllegalArgumentException ignored) {
                    // Never opened.
                }
            }, 150);
        });
    }

    boolean connected() {
        return channel != null;
    }

    private void connect() {
        if (closed || asleep || gatt != null || scanning) return;
        if (adapter == null || !adapter.isEnabled()) {
            retry();
            return;
        }
        String address = prefs.getString(PREF_ADDRESS, null);
        if (address == null) {
            scan();
            return;
        }
        Log.i(TAG, "connecting to " + address);
        BluetoothDevice device = adapter.getRemoteDevice(address);
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        handler.removeCallbacks(connectTimeout);
        if (gatt == null) {
            retry();
        } else {
            handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
        }
    }

    /** One pending attempt at a time: a later retry replaces an earlier one. */
    private void retry() {
        handler.removeCallbacks(reconnect);
        if (!closed && !asleep) handler.postDelayed(reconnect, RETRY_MS);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult result) {
            String advertised = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            String name = advertised != null ? advertised : result.getDevice().getName();
            if (name == null || !name.startsWith(Leddmx.NAME_PREFIX)) return;
            String address = result.getDevice().getAddress();
            handler.post(() -> {
                if (!scanning) return;
                Log.i(TAG, "found " + name + " at " + address);
                prefs.edit().putString(PREF_ADDRESS, address).apply();
                stopScan();
                connect();
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "scan failed " + errorCode);
        }
    };

    private void scan() {
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            retry();
            return;
        }
        Log.i(TAG, "scanning for " + Leddmx.NAME_PREFIX + "*");
        scanning = true;
        scanner.startScan(scanCallback);
        handler.postDelayed(() -> {
            if (!scanning) return;
            stopScan();
            retry();
        }, SCAN_MS);
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        BluetoothLeScanner scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (IllegalStateException ignored) {
                // Bluetooth went off mid-scan.
            }
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            handler.post(() -> {
                if (g != gatt) return;
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    if (!g.discoverServices()) {
                        drop();
                        retry();
                    }
                } else {
                    Log.i(TAG, "disconnected, status " + status);
                    drop();
                    retry();
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            handler.post(() -> {
                if (g != gatt) return;
                if (channel != null) return; // reported twice
                BluetoothGattService service = g.getService(Leddmx.SERVICE);
                BluetoothGattCharacteristic c = service == null ? null : service.getCharacteristic(Leddmx.CHARACTERISTIC);
                if (c == null) {
                    Log.w(TAG, "no LEDDMX channel on this device");
                    drop();
                    retry();
                    return;
                }
                c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                handler.removeCallbacks(connectTimeout);
                channel = c;
                Log.i(TAG, "strip ready");
                ready();
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            handler.post(() -> {
                writing = false;
                flush();
            });
        }
    };

    /** Switches the strip on now, again while it may still be starting up, then keeps it on. */
    private void ready() {
        switchOn();
        handler.removeCallbacks(settle);
        for (long ms : SETTLE_MS) handler.postDelayed(settle, ms);
        handler.removeCallbacks(keepOn);
        handler.postDelayed(keepOn, KEEP_ON_MS);
    }

    private void flush() {
        if (channel == null || (pending == null && controls.isEmpty())) return;
        long now = System.currentTimeMillis();
        if (writing && now - writeStarted < WRITE_TIMEOUT_MS) return;
        if (now < quietUntil) {
            // The strip is still switching on; anything sent now would cancel it.
            handler.removeCallbacks(flushLater);
            handler.postDelayed(flushLater, quietUntil - now);
            return;
        }
        byte[] frame;
        if (!controls.isEmpty()) {
            frame = controls.poll();
        } else {
            frame = pending;
            pending = null;
            lastColor = frame;
        }
        write(frame);
    }

    private void write(byte[] frame) {
        channel.setValue(frame);
        writing = gatt != null && gatt.writeCharacteristic(channel);
        writeStarted = System.currentTimeMillis();
        if (writing) {
            refusedSince = -1;
            if (frame[2] == 0x04) quietUntil = writeStarted + POWER_GAP_MS;
        } else if (refusedSince < 0) {
            refusedSince = writeStarted;
        } else if (writeStarted - refusedSince > STUCK_MS) {
            Log.i(TAG, "writes refused for " + (writeStarted - refusedSince) + " ms, reconnecting");
            drop();
            handler.removeCallbacks(reconnect);
            connect();
            return;
        }
        if (!writing) {
            // The stack was busy; try this frame again shortly (a colour only unless a newer one replaces it).
            if (frame[2] == 0x07) {
                if (pending == null) pending = frame;
            } else {
                controls.addFirst(frame);
            }
            handler.postDelayed(this::flush, 20);
        }
    }
}
