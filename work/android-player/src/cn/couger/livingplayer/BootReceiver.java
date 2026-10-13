package cn.couger.livingplayer;
import android.content.*;
public final class BootReceiver extends BroadcastReceiver {
 public void onReceive(Context c,Intent i){if(!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()))return;if(c.getSharedPreferences("player",0).getBoolean("receiver",false))c.startForegroundService(new Intent(c,ResidentService.class));}
}
