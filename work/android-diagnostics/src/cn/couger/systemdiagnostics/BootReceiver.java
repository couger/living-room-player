package cn.couger.systemdiagnostics;
import android.content.*;
public final class BootReceiver extends BroadcastReceiver {
 public void onReceive(Context c,Intent i){if(Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())&&c.getSharedPreferences("diagnostics",0).getBoolean("enabled",false))c.startForegroundService(new Intent(c,StartupService.class));}
}
