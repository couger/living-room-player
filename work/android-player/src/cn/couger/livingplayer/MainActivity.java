package cn.couger.livingplayer;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity {
  final Handler ui = new Handler(Looper.getMainLooper());
  final ExecutorService jobs = Executors.newFixedThreadPool(3),
      actions = Executors.newSingleThreadExecutor();
  LinearLayout root;
  TextView state, message;
  volatile DeviceClient device;
  SurfaceView surface;
  FrameLayout videoBox;
  VideoSession video;
  boolean controller = false, visible = false, polling = false, videoEnabled = false;
  volatile boolean alive = true;
  Button mediaButton;
  boolean mediaPending = false, splitController = false;
  final java.util.LinkedHashSet<String> identifiedKeys = new java.util.LinkedHashSet<>();
  TextView powerAdvice;
  TextView keyBindingInfo;
  TextView playerInfo;
  TextView videoPlaceholder;
  Button videoOn, videoOff, sleepButton, displayOffButton;
  TextView connection;
  String page = "home";
  int frameWidth = 1080,
      frameHeight = 2400,
      remoteWidth = 1080,
      remoteHeight = 2400,
      remoteRotation = 0;
  boolean frameReady = false, frameFormat = false;
  long lastLocalInputAt = 0;
  JSONObject latest;
  long lastDiscovery = 0;
  float downX, downY;
  long downTime;
  String shownPair = "";
  boolean pairingDialog = false;
  SharedPreferences prefs;
  SavedDevices savedDevices;

  int dp(int n) {
    return (int) (n * getResources().getDisplayMetrics().density + .5f);
  }

  TextView text(String s, int size) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(ink);
    t.setPadding(dp(8), dp(5), dp(8), dp(5));
    return t;
  }

  int ink = Color.rgb(231, 237, 244),
      accent = Color.rgb(108, 213, 210),
      background = Color.rgb(16, 23, 32),
      panelColor = Color.rgb(30, 41, 55),
      buttonColor = Color.rgb(38, 55, 72),
      selectedColor = Color.rgb(24, 109, 119);

  android.graphics.drawable.Drawable shape(int color) {
    android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
    g.setColor(color);
    g.setCornerRadius(dp(14));
    return g;
  }

  void base(String title) {
    stopVideo();
    surface = null;
    videoBox = null;
    videoPlaceholder = null;
    videoOn = null;
    videoOff = null;
    connection = null;
    playerInfo = null;
    message = null;
    state = null;
    sleepButton = null;
    displayOffButton = null;
    keyBindingInfo = null;
    powerAdvice = null;
    mediaButton = null;
    root = new LinearLayout(this);
    root.setOrientation(1);
    root.setPadding(dp(12), dp(8), dp(12), dp(8));
    root.setBackgroundColor(background);
    setContentView(root);
    getWindow().setStatusBarColor(background);
    getWindow().setNavigationBarColor(background);
    getWindow().getDecorView().setSystemUiVisibility(0);
    LinearLayout head = row();
    TextView heading = text(title, 21);
    heading.setTypeface(null, 1);
    heading.setMaxLines(1);
    heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
    if (controller) {
      heading.setText(title + " ▾");
      heading.setContentDescription("切换或管理播放器");
      heading.setOnClickListener(v -> connectPage());
    }
    head.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
    if (controller) {
      Button rotate =
          button(
              head,
              getResources().getConfiguration().orientation == 2 ? "竖屏" : "横屏",
              () ->
                  setRequestedOrientation(
                      getResources().getConfiguration().orientation == 2
                          ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                          : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE));
      rotate.setLayoutParams(new LinearLayout.LayoutParams(dp(76), dp(46)));
      {
        Button settings = button(head, "设置", () -> controlSettings());
        settings.setLayoutParams(new LinearLayout.LayoutParams(dp(66), dp(46)));
      }
    } else {
      setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
      head.addView(text("0.9.4", 12));
    }
  }

  Button button(LinearLayout parent, String label, final Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setTextSize(14);
    b.setTextColor(ink);
    b.setAllCaps(false);
    b.setGravity(Gravity.CENTER);
    b.setMaxLines(2);
    b.setPadding(dp(6), dp(9), dp(6), dp(9));
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setMinHeight(dp(46));
    b.setMinimumHeight(dp(46));
    b.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x336CD5D2), shape(buttonColor), null));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    lp.setMargins(dp(3), dp(3), dp(3), dp(3));
    parent.addView(b, lp);
    b.setOnClickListener(v -> action.run());
    if (label.startsWith("停止") || label.startsWith("撤销") || label.startsWith("忘记")) danger(b);
    return b;
  }

  LinearLayout row() {
    LinearLayout r = new LinearLayout(this);
    r.setOrientation(0);
    root.addView(r);
    return r;
  }

  LinearLayout card(LinearLayout parent) {
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(1);
    box.setPadding(dp(8), dp(8), dp(8), dp(8));
    box.setBackground(shape(panelColor));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(0, dp(6), 0, dp(8));
    parent.addView(box, lp);
    return box;
  }

  void details() {
    new AlertDialog.Builder(this)
        .setTitle(controller ? "被控端设备详情" : "本机设备详情")
        .setMessage(
            latest == null
                ? "等待设备状态…"
                : statusText(latest)
                    + "\nUSB 设备："
                    + latest.optJSONArray("usbDevices")
                    + "\n供电类型："
                    + latest.optString("usbType")
                    + "\n屏幕："
                    + latest.optString("screen"))
        .setPositiveButton("关闭", null)
        .show();
  }

  void renderStatus(JSONObject s) {
    if (state == null) return;
    if (playerInfo != null)
      playerInfo.setText(
          (controller ? "被控端音乐 APP：" : "本机音乐 APP：")
              + MediaActions.name(s.optString("playerPackage", ""))
              + (controller
                  ? (s.optString("playbackState").equals("playing")
                      ? " · 播放中"
                      : s.optString("playbackState").equals("paused") ? " · 已暂停" : "")
                  : (s.optString("playbackRequest").isEmpty()
                      ? ""
                      : "\n" + s.optString("playbackRequest"))));
    boolean box = s.optString("deviceType").equals("box");
    if (powerAdvice != null) {
      String reason = s.optString("reason");
      powerAdvice.setText(powerReminder(s));
      powerAdvice.setTextColor(
          reason.equals("fault")
              ? Color.rgb(255, 146, 155)
              : reason.equals("temperature") || !box && !powerConfig(s).optBoolean("protect", true)
                  ? Color.rgb(255, 210, 122)
                  : Color.rgb(156, 174, 193));
    }
    if (controller) {
      long input = s.optLong("localInputAt");
      if (input != lastLocalInputAt) {
        boolean recent =
            s.optLong("localInputAgeMs", -1) >= 0 && s.optLong("localInputAgeMs", -1) < 10000;
        lastLocalInputAt = input;
        if (recent) note("检测到被控端本地遥控器／键盘操作");
      }
    }
    if (displayOffButton != null)
      displayOffButton.setVisibility(
          s.optBoolean("displayOnlyOffSupported", false) ? View.VISIBLE : View.GONE);
    if (keyBindingInfo != null) {
      keyBindingInfo.setVisibility(box ? View.VISIBLE : View.GONE);
      String bound = s.optString("oneTapKey", "");
      keyBindingInfo.setText(
          (controller ? "被控端遥控器播放键：" : "本机遥控器播放键：")
              + (bound.isEmpty() ? "未绑定" : bound + " · 一键播放／暂停"));
    }
    if (sleepButton != null) {
      boolean awake = s.optString("screen").equals("Awake");
      sleepButton.setText(awake ? (box ? "盒子待机" : "手机息屏") : "唤醒");
      if (awake) danger(sleepButton);
      else {
        sleepButton.setTextColor(ink);
        sleepButton.setBackground(shape(buttonColor));
      }
    }
    if (displayOffButton != null) {
      displayOffButton.setText(s.optString("displayOutput").equals("Off") ? "开启 HDMI" : "关闭 HDMI");
      if (!s.optString("displayOutput").equals("Off")) danger(displayOffButton);
      else {
        displayOffButton.setTextColor(ink);
        displayOffButton.setBackground(shape(buttonColor));
      }
    }
    if (mediaButton != null) {
      boolean playing = QuickPlayState.playing(s.optString("playbackState"));
      setTransportIcon(mediaButton, playing ? "pause" : "play");
      mediaButton.setContentDescription(playing ? "暂停" : "连接并播放");
      mediaButton.setEnabled(!mediaPending);
    }

    if (box)
      state.setText(
          (controller ? "被控端盒子" : "本机盒子")
              + " · 外接电源\n芯片温度 "
              + String.format(Locale.CHINA, "%.1f℃", s.optInt("socTemp") / 1000.0)
              + "\n无电池保护"
              + "\n系统："
              + (s.optString("screen").equals("Awake") ? "已唤醒" : "待机／未知")
              + " · HDMI："
              + (s.optString("displayOutput").equals("Off")
                  ? "已关闭"
                  : s.optString("displayOutput").equals("On") ? "开启" : "未知")
              + "\n一键播放："
              + (s.optString("playerPackage").isEmpty() ? "未配置" : "已配置"));
    else {
      boolean online = s.optInt("usbOnline") == 1;
      state.setText(
          String.format(
              Locale.CHINA,
              controller ? "被控端电量 %d%% · 温度 %.1f℃\n被控端供电：%s\n充电保护：%s" : "%d%%    %.1f℃\n%s\n%s",
              s.optInt("capacity"),
              s.optInt("temp") / 10.0,
              online ? "外部供电在线" : "使用电池供电",
              reason(s)));
      if (controller && getResources().getConfiguration().orientation == 2)
        state.setText(
            String.format(
                Locale.CHINA,
                "被控端：%d%% · %.1f℃\n被控端供电：%s",
                s.optInt("capacity"),
                s.optInt("temp") / 10.0,
                online ? "外部供电在线" : "使用电池供电"));
    }
    if (controller) {
      if (box)
        state.setText(
            "被控端 · 外接供电 · "
                + (s.optInt("socTemp") > 0
                    ? String.format(Locale.CHINA, "%.1f℃", s.optInt("socTemp") / 1000.0)
                    : "温度未知")
                + "\n系统："
                + (s.optString("screen").equals("Awake") ? "已唤醒" : "待机／未知")
                + " · HDMI："
                + (s.optString("displayOutput").equals("On")
                    ? "开启"
                    : s.optString("displayOutput").equals("Off") ? "关闭" : "未知"));
      else
        state.setText(
            String.format(
                Locale.CHINA,
                "被控端电量 %d%% · %.1f℃\n被控端供电：%s · %s",
                s.optInt("capacity"),
                s.optInt("temp") / 10.0,
                s.optInt("usbOnline") == 1 ? "外部供电" : "电池供电",
                reason(s)));
    }
    state.setTextColor(
        s.optString("reason").equals("fault") || box && s.optInt("socTemp") >= 85000
            ? Color.rgb(255, 146, 155)
            : s.optString("reason").equals("temperature")
                    || !box && s.optInt("capacity") < 20
                    || box && s.optInt("socTemp") >= 75000
                ? Color.rgb(255, 210, 122)
                : ink);
    if (connection != null) {
      String text = controller ? "已连接：" + savedDevices.name(device) : "受控服务运行中";
      if (controller && s.optBoolean("remoteControlActive")) text += " · 远控中";
      if (controller
          && s.optLong("localInputAgeMs", -1) >= 0
          && s.optLong("localInputAgeMs", -1) < 10000) text += " · 被控端检测到本地操作";
      connection.setText(text);
      connection.setTextColor(accent);
    }
  }

  void note(String s) {
    ui.post(
        () -> {
          if (message != null) message.setText(s);
        });
  }

  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    prefs = getSharedPreferences("player", 0);
    savedDevices = new SavedDevices(prefs);
    savedDevices.migrate();
    if (openQuickIntent(getIntent())) return;
    if (prefs.getBoolean("receiver", false) && prefs.contains("receiverCode")) receiver();
    else chooser();
  }

  void chooser() {
    releaseController();
    page = "home";
    controller = false;
    device = null;
    base("客厅播放器");
    state = text("在播放器手机上选择受控端，在日常手机／平板上选择控制端。", 17);
    root.addView(state);
    LinearLayout r = row();
    button(r, "控制其他设备", () -> connectPage());
    button(r, "作为受控端", () -> receiver());
    root.addView(text("仅在家里 Wi-Fi 使用，无音乐账号登录，不采集音频。", 14));
    message = text("", 14);
    root.addView(message);
  }

  void releaseController() {
    DeviceClient old = device;
    device = null;
    stopVideo();
    latest = null;
    lastLocalInputAt = 0;
    frameReady = false;
    ui.removeCallbacks(pollTask);
    ui.removeCallbacks(mediaPollTask);
    if (old != null) old.cancelPending();
  }

  void openSaved(JSONObject entry) {
    try {
      releaseController();
      device = new DeviceClient(entry.getString("code"));
      control();
    } catch (Exception e) {
      note(e.getMessage());
    }
  }

  void playSaved(JSONObject entry) {
    try {
      releaseController();
      device = new DeviceClient(entry.getString("code"));
      prefs.edit().putBoolean("showVideo", false).apply();
      control();
      togglePlayback();
    } catch (Exception e) {
      note(e.getMessage());
    }
  }

  int addressGeneration = 0;

  void manageSaved(JSONObject entry) {
    new AlertDialog.Builder(this)
        .setTitle(entry.optString("name") + " · " + entry.optString("host"))
        .setItems(
            new String[] {"连接此设备", "修改 IP 地址", "自动查找新地址", "重命名", "忘记本机配对"},
            (dialog, index) -> {
              String id = entry.optString("id");
              if (index == 0) openSaved(entry);
              else if (index == 1) editSavedAddress(entry);
              else if (index == 2) updateSavedAddress(entry, null, false);
              else if (index == 3) {
                EditText input = new EditText(this);
                input.setSingleLine(true);
                input.setText(entry.optString("name"));
                new AlertDialog.Builder(this)
                    .setTitle("设备名称")
                    .setView(input)
                    .setPositiveButton(
                        "保存",
                        (d, w) -> {
                          String name = input.getText().toString().trim();
                          if (!name.isEmpty() && name.length() <= 40) {
                            savedDevices.rename(id, name);
                            connectPage();
                          }
                        })
                    .setNegativeButton("取消", null)
                    .show();
              } else
                new AlertDialog.Builder(this)
                    .setTitle("忘记此设备？")
                    .setMessage("只删除本机档案，不停止音乐，也不撤销受控端授权。")
                    .setPositiveButton(
                        "忘记",
                        (d, w) -> {
                          savedDevices.forget(id);
                          prefs.edit().remove("controllerCode").apply();
                          connectPage();
                        })
                    .setNegativeButton("取消", null)
                    .show();
            })
        .show();
  }

  void editSavedAddress(JSONObject entry) {
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(1);
    body.setPadding(dp(16), dp(8), dp(16), dp(8));
    body.setBackgroundColor(background);
    body.addView(text("填写受控端当前显示的 IP，不含端口。验证同一设备后保存，保留名称与原配对；无需在受控端开启配对。", 14));
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setTextColor(ink);
    input.setInputType(1);
    input.setText(entry.optString("host"));
    body.addView(input);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("修改设备 IP 地址")
            .setView(body)
            .setPositiveButton("验证并保存", null)
            .setNegativeButton("取消", null)
            .create();
    dialog.show();
    dialog
        .getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(
            v -> {
              try {
                String host = LanAddress.parse(input.getText().toString());
                dialog.dismiss();
                updateSavedAddress(entry, host, false);
              } catch (Exception e) {
                input.setError(e.getMessage());
              }
            });
  }

  void updateSavedAddress(JSONObject entry, String host, boolean connectAfter) {
    final int generation = ++addressGeneration;
    note(host == null ? "正在查找此设备的新地址…" : "正在验证设备身份与原有授权…");
    jobs.execute(
        () -> {
          try {
            DeviceClient candidate = new DeviceClient(entry.getString("code"));
            if (host == null) {
              if (!candidate.rediscover()) throw new IOException("未找到此设备，请检查同一网络及受控端发现开关，或手动修改 IP");
            } else candidate.host = LanAddress.parse(host);
            JSONObject s = candidate.request("/status", null);
            ui.post(
                () -> {
                  if (!alive
                      || !page.equals("connect")
                      || generation != addressGeneration
                      || savedDevices.find(entry.optString("id")) == null) return;
                  savedDevices.save(candidate, null, s.optString("deviceType"));
                  if (connectAfter) openSaved(savedDevices.find(candidate.pin));
                  else {
                    connectPage();
                    note("地址已更新为 " + candidate.host + "，原配对保留；点击连接即可使用");
                  }
                });
          } catch (Exception e) {
            ui.post(
                () -> {
                  if (alive && page.equals("connect") && generation == addressGeneration)
                    note("地址未修改：" + e.getMessage() + "。原地址与配对保留");
                });
          }
        });
  }

  void connectPage() {
    releaseController();
    page = "connect";
    controller = false;
    base("我的播放器");
    root.addView(text("手机与盒子分别保存配对；每次只控制一台，切换不会停止音乐。", 14));
    LinearLayout search = row();
    button(search, "添加／搜索设备", () -> searchDevices());
    button(search, "返回", () -> chooser());
    ScrollView scroll = new ScrollView(this);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout list = new LinearLayout(this);
    list.setOrientation(1);
    scroll.addView(list);
    JSONArray entries = savedDevices.list();
    if (entries.length() == 0) list.addView(text("尚未保存设备。请在受控端允许配对三分钟，再搜索。", 16));
    for (int i = 0; i < entries.length(); i++) {
      JSONObject entry = entries.optJSONObject(i);
      if (entry == null) continue;
      LinearLayout item = card(list);
      item.addView(text(entry.optString("name"), 18));
      item.addView(
          text(
              (entry.optString("type").equals("box")
                      ? "盒子"
                      : entry.optString("type").equals("phone") ? "手机／平板" : "类型待连接确认")
                  + " · "
                  + entry.optString("host")
                  + "\n未连接 · 连接后读取实时状态",
              13));
      LinearLayout r = controlRow(item);
      button(r, "连接", () -> openSaved(entry));
      button(r, "播放／暂停", () -> playSaved(entry));
      button(r, "管理", () -> manageSaved(entry));
    }
    button(
        row(),
        "手动连接（备用）",
        () -> {
          EditText code = new EditText(this);
          code.setHint("LP1:…");
          new AlertDialog.Builder(this)
              .setTitle("备用连接码")
              .setView(code)
              .setPositiveButton(
                  "连接",
                  (d, w) -> {
                    try {
                      device = new DeviceClient(code.getText().toString());
                      savedDevices.save(device, null, null);
                      control();
                    } catch (Exception e) {
                      note(e.getMessage());
                    }
                  })
              .setNegativeButton("取消", null)
              .show();
        });
    message = text("", 14);
    root.addView(message);
  }

  void searchDevices() {
    note("正在搜索家庭 Wi-Fi 内的受控端…");
    jobs.execute(
        () -> {
          java.util.List<JSONObject> found = DeviceClient.discover();
          ui.post(
              () -> {
                if (!alive || !page.equals("connect")) return;
                if (found.isEmpty()) {
                  note("没有发现播放器。请检查同一 Wi-Fi、受控服务和路由器设备隔离设置。");
                  return;
                }
                String[] labels = new String[found.size()];
                for (int i = 0; i < labels.length; i++)
                  labels[i] =
                      found.get(i).optString("name", "客厅播放器")
                          + "\n"
                          + found.get(i).optString("host");
                new AlertDialog.Builder(this)
                    .setTitle("选择播放器")
                    .setItems(labels, (dialog, index) -> beginPair(found.get(index)))
                    .setNegativeButton("取消", null)
                    .show();
                note("发现 " + found.size() + " 台播放器");
              });
        });
  }

  void beginPair(JSONObject found) {
    if (!page.equals("connect")) return;
    JSONObject known = savedDevices.find(found.optString("fingerprint"));
    if (known != null) {
      updateSavedAddress(known, found.optString("host"), true);
      return;
    }
    note("正在发起配对…");
    jobs.execute(
        () -> {
          final AlertDialog[] dialog = new AlertDialog[1];
          DeviceClient cleanupClient = null;
          String cleanupId = null;
          try {
            final String host = found.getString("host"), pin = found.getString("fingerprint");
            String zero = "000000000000000000000000000000000000000000000000";
            DeviceClient candidate = new DeviceClient(DeviceClient.code(host, zero, pin));
            java.security.KeyPairGenerator generator =
                java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            java.security.KeyPair keys = generator.generateKeyPair();
            String key =
                android.util.Base64.encodeToString(
                    keys.getPublic().getEncoded(), android.util.Base64.NO_WRAP);
            byte[] random = new byte[16];
            new java.security.SecureRandom().nextBytes(random);
            String nonce = CertificateMaker.hex(random);
            String verify = Pairing.code(pin, key, nonce);
            JSONObject request =
                candidate.request(
                    "/pair/request",
                    new JSONObject()
                        .put("key", key)
                        .put("nonce", nonce)
                        .put("name", android.os.Build.MODEL));
            final String id = request.getString("id");
            cleanupClient = candidate;
            cleanupId = id;
            final java.util.concurrent.atomic.AtomicBoolean waiting =
                new java.util.concurrent.atomic.AtomicBoolean(true);
            ui.post(
                () -> {
                  if (!alive) {
                    waiting.set(false);
                    return;
                  }
                  dialog[0] =
                      new AlertDialog.Builder(this)
                          .setTitle("核对配对码：" + verify)
                          .setMessage(
                              "请查看受控端上的配对码。两边都是 "
                                  + verify
                                  + " 时，在受控端上点“允许配对”。\n\n不需要输入或复制长码。请求 3 分钟后失效。")
                          .setNegativeButton("取消", (d, w) -> waiting.set(false))
                          .setOnCancelListener(d -> waiting.set(false))
                          .show();
                });
            long end = System.currentTimeMillis() + 180000;
            while (alive
                && page.equals("connect")
                && waiting.get()
                && System.currentTimeMillis() < end) {
              Thread.sleep(1200);
              if (!waiting.get()) break;
              JSONObject result = candidate.request("/pair/result", new JSONObject().put("id", id));
              String state = result.optString("state");
              if (state.equals("denied") || state.equals("expired"))
                throw new IOException("配对被拒绝或已过期");
              if (!state.equals("approved")) continue;
              javax.crypto.Cipher cipher =
                  javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
              cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keys.getPrivate());
              JSONObject secret =
                  new JSONObject(
                      new String(
                          cipher.doFinal(
                              android.util.Base64.decode(
                                  result.getString("cipher"), android.util.Base64.NO_WRAP)),
                          "UTF-8"));
              if (!pin.equals(secret.getString("fingerprint")))
                throw new IOException("配对设备身份不一致，请重试");
              final DeviceClient paired =
                  new DeviceClient(DeviceClient.code(host, secret.getString("token"), pin));
              ui.post(
                  () -> {
                    if (dialog[0] != null) dialog[0].dismiss();
                    if (!alive || !page.equals("connect")) return;
                    device = paired;
                    savedDevices.save(paired, found.optString("name"), null);
                    control();
                  });
              return;
            }
            ui.post(
                () -> {
                  if (dialog[0] != null) dialog[0].dismiss();
                  note("配对已取消或超时，请重新搜索");
                });
          } catch (Exception e) {
            note("配对未完成：" + e.getMessage());
          } finally {
            ui.post(
                () -> {
                  if (dialog[0] != null) dialog[0].dismiss();
                });
            if (cleanupClient != null && cleanupId != null)
              try {
                cleanupClient.request("/pair/cancel", new JSONObject().put("id", cleanupId));
              } catch (Exception ignored) {
              }
          }
        });
  }

  void incomingPair(JSONObject status) {
    if (controller
        || pairingDialog
        || !prefs.getBoolean("receiver", false)
        || device == null
        || !device.host.equals("127.0.0.1")) return;
    JSONArray requests = status.optJSONArray("pairing");
    if (requests == null || requests.length() == 0) return;
    JSONObject request = requests.optJSONObject(0);
    if (request == null || request.optString("id").equals(shownPair)) return;
    shownPair = request.optString("id");
    pairingDialog = true;
    final String id = shownPair;
    new AlertDialog.Builder(this)
        .setTitle("允许新控制端？")
        .setMessage(
            "设备："
                + request.optString("name")
                + "\n\n配对码："
                + request.optString("code")
                + "\n\n请确认控制端显示相同的六位数字，再允许配对。")
        .setPositiveButton("允许配对", (d, w) -> approvePair(id, true))
        .setNegativeButton("拒绝", (d, w) -> approvePair(id, false))
        .setOnCancelListener(d -> approvePair(id, false))
        .setOnDismissListener(d -> pairingDialog = false)
        .show();
  }

  void approvePair(String id, boolean allow) {
    jobs.execute(
        () -> {
          try {
            device.request("/pair/approve", new JSONObject().put("id", id).put("allow", allow));
            note(allow ? "已允许配对" : "已拒绝配对");
          } catch (Exception e) {
            note("配对确认失败：" + e.getMessage());
          }
        });
  }

  void receiver() {
    releaseController();
    page = "receiver";
    controller = false;
    base("客厅受控端");
    device = null;
    ScrollView scroll = new ScrollView(this);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(1);
    scroll.addView(content);
    boolean box = ReceiverRuntime.box(),
        wide = box && getResources().getConfiguration().orientation == 2;
    LinearLayout columns = new LinearLayout(this);
    columns.setOrientation(wide ? 0 : 1);
    content.addView(columns);
    LinearLayout overview = card(columns);
    overview.addView(text("本机供电与运行状态", 17));
    connection = text("等待服务状态", 14);
    overview.addView(connection);
    state = text(box ? "正在读取盒子状态…" : "正在读取电池与供电…", 21);
    overview.addView(state);
    powerAdvice = text("正在读取本机保护策略…", 13);
    overview.addView(powerAdvice);
    button(controlRow(overview), "供电与设备详情", () -> details());
    if (!box) button(controlRow(overview), "充电与待机设置", () -> settings());
    LinearLayout music = card(columns);
    music.addView(text("音乐与实体按键", 17));
    playerInfo = text("本机音乐 APP：读取中…", 15);
    music.addView(playerInfo);
    button(controlRow(music), "选择音乐 APP", () -> choosePlayer());
    if (box) {
      keyBindingInfo = text("本机遥控器播放键：读取中…", 13);
      music.addView(keyBindingInfo);
      button(controlRow(music), "识别与绑定按键", () -> localKeyTools());
    } else music.addView(text("播放曲目由音乐 APP 自己决定。控制端读取本机选择，不替本机修改。", 13));
    LinearLayout manage = card(columns);
    manage.addView(text("连接与本机管理", 17));
    button(controlRow(manage), "安全与配对", () -> securitySettings());
    button(controlRow(manage), "后台与待机指南", () -> powerGuide());
    button(controlRow(manage), "启动／恢复服务", () -> bootstrap());
    button(controlRow(manage), "切换模式", () -> chooser());
    manage.addView(text("本机可开启配对、查看并撤销授权；已配对设备可使用保存的地址连接。", 13));
    if (wide)
      for (LinearLayout column : new LinearLayout[] {overview, music, manage}) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(dp(4), dp(6), dp(4), dp(8));
        column.setLayoutParams(lp);
      }
    danger(
        button(
            controlRow(content),
            "停止受控服务",
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("停止受控服务？")
                    .setMessage(box ? "停止远程控制，保留音乐 APP 和本机配置。" : "恢复本工具暂停的充电，并关闭受控端开机自动启动。")
                    .setPositiveButton("停止", (d, w) -> stopReceiver())
                    .setNegativeButton("取消", null)
                    .show()));
    content.addView(
        text(
            box
                ? "盒子无电池。关闭 HDMI、系统待机与停止画面传输是不同操作；音乐、USB 和唤醒表现需按实际设备验证。"
                : "充电与待机策略在本机设置。息屏不等于深度休眠，暂停充电不等于硬件旁路。",
            13));
    message = text("控制端可查看本机状态，并操作屏幕。系统故障日志使用独立工具管理。", 13);
    content.addView(message);
    try {
      String code = prefs.getString("receiverCode", "");
      if (!code.isEmpty()) device = new DeviceClient(code);
      if (prefs.getBoolean("receiver", false))
        startForegroundService(new Intent(this, ResidentService.class));
    } catch (Exception ignored) {
    }
    poll();
  }

  void control() {
    page = "control";
    controller = true;
    videoEnabled = prefs.getBoolean("showVideo", false);
    JSONObject previous = latest;
    base(savedDevices.name(device));
    latest = previous;
    frameReady = false;
    frameFormat = false;
    boolean wide =
        getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            && videoEnabled;
    splitController = wide;
    JSONObject profile = savedDevices.find(device.pin);
    if (previous == null && profile != null && profile.optString("type").equals("box")) {
      frameWidth = 1920;
      frameHeight = 1080;
    }
    LinearLayout panel = new LinearLayout(this);
    panel.setOrientation(1);
    videoBox = new FrameLayout(this);
    videoBox.setBackground(shape(Color.rgb(8, 14, 21)));
    if (wide) {
      LinearLayout body = new LinearLayout(this);
      body.setOrientation(0);
      root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
      body.addView(videoBox, new LinearLayout.LayoutParams(0, -1, 1));
      ScrollView controls = new ScrollView(this);
      controls.setFillViewport(true);
      controls.addView(panel);
      body.addView(
          controls,
          new LinearLayout.LayoutParams(
              Math.min(dp(312), getResources().getDisplayMetrics().widthPixels * 40 / 100), -1));
    } else {
      ScrollView scroll = new ScrollView(this);
      scroll.setFillViewport(true);
      root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
      scroll.addView(panel);
    }
    LinearLayout summary = card(panel);
    connection = text("正在连接被控端…", 13);
    summary.addView(connection);
    state = text("正在读取被控端状态…", 14);
    summary.addView(state);
    LinearLayout viewbar = controlRow(panel);
    TextView label = text("被控端画面", 14);
    viewbar.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    videoOn = button(viewbar, "开启画面", () -> setVideoEnabled(!videoEnabled));
    videoOn.setLayoutParams(new LinearLayout.LayoutParams(dp(124), dp(48)));
    if (!wide) panel.addView(videoBox, new LinearLayout.LayoutParams(-1, dp(220)));
    surface = new SurfaceView(this);
    final SurfaceView ownedSurface = surface;
    videoBox.addView(surface, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
    videoBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitVideo());
    videoPlaceholder = text("画面已关闭\n音乐与其他控制仍可使用", 15);
    videoPlaceholder.setGravity(Gravity.CENTER);
    videoBox.addView(videoPlaceholder, new FrameLayout.LayoutParams(-1, -1));
    surface
        .getHolder()
        .addCallback(
            new android.view.SurfaceHolder.Callback() {
              public void surfaceCreated(android.view.SurfaceHolder h) {
                if (surface == ownedSurface && visible && videoEnabled) startVideo();
              }

              public void surfaceChanged(android.view.SurfaceHolder h, int f, int w, int t) {}

              public void surfaceDestroyed(android.view.SurfaceHolder h) {
                if (surface == ownedSurface) stopVideo();
              }
            });
    surface.setOnTouchListener(
        (v, e) -> {
          if (!frameReady || !videoEnabled) return true;
          if (e.getAction() == MotionEvent.ACTION_DOWN)
            v.getParent().requestDisallowInterceptTouchEvent(true);
          else if (e.getAction() == MotionEvent.ACTION_UP
              || e.getAction() == MotionEvent.ACTION_CANCEL)
            v.getParent().requestDisallowInterceptTouchEvent(false);
          float[] p = point(e);
          if (e.getAction() == MotionEvent.ACTION_DOWN) {
            downX = p[0];
            downY = p[1];
            downTime = System.currentTimeMillis();
            return true;
          }
          if (e.getAction() == MotionEvent.ACTION_UP) {
            try {
              JSONObject payload = new JSONObject().put("x", (int) downX).put("y", (int) downY);
              if (Math.hypot(p[0] - downX, p[1] - downY) > 25)
                payload.put("action", "swipe").put("x2", (int) p[0]).put("y2", (int) p[1]);
              else
                payload.put(
                    "action", System.currentTimeMillis() - downTime > 600 ? "long_press" : "tap");
              send(payload);
            } catch (Exception ignored) {
            }
          }
          return true;
        });
    LinearLayout music = card(panel);
    music.addView(text("音乐", 16));
    LinearLayout transport = controlRow(music);
    transportButton(transport, "previous", "上一首", () -> act("previous"));
    mediaButton = transportButton(transport, "play", "连接并播放", () -> togglePlayback());
    mediaButton.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x337bdfb0),
            shape(Color.rgb(35, 86, 79)),
            null));
    transportButton(transport, "next", "下一首", () -> act("next"));
    playerInfo = text("被控端音乐 APP：读取中…", 13);
    music.addView(playerInfo);
    LinearLayout volume = card(panel);
    volume.addView(text("系统音量", 15));
    LinearLayout r = controlRow(volume);
    button(r, "音量 −", () -> act("volume_down"));
    button(r, "音量 ＋", () -> act("volume_up"));
    volume.addView(text("USB 独占输出时可能无效", 11));
    LinearLayout operations = card(panel);
    operations.addView(text("设备操作", 16));
    r = controlRow(operations);
    sleepButton =
        button(
            r,
            "唤醒",
            () ->
                act(
                    latest != null && latest.optString("screen").equals("Awake")
                        ? "sleep"
                        : "wake"));
    displayOffButton =
        button(
            r,
            "关闭 HDMI",
            () ->
                act(
                    latest != null && latest.optString("displayOutput").equals("Off")
                        ? "wake"
                        : "display_off"));
    displayOffButton.setVisibility(View.GONE);
    r = controlRow(operations);
    button(r, "左滑", () -> act("swipe_left"));
    button(r, "右滑", () -> act("swipe_right"));
    r = controlRow(operations);
    button(r, "返回", () -> act("back"));
    button(r, "主页", () -> act("home"));
    danger(button(r, "断开", () -> disconnectController(false)));
    keyBindingInfo = text("", 12);
    keyBindingInfo.setVisibility(View.GONE);
    operations.addView(keyBindingInfo);
    message = text("断开远控不停止音乐。充电、待机策略与按键绑定在被控端本机设置。", 12);
    panel.addView(message);
    updateVideoMode();
    if (latest != null) renderStatus(latest);
    poll();
    ui.removeCallbacks(mediaPollTask);
    ui.post(mediaPollTask);
  }

  Button danger(Button b) {
    b.setTextColor(Color.rgb(255, 174, 184));
    b.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33ff929b),
            shape(Color.rgb(57, 36, 46)),
            null));
    return b;
  }

  Button transportButton(LinearLayout row, String icon, String description, Runnable action) {
    Button b = button(row, "", action);
    b.setMinHeight(dp(64));
    b.setContentDescription(description);
    setTransportIcon(b, icon);
    return b;
  }

  void setTransportIcon(Button b, String icon) {
    ControlIcon draw =
        new ControlIcon(icon, icon.equals("play") || icon.equals("pause") ? accent : ink);
    android.graphics.drawable.LayerDrawable layer =
        new android.graphics.drawable.LayerDrawable(
            new android.graphics.drawable.Drawable[] {draw});
    layer.setLayerSize(0, dp(30), dp(30));
    layer.setLayerGravity(0, Gravity.CENTER);
    b.setForeground(layer);
  }

  void togglePlayback() {
    final boolean wantPause =
        latest != null && QuickPlayState.playing(latest.optString("playbackState"));
    final DeviceClient d = device;
    if (d == null || mediaPending) return;
    mediaPending = true;
    if (mediaButton != null) mediaButton.setEnabled(false);
    jobs.execute(
        () -> {
          try {
            JSONObject s = d.request("/status", null);
            if (!s.optBoolean("mediaToggleSupported"))
              throw new IOException("请先更新受控端到 0.8.9 或更新版本");
            if (s.optString("playerPackage").isEmpty()) throw new IOException("请在被控端本机设置音乐 APP");
            if (device != d || !alive) return;
            boolean audioOnly = QuickPlay.audioOnly(this, d.pin);
            if (audioOnly && !s.optBoolean("audioOnlySupported"))
              throw new IOException("请更新盒子受控端到 0.9.3");
            if (!audioOnly
                && !wantPause
                && (s.optString("screen").equals("Asleep")
                    || s.optString("screen").equals("Dozing")))
              d.request("/action", new JSONObject().put("action", "wake"));
            JSONObject command =
                new JSONObject().put("action", "media_toggle").put("audioOnly", audioOnly);
            if (s.optBoolean("mediaIntentSupported"))
              command.put("desiredState", wantPause ? "paused" : "playing");
            long id = d.request("/action", command).optLong("mediaRequestId");
            long until = android.os.SystemClock.elapsedRealtime() + 20000;
            while (device == d && alive && android.os.SystemClock.elapsedRealtime() < until) {
              Thread.sleep(600);
              JSONObject result = d.request("/status", null);
              ui.post(
                  () -> {
                    if (device == d && controller) {
                      latest = result;
                      renderStatus(result);
                      QuickPlay.record(this, d.pin, result);
                    }
                  });
              int completed =
                  QuickPlayState.completed(
                      id,
                      result.optLong("mediaRequestId"),
                      result.optBoolean("playbackBusy"),
                      result.optString("playbackRequest"));
              if (completed < 0)
                throw new IOException(result.optString("playbackRequest", "本次操作未确认"));
              if (completed > 0) {
                note(result.optString("playbackRequest"));
                return;
              }
            }
            if (device == d && alive) throw new IOException("操作确认超时");
          } catch (Exception e) {
            if (device == d && alive) note("播放控制失败：" + e.getMessage());
          } finally {
            ui.post(
                () -> {
                  mediaPending = false;
                  if (mediaButton != null) mediaButton.setEnabled(true);
                });
          }
        });
  }

  boolean openQuickIntent(Intent intent) {
    String pin = intent.getStringExtra("quickPin");
    if (android.service.quicksettings.TileService.ACTION_QS_TILE_PREFERENCES.equals(
        intent.getAction())) pin = QuickPlay.target(this, -1);
    if (pin == null) return false;
    JSONObject entry = savedDevices.find(pin);
    if (entry != null) openSaved(entry);
    else {
      connectPage();
      note("快捷入口绑定的设备已删除，请重新选择");
    }
    return true;
  }

  public void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    openQuickIntent(intent);
  }

  LinearLayout controlRow(LinearLayout parent) {
    LinearLayout r = new LinearLayout(this);
    r.setOrientation(LinearLayout.HORIZONTAL);
    parent.addView(r);
    return r;
  }

  void updateVideoMode() {
    if (videoOn != null) videoOn.setText(videoEnabled ? "关闭画面" : "开启画面");
    boolean wide = splitController;
    if (videoBox != null) {
      videoBox.setVisibility(videoEnabled || wide ? View.VISIBLE : View.GONE);
      if (!wide) {
        int width = Math.max(dp(100), getResources().getDisplayMetrics().widthPixels - dp(24));
        int height =
            Math.max(
                dp(100),
                Math.min(
                    (int) (width * (double) Math.max(1, frameHeight) / Math.max(1, frameWidth)),
                    getResources().getDisplayMetrics().heightPixels * 55 / 100));
        videoBox.setLayoutParams(new LinearLayout.LayoutParams(-1, height));
      }
    }
    if (videoPlaceholder != null)
      videoPlaceholder.setVisibility(videoEnabled ? View.GONE : View.VISIBLE);
    if (surface != null) surface.setVisibility(videoEnabled ? View.VISIBLE : View.INVISIBLE);
  }

  void setVideoEnabled(boolean enabled) {
    videoEnabled = enabled;
    prefs.edit().putBoolean("showVideo", enabled).apply();
    if (controller && getResources().getConfiguration().orientation == 2) {
      control();
      return;
    }
    updateVideoMode();
    if (enabled) startVideo();
    else {
      stopVideo();
      note("画面已关闭，停止编码和视频传输；被控端状态每 10 秒更新。");
    }
    poll();
  }

  float[] point(MotionEvent e) {
    float sx = Math.max(1, surface.getWidth()), sy = Math.max(1, surface.getHeight());
    return new float[] {
      Math.max(0, Math.min(remoteWidth - 1, e.getX() * remoteWidth / sx)),
      Math.max(0, Math.min(remoteHeight - 1, e.getY() * remoteHeight / sy))
    };
  }

  void fitVideo() {
    if (surface == null || videoBox == null) return;
    int height = videoBox.getHeight(), width = videoBox.getWidth();
    if (height < 1 || width < 1) return;
    int fw = Math.min(width, (int) (height * (double) frameWidth / frameHeight)),
        fh = (int) (fw * (double) frameHeight / frameWidth);
    if (surface.getWidth() != fw || surface.getHeight() != fh)
      surface.setLayoutParams(new FrameLayout.LayoutParams(fw, fh, Gravity.CENTER));
  }

  void startVideo() {
    if (!controller
        || !visible
        || !videoEnabled
        || device == null
        || surface == null
        || !surface.getHolder().getSurface().isValid()
        || video != null) return;
    frameReady = false;
    frameFormat = false;
    video =
        new VideoSession(
            device,
            surface.getHolder().getSurface(),
            new VideoSession.Listener() {
              public void message(VideoSession sender, String s) {
                ui.post(
                    () -> {
                      if (video == sender && videoEnabled && message != null) {
                        message.setText(s);
                        if (!s.equals("画面已连接")) frameReady = false;
                      }
                    });
              }

              public void format(VideoSession sender, int w, int h, int dw, int dh, int rotation) {
                ui.post(
                    () -> {
                      if (video != sender || !videoEnabled) return;
                      frameWidth = w;
                      frameHeight = h;
                      remoteWidth = dw;
                      remoteHeight = dh;
                      remoteRotation = rotation;
                      frameFormat = true;
                      frameReady = true;
                      updateVideoMode();
                      fitVideo();
                    });
              }
            });
    video.start();
  }

  void stopVideo() {
    VideoSession old = video;
    video = null;
    frameReady = false;
    frameFormat = false;
    if (old != null) {
      final DeviceClient d = old.device;
      old.stop();
      if (!jobs.isShutdown())
        jobs.execute(
            () -> {
              try {
                d.request(
                    "/action", new JSONObject().put("action", "video_stop").put("session", old.id));
              } catch (Exception ignored) {
              }
            });
    }
  }

  public void onConfigurationChanged(android.content.res.Configuration config) {
    super.onConfigurationChanged(config);
    if (controller) control();
    else if (page.equals("receiver")) receiver();
    else if (page.equals("connect")) connectPage();
    else chooser();
  }

  void act(String a) {
    try {
      send(new JSONObject().put("action", a));
    } catch (Exception ignored) {
    }
  }

  void choosePlayer() {
    final DeviceClient d = device;
    if (controller || d == null || !d.host.equals("127.0.0.1")) {
      note("请在被控端本机设置音乐 APP");
      return;
    }
    boolean box = latest != null && latest.optString("deviceType").equals("box");
    String[] names =
        box
            ? new String[] {"网易云音乐 TV 版", "网易云音乐手机版", "海贝音乐", "不使用"}
            : new String[] {"网易云音乐", "海贝音乐", "不使用"};
    new AlertDialog.Builder(this)
        .setTitle("设置本机的一键播放 APP")
        .setItems(
            names,
            (dialog, index) -> {
              String pkg =
                  box
                      ? (index == 0
                          ? "com.netease.cloudmusic.tv"
                          : index == 1
                              ? "com.netease.cloudmusic"
                              : index == 2 ? "com.hiby.music" : "")
                      : (index == 0
                          ? "com.netease.cloudmusic"
                          : index == 1 ? "com.hiby.music" : "");
              jobs.execute(
                  () -> {
                    if (controller || device != d) return;
                    try {
                      d.request("/config", new JSONObject().put("playerPackage", pkg));
                      JSONObject s = d.request("/status", null);
                      ui.post(
                          () -> {
                            if (device == d) {
                              latest = s;
                              renderStatus(s);
                              note(pkg.isEmpty() ? "已关闭一键播放" : "一键播放 APP 已设置；曲目由音乐 APP 自己决定");
                            }
                          });
                    } catch (Exception e) {
                      note("设置音乐 APP 失败：" + e.getMessage());
                    }
                  });
            })
        .show();
  }

  void oneTap() {
    if (latest == null) {
      note("正在读取被控端配置，请稍候");
      return;
    }
    if (latest.optString("playerPackage", "").isEmpty()) {
      note("请在被控端本机设置一键播放的音乐 APP");
      return;
    }
    if (videoEnabled) setVideoEnabled(false);
    note("正在打开已配置的音乐 APP 并请求播放…");
    act("one_tap");
  }

  void localKeyTools() {
    if (controller || device == null || !device.host.equals("127.0.0.1")) {
      note("请在受控端本机设置遥控器按键");
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("本机遥控器按键工具")
        .setItems(
            new String[] {"识别所有实体按键", "绑定已识别的按键", "解除播放键绑定"},
            (dialog, index) -> {
              if (index == 0) inspectRemoteKeys();
              else if (index == 1) captureRemoteKey();
              else clearRemoteKey();
            })
        .setNegativeButton("关闭", null)
        .show();
  }

  void clearRemoteKey() {
    final DeviceClient d = device;
    if (controller || d == null || !d.host.equals("127.0.0.1")) return;
    new AlertDialog.Builder(this)
        .setTitle("解除本机播放键绑定？")
        .setMessage("取消本工具的实体键一键播放，不修改遥控器原有功能。")
        .setPositiveButton(
            "解除绑定",
            (dialog, index) ->
                jobs.execute(
                    () -> {
                      if (controller || device != d) return;
                      try {
                        d.request("/config", new JSONObject().put("oneTapKey", ""));
                        JSONObject s = d.request("/status", null);
                        ui.post(
                            () -> {
                              if (device == d) {
                                latest = s;
                                renderStatus(s);
                                note("已解除本机播放键绑定");
                              }
                            });
                      } catch (Exception e) {
                        note("解除绑定失败：" + e.getMessage());
                      }
                    }))
        .setNegativeButton("取消", null)
        .show();
  }

  void inspectRemoteKeys() {
    final DeviceClient d = device;
    if (controller
        || d == null
        || !d.host.equals("127.0.0.1")
        || latest == null
        || !latest.optString("deviceType").equals("box")) {
      note("按键识别只能在受控端本机操作");
      return;
    }
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(1);
    body.setPadding(dp(16), dp(8), dp(16), dp(8));
    body.setBackgroundColor(background);
    body.addView(
        text(
            "逐个按遥控器按钮，查看它实际发出的系统键值。也会识别 USB 键盘；电源键可能令盒子待机并中断连接。\n"
                + "只观察，不绑定；原按键功能仍会执行。点击下面已识别的按键即可选择绑定，也可关闭后在“绑定已识别的按键”中选择。原功能不会被屏蔽。识别 60"
                + " 秒后自动停止，候选仅保存在本次 APP 运行的内存中。",
            14));
    TextView progress = text("正在启动识别…", 14);
    body.addView(progress);
    ScrollView scroll = new ScrollView(this);
    LinearLayout keys = new LinearLayout(this);
    keys.setOrientation(1);
    scroll.addView(keys);
    body.addView(scroll, new LinearLayout.LayoutParams(-1, dp(220)));
    final java.util.HashSet<String> shown = new java.util.HashSet<>();
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(controller ? "被控端遥控器按键识别" : "本机遥控器按键识别")
            .setView(body)
            .setPositiveButton("关闭", null)
            .create();
    dialog.setOnDismissListener(
        v -> {
          if (!jobs.isShutdown())
            jobs.execute(
                () -> {
                  try {
                    d.request("/action", new JSONObject().put("action", "inspect_keys_stop"));
                  } catch (Exception ignored) {
                  }
                });
        });
    dialog.show();
    jobs.execute(
        () -> {
          try {
            d.request("/action", new JSONObject().put("action", "inspect_keys_start"));
            while (alive && visible && device == d && dialog.isShowing()) {
              JSONObject s = d.request("/status", null),
                  inspection = s.optJSONObject("keyInspection");
              if (inspection == null) throw new IOException("请先更新受控端至 0.8.7");
              int seconds = inspection.optInt("seconds");
              JSONArray list = inspection.optJSONArray("events");
              java.util.ArrayList<String> candidates = new java.util.ArrayList<>();
              if (list != null)
                for (int i = 0; i < list.length(); i++) {
                  JSONObject event = list.getJSONObject(i);
                  String key = event.optString("key");
                  if (event.optBoolean("bindingCandidate")
                      && KeyBinding.accepts(key)
                      && !candidates.contains(key)) candidates.add(key);
                }
              ui.post(
                  () -> {
                    if (dialog.isShowing()) {
                      progress.setText(
                          seconds > 0
                              ? (shown.isEmpty() ? "等待实体按键 · 剩余 " : "点选按键即可绑定 · 剩余 ")
                                  + seconds
                                  + " 秒"
                              : "识别已停止，仍可选择下面的按键");
                      for (String key : candidates) {
                        identifiedKeys.add(key);
                        while (identifiedKeys.size() > 32)
                          identifiedKeys.remove(identifiedKeys.iterator().next());
                        if (shown.add(key))
                          button(
                              controlRow(keys),
                              key + " · 选择绑定",
                              () -> {
                                dialog.dismiss();
                                confirmRemoteKeyBinding(key);
                              });
                      }
                    }
                  });
              if (seconds <= 0) return;
              Thread.sleep(500);
            }
          } catch (Exception e) {
            ui.post(
                () -> {
                  if (dialog.isShowing()) progress.setText("识别失败：" + e.getMessage());
                });
          } finally {
            try {
              d.request("/action", new JSONObject().put("action", "inspect_keys_stop"));
            } catch (Exception ignored) {
            }
          }
        });
  }

  void captureRemoteKey() {
    if (controller
        || device == null
        || !device.host.equals("127.0.0.1")
        || latest == null
        || !latest.optString("deviceType").equals("box")) {
      note("按键绑定只能在受控端本机操作");
      return;
    }
    if (identifiedKeys.isEmpty()) {
      new AlertDialog.Builder(this)
          .setTitle("先识别要绑定的按键")
          .setMessage("本次尚无识别记录。开始识别后按一次遥控器目标键，再点屏幕中的按键名称选择绑定，无需再次按键。候选不写入磁盘，退出 APP 后需重新识别。")
          .setPositiveButton("开始识别", (a, b) -> inspectRemoteKeys())
          .setNegativeButton("取消", null)
          .show();
      return;
    }
    String[] keys = identifiedKeys.toArray(new String[0]);
    new AlertDialog.Builder(this)
        .setTitle("选择已识别的按键")
        .setItems(keys, (a, index) -> confirmRemoteKeyBinding(keys[index]))
        .setNeutralButton("识别其他按键", (a, b) -> inspectRemoteKeys())
        .setNegativeButton("取消", null)
        .show();
  }

  void confirmRemoteKeyBinding(String key) {
    final DeviceClient d = device;
    if (controller || d == null || !d.host.equals("127.0.0.1") || !KeyBinding.accepts(key)) return;
    new AlertDialog.Builder(this)
        .setTitle("确认绑定遥控器按键")
        .setMessage(
            "绑定 "
                + key
                + "。首次启动需盒子待机或 HDMI 关闭；音乐播放中再按暂停，暂停后再按恢复。\n\n绑定不会屏蔽原功能，可能同时触发原操作；请优先选择不常用的键，避免电源键。")
        .setPositiveButton(
            "绑定",
            (a, b) ->
                jobs.execute(
                    () -> {
                      if (controller || device != d) return;
                      try {
                        d.request("/config", new JSONObject().put("oneTapKey", key));
                        JSONObject s = d.request("/status", null);
                        ui.post(
                            () -> {
                              if (device == d) {
                                latest = s;
                                renderStatus(s);
                                note("遥控器一键播放键已绑定：" + key);
                              }
                            });
                      } catch (Exception e) {
                        note("绑定失败：" + e.getMessage());
                      }
                    }))
        .setNegativeButton("取消", null)
        .show();
  }

  void send(JSONObject a) {
    final DeviceClient d = device;
    if (d == null) {
      note("请先启动或连接受控端");
      return;
    }
    actions.execute(
        () -> {
          if (device != d) return;
          try {
            JSONObject payload = a;
            String action = a.optString("action");
            if (action.equals("swipe_left") || action.equals("swipe_right")) {
              JSONObject s = d.request("/status", null);
              if (device != d) return;
              int w = s.getInt("width"), h = s.getInt("height");
              if (w < 2 || h < 2 || w > 5000 || h > 5000) throw new IOException("无法取得被控端屏幕尺寸");
              boolean left = action.equals("swipe_left");
              payload =
                  new JSONObject()
                      .put("action", "swipe")
                      .put("x", w * (left ? 3 : 1) / 4)
                      .put("y", h / 2)
                      .put("x2", w * (left ? 1 : 3) / 4)
                      .put("y2", h / 2);
            }
            d.request("/action", payload);
            note("已发送操作");
          } catch (Exception e) {
            note("操作未完成：" + e.getMessage());
          }
        });
  }

  String reason(JSONObject s) {
    switch (s.optString("reason")) {
      case "external_power":
        return "盒子外接电源，不适用电池保护";
      case "target":
        return "达到电量上限，暂停充电";
      case "temperature":
        return "温度保护，暂停充电";
      case "charging":
        return "允许系统充电";
      case "unplugged":
        return "未连接外部供电";
      case "system":
        return "保护已关闭，系统管理充电";
      case "fault":
        return "保护异常：" + s.optString("fault");
      default:
        return "启动中";
    }
  }

  String statusText(JSONObject s) {
    if (s.optString("deviceType").equals("box"))
      return "被控端盒子使用外接电源，无可管理电池\n芯片温度："
          + String.format(Locale.CHINA, "%.1f℃", s.optInt("socTemp") / 1000.0)
          + "\nUSB 设备："
          + s.optJSONArray("usbDevices")
          + "\n地址："
          + s.optJSONArray("addresses");
    boolean online = s.optInt("usbOnline") == 1;
    String input = online ? "外部供电在线 · " + s.optString("usbType") : "外部供电未在线";
    int voltage = s.optInt("inputVoltage"), current = s.optInt("inputCurrent");
    if (online && voltage > 0)
      input +=
          String.format(Locale.CHINA, "  %.2fV / %.0fmA", voltage / 1000000.0, current / 1000.0);
    return input
        + String.format(
            Locale.CHINA,
            "\n电量 %d%% · %.1f℃ · 电池电流 %+.0fmA",
            s.optInt("capacity"),
            s.optInt("temp") / 10.0,
            s.optInt("current") / 1000.0)
        + "\n"
        + reason(s)
        + (s.optBoolean("owned") ? "（本工具暂停）" : "")
        + (controller
            ? ""
            : "\n电池电流负值表示充电，正值表示放电\nUSB 设备："
                + s.optJSONArray("usbDevices")
                + "\n屏幕："
                + s.optString("screen")
                + "\n地址："
                + s.optJSONArray("addresses"));
  }

  final Runnable pollTask =
      new Runnable() {
        public void run() {
          poll();
        }
      };

  void poll() {
    ui.removeCallbacks(pollTask);
    if (!alive || !visible) return;
    ui.postDelayed(pollTask, controller && !videoEnabled ? 10000 : 3000);
    final DeviceClient d = device;
    if (d == null || polling) return;
    polling = true;
    jobs.execute(
        () -> {
          try {
            if (device != d) return;
            JSONObject s = d.request("/status", null);
            if (device != d) return;
            VideoSession active = video;
            if (controller && videoEnabled && active != null)
              d.request(
                  "/action",
                  new JSONObject().put("action", "video_ping").put("session", active.id));
            ui.post(
                () -> {
                  if (device == d && state != null) {
                    boolean rotated =
                        latest != null
                            && (latest.optInt("width") != s.optInt("width")
                                || latest.optInt("height") != s.optInt("height")
                                || latest.optInt("rotation") != s.optInt("rotation"));
                    latest = s;
                    renderStatus(s);
                    if (controller) {
                      savedDevices.save(d, null, s.optString("deviceType"));
                      QuickPlay.record(this, d.pin, s);
                      fitVideo();
                      if (video != null)
                        frameReady =
                            frameFormat
                                && remoteWidth == s.optInt("width")
                                && remoteHeight == s.optInt("height")
                                && remoteRotation == s.optInt("rotation");
                      if (rotated
                          && video != null
                          && (remoteWidth != s.optInt("width")
                              || remoteHeight != s.optInt("height")
                              || remoteRotation != s.optInt("rotation"))) {
                        frameReady = false;
                        note("屏幕方向变化，正在适配画面…");
                      }
                    } else incomingPair(s);
                  }
                });
          } catch (Exception e) {
            if (controller && device == d) QuickPlay.record(this, d.pin, false);
            if (controller && e instanceof DeviceClient.AuthorizationException) {
              ui.post(
                  () -> {
                    if (device == d) {
                      disconnectController(false);
                      note(e.getMessage());
                    }
                  });
              return;
            }
            ui.post(
                () -> {
                  if (device == d && state != null)
                    state.setText(
                        (controller ? "与被控端连接中断，正在重连…\n" : "服务连接中断，正在重连…\n") + e.getMessage());
                });
            if (device == d && controller && System.currentTimeMillis() - lastDiscovery > 20000) {
              lastDiscovery = System.currentTimeMillis();
              if (d.rediscover())
                ui.post(
                    () -> {
                      if (device == d) {
                        savedDevices.save(d, null, null);
                        stopVideo();
                        note("已找到受控端新地址：" + d.host + "，正在恢复连接…");
                        startVideo();
                      }
                    });
            }
          } finally {
            polling = false;
          }
        });
  }

  JSONObject powerConfig(JSONObject s) {
    JSONObject c = s == null ? null : s.optJSONObject("config");
    return c == null ? new JSONObject() : c;
  }

  String powerReminder(JSONObject s) {
    if (s.optString("deviceType").equals("box"))
      return "本机为外接供电盒子，无电池保护。无屏播放可仅关闭 HDMI；整机待机可能影响网络、音乐与 USB。详见“充电与待机”。";
    JSONObject c = powerConfig(s);
    String reason = s.optString("reason");
    if (reason.equals("fault")) return "提醒：充电保护异常，请查看故障信息并检查 ROOT 与供电；界面显示不代表驱动已成功停充。";
    if (reason.equals("temperature")) return "提醒：已触发温度保护，请检查散热；降到恢复温度后由本机策略决定是否恢复充电。";
    if (!c.optBoolean("protect", true)) return "提醒：充电保护已关闭，由系统管理充电；长期插电时请确认这是你的选择。";
    return "本机保护：达到 "
        + c.optInt("high", 80)
        + "% 暂停，降至 "
        + c.optInt("low", 60)
        + "% 恢复；系统无操作 "
        + c.optInt("idle", 120)
        + " 秒熄屏。息屏不保证深度休眠。";
  }

  String powerDetails(JSONObject s) {
    if (s == null) return "等待设备状态，请稍后重试。";
    boolean box = s.optString("deviceType").equals("box");
    if (box)
      return "外接供电盒子，不读取模拟电池、不设置手机充电保护，也不改写盒子自动待机策略。\n\n"
          + "仅关闭 HDMI：关闭显示输出，不主动请求整机待机；可能中断 HDMI 音频。\n"
          + "整机待机：可能暂停网络、音乐或 USB，具体取决于固件。\n"
          + "关闭控制端画面：只停止画面传输，音乐继续。\n\n"
          + "USB 独占播放在这些状态间是否连续、真正休眠后的唤醒能力仍需实测。";
    JSONObject c = powerConfig(s);
    return "充电保护："
        + (c.optBoolean("protect", true) ? "开启" : "关闭，由系统管理充电")
        + "\n电量：达到 "
        + c.optInt("high", 80)
        + "% 暂停，降至 "
        + c.optInt("low", 60)
        + "% 恢复\n温度：达到 "
        + String.format(Locale.CHINA, "%.1f℃", c.optInt("hot", 420) / 10.0)
        + " 暂停，降至 "
        + String.format(Locale.CHINA, "%.1f℃", c.optInt("cool", 380) / 10.0)
        + " 后按策略恢复\n系统无操作熄屏："
        + c.optInt("idle", 120)
        + " 秒\n\n当前状态："
        + reason(s)
        + "\n\n暂停充电不等于硬件旁路；息屏不等于深度休眠，也不保证后台网络与音乐持续运行。设置仅在受控端本机修改。";
  }

  void powerInfo() {
    new AlertDialog.Builder(this)
        .setTitle(controller ? "被控端充电与待机 · 只读" : "本机充电与待机")
        .setMessage(powerDetails(latest))
        .setPositiveButton("关闭", null)
        .show();
  }

  void powerGuide() {
    if (controller) return;
    new AlertDialog.Builder(this)
        .setTitle("本机长期播放设置指南")
        .setMessage(
            "1. 手机：在“充电与待机”检查电量、温度保护和无操作熄屏时间；达到上限暂停充电是正常保护状态。盒子无电池，不适用这些参数。\n\n"
                + "2. 在系统应用设置中，允许客厅受控端和音乐 APP 后台运行、自启动；手机如有应用省电选项，按需设为无限制。不同系统名称不同，盒子可能没有这些选项。\n\n"
                + "3. 在本机设置一键播放的音乐 APP，先确认它能正常播放，再测试息屏后播放及远程唤醒。安全锁屏仍需正常解锁。\n\n"
                + "4. 盒子优先区分“关闭画面传输”“仅关闭 HDMI”“整机待机”，不要把它们当成同一状态。\n\n"
                + "5. 本机可在安全与配对中撤销连接，或停止受控服务。无需为日常控制开启无线调试。")
        .setPositiveButton(
            "应用系统设置",
            (d, w) -> {
              try {
                startActivity(
                    new Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + getPackageName())));
              } catch (Exception e) {
                note("请手动打开系统设置中的应用信息页面");
              }
            })
        .setNegativeButton("关闭", null)
        .show();
  }

  void settings() {
    final DeviceClient d = device;
    if (controller || d == null || !d.host.equals("127.0.0.1")) {
      note("充电与自动熄屏策略只能在受控端本机设置");
      return;
    }
    if (latest == null) {
      note("正在读取本机状态，请稍候");
      return;
    }
    if (latest.optString("deviceType").equals("box")) {
      new AlertDialog.Builder(this)
          .setTitle("本机供电与待机")
          .setMessage(powerDetails(latest))
          .setPositiveButton("设置指南", (dialog, index) -> powerGuide())
          .setNegativeButton("关闭", null)
          .show();
      return;
    }
    JSONObject c = powerConfig(latest);
    LinearLayout box = new LinearLayout(this);
    box.setPadding(dp(16), 0, dp(16), 0);
    box.setOrientation(1);
    box.setBackgroundColor(background);
    box.addView(
        text(
            "这些是本机长期策略，控制端仅查看。达到上限暂停、降至下限恢复，可避免在同一电量附近反复切换。开启保护时，电量与温度分别判断，任一达到上限都会暂停充电。\n"
                + "无操作熄屏会修改系统熄屏时间，并关闭插电常亮；停止服务时尝试恢复原设置。息屏不保证网络与音乐仍在运行。",
            14));
    Switch enabled = new Switch(this);
    enabled.setText("自动保护电池");
    enabled.setTextColor(ink);
    enabled.setChecked(c.optBoolean("protect", true));
    box.addView(enabled);
    String[] names = {"恢复充电电量 %", "暂停充电电量 %", "暂停充电温度 ℃", "恢复充电温度 ℃", "系统无操作熄屏秒数"};
    String[] keys = {"low", "high", "hot", "cool", "idle"};
    int[] defaults = {60, 80, 420, 380, 120};
    EditText[] fields = new EditText[5];
    for (int i = 0; i < 5; i++) {
      box.addView(text(names[i], 14));
      fields[i] = new EditText(this);
      fields[i].setTextColor(ink);
      fields[i].setInputType(8194);
      fields[i].setText(
          i == 2 || i == 3
              ? String.valueOf(c.optInt(keys[i], defaults[i]) / 10.0)
              : String.valueOf(c.optInt(keys[i], defaults[i])));
      box.addView(fields[i]);
    }
    TextView feedback =
        text(
            "电量 40–95%，上下限至少相差 5%；暂停温度 35–48℃、恢复温度至少低 1℃且不低于 30℃；熄屏 30–1800"
                + " 秒。以上是输入范围，不代表适合所有设备。暂停充电不等于旁路供电。",
            13);
    box.addView(feedback);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(box);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("本机充电与待机设置")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setNeutralButton("设置指南", (v, w) -> powerGuide())
            .setPositiveButton("保存", null)
            .create();
    dialog.show();
    dialog
        .getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(
            v -> {
              try {
                JSONObject next = new JSONObject().put("protect", enabled.isChecked());
                for (int i = 0; i < 5; i++)
                  next.put(
                      keys[i],
                      i == 2 || i == 3
                          ? (int)
                              Math.round(Double.parseDouble(fields[i].getText().toString()) * 10)
                          : Integer.parseInt(fields[i].getText().toString()));
                int low = next.getInt("low"),
                    high = next.getInt("high"),
                    hot = next.getInt("hot"),
                    cool = next.getInt("cool"),
                    idle = next.getInt("idle");
                if (low < 40
                    || high > 95
                    || high - low < 5
                    || hot < 350
                    || hot > 480
                    || cool < 300
                    || hot - cool < 10
                    || idle < 30
                    || idle > 1800) throw new IllegalArgumentException("请检查电量与温度上下限，以及熄屏秒数的范围");
                feedback.setText("正在保存本机设置…");
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                jobs.execute(
                    () -> {
                      if (controller || device != d) {
                        ui.post(() -> dialog.dismiss());
                        return;
                      }
                      try {
                        d.request("/config", next);
                        JSONObject s = d.request("/status", null);
                        ui.post(
                            () -> {
                              if (device == d) {
                                latest = s;
                                renderStatus(s);
                                note("本机策略已保存；请确认息屏后的音乐与唤醒表现");
                              }
                              dialog.dismiss();
                            });
                      } catch (Exception e) {
                        ui.post(
                            () -> {
                              if (dialog.isShowing()) {
                                feedback.setText("未保存：" + e.getMessage());
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                              }
                            });
                      }
                    });
              } catch (Exception e) {
                feedback.setText(
                    "未保存：" + (e instanceof NumberFormatException ? "请输入有效数字" : e.getMessage()));
              }
            });
  }

  void musicButtonSettings() {
    final DeviceClient d = device;
    if (d == null) return;
    new AlertDialog.Builder(this)
        .setTitle("当前设备的音乐按钮模式")
        .setSingleChoiceItems(
            new String[] {"普通播放／暂停", "关闭 HDMI 并播放／暂停（盒子）"},
            QuickPlay.audioOnly(this, d.pin) ? 1 : 0,
            (dialog, index) -> {
              if (index == 1 && (latest == null || !latest.optBoolean("audioOnlySupported"))) {
                note("该模式需要更新后的盒子受控端");
                return;
              }
              prefs.edit().putBoolean("musicAudioOnly." + d.pin, index == 1).apply();
              dialog.dismiss();
              note(index == 1 ? "音乐按钮将关闭 HDMI，并按实际状态播放／暂停；不发送投影仪关机指令" : "音乐按钮恢复普通播放／暂停");
            })
        .setNegativeButton("关闭", null)
        .show();
  }

  volatile boolean mediaPolling = false;
  final Runnable mediaPollTask =
      new Runnable() {
        public void run() {
          if (!alive || !visible || !controller || device == null) return;
          ui.postDelayed(this, 2000);
          final DeviceClient d = device;
          if (mediaPolling || latest == null || !latest.optBoolean("mediaStatusSupported")) return;
          mediaPolling = true;
          jobs.execute(
              () -> {
                try {
                  JSONObject s = d.request("/media-status", null);
                  ui.post(
                      () -> {
                        if (visible && controller && device == d && latest != null) {
                          for (String key :
                              new String[] {
                                "playbackState",
                                "playbackBusy",
                                "playbackRequest",
                                "mediaRequestId",
                                "playerPackage"
                              })
                            try {
                              latest.put(key, s.get(key));
                            } catch (Exception ignored) {
                            }
                          renderStatus(latest);
                          QuickPlay.record(MainActivity.this, d.pin, latest);
                        }
                      });
                } catch (Exception ignored) {
                } finally {
                  mediaPolling = false;
                }
              });
        }
      };

  void controlSettings() {
    new AlertDialog.Builder(this)
        .setItems(
            new String[] {
              "被控端充电与待机（只读）",
              "切换／管理设备",
              "返回模式选择",
              "被控端供电与设备详情",
              "控制端跟随系统方向",
              "断开连接",
              "断开并忘记本机配对",
              "快捷播放设备",
              "音乐按钮模式"
            },
            (d, i) -> {
              if (i == 0) powerInfo();
              else if (i == 1) connectPage();
              else if (i == 2) chooser();
              else if (i == 3) details();
              else if (i == 4)
                setRequestedOrientation(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
              else if (i == 5) disconnectController(false);
              else if (i == 8) musicButtonSettings();
              else if (i == 7) startActivity(new Intent(this, QuickPlayConfigActivity.class));
              else
                new AlertDialog.Builder(this)
                    .setTitle("忘记本机配对？")
                    .setMessage("只删除本控制端保存的连接凭据。要禁止此设备再次连接，请在受控端撤销授权。")
                    .setPositiveButton("忘记", (a, b) -> disconnectController(true))
                    .setNegativeButton("取消", null)
                    .show();
            })
        .show();
  }

  void disconnectController(boolean forget) {
    DeviceClient old = device;
    stopVideo();
    device = null;
    latest = null;
    ui.removeCallbacks(pollTask);
    ui.removeCallbacks(mediaPollTask);
    if (old != null) old.cancelPending();
    if (forget) {
      if (old != null) savedDevices.forget(old.pin);
      prefs.edit().remove("controllerCode").apply();
    }
    connectPage();
    note(forget ? "已断开并忘记本机配对。受控端音乐继续播放。" : "已断开，配对记录保留。受控端音乐与电池保护继续运行。");
  }

  void securitySettings() {
    final DeviceClient d = device;
    if (d == null || controller || !d.host.equals("127.0.0.1")) {
      note("请先在本机启动受控服务");
      return;
    }
    jobs.execute(
        () -> {
          try {
            JSONObject s = d.request("/security", null);
            ui.post(
                () -> {
                  if (device == d && page.equals("receiver")) showSecurity(d, s);
                });
          } catch (Exception e) {
            note("读取安全设置失败：" + e.getMessage());
          }
        });
  }

  void securityChange(DeviceClient d, JSONObject p) {
    jobs.execute(
        () -> {
          try {
            d.request("/security", p);
            note("安全设置已更新");
          } catch (Exception e) {
            note("安全设置未保存：" + e.getMessage());
          }
        });
  }

  void showSecurity(DeviceClient d, JSONObject s) {
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(1);
    box.setPadding(dp(16), dp(6), dp(16), dp(8));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(box);
    box.addView(text("仅能在受控端本机管理。关闭下列入口不停止音乐和电池保护。", 14));
    Switch web = new Switch(this);
    web.setText("WEB 调试服务（HTTP 未加密）");
    web.setChecked(s.optBoolean("webEnabled"));
    box.addView(web);
    box.addView(text("关闭后最多 15 秒停止网页服务；APP 控制不受影响。", 12));
    Switch discovery = new Switch(this);
    discovery.setText("允许局域网搜索发现");
    discovery.setChecked(s.optBoolean("discoveryEnabled"));
    box.addView(discovery);
    box.addView(text("关闭后已配对且地址不变时仍能直连；地址改变后需重新开启发现，建议路由器保留固定地址。", 12));
    Switch legacy = new Switch(this);
    legacy.setText("兼容旧配对凭据");
    legacy.setChecked(s.optBoolean("legacyAllowed"));
    box.addView(legacy);
    box.addView(text("旧版配对共用凭据，不能逐台撤销。请先让各控制端重新搜索配对，再关闭兼容；关闭会断开所有旧配对。", 12));
    long left = s.optLong("pairingSeconds");
    box.addView(text(left > 0 ? "新设备配对已开放，剩余约 " + left + " 秒" : "新设备配对已关闭", 14));
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("安全与配对")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton(
                "保存开关",
                (a, b) -> {
                  try {
                    securityChange(
                        d,
                        new JSONObject()
                            .put("webEnabled", web.isChecked())
                            .put("discoveryEnabled", discovery.isChecked())
                            .put("legacyAllowed", legacy.isChecked()));
                  } catch (Exception ignored) {
                  }
                })
            .create();
    LinearLayout pair = controlRow(box);
    button(
        pair,
        "允许配对三分钟",
        () -> {
          try {
            securityChange(d, new JSONObject().put("op", "pair_open"));
            dialog.dismiss();
            note("已请求开启配对，同时启用发现；请在控制端搜索，并核对六位数字。");
          } catch (Exception ignored) {
          }
        });
    button(
        pair,
        "关闭新配对",
        () -> {
          try {
            securityChange(d, new JSONObject().put("op", "pair_close"));
            dialog.dismiss();
          } catch (Exception ignored) {
          }
        });
    box.addView(text("独立授权的控制端", 16));
    JSONArray list = s.optJSONArray("controllers");
    if (list == null || list.length() == 0) box.addView(text("尚无独立授权记录；已有旧配对请重新配对。", 13));
    if (list != null)
      for (int i = 0; i < list.length(); i++) {
        JSONObject entry = list.optJSONObject(i);
        if (entry == null) continue;
        final String id = entry.optString("id"), name = entry.optString("name");
        box.addView(
            text(
                name
                    + " · "
                    + new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                        .format(new java.util.Date(entry.optLong("created"))),
                14));
        button(
            controlRow(box),
            "撤销此设备授权",
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("撤销 " + name + "？")
                    .setMessage("此设备将无法继续控制；其他独立配对及音乐播放不受影响。重新连接必须再次配对。")
                    .setPositiveButton(
                        "撤销",
                        (a, b) -> {
                          try {
                            securityChange(d, new JSONObject().put("op", "revoke").put("id", id));
                            dialog.dismiss();
                          } catch (Exception ignored) {
                          }
                        })
                    .setNegativeButton("取消", null)
                    .show());
      }
    dialog.show();
  }

  void stopReceiver() {
    if (device == null) return;
    jobs.execute(
        () -> {
          try {
            device.request("/action", new JSONObject().put("action", "stop"));
            prefs.edit().putBoolean("receiver", false).apply();
            stopService(new Intent(this, ResidentService.class));
            device = null;
            ui.post(
                () -> state.setText(ReceiverRuntime.box() ? "受控服务已停止。" : "服务已停止，恢复系统充电；开机不再启动。"));
            note("需要使用时点击“启动／恢复服务”");
          } catch (Exception e) {
            note("停止失败：" + e.getMessage());
          }
        });
  }

  void bootstrap() {
    note("正在初始化受控端，请允许 ROOT 授权…");
    jobs.execute(
        () -> {
          try {
            String raw = ReceiverRuntime.install(this);
            int a = raw.indexOf('{'), z = raw.lastIndexOf('}');
            if (a < 0 || z < a) throw new IOException("初始化失败");
            JSONObject secret = new JSONObject(raw.substring(a, z + 1));
            String code =
                DeviceClient.code(
                    "127.0.0.1", secret.getString("token"), secret.getString("fingerprint"));
            device = new DeviceClient(code);
            prefs.edit().putString("receiverCode", code).putBoolean("receiver", true).commit();
            startForegroundService(new Intent(this, ResidentService.class));
            note("受控端已启动，可在安全与配对中允许新设备");
            ui.postDelayed(() -> poll(), 1500);
          } catch (Exception e) {
            note("启动失败：" + e.getMessage());
          }
        });
  }

  public void onResume() {
    super.onResume();
    visible = true;
    poll();
    startVideo();
    ui.removeCallbacks(mediaPollTask);
    ui.post(mediaPollTask);
  }

  public void onPause() {
    visible = false;
    stopVideo();
    ui.removeCallbacks(pollTask);
    ui.removeCallbacks(mediaPollTask);
    super.onPause();
  }

  public void onDestroy() {
    alive = false;
    stopVideo();
    ui.removeCallbacksAndMessages(null);
    jobs.shutdown();
    actions.shutdown();
    super.onDestroy();
  }
}
