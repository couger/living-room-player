package cn.couger.livingplayer;

import android.content.Context;
import java.io.*;
import java.util.concurrent.TimeUnit;

/** App boot receiver is the fallback on factory-root boxes without Magisk. */
final class ReceiverRuntime {
  static boolean box() {
    return android.os.Build.HARDWARE.equals("rk30board");
  }

  static String root(String script) throws Exception {
    java.lang.Process p = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Thread reader =
        new Thread(
            () -> {
              try {
                byte[] b = new byte[4096];
                int n;
                while ((n = p.getInputStream().read(b)) > 0)
                  if (out.size() < 65536) out.write(b, 0, n);
              } catch (Exception ignored) {
              }
            });
    reader.start();
    if (!p.waitFor(30, TimeUnit.SECONDS)) {
      p.destroy();
      throw new IOException("ROOT 初始化超时");
    }
    reader.join(2000);
    if (p.exitValue() != 0) throw new IOException("请检查 ROOT 授权");
    return out.toString("UTF-8");
  }

  static String install(Context context) throws Exception {
    String base = ReceiverServer.BASE;
    for (String name : new String[] {"receiver.jar", "guardian.sh", "boot.sh"}) {
      try (InputStream in = context.getAssets().open(name);
          OutputStream out = new FileOutputStream(new File(context.getFilesDir(), name))) {
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
      }
    }
    String f = context.getFilesDir().getAbsolutePath();
    String script =
        "set -e\nmkdir -p "
            + base
            + "\nchmod 700 "
            + base
            + "\ncp "
            + f
            + "/receiver.jar "
            + base
            + "/receiver-new.jar\nmv "
            + base
            + "/receiver-new.jar "
            + base
            + "/receiver.jar\ncp "
            + f
            + "/guardian.sh "
            + base
            + "/living-player-guardian.sh\nchmod 700 "
            + base
            + "/*.sh\n";
    // Only Magisk installations execute service.d. Factory-root uses BOOT_COMPLETED.
    script +=
        "if [ -d /data/adb/service.d ]; then cp "
            + f
            + "/boot.sh /data/adb/service.d/living-player.sh; chmod 700"
            + " /data/adb/service.d/living-player.sh; fi\n";
    if (box())
      script +=
          "appops set cn.couger.livingplayer SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true\n";
    script +=
        "CLASSPATH="
            + base
            + "/receiver.jar app_process /system/bin cn.couger.livingplayer.ReceiverServer"
            + " enable\n";
    return root(script);
  }

  static void resume(Context context) throws Exception {
    boolean receiver = context.getSharedPreferences("player", 0).getBoolean("receiver", false);
    String base = ReceiverServer.BASE, script = "";
    if (receiver)
      script += "nohup sh " + base + "/living-player-guardian.sh >/dev/null 2>&1 </dev/null &\n";
    if (!script.isEmpty()) root(script + "exit 0\n");
  }
}
