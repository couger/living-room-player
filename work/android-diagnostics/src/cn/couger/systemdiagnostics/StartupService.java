package cn.couger.systemdiagnostics;

import android.app.*;
import android.content.*;
import android.os.*;

public final class StartupService extends Service {
  public void onCreate() {
    super.onCreate();
    NotificationManager m = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    m.createNotificationChannel(
        new NotificationChannel("diagnostics", "系统故障记录启动", NotificationManager.IMPORTANCE_LOW));
    startForeground(
        1,
        new Notification.Builder(this, "diagnostics")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("正在恢复系统故障记录")
            .build());
  }

  public int onStartCommand(Intent i, int flags, int id) {
    new Thread(
            () -> {
              try {
                if (getSharedPreferences("diagnostics", 0).getBoolean("enabled", false))
                  RootRuntime.resume();
              } catch (Exception e) {
                android.util.Log.e("SystemDiagnostics", "Recorder startup failed", e);
              } finally {
                stopSelf(id);
              }
            },
            "diagnostics-start")
        .start();
    return START_NOT_STICKY;
  }

  public IBinder onBind(Intent i) {
    return null;
  }
}
