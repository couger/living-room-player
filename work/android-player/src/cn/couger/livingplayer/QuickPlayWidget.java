package cn.couger.livingplayer;

import android.appwidget.*;
import android.content.*;

public class QuickPlayWidget extends AppWidgetProvider {
  public void onUpdate(Context c, AppWidgetManager manager, int[] ids) {
    for (int id : ids) QuickPlay.widget(c, id);
  }

  public void onAppWidgetOptionsChanged(
      Context c, AppWidgetManager manager, int id, android.os.Bundle options) {
    QuickPlay.widget(c, id);
  }

  public void onDeleted(Context c, int[] ids) {
    android.content.SharedPreferences.Editor edit = QuickPlay.prefs(c).edit();
    for (int id : ids) edit.remove("quickWidget." + id);
    edit.apply();
  }
}
