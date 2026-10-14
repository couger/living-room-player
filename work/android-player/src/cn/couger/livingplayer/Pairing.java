package cn.couger.livingplayer;

import android.util.Base64;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.Cipher;
import org.json.*;

final class Pairing {
  static final Map<String, Request> pending = new LinkedHashMap<String, Request>();

  static final class Request {
    String id, key, nonce, name, code, peer, cipher = "";
    long expires;
    boolean denied = false;

    JSONObject status() throws Exception {
      return new JSONObject()
          .put("id", id)
          .put("name", name)
          .put("code", code)
          .put("expires", expires);
    }
  }

  static String code(String fingerprint, String key, String nonce) throws Exception {
    byte[] b =
        MessageDigest.getInstance("SHA-256")
            .digest((fingerprint + "|" + key + "|" + nonce).getBytes("UTF-8"));
    long n = ((b[0] & 255L) << 24) | ((b[1] & 255L) << 16) | ((b[2] & 255L) << 8) | (b[3] & 255L);
    return String.format(Locale.US, "%06d", n % 1000000L);
  }

  static void cleanup() {
    Iterator<Request> it = pending.values().iterator();
    long now = System.currentTimeMillis();
    while (it.hasNext()) if (it.next().expires < now) it.remove();
  }

  static synchronized JSONObject request(JSONObject input, String peer, String fingerprint)
      throws Exception {
    if (!ReceiverServer.pairingOpen()) throw new Exception("请先在受控端开启三分钟配对");
    cleanup();
    Iterator<Request> old = pending.values().iterator();
    while (old.hasNext()) {
      Request r = old.next();
      if (r.peer.equals(peer)) {
        if (r.denied || !r.cipher.isEmpty()) old.remove();
        else throw new Exception("此设备已有配对请求，请等待或取消");
      }
    }
    if (pending.size() >= 4) throw new Exception("配对请求较多，请稍后重试");
    String key = input.getString("key"), nonce = input.getString("nonce");
    if (key.length() > 600 || !nonce.matches("[0-9a-f]{32}")) throw new Exception("配对参数无效");
    PublicKey pub =
        KeyFactory.getInstance("RSA")
            .generatePublic(new X509EncodedKeySpec(Base64.decode(key, Base64.NO_WRAP)));
    if (!(pub instanceof java.security.interfaces.RSAPublicKey)
        || ((java.security.interfaces.RSAPublicKey) pub).getModulus().bitLength() != 2048)
      throw new Exception("配对密钥无效");
    Request r = new Request();
    r.key = key;
    r.nonce = nonce;
    r.peer = peer;
    r.name = input.optString("name", "Android 控制端");
    if (r.name.length() > 40) r.name = r.name.substring(0, 40);
    r.name = r.name.replaceAll("[\\p{Cntrl}]", "");
    byte[] random = new byte[16];
    new SecureRandom().nextBytes(random);
    r.id = CertificateMaker.hex(random);
    r.expires = System.currentTimeMillis() + 180000;
    r.code = code(fingerprint, key, nonce);
    pending.put(r.id, r);
    return new JSONObject().put("id", r.id).put("expires", r.expires);
  }

  static synchronized JSONArray list() throws Exception {
    cleanup();
    JSONArray a = new JSONArray();
    for (Request r : pending.values()) if (r.cipher.isEmpty() && !r.denied) a.put(r.status());
    return a;
  }

  static synchronized void approve(JSONObject input, String token, String fingerprint)
      throws Exception {
    cleanup();
    Request r = pending.get(input.getString("id"));
    if (r == null || !ReceiverServer.pairingOpen()) throw new Exception("配对请求已过期或已关闭");
    if (r.denied || !r.cipher.isEmpty()) throw new Exception("此请求已处理");
    if (!input.getBoolean("allow")) {
      r.denied = true;
      return;
    }
    PublicKey pub =
        KeyFactory.getInstance("RSA")
            .generatePublic(new X509EncodedKeySpec(Base64.decode(r.key, Base64.NO_WRAP)));
    Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
    cipher.init(Cipher.ENCRYPT_MODE, pub);
    byte[] body =
        new JSONObject()
            .put("token", Controllers.issue(r.name))
            .put("fingerprint", fingerprint)
            .toString()
            .getBytes("UTF-8");
    r.cipher = Base64.encodeToString(cipher.doFinal(body), Base64.NO_WRAP);
  }

  static synchronized JSONObject result(JSONObject input, String peer) throws Exception {
    cleanup();
    Request r = pending.get(input.getString("id"));
    if (r == null) return new JSONObject().put("state", "expired");
    if (!r.peer.equals(peer)) throw new Exception("配对来源不同");
    if (r.denied) return new JSONObject().put("state", "denied");
    if (r.cipher.isEmpty()) return new JSONObject().put("state", "pending");
    return new JSONObject().put("state", "approved").put("cipher", r.cipher);
  }

  static synchronized void clear() {
    pending.clear();
  }

  static synchronized JSONObject cancel(JSONObject input, String peer) throws Exception {
    cleanup();
    Request r = pending.get(input.getString("id"));
    if (r != null && r.peer.equals(peer)) pending.remove(r.id);
    return new JSONObject().put("ok", true);
  }
}
