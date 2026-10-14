"""Run the documented, device-free checks without silently skipping Java tests."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]


def main():
    home = Path(os.environ.get("JAVA_HOME", ""))
    if not (home / "bin" / ("javac.exe" if os.name == "nt" else "javac")).is_file():
        raise SystemExit("Set JAVA_HOME to a JDK 17 installation; Java tests must not be skipped.")
    if not shutil.which("node"):
        raise SystemExit("Install Node.js to run the WEB regressions.")
    tests = unittest.defaultTestLoader.discover(str(ROOT / "tools"), pattern="test_*.py")
    result = unittest.TextTestRunner(verbosity=2).run(tests)
    if not result.wasSuccessful() or result.skipped:
        raise SystemExit("Checks failed or were skipped; this is not a passing release check.")
    subprocess.run(["node", str(ROOT / "tools/test_web_controls.cjs")], check=True)
    print("All device-free checks passed. Hardware acceptance remains separate.")


if __name__ == "__main__":
    main()
