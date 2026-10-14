package cn.couger.livingplayer;

import android.media.*;
import android.view.Surface;
import java.io.*;
import java.nio.ByteBuffer;
import javax.net.ssl.HttpsURLConnection;

/** Native H.264 decoder. Screen capture and sound are never saved to disk. */
final class VideoSession {
  interface Listener {
    void message(VideoSession sender, String message);

    void format(
        VideoSession sender,
        int width,
        int height,
        int displayWidth,
        int displayHeight,
        int rotation);
  }

  final DeviceClient device;
  final Surface surface;
  final Listener listener;
  final String id = java.util.UUID.randomUUID().toString().replace("-", "");
  volatile boolean running = true;
  volatile HttpsURLConnection connection;
  volatile MediaCodec codec;

  VideoSession(DeviceClient d, Surface s, Listener l) {
    device = d;
    surface = s;
    listener = l;
  }

  void stop() {
    running = false;
    HttpsURLConnection c = connection;
    if (c != null) c.disconnect();
  }

  void start() {
    new Thread(
            new Runnable() {
              public void run() {
                loop();
              }
            },
            "remote-video")
        .start();
  }

  void loop() {
    while (running && surface.isValid()) {
      try {
        if (device.request("/status", null).optString("videoMode").equals("snapshot")) {
          snapshots();
          continue;
        }
        connection = device.connection("/video");
        connection.setRequestProperty("X-Video-Session", id);
        connection.setReadTimeout(12000);
        int response = connection.getResponseCode();
        if (response == 401) {
          running = false;
          listener.message(this, "授权已失效，请重新配对");
          break;
        }
        if (response != 200) throw new IOException("画面通道忙碌");
        int width = Integer.parseInt(connection.getHeaderField("X-Video-Width")),
            height = Integer.parseInt(connection.getHeaderField("X-Video-Height"));
        int dw = Integer.parseInt(connection.getHeaderField("X-Display-Width")),
            dh = Integer.parseInt(connection.getHeaderField("X-Display-Height")),
            rotation = Integer.parseInt(connection.getHeaderField("X-Display-Rotation"));
        codec = MediaCodec.createDecoderByType("video/avc");
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024);
        codec.configure(format, surface, null, 0);
        codec.start();
        listener.format(this, width, height, dw, dh, rotation);
        listener.message(this, "画面已连接");
        final MediaCodec decoder = codec;
        Thread output =
            new Thread(
                new Runnable() {
                  public void run() {
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    try {
                      while (running && codec == decoder) {
                        int index = decoder.dequeueOutputBuffer(info, 10000);
                        if (index >= 0) decoder.releaseOutputBuffer(index, true);
                      }
                    } catch (Exception ignored) {
                    }
                  }
                },
                "video-render");
        output.start();
        try (InputStream in = connection.getInputStream()) {
          ByteArrayOutputStream nal = new ByteArrayOutputStream();
          byte[] b = new byte[16384];
          int n, zeros = 0;
          boolean started = false;
          while (running && (n = in.read(b)) >= 0) {
            for (int i = 0; i < n; i++) {
              int v = b[i] & 255;
              if (v == 0) {
                zeros++;
                continue;
              }
              if (v == 1 && zeros >= 2) {
                if (started && nal.size() > 4) feed(nal.toByteArray());
                nal.reset();
                nal.write(new byte[] {0, 0, 0, 1});
                started = true;
                zeros = 0;
                continue;
              }
              if (started) {
                for (int z = 0; z < zeros; z++) nal.write(0);
                nal.write(v);
                if (nal.size() > 1024 * 1024) throw new IOException("video frame too large");
              }
              zeros = 0;
            }
          }
          if (started && nal.size() > 4) feed(nal.toByteArray());
          if (running) listener.message(this, "画面重连中…");
        } finally {
          MediaCodec old = codec;
          codec = null;
          if (old != null) {
            try {
              old.stop();
            } catch (Exception ignored) {
            }
            old.release();
          }
          output.join(1000);
        }
      } catch (Exception e) {
        if (running) listener.message(this, "画面重连中…");
      } finally {
        MediaCodec old = codec;
        codec = null;
        if (old != null) {
          try {
            old.stop();
            old.release();
          } catch (Exception ignored) {
          }
        }
        if (connection != null) connection.disconnect();
        connection = null;
      }
      if (running)
        try {
          Thread.sleep(1500);
        } catch (InterruptedException ignored) {
        }
    }
  }

  void snapshots() throws Exception {
    while (running && surface.isValid()) {
      connection = device.connection("/frame");
      connection.setReadTimeout(10000);
      if (connection.getResponseCode() == 401) {
        running = false;
        listener.message(this, "授权已失效，请重新配对");
        return;
      }
      if (connection.getResponseCode() != 200) throw new IOException("截图暂不可用");
      int dw = Integer.parseInt(connection.getHeaderField("X-Display-Width")),
          dh = Integer.parseInt(connection.getHeaderField("X-Display-Height")),
          rotation = Integer.parseInt(connection.getHeaderField("X-Display-Rotation"));
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (InputStream in = connection.getInputStream()) {
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) {
          if (bytes.size() + n > 4194304) throw new IOException("frame too large");
          bytes.write(b, 0, n);
        }
      }
      connection.disconnect();
      connection = null;
      if (!running) return;
      android.graphics.Bitmap bitmap =
          android.graphics.BitmapFactory.decodeByteArray(bytes.toByteArray(), 0, bytes.size());
      if (bitmap == null) throw new IOException("invalid screen frame");
      try {
        android.graphics.Canvas canvas = surface.lockCanvas(null);
        try {
          canvas.drawColor(android.graphics.Color.BLACK);
          canvas.drawBitmap(
              bitmap,
              null,
              new android.graphics.Rect(0, 0, canvas.getWidth(), canvas.getHeight()),
              null);
        } finally {
          surface.unlockCanvasAndPost(canvas);
        }
        listener.format(this, bitmap.getWidth(), bitmap.getHeight(), dw, dh, rotation);
        listener.message(this, "画面已连接");
      } finally {
        bitmap.recycle();
      }
      Thread.sleep(1000); // Box firmware H.264 capture is unreliable; no capture when closed.
    }
  }

  void feed(byte[] nal) throws Exception {
    MediaCodec decoder = codec;
    if (decoder == null || !running) return;
    int type = nal[4] & 31;
    int index = -1;
    while (running && codec == decoder && (index = decoder.dequeueInputBuffer(10000)) < 0) {}
    if (index < 0) return;
    ByteBuffer buffer = decoder.getInputBuffer(index);
    buffer.clear();
    buffer.put(nal);
    decoder.queueInputBuffer(
        index,
        0,
        nal.length,
        System.nanoTime() / 1000,
        (type == 7 || type == 8) ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0);
  }
}
