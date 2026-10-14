package cn.couger.livingplayer;

import android.content.*;
import android.graphics.drawable.Icon;
import android.service.quicksettings.*;

public final class QuickPlayTile extends TileService {
  boolean listening = false;
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
    int color = QuickPlay.color(this, pin);
    boolean playing = QuickPlay.playing(this, pin);
    tile.setIcon(Icon.createWithBitmap(QuickPlay.icon(color, !playing)));
    tile.setState(playing ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
    tile.setLabel(playing ? "暂停" : "播放");
    tile.setContentDescription(playing ? "已选播放器正在播放；点击暂停" : "已选播放器未播放；点击连接并播放");
    tile.updateTile();
  }
}
