"""Reject non-private commit emails before publishing; never echo the address."""
import re
import subprocess


def private_email(address):
    return bool(re.fullmatch(r"[^\s<>@]+@users\.noreply\.github\.com", address)) or address == "noreply@github.com"


def main():
    raw = subprocess.check_output([
        "git", "log", "--all", "--format=%H%x00%ae%x00%ce"
    ]).decode("utf-8", "replace")
    failed = []
    for line in raw.splitlines():
        commit, author, committer = line.split("\0")
        roles = [role for role, email in (("author", author), ("committer", committer)) if not private_email(email)]
        if roles:
            failed.append((commit, roles))
    for commit, roles in failed:
        print(f"Non-private email in {commit[:12]} ({', '.join(roles)}); inspect locally.")
    if failed:
        raise SystemExit("Publication blocked: commit metadata needs privacy review. Deleting a file does not clean history.")
    print("Commit email privacy check passed for all fetched refs; this does not audit GitHub caches or Actions metadata.")


if __name__ == "__main__":
    main()
