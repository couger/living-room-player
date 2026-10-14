"""Run the receiver's actual input handler without physical device commands."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'work/android-player/src/cn/couger/livingplayer'


class KeyBindingTests(unittest.TestCase):
    def test_capture_and_playback_state_gates(self):
        jdk = Path(os.environ.get('JAVA_HOME', ''))
        if not (jdk / 'bin/javac.exe').exists():
            self.skipTest('JDK 17 required')
        source = (SRC / 'ReceiverServer.java').read_text(encoding='utf-8')
        handler = source[source.index(' static void physicalInput(String key)'):source.index(' static final AtomicBoolean startingPlayback')]
        probe = '''package cn.couger.livingplayer;
import java.util.concurrent.atomic.AtomicInteger;
public class ReceiverServer {
 static long localInputAt,captureKeyUntil;static String capturedKey="",screenState="Awake",hdmi="On";
 static boolean BOX=true;static AtomicInteger plays=new AtomicInteger();
 static class Config {String bound="KEY_TEXT";String optString(String key,String fallback){return key.equals("oneTapKey")?bound:"com.hiby.music";}}
 static Config config=new Config();
 static String screen(){return screenState;}
 static boolean sleeping(String state){return state.equals("Asleep");}
 static String read(String path){return hdmi;}
 static String displayOutput(){return hdmi;}
 static long requestPlayback(String pkg,boolean toggle){return plays.incrementAndGet();}
''' + handler + '''
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[]args)throws Exception{
  for(String key:new String[]{"KEY_TEXT","KEY_REPLY","KEY_F14","KEY_VOLUMEUP","KEY_POWER"}){
   check(KeyBinding.accepts(key));beginKeyCapture();physicalInput(key);check(capturedKey.equals(key)&&captureKeyUntil==0);
  }
  for(String bad:new String[]{"","BTN_LEFT","KEY_TEXT;reboot","KEY_TEXT\\nKEY_REPLY","key_text"})check(!KeyBinding.accepts(bad));
  check(plays.get()==0);
  physicalInput("KEY_TEXT");Thread.sleep(100);check(plays.get()==0);
  hdmi="Off";physicalInput("KEY_REPLY");Thread.sleep(100);check(plays.get()==0);
  physicalInput("KEY_TEXT");Thread.sleep(100);check(plays.get()==1);
  config.bound="KEY_REPLY";hdmi="On";screenState="Asleep";physicalInput("KEY_REPLY");Thread.sleep(100);check(plays.get()==2);
  screenState="Awake";MediaActions.session="playing";physicalInput("KEY_REPLY");Thread.sleep(100);check(plays.get()==3);
  MediaActions.session="paused";physicalInput("KEY_REPLY");Thread.sleep(100);check(plays.get()==4);
  beginKeyCapture();physicalInput("KEY_TEXT");Thread.sleep(100);check(plays.get()==4&&capturedKey.equals("KEY_TEXT"));
  KeyInspection.observing=true;physicalInput("KEY_REPLY","test-input");Thread.sleep(100);check(plays.get()==4);
 }
}
class MediaActions {static String session="none";static String state(String pkg){return session;}static boolean active(String state){return !state.equals("none");}}
class KeyInspection {static boolean observing;static void stop(){observing=false;}static boolean record(String key,String source){return observing;}}
'''
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / 'ReceiverServer.java'
            file.write_text(probe, encoding='utf-8')
            subprocess.run([str(jdk/'bin/javac.exe'), '-encoding', 'UTF-8', '-d', temp, str(SRC/'KeyBinding.java'), str(file)], check=True, capture_output=True)
            subprocess.run([str(jdk/'bin/java.exe'), '-cp', temp, 'cn.couger.livingplayer.ReceiverServer'], check=True, capture_output=True)


if __name__ == '__main__':
    unittest.main()
