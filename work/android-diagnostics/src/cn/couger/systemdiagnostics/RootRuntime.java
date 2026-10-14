package cn.couger.systemdiagnostics;

import android.content.Context;
import java.io.*;
import java.util.concurrent.TimeUnit;

final class RootRuntime {
  static final String BASE = "/data/adb/system-diagnostics", LOGS = BASE + "/logs";

  static String root(String command) throws Exception {
    Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
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
      throw new IOException("ROOT 请求超时，请检查授权");
    }
    reader.join(2000);
    if (p.exitValue() != 0) throw new IOException("请允许系统故障记录的 ROOT 权限");
    return out.toString("UTF-8");
  }

  static void install(Context c) throws Exception {
    for (String name : new String[] {"recorder.sh", "boot.sh"})
      try (InputStream in = c.getAssets().open(name);
          OutputStream out = new FileOutputStream(new File(c.getFilesDir(), name))) {
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
      }
    String f = c.getFilesDir().getAbsolutePath();
    root(
        "set -e\nmkdir -p "
            + LOGS
            + "\nchmod 700 "
            + BASE
            + " "
            + LOGS
            + "\ncp "
            + f
            + "/recorder.sh "
            + BASE
            + "/recorder.new\nchmod 700 "
            + BASE
            + "/recorder.new\nmv "
            + BASE
            + "/recorder.new "
            + BASE
            + "/recorder.sh\nif [ -d /data/adb/service.d ]; then cp "
            + f
            + "/boot.sh /data/adb/service.d/system-diagnostics.sh; chmod 700"
            + " /data/adb/service.d/system-diagnostics.sh; fi\n");
  }

  static void resume() throws Exception {
    root(
        "if [ -f "
            + LOGS
            + "/enabled ] && [ -f "
            + BASE
            + "/recorder.sh ]; then nohup sh "
            + BASE
            + "/recorder.sh >/dev/null 2>&1 </dev/null &\nfi\nexit 0\n");
  }

  static void enable(Context c) throws Exception {
    install(c);
    root("umask 077; touch " + LOGS + "/enabled");
    resume();
    c.getSharedPreferences("diagnostics", 0).edit().putBoolean("enabled", true).commit();
  }

  static void disable(Context c) throws Exception {
    root(
        "rm -f "
            + LOGS
            + "/enabled\n"
            + "p=$(cat "
            + LOGS
            + "/recorder.pid 2>/dev/null); case $p in \"\"|*[!0-9]*) ;; *) cmd=$(tr '\\000' ' ' <"
            + " /proc/$p/cmdline 2>/dev/null); case $cmd in *"
            + BASE
            + "/recorder.sh*) kill -TERM $p 2>/dev/null || true;; esac;; esac\n");
    c.getSharedPreferences("diagnostics", 0).edit().putBoolean("enabled", false).commit();
  }

  static String status() throws Exception {
    return root(
        "if [ -f "
            + LOGS
            + "/enabled ]; then echo enabled=1; else echo enabled=0; fi\np=$(cat "
            + LOGS
            + "/recorder.pid 2>/dev/null); case $p in \"\"|*[!0-9]*) echo running=0;; *) cmd=$(tr"
            + " '\\000' ' ' < /proc/$p/cmdline 2>/dev/null); case $cmd in *"
            + BASE
            + "/recorder.sh*) echo running=1;; *) echo running=0;; esac;; esac\nif [ -d "
            + LOGS
            + " ]; then du -sk "
            + LOGS
            + "; fi\n");
  }
}
