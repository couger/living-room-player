package cn.couger.livingplayer;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.*;
import org.json.*;

/** Select a fixed paired receiver; never silently follows the main screen selection. */
public final class QuickPlayConfigActivity extends Activity {
  public void onCreate(Bundle b) {
    super.onCreate(b);
    setResult(RESULT_CANCELED);
    int widget = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
    android.content.SharedPreferences prefs = QuickPlay.prefs(this);
    SavedDevices saved = new SavedDevices(prefs);
    saved.migrate();
    JSONArray list = saved.list();
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(1);
    body.setPadding(24, 24, 24, 24);
    body.setBackgroundColor(0xff101720);
    setContentView(body);
    getWindow().setStatusBarColor(0xff101720);
    getWindow().setNavigationBarColor(0xff101720);
    TextView title = new TextView(this);
    title.setText(widget < 0 ? "下拉快捷按钮 · 选择播放器" : "桌面小组件 · 选择播放器");
    title.setTextColor(Color.WHITE);
    title.setTextSize(22);
    body.addView(title);
    TextView help = new TextView(this);
    help.setText(
        "固定控制所选设备；音乐 APP"
            + " 由被控端设置。单个按钮：未播放时启动或继续，播放成功后变为暂停，暂停后变回播放。绿色表示最近检查可连接，灰色表示未确认或暂不可达。不持续后台轮询；点击前会重新核对实际状态。\n"
            + "控制端和受控端都需升级到支持播放／暂停的版本。下拉按钮保留简短名称。\n");
    help.setTextColor(0xff9daebf);
    body.addView(help);
    ScrollView scroll = new ScrollView(this);
    body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout rows = new LinearLayout(this);
    rows.setOrientation(1);
    scroll.addView(rows);
    for (int n = 0; n < list.length(); n++) {
      JSONObject entry = list.optJSONObject(n);
      if (entry == null) continue;
      String pin = entry.optString("id");
      Button choice = new Button(this);
      choice.setAllCaps(false);
      choice.setText(entry.optString("name"));
      choice.setTextColor(Color.WHITE);
      choice.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff263748));
      rows.addView(choice);
      choice.setOnClickListener(
          v -> {
            prefs.edit().putString(widget < 0 ? "quickTile" : "quickWidget." + widget, pin).apply();
            if (widget >= 0) {
              QuickPlay.widget(this, widget);
              setResult(
                  RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widget));
            }
            QuickPlay.refresh(this);
            finish();
          });
    }
    if (list.length() == 0) {
      TextView empty = new TextView(this);
      empty.setText("请先在控制端配对并保存播放器，然后再添加快捷入口。");
      empty.setTextColor(Color.WHITE);
      rows.addView(empty);
    }
  }
}
