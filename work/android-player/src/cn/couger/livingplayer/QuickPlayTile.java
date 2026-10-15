package cn.couger.livingplayer;

import android.content.*;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.*;

public final class QuickPlayTile extends TileService {
  boolean listening = false;
  final Handler ui = new Handler(Looper.getMainLooper());
  final Runnable expiry = () -> paint();
  final BroadcastReceiver changes =
      new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
          paint();
        }
      };

  public void onStartListening() {
    if (!listening) {
      if (android.os.Build.VERSION.SDK_INT >= 33)
        registerReceiver(
            changes,
            new IntentFilter("cn.couger.livingplayer.QUICK_STATE"),
            Context.RECEIVER_NOT_EXPORTED);
      else registerReceiver(changes, new IntentFilter("cn.couger.livingplayer.QUICK_STATE"));
      listening = true;
    }
    paint();
  }

  public void onStopListening() {
    ui.removeCallbacks(expiry);
    if (listening) {
      unregisterReceiver(changes);
      listening = false;
    }
  }

  public void onDestroy() {
    onStopListening();
    super.onDestroy();
  }

  public void onClick() {
    if (QuickPlay.profile(this, QuickPlay.target(this, -1)) == null) {
      startActivityAndCollapse(
          new Intent(this, QuickPlayConfigActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      return;
    }
    // Receiver decides play or pause from its current selected media session.
    QuickPlay.start(this, -1, true);
  }

  void paint() {
    Tile tile = getQsTile();
    if (tile == null) return;
    String pin = QuickPlay.target(this, -1);
    QuickPlayState.Snapshot state = QuickPlay.snapshot(this, pin);
    tile.setIcon(
        Icon.createWithBitmap(
            QuickPlay.icon(QuickPlay.color(state), !state.playing, state.online)));
    // Keep unknown/offline tiles clickable so they can reconnect.
    tile.setState(state.playing ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
    tile.setLabel(state.playing ? "暂停" : state.online ? "播放" : "连接");
    if (android.os.Build.VERSION.SDK_INT >= 29)
      tile.setSubtitle(state.playing ? "播放中" : state.online ? "待播放" : "待连接");
    tile.setContentDescription(
        state.playing
            ? "最近确认正在播放；点击重新核对并切换播放状态"
            : state.online ? "最近确认可连接；点击重新核对并切换播放状态" : "连接状态未确认；点击连接并切换播放状态");
    tile.updateTile();
    ui.removeCallbacks(expiry);
    if (listening && state.online && state.remainingMillis > 0)
      ui.postDelayed(expiry, state.remainingMillis);
  }
}
