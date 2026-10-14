package cn.couger.livingplayer;

import java.io.IOException;
import java.util.regex.*;

/** Match the vendor HWC connector, never Android's translated display name. */
final class HdmiDisplay {
  static String power(String dump, long id) throws IOException {
    Matcher blocks =
        Pattern.compile(
                "(?m)^Display (\\d+) \\((?:internal|external)[^\\n"
                    + "]*\\n"
                    + "([\\s\\S]*?)(?=^Display \\d+ \\(|\\z)")
            .matcher(dump);
    while (blocks.find())
      if (blocks.group(1).equals(Long.toString(id))) {
        Matcher mode = Pattern.compile("(?m)^\\s*powerMode=(On|Off)\\s*$").matcher(blocks.group(2));
        if (mode.find()) return mode.group(1);
      }
    throw new IOException("无法确认 HDMI 显示电源状态");
  }

  static long select(long[] physicalIds, String hwc) throws IOException {
    Matcher matches =
        Pattern.compile(
                "(?m)^\\s*DisplayId=(\\d+),\\s*Connector\\s+\\d+,\\s*Type\\s*=\\s*HDMI-A-1\\s*,")
            .matcher(hwc);
    Long found = null;
    while (matches.find()) {
      long id;
      try {
        id = Long.parseLong(matches.group(1));
      } catch (NumberFormatException e) {
        throw new IOException("HDMI 显示编号无效");
      }
      if (found != null) throw new IOException("HDMI 接口映射不唯一，未切换输出");
      found = id;
    }
    if (found != null) for (long id : physicalIds) if (id == found) return id;
    throw new IOException("无法确认 HDMI 对应的物理显示设备，未切换输出");
  }
}
