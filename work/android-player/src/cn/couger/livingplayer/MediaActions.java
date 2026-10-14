package cn.couger.livingplayer;

/** Launches an allowlisted app and uses Android's system media session, without account APIs. */
final class MediaActions {
  private MediaActions() {}

  static String name(String pkg) {
    switch (pkg) {
      case "com.netease.cloudmusic":
        return "网易云音乐手机版";
      case "com.netease.cloudmusic.tv":
        return "网易云音乐 TV 版";
      case "com.hiby.music":
        return "海贝音乐";
      case "":
        return "未设置（请在被控端本机设置）";
      default:
        return "未知 APP";
    }
  }

  static boolean allowed(String pkg) {
    return "com.netease.cloudmusic".equals(pkg)
        || "com.netease.cloudmusic.tv".equals(pkg)
        || "com.hiby.music".equals(pkg);
  }

  static int stateOf(Object controller) throws Exception {
    android.media.session.PlaybackState state =
        (android.media.session.PlaybackState)
            Class.forName("android.media.session.ISessionController")
                .getMethod("getPlaybackState")
                .invoke(controller);
    return state == null ? -1 : state.getState();
  }

  static String stateName(int state) {
    switch (state) {
      case 0:
        return "none";
      case 1:
        return "stopped";
      case 2:
        return "paused";
      case 3:
        return "playing";
      case 6:
        return "buffering";
      case 7:
        return "error";
      case 8:
        return "connecting";
      default:
        return "unknown";
    }
  }

  static String state(String pkg) {
    if (!allowed(pkg)) return "none";
    try {
      Object controller = selectedSession(pkg);
      return controller == null ? "none" : stateName(stateOf(controller));
    } catch (Exception ignored) {
      return "unknown";
    }
  }

  static String toggle(String pkg) throws Exception {
    if (!allowed(pkg)) throw new java.io.IOException("请先配置音乐 APP");
    Object controller = selectedSession(pkg);
    return controller == null ? oneTap(pkg) : toggleSession(controller);
  }

  static boolean active(String state) {
    return state.equals("playing")
        || state.equals("paused")
        || state.equals("buffering")
        || state.equals("connecting")
        || state.equals("stopped");
  }

  static String desired(String pkg, String state) throws Exception {
    if (!allowed(pkg)) throw new java.io.IOException("请先配置音乐 APP");
    Object controller = selectedSession(pkg);
    if (controller == null) return state.equals("playing") ? oneTap(pkg) : "所选 APP 已暂停";
    return desiredSession(controller, state);
  }

  static String desiredSession(Object controller, String target) throws Exception {
    int state = stateOf(controller);
    if (target.equals("playing"))
      return state == 3 ? "所选 APP 已进入播放状态（音箱实际出声尚未核验）" : playAndConfirm(controller);
    if (!target.equals("paused")) throw new java.io.IOException("无效的播放意图");
    return state == 2 || state == 1 || state == 0 ? "所选 APP 已暂停" : pauseAndConfirm(controller);
  }

  static String toggleSession(Object controller) throws Exception {
    int state = stateOf(controller);
    if (state == 3 || state == 6 || state == 8) return pauseAndConfirm(controller);
    if (state == 0 || state == 1 || state == 2) return playAndConfirm(controller);
    throw new java.io.IOException("无法确认所选 APP 播放状态，请在 APP 内检查播放任务");
  }

  static String pauseAndConfirm(Object controller) throws Exception {
    Class<?> api = Class.forName("android.media.session.ISessionController");
    api.getMethod("pause", String.class).invoke(controller, "com.android.shell");
    for (int i = 0; i < 32; i++) {
      int state = stateOf(controller);
      if (state == 2 || state == 1) return "所选 APP 已暂停";
      if (state == 7) throw new java.io.IOException("音乐 APP 报告暂停错误");
      Thread.sleep(250);
    }
    throw new java.io.IOException("音乐 APP 未确认暂停");
  }

  static String oneTap(String pkg) throws Exception {
    if (!allowed(pkg)) throw new java.io.IOException("请先配置网易云音乐或海贝音乐");
    if (!ReceiverServer.command("pm", "path", pkg).startsWith("package:"))
      throw new java.io.IOException("所选音乐 APP 未安装");
    ReceiverServer.command("monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1");
    for (int i = 0; i < 20; i++) {
      Object controller = selectedSession(pkg);
      if (controller != null) return playAndConfirm(controller);
      Thread.sleep(250);
    }
    throw new java.io.IOException("所选 APP 尚未建立系统媒体会话，请先在 APP 内选择并播放过一首歌");
  }

  static Object selectedSession(String pkg) throws Exception {
    android.os.IBinder service =
        (android.os.IBinder)
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, "media_session");
    Object manager =
        Class.forName("android.media.session.ISessionManager$Stub")
            .getMethod("asInterface", android.os.IBinder.class)
            .invoke(null, service);
    Class<?> managerClass = Class.forName("android.media.session.ISessionManager"),
        controllerClass = Class.forName("android.media.session.ISessionController");
    java.util.List<?> sessions =
        (java.util.List<?>)
            managerClass
                .getMethod("getSessions", android.content.ComponentName.class, int.class)
                .invoke(manager, null, 0);
    for (Object token : sessions) {
      Object controller =
          android.media.session.MediaSession.Token.class.getMethod("getBinder").invoke(token);
      if (controller instanceof android.os.IBinder)
        controller =
            Class.forName("android.media.session.ISessionController$Stub")
                .getMethod("asInterface", android.os.IBinder.class)
                .invoke(null, controller);
      if (!pkg.equals(controllerClass.getMethod("getPackageName").invoke(controller))) continue;
      return controller;
    }
    return null;
  }

  static String playAndConfirm(Object controller) throws Exception {
    Class<?> api = Class.forName("android.media.session.ISessionController");
    api.getMethod("play", String.class).invoke(controller, "com.android.shell");
    android.media.session.PlaybackState state = null;
    for (int i = 0; i < 32; i++) {
      state =
          (android.media.session.PlaybackState)
              api.getMethod("getPlaybackState").invoke(controller);
      if (state != null && state.getState() == android.media.session.PlaybackState.STATE_PLAYING)
        return "所选 APP 已进入播放状态（音箱实际出声尚未核验）";
      if (state != null && state.getState() == android.media.session.PlaybackState.STATE_ERROR)
        throw new java.io.IOException("音乐 APP 报告播放错误，请在 APP 内检查歌曲或授权");
      Thread.sleep(250);
    }
    if (state != null
        && (state.getState() == android.media.session.PlaybackState.STATE_BUFFERING
            || state.getState() == android.media.session.PlaybackState.STATE_CONNECTING))
      return "音乐 APP 仍在缓冲，尚未确认开始播放";
    throw new java.io.IOException("音乐 APP 未开始播放，请在 APP 内确认有可播放的歌曲；失效的历史曲目不能自动恢复");
  }
}
