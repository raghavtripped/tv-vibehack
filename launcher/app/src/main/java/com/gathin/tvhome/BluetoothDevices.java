package com.gathin.tvhome;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.SparseArray;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * bluetooth_pill: the paired devices and which of them are connected, for the Bluetooth pill and its menu.
 * Connection state comes from the public profile proxies (audio, headset, input devices such as remotes,
 * hearing aids), held only while Home is started. An app cannot connect or disconnect a device itself on
 * Android 11 (that needs BLUETOOTH_PRIVILEGED), so the menu only shows state and hands off to TV Settings.
 */
final class BluetoothDevices {

    enum Kind { HEADPHONES, SPEAKER, REMOTE, OTHER }

    static final class Device {
        final String address;
        final String name;
        final Kind kind;
        final boolean connected;

        Device(String address, String name, Kind kind, boolean connected) {
            this.address = address;
            this.name = name;
            this.kind = kind;
            this.connected = connected;
        }
    }

    // Profile ids: HID_HOST (4) and HEARING_AID (21) are not public constants on every API level the launcher
    // supports, but getProfileProxy takes them on Android 8 and later.
    private static final int HID_HOST = 4;
    private static final int HEARING_AID = 21;
    private static final int[] PROFILES = {BluetoothProfile.A2DP, BluetoothProfile.HEADSET, HID_HOST, HEARING_AID};

    private final Context context;
    private final BluetoothAdapter adapter;
    private final SparseArray<BluetoothProfile> proxies = new SparseArray<>();
    private Runnable onChange;
    private boolean started;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (onChange != null) onChange.run();
        }
    };

    BluetoothDevices(Context context) {
        this.context = context;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
    }

    /** The TV has Bluetooth and the launcher may read it (Android 12+ asks for BLUETOOTH_CONNECT). */
    boolean available() {
        if (adapter == null) return false;
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    boolean enabled() {
        try {
            return available() && adapter.isEnabled();
        } catch (SecurityException e) {
            return false;
        }
    }

    void start(Runnable onChange) {
        if (started || !available()) return;
        started = true;
        this.onChange = onChange;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        filter.addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED");
        // System broadcasts: exported is fine, nothing but the system sends these.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        for (int profile : PROFILES) {
            try {
                adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int id, BluetoothProfile proxy) {
                        if (!started) {
                            adapter.closeProfileProxy(id, proxy);
                            return;
                        }
                        proxies.put(id, proxy);
                        if (BluetoothDevices.this.onChange != null) BluetoothDevices.this.onChange.run();
                    }

                    @Override
                    public void onServiceDisconnected(int id) {
                        proxies.remove(id);
                    }
                }, profile);
            } catch (RuntimeException ignored) {
                // A profile this TV's stack does not offer.
            }
        }
    }

    void stop() {
        if (!started) return;
        started = false;
        onChange = null;
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        for (int i = 0; i < proxies.size(); i++) {
            try {
                adapter.closeProfileProxy(proxies.keyAt(i), proxies.valueAt(i));
            } catch (RuntimeException ignored) {
            }
        }
        proxies.clear();
    }

    private Set<String> connectedAddresses() {
        Set<String> out = new HashSet<>();
        for (int i = 0; i < proxies.size(); i++) {
            try {
                for (BluetoothDevice d : proxies.valueAt(i).getConnectedDevices()) out.add(d.getAddress());
            } catch (RuntimeException ignored) {
            }
        }
        return out;
    }

    /** Paired devices: connected first, then headphones and speakers, then the rest, each group by name. */
    List<Device> paired() {
        if (!enabled()) return Collections.emptyList();
        Set<String> connected = connectedAddresses();
        List<Device> out = new ArrayList<>();
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                // The name the owner gave it in Settings, where the API has it (Android 11+).
                String alias = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? d.getAlias() : null;
                String name = alias != null ? alias : d.getName();
                if (name == null || name.trim().isEmpty()) name = d.getAddress();
                BluetoothClass cls = d.getBluetoothClass();
                Kind kind = kind(cls == null ? -1 : cls.getMajorDeviceClass(), cls == null ? -1 : cls.getDeviceClass(), name);
                out.add(new Device(d.getAddress(), name, kind, connected.contains(d.getAddress())));
            }
        } catch (SecurityException e) {
            return Collections.emptyList();
        }
        sort(out);
        return out;
    }

    /** Name of a connected pair of headphones or speaker, for the pill; null when none is connected. */
    String connectedAudioName() {
        for (Device d : paired()) {
            if (d.connected && (d.kind == Kind.HEADPHONES || d.kind == Kind.SPEAKER)) return d.name;
        }
        return null;
    }

    static void sort(List<Device> devices) {
        devices.sort((a, b) -> {
            if (a.connected != b.connected) return a.connected ? -1 : 1;
            int ra = rank(a.kind), rb = rank(b.kind);
            if (ra != rb) return Integer.compare(ra, rb);
            return a.name.compareToIgnoreCase(b.name);
        });
    }

    private static int rank(Kind kind) {
        return kind == Kind.HEADPHONES || kind == Kind.SPEAKER ? 0 : kind == Kind.REMOTE ? 1 : 2;
    }

    /** What icon a device gets, from its Bluetooth class, or its name when the class says nothing (LE remotes). */
    static Kind kind(int major, int deviceClass, String name) {
        if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) {
            if (deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES
                    || deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET
                    || deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE) return Kind.HEADPHONES;
            return Kind.SPEAKER;
        }
        if (major == BluetoothClass.Device.Major.PERIPHERAL) return Kind.REMOTE;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.matches(".*\\b(rc|remote)\\b.*")) return Kind.REMOTE;
        if (n.matches(".*\\b(airpods|buds|headphones?|earbuds|headset)\\b.*")) return Kind.HEADPHONES;
        return Kind.OTHER;
    }
}
