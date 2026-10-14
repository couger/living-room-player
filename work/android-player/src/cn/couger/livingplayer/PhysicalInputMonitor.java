package cn.couger.livingplayer;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Watches physical Linux input events only; injected remote-control actions do not enter evdev. */
final class PhysicalInputMonitor {
  private static volatile Process process;
  private static volatile boolean running;
  private static final java.util.regex.Pattern SOURCE =
      java.util.regex.Pattern.compile("/dev/input/event[0-9]+");

  static boolean ready() {
    Process p = process;
    return running && p != null && p.isAlive();
  }

  private PhysicalInputMonitor() {}

  static synchronized void start() {
    if (running) return;
    running = true;
    Thread watcher =
        new Thread(
            () -> {
              while (running) {
                try {
                  int parent = android.os.Process.myPid();
                  String guard =
                      "getevent -lt & child=$!; trap 'kill $child 2>/dev/null; exit' TERM INT EXIT;"
                          + " while [ -d /proc/"
                          + parent
                          + " ] && kill -0 $child 2>/dev/null; do sleep 1; done";
                  Process p =
                      new ProcessBuilder("sh", "-c", guard).redirectErrorStream(true).start();
                  synchronized (PhysicalInputMonitor.class) {
                    if (!running) {
                      p.destroy();
                      return;
                    }
                    process = p;
                  }
                  try (BufferedReader in =
                      new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                      int key = line.indexOf("EV_KEY");
                      if (key < 0 || !line.trim().endsWith("DOWN")) continue;
                      String[] parts = line.substring(key + 6).trim().split("\\s+");
                      java.util.regex.Matcher source = SOURCE.matcher(line.substring(0, key));
                      if (parts.length > 0)
                        ReceiverServer.physicalInput(
                            parts[0], source.find() ? source.group() : "未知输入设备");
                    }
                  }
                  p.waitFor();
                  if (process == p) process = null;
                } catch (Exception ignored) {
                }
                if (!running) return;
                try {
                  Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                  return;
                }
              }
            },
            "physical-input-monitor");
    watcher.setDaemon(true);
    watcher.start();
  }

  static synchronized void stop() {
    running = false;
    Process p = process;
    process = null;
    if (p != null) {
      p.destroy();
      try {
        if (!p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS))
          p.destroyForcibly().waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
