import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("check-public-metadata.py")
spec = importlib.util.spec_from_file_location("public_metadata", SCRIPT)
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)


class PublicMetadataTests(unittest.TestCase):
    def test_only_github_privacy_addresses_are_accepted(self):
        for address in ["user@users.noreply.github.com", "123+user@users.noreply.github.com", "noreply@github.com"]:
            self.assertTrue(metadata.private_email(address))
        for address in ["person@example.com", "user@users.noreply.github.com.attacker.example", "user@github.com", ""]:
            self.assertFalse(metadata.private_email(address))

    def test_history_and_both_identities_are_checked_without_echoing_email(self):
        with tempfile.TemporaryDirectory() as folder:
            def git(*args):
                return subprocess.run(["git", *args], cwd=folder, check=True, capture_output=True)
            git("init", "-q")
            for email in ["private@example.com", "user@users.noreply.github.com"]:
                git("-c", "user.name=Example", "-c", "user.email=" + email,
                    "commit", "--allow-empty", "-m", "Synthetic fixture")
            result = subprocess.run([sys.executable, str(SCRIPT)], cwd=folder, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("author, committer", result.stdout)
            self.assertNotIn("private@example.com", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
