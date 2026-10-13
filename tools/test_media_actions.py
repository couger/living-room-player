"""Run the actual media confirmation code against system-session test doubles."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class MediaConfirmationTests(unittest.TestCase):
    def test_confirmation_states(self):
        home = os.environ.get("JAVA_HOME")
        javac = str(Path(home) / "bin/javac.exe") if home and os.name == "nt" else shutil.which("javac")
        if not javac or not Path(javac).exists():
            self.skipTest("Set JAVA_HOME to a JDK")
        java = str(Path(javac).with_name("java.exe" if os.name == "nt" else "java"))
        stubs = {
            "android/os/IBinder.java": "package android.os;public interface IBinder{}",
            "android/content/ComponentName.java": "package android.content;public class ComponentName{}",
            "android/media/session/MediaSession.java": "package android.media.session;public class MediaSession{public static class Token{}}",
            "android/media/session/PlaybackState.java": """package android.media.session;public class PlaybackState{
                public static final int STATE_PLAYING=3,STATE_ERROR=7,STATE_BUFFERING=6,STATE_CONNECTING=8;
                private final int state;public PlaybackState(int value){state=value;}public int getState(){return state;}}""",
            "android/media/session/ISessionController.java": """package android.media.session;public interface ISessionController{
                void play(String caller);void pause(String caller);PlaybackState getPlaybackState();}""",
            "cn/couger/livingplayer/ReceiverServer.java": "package cn.couger.livingplayer;class ReceiverServer{static String command(String...args){throw new AssertionError(\"No device commands allowed\");}}",
            "cn/couger/livingplayer/ConfirmationProbe.java": """package cn.couger.livingplayer;
                import android.media.session.*;
                public class ConfirmationProbe{
                    public static class Session implements ISessionController{
                        final int mode;int calls=0,reads=0;Session(int mode){this.mode=mode;}
                        public void play(String caller){calls++;}
                        public void pause(String caller){throw new AssertionError("Unexpected pause during play confirmation");}
                        public PlaybackState getPlaybackState(){reads++;if(mode==9)return reads<3?null:new PlaybackState(3);return new PlaybackState(mode);}
                    }
                    public static void main(String[]args)throws Exception{
                        Session session=new Session(Integer.parseInt(args[0]));
                        try{System.out.println("result="+MediaActions.playAndConfirm(session));}
                        catch(java.io.IOException expected){System.out.println("error="+expected.getMessage());}
                        System.out.println("calls="+session.calls);
                    }
                }""",
            "cn/couger/livingplayer/ToggleProbe.java": """package cn.couger.livingplayer;
                import android.media.session.*;
                public class ToggleProbe {
                    public static class Session implements ISessionController {
                        int state,plays=0,pauses=0;boolean error;
                        Session(int initial){error=initial==93;state=error?3:initial;}
                        public void play(String caller){plays++;state=3;}
                        public void pause(String caller){pauses++;state=error?7:2;}
                        public PlaybackState getPlaybackState(){return state<0?null:new PlaybackState(state);}
                    }
                    public static void main(String[]args)throws Exception {
                        Session session=new Session(Integer.parseInt(args[0]));
                        try{System.out.println("result="+(args.length>1?MediaActions.desiredSession(session,args[1]):MediaActions.toggleSession(session)));}
                        catch(java.io.IOException expected){System.out.println("error="+expected.getMessage());}
                        System.out.println("plays="+session.plays+",pauses="+session.pauses+",state="+MediaActions.stateName(session.state));
                    }
                }""",
        }
        source = Path(__file__).resolve().parents[1] / "work/android-player/src/cn/couger/livingplayer/MediaActions.java"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stubs["cn/couger/livingplayer/MediaActions.java"] = source.read_text(encoding="utf-8")
            for name, data in stubs.items():
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(data, encoding="utf-8")
            compilation = subprocess.run([javac, "-J-Dfile.encoding=UTF-8", "-encoding", "UTF-8", "-d", directory, *map(str, root.rglob("*.java"))], capture_output=True)
            self.assertEqual(compilation.returncode, 0, compilation.stderr.decode("utf-8", errors="replace"))
            for mode, expected in [(3, "已进入播放状态"), (9, "已进入播放状态"), (7, "error=音乐 APP 报告播放错误"), (6, "仍在缓冲"), (2, "error=音乐 APP 未开始播放")]:
                with self.subTest(mode=mode):
                    result = subprocess.run([java, "-Dfile.encoding=UTF-8", "-cp", directory, "cn.couger.livingplayer.ConfirmationProbe", str(mode)], check=True, capture_output=True, text=True, encoding="utf-8", timeout=13)
                    self.assertIn(expected, result.stdout)
                    self.assertIn("calls=1", result.stdout, "Poll state without repeatedly issuing play")
            for mode, expected, counts in [(3,"所选 APP 已暂停","plays=0,pauses=1,state=paused"),(2,"已进入播放状态","plays=1,pauses=0,state=playing"),(0,"已进入播放状态","plays=1,pauses=0,state=playing"),(1,"已进入播放状态","plays=1,pauses=0,state=playing"),(6,"所选 APP 已暂停","plays=0,pauses=1,state=paused"),(8,"所选 APP 已暂停","plays=0,pauses=1,state=paused"),(7,"无法确认","plays=0,pauses=0,state=error"),(-1,"无法确认","plays=0,pauses=0,state=unknown"),(93,"暂停错误","plays=0,pauses=1,state=error")]:
                with self.subTest(toggle_mode=mode):
                    result=subprocess.run([java,"-Dfile.encoding=UTF-8","-cp",directory,"cn.couger.livingplayer.ToggleProbe",str(mode)],check=True,capture_output=True,text=True,encoding="utf-8",timeout=13)
                    self.assertIn(expected,result.stdout)
                    self.assertIn(counts,result.stdout,"Only the intended transport action may be sent")
            for initial,desired,counts in [(3,"playing","plays=0,pauses=0"),(2,"paused","plays=0,pauses=0"),(3,"paused","plays=0,pauses=1"),(2,"playing","plays=1,pauses=0")]:
                result=subprocess.run([java,"-Dfile.encoding=UTF-8","-cp",directory,"cn.couger.livingplayer.ToggleProbe",str(initial),desired],check=True,capture_output=True,text=True,encoding="utf-8",timeout=13)
                self.assertIn(counts,result.stdout,"Explicit intent must be idempotent even if another controller changed state")



if __name__ == "__main__":
    unittest.main()
