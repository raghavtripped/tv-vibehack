package io.cleanyourtv.ambilight;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Turns the ambient light back on after the TV starts, or after the add-on is updated, if it was on before.
 * Opening an activity from the background needs the "display over other apps" permission, which the README
 * grants over ADB.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (AmbiService.running) return;
        if (!context.getSharedPreferences(AmbiService.PREFS, Context.MODE_PRIVATE).getBoolean(AmbiService.PREF_ENABLED, false)) return;
        context.startActivity(new Intent(context, AmbiActivity.class)
                .putExtra("boot", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }
}
