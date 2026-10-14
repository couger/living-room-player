package cn.couger.livingplayer;

import android.content.SharedPreferences;
import org.json.*;

/** Local controller profiles, keyed by the pinned receiver certificate. */
final class SavedDevices {
  final SharedPreferences prefs;

  SavedDevices(SharedPreferences p) {
    prefs = p;
  }

  JSONArray list() {
    try {
      return new JSONArray(prefs.getString("devices", "[]"));
    } catch (Exception e) {
      return new JSONArray();
    }
  }

  JSONObject find(String pin) {
    JSONArray a = list();
    for (int i = 0; i < a.length(); i++) {
      JSONObject d = a.optJSONObject(i);
      if (d != null && pin.equals(d.optString("id"))) return d;
    }
    return null;
  }

  void save(DeviceClient client, String suggested, String type) {
    synchronized (SavedDevices.class) {
      try {
        JSONArray a = list();
        JSONObject d = find(client.pin);
        if (d == null) {
          d =
              new JSONObject()
                  .put("id", client.pin)
                  .put(
                      "name",
                      suggested == null || suggested.isEmpty()
                          ? "播放器 · " + client.host
                          : suggested);
          a.put(d);
        } else
          for (int i = 0; i < a.length(); i++)
            if (client.pin.equals(a.getJSONObject(i).optString("id"))) {
              d = a.getJSONObject(i);
              break;
            }
        d.put("code", client.code()).put("host", client.host);
        if (type != null) d.put("type", type);
        if (!a.toString().equals(prefs.getString("devices", ""))
            || !client.code().equals(prefs.getString("controllerCode", "")))
          prefs
              .edit()
              .putString("devices", a.toString())
              .putString("controllerCode", client.code())
              .apply();
      } catch (Exception ignored) {
      }
    }
  }

  boolean updateExisting(DeviceClient client) {
    synchronized (SavedDevices.class) {
      try {
        JSONArray a = list();
        for (int i = 0; i < a.length(); i++) {
          JSONObject d = a.getJSONObject(i);
          if (client.pin.equals(d.optString("id"))) {
            d.put("host", client.host).put("code", client.code());
            prefs.edit().putString("devices", a.toString()).apply();
            return true;
          }
        }
      } catch (Exception ignored) {
      }
      return false;
    }
  }

  void rename(String pin, String name) {
    synchronized (SavedDevices.class) {
      try {
        JSONArray a = list();
        for (int i = 0; i < a.length(); i++)
          if (pin.equals(a.getJSONObject(i).optString("id"))) a.getJSONObject(i).put("name", name);
        prefs.edit().putString("devices", a.toString()).apply();
      } catch (Exception ignored) {
      }
    }
  }

  void forget(String pin) {
    synchronized (SavedDevices.class) {
      JSONArray next = new JSONArray(), a = list();
      for (int i = 0; i < a.length(); i++)
        if (!pin.equals(a.optJSONObject(i).optString("id"))) next.put(a.optJSONObject(i));
      prefs.edit().putString("devices", next.toString()).apply();
    }
  }

  String name(DeviceClient d) {
    JSONObject p = find(d.pin);
    return p == null ? "播放器 · " + d.host : p.optString("name");
  }

  void migrate() {
    if (!prefs.contains("devices") && prefs.contains("controllerCode"))
      try {
        save(new DeviceClient(prefs.getString("controllerCode", "")), null, null);
      } catch (Exception ignored) {
      }
  }
}
