#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
通过 GitHub REST API 推送（绕过 github.com 直连不通的问题）。

背景：这台机器上 github.com 走代理会 502、直连超时，但 api.github.com 可达，
      git push 因此一直失败。这个脚本用 Git Data API 完成同样的事：
      blob -> tree -> commit -> 更新 ref。

用法：
    python tools/push_via_api.py                # 推送当前 HEAD 到 origin 的分支
    python tools/push_via_api.py --dry-run      # 只看要推什么

凭证：复用 git 的 credential manager（`git credential fill`），不落地、不打印。
快进判断：比较「远端 head 的 tree」与「本地 HEAD^ 的 tree」是否相同，
          不依赖 commit sha，因为 GitHub 会规范化提交时间的时区与消息结尾。
"""

import base64
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"


def run(*args, **kwargs):
    return subprocess.run(args, capture_output=True, check=True, **kwargs).stdout


def git(*args):
    return run("git", *args).decode("utf-8", "ignore").strip()


def token():
    out = run(
        "git", "credential", "fill",
        input=b"protocol=https\nhost=github.com\n\n",
    ).decode("utf-8", "ignore")
    fields = dict(line.split("=", 1) for line in out.splitlines() if "=" in line)
    tok = fields.get("password")
    if not tok:
        sys.exit("没拿到 GitHub 凭证，先在别处 git push 一次让 credential manager 记住")
    return tok


def remote_slug():
    url = git("remote", "get-url", "origin")
    m = re.search(r"github\.com[:/]+(.+?)(?:\.git)?$", url)
    if not m:
        sys.exit(f"origin 不是 GitHub 仓库：{url}")
    return m.group(1)


class Api:
    def __init__(self, tok):
        self.tok = tok

    def __call__(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(
            API + path, method=method, data=data,
            headers={
                "Authorization": "token " + self.tok,
                "Accept": "application/vnd.github+json",
                "Content-Type": "application/json",
                "User-Agent": "open-tv-launcher-push",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                text = resp.read().decode()
                return json.loads(text) if text else {}
        except urllib.error.HTTPError as e:
            sys.exit(f"HTTP {e.code} {method} {path}\n{e.read().decode()[:500]}")


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    dry = "--dry-run" in sys.argv
    repo = remote_slug()
    branch = git("rev-parse", "--abbrev-ref", "HEAD")
    head = git("rev-parse", "HEAD")
    parent = git("rev-parse", "HEAD^")
    message = run("git", "log", "-1", "--format=%B").decode("utf-8", "ignore")
    author = git("log", "-1", "--format=%an")
    email = git("log", "-1", "--format=%ae")
    when = git("log", "-1", "--format=%aI")

    api = Api(token())
    ref = api("GET", f"/repos/{repo}/git/ref/heads/{branch}")
    remote_head = ref["object"]["sha"]
    remote_tree = api("GET", f"/repos/{repo}/git/commits/{remote_head}")["tree"]["sha"]
    local_head_tree = git("rev-parse", f"{head}^{{tree}}")
    local_parent_tree = git("rev-parse", f"{parent}^{{tree}}")

    if local_head_tree == remote_tree:
        print(f"远端 {branch} 已经和本地 HEAD 内容一致（{remote_head[:10]}），无需推送")
        return

    if local_parent_tree != remote_tree:
        sys.exit(
            "远端有本地没有的提交，不能快进。\n"
            f"  远端 tree {remote_tree[:10]}\n"
            f"  本地父 tree {local_parent_tree[:10]}\n"
            "先在能连 github.com 的环境里 git pull --rebase 同步一次"
        )

    changed = run("git", "diff-tree", "-r", "--no-commit-id", "--name-status", head)
    changed = changed.decode("utf-8", "ignore").splitlines()
    print(f"推送 {len(changed)} 个文件到 {repo}@{branch}（{remote_head[:10]} -> 新提交）")
    if dry:
        for line in changed[:20]:
            print("  ", line.replace("\t", "  "))
        return

    entries = []
    for line in changed:
        parts = line.split("\t")
        status, path = parts[0], parts[-1]
        if status.startswith("D"):
            entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
            continue
        content = run("git", "show", f"{head}:{path}")
        blob = api("POST", f"/repos/{repo}/git/blobs", {
            "content": base64.b64encode(content).decode(),
            "encoding": "base64",
        })
        entries.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})

    tree = api("POST", f"/repos/{repo}/git/trees", {"base_tree": remote_tree, "tree": entries})
    commit = api("POST", f"/repos/{repo}/git/commits", {
        "message": message,
        "tree": tree["sha"],
        "parents": [remote_head],
        "author": {"name": author, "email": email, "date": when},
        "committer": {"name": author, "email": email, "date": when},
    })
    api("PATCH", f"/repos/{repo}/git/refs/heads/{branch}", {"sha": commit["sha"], "force": False})
    print(f"已推送：{commit['sha']}")
    print(f"https://github.com/{repo}/commit/{commit['sha']}")


if __name__ == "__main__":
    main()
