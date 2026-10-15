"""Compile actual shortcut state and service code with device-free collaborators."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "work/android-player/src/cn/couger/livingplayer"


class ShortcutStateTests(unittest.TestCase):
    def setUp(self):
        self.jdk = Path(os.environ.get("JAVA_HOME", "")) / "bin"
        if not (self.jdk / "javac.exe").exists():
            self.skipTest("JDK 17 required")

    def compile_and_run(self, files, main):
        with tempfile.TemporaryDirectory() as directory:
            inputs = []
            for name, content in files.items():
                path = Path(directory) / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
                inputs.append(str(path))
            subprocess.run([str(self.jdk / "javac.exe"), "-encoding", "UTF-8", "-d", directory, *inputs], check=True, capture_output=True)
            result = subprocess.run([str(self.jdk / "java.exe"), "-cp", directory, main], check=True, capture_output=True, text=True, timeout=10)
            return result.stdout

    def test_expiry_network_loss_and_playback_share_one_snapshot(self):
        output = self.compile_and_run({
            "cn/couger/livingplayer/QuickPlayState.java": (SRC / "QuickPlayState.java").read_text(encoding="utf-8"),
            "cn/couger/livingplayer/QuickPlayStateCheck.java": (ROOT / "tools/QuickPlayStateCheck.java").read_text(encoding="utf-8"),
        }, "cn.couger.livingplayer.QuickPlayStateCheck")
        self.assertIn("quick-play protocol checks passed", output)

    def test_shortcut_reads_actual_state_and_never_retries_play_post(self):
        files = {
            "android/content/Context.java": 'package android.content; public class Context { public static final String NOTIFICATION_SERVICE="notification"; public Object getSystemService(String key){return new android.app.NotificationManager();}}',
            "android/content/Intent.java": 'package android.content; public class Intent {public int getIntExtra(String k,int d){return d;} public String getAction(){return "play";}}',
            "android/app/Service.java": 'package android.app;public class Service extends android.content.Context {public static final int START_NOT_STICKY=2; public void onCreate(){} public void onDestroy(){} public void startForeground(int id,Notification n){} public void stopSelf(int id){}}',
            "android/app/Notification.java": 'package android.app;public class Notification {public static class Builder {public Builder(Object c,String id){} public Builder setSmallIcon(int i){return this;}public Builder setContentTitle(String s){return this;}public Builder setOngoing(boolean b){return this;}public Notification build(){return new Notification();}}}',
            "android/app/NotificationChannel.java": 'package android.app;public class NotificationChannel {public NotificationChannel(String a,String b,int c){}}',
            "android/app/NotificationManager.java": 'package android.app;public class NotificationManager {public static final int IMPORTANCE_LOW=2;public void createNotificationChannel(NotificationChannel c){}}',
            "android/os/IBinder.java": 'package android.os;public interface IBinder{}',
            "android/os/SystemClock.java": 'package android.os;public class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}}',
            "android/R.java": 'package android;public class R {public static class drawable {public static final int ic_media_play=1;}}',
            "org/json/JSONObject.java": '''package org.json;import java.util.*;public class JSONObject {
              final Map<String,Object> map=new HashMap<>();public JSONObject put(String k,Object v){map.put(k,v);return this;}
              public String getString(String k){return (String)map.get(k);}public String optString(String k){return optString(k,"");}
              public String optString(String k,String d){Object v=map.get(k);return v==null?d:v.toString();}
              public boolean optBoolean(String k){return Boolean.TRUE.equals(map.get(k));}
              public long optLong(String k,int d){Object v=map.get(k);return v==null?d:((Number)v).longValue();}
            }''',
            "cn/couger/livingplayer/QuickPlayState.java": (SRC / "QuickPlayState.java").read_text(encoding="utf-8"),
            "cn/couger/livingplayer/QuickPlayService.java": (SRC / "QuickPlayService.java").read_text(encoding="utf-8"),
            "cn/couger/livingplayer/ShortcutProbe.java": '''package cn.couger.livingplayer;
              import org.json.*;import android.content.*;
              public class ShortcutProbe {
                static void check(boolean ok){if(!ok)throw new AssertionError();}
                static void scenario(String actual,String cached,String desired){
                  DeviceClient.actual=actual;DeviceClient.desired="";DeviceClient.posts=0;DeviceClient.timeout=false;QuickPlay.cached=cached;
                  new QuickPlayService().run("test",true);check(DeviceClient.posts==1);check(DeviceClient.desired.equals(desired));
                }
                public static void main(String[] args){
                  scenario("paused","playing","playing");scenario("playing","paused","paused");
                  DeviceClient.posts=0;DeviceClient.timeout=true;QuickPlay.failures=0;
                  new QuickPlayService().run("test",true);check(DeviceClient.posts==1&&QuickPlay.failures==1&&!QuickPlay.online);
                  DeviceClient.timeout=false;DeviceClient.posts=0;new QuickPlayService().run("test",false);check(DeviceClient.posts==0);
                  System.out.println("Actual playback and no-retry checks passed");
                }
              }
              class QuickPlay {
                static String cached;static boolean online;static int failures;
                static JSONObject profile(Context c,String p){return new JSONObject().put("code","test-code");}
                static Object prefs(Context c){return null;}static String target(Context c,int id){return "test";}
                static int[] widgets(Context c){return new int[0];}static boolean audioOnly(Context c,String p){return false;}
                static boolean playing(Context c,String p){return "playing".equals(cached);}
                static void record(Context c,String p,JSONObject s){online=true;}static void record(Context c,String p,boolean b){online=b;}
                static void failure(Context c){failures++;}
              }
              class SavedDevices {SavedDevices(Object p){}boolean updateExisting(DeviceClient c){return true;}}
              class DeviceClient {
                static String actual,desired;static int posts;static boolean timeout;
                static class AuthorizationException extends java.io.IOException {}
                DeviceClient(String code){}boolean rediscover(){return false;}void cancelPending(){}
                JSONObject request(String route,JSONObject command)throws Exception{
                  if(route.equals("/action")){
                    posts++;if(timeout)throw new java.net.SocketTimeoutException();
                    if(!command.optString("action").equals("media_toggle"))throw new AssertionError("Unexpected action");
                    desired=command.optString("desiredState");actual=desired;return new JSONObject().put("mediaRequestId",1);
                  }
                  if(!route.equals("/status"))throw new AssertionError("Unexpected route");
                  return new JSONObject().put("playerPackage","example.music").put("mediaToggleSupported",true)
                    .put("mediaIntentSupported",true).put("playbackState",actual).put("screen","Awake")
                    .put("mediaRequestId",1).put("playbackRequest",actual.equals("paused")?"所选 APP 已暂停":"所选 APP 已进入播放状态");
                }
              }
            ''',
        }
        self.assertIn("Actual playback and no-retry checks passed", self.compile_and_run(files, "cn.couger.livingplayer.ShortcutProbe"))
