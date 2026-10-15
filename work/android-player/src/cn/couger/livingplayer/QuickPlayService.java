package cn.couger.livingplayer;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.IOException;
import java.util.concurrent.*;
import org.json.*;

/** Short user-initiated job, not a resident controller service. */
public final class QuickPlayService extends Service {
  final ExecutorService jobs = Executors.newSingleThreadExecutor();
  final java.util.Set<String> active =
      java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

  public void onCreate() {
    super.onCreate();
    NotificationManager m = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    m.createNotificationChannel(
        new NotificationChannel("quick_play", "快捷播放连接", NotificationManager.IMPORTANCE_LOW));
    startForeground(
        3,
        new Notification.Builder(this, "quick_play")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("正在连接播放器")
            .setOngoing(true)
            .build());
  }

  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) {
      stopSelf(startId);
      return START_NOT_STICKY;
    }
    int widget = intent.getIntExtra("widget", -1);
    boolean play = "play".equals(intent.getAction());
    String pin = QuickPlay.target(this, widget);
    // Reject stale/deleted widget intents and coalesce repeated taps for the same device.
    boolean valid = widget < 0;
    for (int id : QuickPlay.widgets(this)) if (id == widget) valid = true;
    if (!valid || (!"play".equals(intent.getAction()) && !"status".equals(intent.getAction()))) {
      if (active.isEmpty()) stopSelf(startId);
      return START_NOT_STICKY;
    }
    if (!active.add(pin)) {
      jobs.execute(() -> stopSelf(startId));
      return START_NOT_STICKY;
    }
    jobs.execute(
        () -> {
          try {
            run(pin, play);
          } finally {
            active.remove(pin);
            stopSelf(startId);
          }
        });
    return START_NOT_STICKY;
  }

  void run(String pin, boolean play) {
    boolean connected = false;
    DeviceClient client = null;
    try {
      JSONObject entry = QuickPlay.profile(this, pin);
      if (entry == null) throw new IOException("请先选择播放器");
      client = new DeviceClient(entry.getString("code"));
      JSONObject status;
      try {
        status = client.request("/status", null);
      } catch (DeviceClient.AuthorizationException e) {
        throw e;
      } catch (Exception e) {
        if (!client.rediscover()) throw e;
        status = client.request("/status", null);
      }
      // Never restore a profile forgotten while a request was in flight.
      if (QuickPlay.profile(this, pin) == null) throw new IOException("配对已删除");
      if (!new SavedDevices(QuickPlay.prefs(this)).updateExisting(client))
        throw new IOException("配对已删除");
      connected = true;
      QuickPlay.record(this, pin, status);
      if (!play) return;
      // A stale shortcut is not evidence of what the receiver is currently doing.
      boolean wantPause = QuickPlayState.playing(status.optString("playbackState", "unknown"));
      if (status.optString("playerPackage").isEmpty()) throw new IOException("受控端未配置音乐 APP");
      if (!status.optBoolean("mediaToggleSupported")) throw new IOException("请先更新受控端以支持播放／暂停");
      boolean audioOnly = QuickPlay.audioOnly(this, pin);
      if (audioOnly && !status.optBoolean("audioOnlySupported")) throw new IOException("请更新盒子受控端");
      String screen = status.optString("screen");
      if (!audioOnly && !wantPause && (screen.equals("Asleep") || screen.equals("Dozing")))
        client.request("/action", new JSONObject().put("action", "wake"));
      // Do not retry this POST: a timeout can occur after the receiver already accepted it.
      if (QuickPlay.profile(this, pin) == null) throw new IOException("配对已删除");
      JSONObject command =
          new JSONObject().put("action", "media_toggle").put("audioOnly", audioOnly);
      if (status.optBoolean("mediaIntentSupported"))
        command.put("desiredState", wantPause ? "paused" : "playing");
      long requestId = client.request("/action", command).optLong("mediaRequestId", 0);
      long until = SystemClock.elapsedRealtime() + 20000;
      while (SystemClock.elapsedRealtime() < until) {
        Thread.sleep(600);
        if (QuickPlay.profile(this, pin) == null) throw new IOException("配对已删除");
        status = client.request("/status", null);
        QuickPlay.record(this, pin, status);
        int result =
            QuickPlayState.completed(
                requestId,
                status.optLong("mediaRequestId", 0),
                status.optBoolean("playbackBusy"),
                status.optString("playbackRequest"));
        if (result > 0) return;
        if (result < 0) throw new IOException("音乐 APP 未确认本次操作");
      }
      throw new IOException("播放确认超时");
    } catch (Exception e) {
      boolean unreachable =
          e instanceof java.net.SocketException
              || e instanceof java.net.SocketTimeoutException
              || e instanceof java.net.UnknownHostException
              || e instanceof javax.net.ssl.SSLException
              || e instanceof java.io.EOFException
              || e instanceof DeviceClient.AuthorizationException;
      if ((!connected || unreachable) && !pin.isEmpty()) QuickPlay.record(this, pin, false);
      if (play) QuickPlay.failure(this);
    } finally {
      if (client != null) client.cancelPending();
    }
  }

  public void onDestroy() {
    jobs.shutdown();
    super.onDestroy();
  }

  public IBinder onBind(Intent intent) {
    return null;
  }
}
