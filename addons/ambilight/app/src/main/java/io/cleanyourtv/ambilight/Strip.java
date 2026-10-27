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
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.util.Log;

/**
 * The Bluetooth link to one LEDDMX strip. The first time it scans for a device whose name starts with LEDDMX and
 * remembers its address; after that it connects to the address directly, which needs no scan (and so no location
 * mode). It reconnects on its own and only ever sends the newest frame: a colour that arrives while the previous
 * write is in flight replaces any colour still waiting.
 *
 * <p>Everything runs on the handler passed in, including the GATT callbacks' follow-up work.
 */
@SuppressLint("MissingPermission") // BLUETOOTH and BLUETOOTH_ADMIN are install-time on the API levels this runs on.
final class Strip {

    private static final String TAG = "Ambilight";
    static final String PREF_ADDRESS = "strip_address";
    private static final long SCAN_MS = 20_000, RETRY_MS = 5_000, WRITE_TIMEOUT_MS = 300;

    private final Context context;
    private final Handler handler;
    private final SharedPreferences prefs;
    private final BluetoothAdapter adapter;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic channel;
    private boolean closed, writing, scanning;
    private byte[] pending;
    /** Power and brightness frames go before colours and are never replaced by them. */
    private final java.util.ArrayDeque<byte[]> controls = new java.util.ArrayDeque<>();
    private long writeStarted;

    Strip(Context context, Handler handler, SharedPreferences prefs) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.prefs = prefs;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
    }

    void open() {
        handler.post(this::connect);
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
                stopScan();
                if (gatt != null) {
                    gatt.disconnect();
                    gatt.close();
                    gatt = null;
                }
                channel = null;
            }, 150);
        });
    }

    boolean connected() {
        return channel != null;
    }

    private void connect() {
        if (closed || gatt != null) return;
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
    }

    private void retry() {
        if (!closed) handler.postDelayed(this::connect, RETRY_MS);
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
                    g.discoverServices();
                } else {
                    Log.i(TAG, "disconnected, status " + status);
                    g.close();
                    gatt = null;
                    channel = null;
                    writing = false;
                    controls.clear();
                    retry();
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            handler.post(() -> {
                if (g != gatt) return;
                BluetoothGattService service = g.getService(Leddmx.SERVICE);
                BluetoothGattCharacteristic c = service == null ? null : service.getCharacteristic(Leddmx.CHARACTERISTIC);
                if (c == null) {
                    Log.w(TAG, "no LEDDMX channel on this device");
                    g.disconnect();
                    return;
                }
                c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                channel = c;
                Log.i(TAG, "strip ready");
                controls.addFirst(Leddmx.brightness(100));
                controls.addFirst(Leddmx.power(true));
                flush();
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

    private void flush() {
        if (channel == null || (pending == null && controls.isEmpty())) return;
        if (writing && System.currentTimeMillis() - writeStarted < WRITE_TIMEOUT_MS) return;
        byte[] frame;
        if (!controls.isEmpty()) {
            frame = controls.poll();
        } else {
            frame = pending;
            pending = null;
        }
        write(frame);
    }

    private void write(byte[] frame) {
        channel.setValue(frame);
        writing = gatt != null && gatt.writeCharacteristic(channel);
        writeStarted = System.currentTimeMillis();
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
