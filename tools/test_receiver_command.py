"""Check the backend's actual process runner against a command that hangs."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


class CommandTimeoutTests(unittest.TestCase):
    def test_output_and_timeout(self):
        java_home = os.environ.get("JAVA_HOME")
        javac = str(Path(java_home) / "bin" / "javac.exe") if java_home and os.name == "nt" else shutil.which("javac")
        if not javac or not Path(javac).exists():
            self.skipTest("JDK required; set JAVA_HOME")
        java = str(Path(javac).with_name("java.exe" if os.name == "nt" else "java"))
        source = (Path(__file__).resolve().parents[1] / "work/android-player/src/cn/couger/livingplayer/ReceiverServer.java").read_text(encoding="utf-8")
        method = source[source.index(" static String command("):source.index(" static JSONObject defaults(")]
        harness = "import java.io.*;import java.util.concurrent.*;public class CommandProbe {" + method + '''
        public static void main(String[] args) throws Exception {
            long start=System.nanoTime();
            try { System.out.println(command(args)); }
            catch(IOException error) { System.out.println(error.getMessage()); }
            System.out.println("elapsedMs="+(System.nanoTime()-start)/1000000);
        }}'''
        with tempfile.TemporaryDirectory() as directory:
            probe = Path(directory) / "CommandProbe.java"
            probe.write_text(harness, encoding="utf-8")
            child = Path(directory) / "ChildProbe.java"
            child.write_text('public class ChildProbe {public static void main(String[] args) throws Exception {if(args[0].equals("sleep")) Thread.sleep(20000); else System.out.println("probe-ok");}}', encoding="utf-8")
            subprocess.run([javac, str(probe), str(child)], check=True, capture_output=True)
            # Test command execution, not the host shell's cold-start time.
            commands = [[java, "-cp", directory, "ChildProbe", mode] for mode in ("echo", "sleep")]
            output = []
            for command in commands:
                result = subprocess.run([java, "-cp", directory, "CommandProbe", *command], check=True, capture_output=True, text=True, timeout=14)
                output.append(result.stdout)
            self.assertIn("probe-ok", output[0])
            self.assertIn("command timeout", output[1])
            self.assertLess(int(re.search(r"elapsedMs=(\d+)", output[1])[1]), 11000)


if __name__ == "__main__":
    unittest.main()
