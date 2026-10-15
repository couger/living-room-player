package cn.couger.livingplayer;

import android.content.*;

/** A short local repaint; never connects to a receiver or starts a resident service. */
public final class QuickPlayExpiry extends BroadcastReceiver {
  static final String ACTION = "cn.couger.livingplayer.QUICK_EXPIRE";

  public void onReceive(Context context, Intent intent) {
    if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
      QuickPlay.invalidate(context);
    } else if (ACTION.equals(intent.getAction())) {
      QuickPlay.prefs(context).edit().remove("quickExpiryAt").apply();
      QuickPlay.refresh(context);
    }
  }
}
