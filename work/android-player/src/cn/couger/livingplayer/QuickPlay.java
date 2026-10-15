package cn.couger.livingplayer;

import android.app.*;
import android.appwidget.AppWidgetManager;
import android.content.*;
import android.graphics.*;
import android.net.*;
import android.os.*;
import android.widget.*;
import org.json.*;

/** Controller-only entry points. No receiver configuration, video, or idle polling. */
final class QuickPlay {
  static android.content.SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("player", 0);
  }

  static String target(Context c, int widget) {
    return prefs(c).getString(widget >= 0 ? "quickWidget." + widget : "quickTile", "");
  }

  static JSONObject profile(Context c, String pin) {
    return new SavedDevices(prefs(c)).find(pin);
  }

  static boolean audioOnly(Context c, String pin) {
    return prefs(c).getBoolean("musicAudioOnly." + pin, false);
  }

  static int res(Context c, String type, String name) {
    return c.getResources().getIdentifier(name, type, c.getPackageName());
  }

  static PendingIntent task(Context c, int widget, boolean play) {
    Intent i =
        new Intent(c, QuickPlayService.class)
            .setAction(play ? "play" : "status")
            .putExtra("widget", widget);
    return PendingIntent.getForegroundService(
        c,
        (widget + 2) * 2 + (play ? 1 : 0),
        i,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  static void start(Context c, int widget, boolean play) {
    c.startForegroundService(
        new Intent(c, QuickPlayService.class)
            .setAction(play ? "play" : "status")
            .putExtra("widget", widget));
  }

  static boolean localNetwork(Context c) {
    ConnectivityManager manager =
        (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
    if (manager == null) return false;
    try {
      // A local Wi-Fi without Internet can coexist with default mobile data.
      for (Network network : manager.getAllNetworks()) {
        NetworkCapabilities caps = manager.getNetworkCapabilities(network);
        if (caps != null
            && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return true;
      }
    } catch (SecurityException ignored) {
    }
    return false;
  }

  static QuickPlayState.Snapshot snapshot(Context c, String pin) {
    android.content.SharedPreferences p = prefs(c);
    boolean configured = profile(c, pin) != null;
    return QuickPlayState.snapshot(
        configured,
        configured && localNetwork(c),
        p.getBoolean("quickOnline." + pin, false),
        p.getString("quickState." + pin, "unknown"),
        System.currentTimeMillis(),
        p.getLong("quickAt." + pin, 0));
  }

  static int color(QuickPlayState.Snapshot state) {
    return state.online ? 0xff70dba1 : 0xff718095;
  }

  static boolean playing(Context c, String pin) {
    return snapshot(c, pin).playing;
  }

  static Bitmap icon(int color, boolean play, boolean confirmed) {
    Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(b);
    Paint p = new Paint(3);
    p.setColor(color);
    if (!confirmed) {
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(6);
    }
    if (play) {
      Path path = new Path();
      path.moveTo(30, 20);
      path.lineTo(30, 76);
      path.lineTo(75, 48);
      path.close();
      canvas.drawPath(path, p);
    } else {
      canvas.drawRect(26, 22, 42, 74, p);
      canvas.drawRect(54, 22, 70, 74, p);
    }
    return b;
  }

  static void record(Context c, String pin, boolean online) {
    prefs(c)
        .edit()
        .putLong("quickAt." + pin, System.currentTimeMillis())
        .putBoolean("quickOnline." + pin, online)
        .apply();
    refresh(c);
  }

  static void record(Context c, String pin, JSONObject status) {
    prefs(c)
        .edit()
        .putLong("quickAt." + pin, System.currentTimeMillis())
        .putBoolean("quickOnline." + pin, true)
        .putString("quickState." + pin, status.optString("playbackState", "unknown"))
        .apply();
    refresh(c);
  }

  static int[] widgets(Context c) {
    AppWidgetManager manager = AppWidgetManager.getInstance(c);
    int[] small = manager.getAppWidgetIds(new ComponentName(c, QuickPlayWidget.class)),
        wide = manager.getAppWidgetIds(new ComponentName(c, QuickPlayWideWidget.class)),
        all = java.util.Arrays.copyOf(small, small.length + wide.length);
    System.arraycopy(wide, 0, all, small.length, wide.length);
    return all;
  }

  static PendingIntent open(Context c, int id, String pin) {
    Intent i =
        new Intent(c, MainActivity.class)
            .putExtra("quickPin", pin)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    return PendingIntent.getActivity(
        c, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  static Bitmap openIcon() {
    Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(b);
    Paint p = new Paint(3);
    p.setColor(0xff73d5ce);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(5);
    Path path = new Path();
    path.moveTo(44, 22);
    path.lineTo(24, 22);
    path.lineTo(24, 74);
    path.lineTo(76, 74);
    path.lineTo(76, 54);
    canvas.drawPath(path, p);
    path.reset();
    path.moveTo(48, 18);
    path.lineTo(80, 18);
    path.lineTo(80, 50);
    path.moveTo(42, 56);
    path.lineTo(80, 18);
    canvas.drawPath(path, p);
    return b;
  }

  static void widget(Context c, int id) {
    String pin = target(c, id);
    JSONObject entry = profile(c, pin);
    android.appwidget.AppWidgetProviderInfo info =
        AppWidgetManager.getInstance(c).getAppWidgetInfo(id);
    boolean wide =
        info != null && info.provider.getClassName().equals(QuickPlayWideWidget.class.getName());
    RemoteViews views =
        new RemoteViews(
            c.getPackageName(),
            res(c, "layout", wide ? "quick_play_wide_widget" : "quick_play_widget"));
    QuickPlayState.Snapshot state = snapshot(c, pin);
    views.setImageViewBitmap(
        res(c, "id", "quick_play"), icon(color(state), !state.playing, state.online));
    String name = entry == null ? "未配置设备" : entry.optString("name");
    views.setContentDescription(
        res(c, "id", "quick_play"),
        name
            + (state.playing ? "，暂停" : state.online ? "，待播放" : "，待连接")
            + "；实心表示最近检查可连接，空心灰色表示未确认或不可达");
    views.setOnClickPendingIntent(res(c, "id", "quick_play"), task(c, id, true));
    if (wide) {
      views.setImageViewBitmap(res(c, "id", "quick_open"), openIcon());
      views.setOnClickPendingIntent(res(c, "id", "quick_open"), open(c, id, pin));
      views.setContentDescription(res(c, "id", "quick_open"), "打开 " + name + " 的控制页");
    }
    AppWidgetManager.getInstance(c).updateAppWidget(id, views);
  }

  static synchronized void refresh(Context c) {
    for (int id : widgets(c)) widget(c, id);
    c.sendBroadcast(
        new Intent("cn.couger.livingplayer.QUICK_STATE").setPackage(c.getPackageName()));
    if (!target(c, -1).isEmpty())
      android.service.quicksettings.TileService.requestListeningState(
          c, new ComponentName(c, QuickPlayTile.class));
    scheduleExpiry(c);
  }

  static void scheduleExpiry(Context c) {
    AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    if (alarms == null) return;
    PendingIntent expired =
        PendingIntent.getBroadcast(
            c,
            0,
            new Intent(c, QuickPlayExpiry.class).setAction(QuickPlayExpiry.ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    java.util.Set<String> pins = new java.util.HashSet<>();
    pins.add(target(c, -1));
    for (int id : widgets(c)) pins.add(target(c, id));
    long now = System.currentTimeMillis(), delay = Long.MAX_VALUE;
    for (String pin : pins) {
      QuickPlayState.Snapshot state = snapshot(c, pin);
      if (state.online && state.remainingMillis > 0) delay = Math.min(delay, state.remainingMillis);
    }
    if (delay == Long.MAX_VALUE) {
      alarms.cancel(expired);
      prefs(c).edit().remove("quickExpiryAt").apply();
      return;
    }
    long scheduled = prefs(c).getLong("quickExpiryAt", 0);
    if (scheduled > now && scheduled <= now + delay) return;
    // One non-wakeup, inexact local repaint. No polling, network work or exact-alarm permission.
    alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delay, expired);
    prefs(c).edit().putLong("quickExpiryAt", now + delay).apply();
  }

  static void invalidate(Context c) {
    android.content.SharedPreferences.Editor edit = prefs(c).edit();
    for (String key : prefs(c).getAll().keySet())
      if (key.startsWith("quickAt.")
          || key.startsWith("quickOnline.")
          || key.startsWith("quickState.")
          || key.equals("quickExpiryAt")) edit.remove(key);
    edit.apply();
    refresh(c);
  }

  static void failure(Context c) {
    new Handler(Looper.getMainLooper())
        .post(
            () -> {
              Toast toast = Toast.makeText(c, "播放控制失败", Toast.LENGTH_SHORT);
              toast.show();
              new Handler(Looper.getMainLooper()).postDelayed(toast::cancel, 1000);
            });
  }
}
