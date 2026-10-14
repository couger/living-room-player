"""Check connector selection when display names and array order are misleading."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class HdmiDisplayTests(unittest.TestCase):
    def test_connector_identity_and_ambiguous_mapping(self):
        jdk = Path(os.environ.get('JAVA_HOME', ''))
        if not (jdk / 'bin/javac.exe').exists():
            self.skipTest('JDK required')
        probe = '''package cn.couger.livingplayer;
public class HdmiDisplayCheck {
 static String line(long id,String type){return " DisplayId="+id+", Connector 333, Type = "+type+", Connector state = DRM_MODE_CONNECTED\\n";}
 static void reject(long[] ids,String dump)throws Exception{try{HdmiDisplay.select(ids,dump);throw new AssertionError("unsafe mapping accepted");}catch(java.io.IOException expected){}}
 public static void main(String[] args)throws Exception{
  String dump=line(0,"HDMI-A-1")+line(1,"TV-1");
  if(HdmiDisplay.select(new long[]{1,0},dump)!=0)throw new AssertionError();
  if(HdmiDisplay.select(new long[]{0},dump.replace("CONNECTED","DISCONNECTED"))!=0)throw new AssertionError();
  if(HdmiDisplay.select(new long[]{8,5},line(8,"TV-1")+line(5,"HDMI-A-1"))!=5)throw new AssertionError();
  reject(new long[]{1},dump);
  reject(new long[]{0,1},dump+line(1,"HDMI-A-1"));
  reject(new long[]{0},line(0,"TV-1"));
  reject(new long[]{0},line(0,"HDMI-A-10"));
  reject(new long[]{0},"Physical display HDMI 屏幕");
  reject(new long[]{},dump);
  String powers="Display 0 (internal, primary)\\n   powerMode=Off\\nDisplay 1 (external)\\n   powerMode=On\\n";
  if(!HdmiDisplay.power(powers,0).equals("Off")||!HdmiDisplay.power(powers,1).equals("On"))throw new AssertionError();
  try{HdmiDisplay.power(powers,2);throw new AssertionError();}catch(java.io.IOException expected){}
  try{HdmiDisplay.power("Display 0 (internal)\\nDisplay 1 (external)\\n powerMode=On\\n",0);throw new AssertionError();}catch(java.io.IOException expected){}
 }
}'''
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / 'HdmiDisplayCheck.java'
            file.write_text(probe, encoding='utf-8')
            src = ROOT/'work/android-player/src/cn/couger/livingplayer/HdmiDisplay.java'
            subprocess.run([str(jdk/'bin/javac.exe'), '-encoding', 'UTF-8', '-d', temp, str(src), str(file)], check=True, capture_output=True)
            subprocess.run([str(jdk/'bin/java.exe'), '-cp', temp, 'cn.couger.livingplayer.HdmiDisplayCheck'], check=True, capture_output=True)


if __name__ == '__main__':
    unittest.main()
