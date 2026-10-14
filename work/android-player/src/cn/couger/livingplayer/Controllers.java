package cn.couger.livingplayer;

import java.io.*;
import java.security.*;
import org.json.*;

/** Independent controller credentials. Only the local receiver can manage them. */
final class Controllers {
  static final File FILE = new File(ReceiverServer.ROOT, "controllers.json");
  static JSONArray entries = new JSONArray();

  static synchronized void load() throws Exception {
    if (FILE.exists()) entries = new JSONArray(ReceiverServer.read(FILE.getPath()));
  }

  static boolean same(String a, String b) {
    try {
      return MessageDigest.isEqual(a.getBytes("UTF-8"), b.getBytes("UTF-8"));
    } catch (Exception e) {
      return false;
    }
  }

  static synchronized boolean accepts(String bearer) {
    for (int i = 0; i < entries.length(); i++)
      if (same(bearer, "Bearer " + entries.optJSONObject(i).optString("token"))) return true;
    return false;
  }

  static synchronized String issue(String name) throws Exception {
    if (entries.length() >= 32) throw new IOException("已配对设备达到上限，请先撤销不用的设备");
    byte[] bytes = new byte[24];
    new SecureRandom().nextBytes(bytes);
    String token = CertificateMaker.hex(bytes);
    JSONObject entry =
        new JSONObject()
            .put("id", token.substring(0, 16))
            .put("token", token)
            .put("name", name)
            .put("created", System.currentTimeMillis());
    JSONArray next = new JSONArray(entries.toString());
    next.put(entry);
    save(next);
    return token;
  }

  static void save(JSONArray next) throws Exception {
    ReceiverServer.atomic(FILE, next.toString());
    entries = next;
  }

  static synchronized JSONArray list() throws Exception {
    JSONArray result = new JSONArray();
    for (int i = 0; i < entries.length(); i++) {
      JSONObject e = entries.getJSONObject(i);
      result.put(
          new JSONObject()
              .put("id", e.getString("id"))
              .put("name", e.getString("name"))
              .put("created", e.getLong("created")));
    }
    return result;
  }

  static synchronized void revoke(String id) throws Exception {
    JSONArray next = new JSONArray();
    boolean found = false;
    for (int i = 0; i < entries.length(); i++) {
      JSONObject e = entries.getJSONObject(i);
      if (e.getString("id").equals(id)) found = true;
      else next.put(e);
    }
    if (!found) throw new IOException("此设备已撤销或不存在");
    save(next);
  }
}
