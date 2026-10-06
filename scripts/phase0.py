#!/usr/bin/env python3
"""Phase 0 evidence collector. Python 3 stdlib only; never changes user auth.

Each invocation owns a new output directory and disposable Git worktree.
Raw stdout/stderr are separate from observations and never rewritten.
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import selectors
import shutil
import signal
import subprocess
import sys
import time
import uuid


def utc():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def capture(folder, argv, cwd, *, version=None, stdin="", timeout=90,
            hold_stdin=False, interrupt_on=None, env=None):
    """Drain both pipes through EOF, with a bounded post-exit drain.

    returncode is Popen's actual status (negative means signal), never a
    tee/timeout wrapper status. Chunk times measure receipt, not emission.
    """
    folder.mkdir(parents=True, exist_ok=False)
    (folder / "stdin.txt").write_text(stdin)
    start = time.monotonic()
    result = dict(source="measured", argv=argv, cwd=str(cwd), version=version,
                  startedAt=utc(), stdinClosed=not hold_stdin, exitCode=None,
                  termination=None, outputComplete=False, chunks=[])
    proc = None
    selector = selectors.DefaultSelector()
    files = {}
    def signal_group(sig):
        try:
            os.killpg(proc.pid, sig)
        except ProcessLookupError:
            pass
        except OSError as exc:
            result.setdefault("cleanupErrors", []).append(str(exc))
            result["collectorError"] = "process group cleanup could not be confirmed"
    try:
        files = {name: (folder / (name + ".log")).open("wb")
                 for name in ("stdout", "stderr")}
        proc = subprocess.Popen(argv, cwd=cwd, env=env, stdin=subprocess.PIPE,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                start_new_session=True)
        result["pid"] = proc.pid
        proc.stdin.write(stdin.encode())
        proc.stdin.flush()
        if not hold_stdin:
            proc.stdin.close()
        selector.register(proc.stdout, selectors.EVENT_READ, "stdout")
        selector.register(proc.stderr, selectors.EVENT_READ, "stderr")
        stopped = None
        exited = None
        observed_stdout = b""

        def stop(reason):
            nonlocal stopped
            if stopped is None:
                result["termination"] = reason
                stopped = time.monotonic()
                signal_group(signal.SIGTERM)

        while selector.get_map() or proc.poll() is None:
            now = time.monotonic()
            if now - start >= timeout:
                stop("timeout")
            if proc.poll() is not None and exited is None:
                exited = now
                result["processExitedAt"] = utc()
            if exited is not None and now - exited > 5:
                stop("drain-timeout")
            if stopped is not None and now - stopped > 1:
                signal_group(signal.SIGKILL)
                if now - stopped > 3:
                    break
            for key, _ in selector.select(0.05):
                data = os.read(key.fileobj.fileno(), 65536)
                if not data:
                    selector.unregister(key.fileobj)
                    key.fileobj.close()
                    continue
                files[key.data].write(data)
                files[key.data].flush()
                result["chunks"].append(dict(stream=key.data, bytes=len(data),
                    elapsedSec=round(time.monotonic() - start, 6),
                    afterExit=proc.poll() is not None))
                if key.data == "stdout":
                    observed_stdout += data
                    if interrupt_on and interrupt_on.encode() in observed_stdout:
                        stop("requested-interruption")
        result["outputComplete"] = not selector.get_map()
        result["exitCode"] = proc.wait(timeout=3)
    except KeyboardInterrupt:
        result["termination"] = "user-interruption"
        result["collectorError"] = "collector interrupted by user"
        raise
    except (OSError, subprocess.TimeoutExpired) as exc:
        result["collectorError"] = str(exc) or type(exc).__name__
    finally:
        if proc is not None:
            # Own process group only; also clean up descendants after parent exit.
            signal_group(signal.SIGKILL)
            try:
                result["exitCode"] = proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                result["exitCode"] = proc.poll()
                result["collectorError"] = "process did not exit within cleanup deadline"
            for stream in (proc.stdin, proc.stdout, proc.stderr):
                if stream and not stream.closed:
                    stream.close()
        selector.close()
        for stream in files.values():
            stream.close()
        result["finishedAt"] = utc()
        result["durationSec"] = round(time.monotonic() - start, 6)
        save(folder / "result.json", result)
    print(f"{folder.name}: exit={result['exitCode']} "
          f"termination={result['termination']}", flush=True)
    return result


def parse_output(provider, folder):
    """Conservative candidate contract; never infer success from exit 0 alone."""
    result = json.loads((folder / "result.json").read_text())
    text = (folder / "stdout.log").read_text(errors="replace")
    sid, reply, success = None, "", False
    try:
        if provider == "codex":
            events = [json.loads(line) for line in text.splitlines() if line.strip()]
            ids = [e.get("thread_id") for e in events if e.get("type") == "thread.started"]
            sid = ids[0] if ids else None
            reply = "\n".join(e["item"].get("text", "") for e in events
                              if e.get("type") == "item.completed"
                              and e.get("item", {}).get("type") == "agent_message")
            success = bool(reply) and any(e.get("type") == "turn.completed" for e in events)
            success &= not any(e.get("type") in ("turn.failed", "error") for e in events)
        else:
            event = json.loads(text)
            sid, reply = event.get("conversation_id"), event.get("response", "")
            # Measured agy 1.2.16 envelope: status=SUCCESS is mandatory.
            success = (event.get("status") == "SUCCESS" and bool(reply)
                       and not event.get("error") and event.get("is_error") is not True)
        success = bool(success and isinstance(sid, str) and sid and
                       result["exitCode"] == 0 and result["outputComplete"] and
                       not result["termination"] and not result.get("collectorError"))
    except (ValueError, TypeError, AttributeError, KeyError):
        success = False
    return dict(sessionId=sid, response=reply, success=success)


def command(provider, binary, cwd, model=None, session=None, effort="low"):
    if provider == "codex":
        argv = [binary, "exec", "--json", "-C", str(cwd),
                "-c", f"model_reasoning_effort={effort}",
                "-c", "sandbox_mode=danger-full-access", "-c", "approval_policy=never"]
        if model:
            argv += ["-m", model]
        return argv + (["resume", session, "-"] if session else ["-"])
    argv = [binary, "--output-format", "json", "--effort", effort,
            "--dangerously-skip-permissions"]
    if model:
        argv += ["--model", model]
    if session:
        argv += ["--conversation", session]
    return argv


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("provider", choices=["codex", "agy"])
    ap.add_argument("--run", action="store_true", help="run real model new/resume in a fresh sample worktree")
    ap.add_argument("--model", help="explicit installed-provider model; Codex may use its configured default")
    ap.add_argument("--binary", help="explicit CLI path; defaults to PATH discovery")
    ap.add_argument("--output", type=Path, help="new evidence directory (must not exist)")
    ap.add_argument("--timeout", type=float, default=120)
    ap.add_argument("--edge-cases", action="store_true", help="additional stdin/interruption probes (Codex)")
    args = ap.parse_args()
    if args.timeout <= 0:
        ap.error("--timeout must be positive")
    if args.run and args.provider == "agy" and not args.model:
        ap.error("agy --run requires --model from the measured models output")
    root = (args.output or Path(__file__).parent / "samples" / args.provider /
            (dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:6])).resolve()
    root.mkdir(parents=True, exist_ok=False)
    binary = shutil.which(args.binary or args.provider)
    if not binary:
        save(root / "summary.json", dict(status="NOT_VERIFIED", reason="binary missing"))
        return 2
    capture(root / "version", [binary, "--version"], root)
    version = (root / "version/stdout.log").read_text().strip()

    def run(name, argv, cwd=root, **kwargs):
        return capture(root / name, argv, cwd, version=version,
                       timeout=kwargs.pop("timeout", args.timeout), **kwargs)

    run("help", [binary, "exec", "--help"] if args.provider == "codex" else [binary, "--help"])
    run("login-shell-path", ["/bin/zsh", "-lc", "command -v codex; command -v agy; command -v git; command -v gh"])
    if args.provider == "codex":
        run("resume-help", [binary, "exec", "resume", "--help"])
        run("auth", [binary, "login", "status"])
        isolated = root / "unauthenticated-home"
        isolated.mkdir()
        env = dict(os.environ, CODEX_HOME=str(isolated))
        for name in ("OPENAI_API_KEY", "CODEX_API_KEY", "OPENAI_ACCESS_TOKEN"):
            env.pop(name, None)
        run("unauthenticated-status", [binary, "-c", 'cli_auth_credentials_store="file"', "login", "status"], env=env)
        run("resume-sandbox-flag", [binary, "exec", "resume", "--sandbox", "read-only", "invalid", "-"])
    else:
        run("models-help", [binary, "models", "--help"])
        run("models-json-candidate", [binary, "models", "--output-format", "json"])
        run("models", [binary, "models"])
    summary = dict(provider=args.provider, binary=binary, version=version,
                   status="NOT_VERIFIED", reason="probe only; new/resume not run")
    save(root / "summary.json", summary)
    if not args.run:
        print(root)
        return 0

    repo, worktree = root / "repo", root / "worktree"
    repo.mkdir()
    (repo / "README.md").write_text("# Disposable Phase 0 CLI verification\n")
    setup = [(["git", "init", "-b", "main"], repo),
             (["git", "add", "README.md"], repo),
             (["git", "-c", "user.name=Phase0", "-c", "user.email=phase0@example.invalid",
               "commit", "-m", "seed sample"], repo),
             (["git", "worktree", "add", str(worktree), "-b", "ai/test", "main"], repo)]
    for index, (argv, cwd) in enumerate(setup):
        if run(f"setup-{index}", argv, cwd)["exitCode"] != 0:
            summary["reason"] = "sample worktree setup failed"
            save(root / "summary.json", summary)
            return 2
    run("shell-login", ["/bin/zsh", "-lc", "git log --oneline -5; [[ ! -o interactive ]]"], worktree)
    script = root / "multiline.zsh"
    script.write_text("set -eu\ngit rev-parse --show-toplevel\nprintf 'multiline-ok\\n'\n")
    run("shell-multiline", ["/bin/zsh", "-l", str(script)], worktree)
    token = "CONTEXT_" + uuid.uuid4().hex
    prompt = ("Work only in the current directory. No network, no external tools or agents. "
              "Write the current directory file names to FILES.md. "
              f"Remember the token {token} in this conversation only; do not write it to disk. "
              "Reply READY when done.")

    def turn(name, prompt, session=None, model=args.model, **kwargs):
        argv = command(args.provider, binary, worktree, model, session)
        if args.provider == "agy":
            argv += ["-p", prompt]
            run(name, argv, worktree, **kwargs)
        else:
            run(name, argv, worktree, stdin=prompt, **kwargs)
        return parse_output(args.provider, root / name)

    new = turn("new", prompt)
    new_file = worktree / "FILES.md"
    file_before = new_file.read_text() if new_file.exists() else None
    if file_before is not None:
        (root / "new/FILES.md").write_text(file_before)
    resumed = None
    if new["success"]:
        resumed = turn("resume", "Append exactly RESUMED to FILES.md. Reply with the remembered token from the previous turn.", new["sessionId"])
        if new_file.exists():
            (root / "resume/FILES.md").write_text(new_file.read_text())
    invalid = turn("invalid-model", "Reply OK only.", model="aiflow-invalid-model-000000")
    if args.edge_cases and args.provider == "codex":
        turn("stdin-held-open", "Reply OK only.", hold_stdin=True, timeout=5)
        turn("interrupt-before-id", "Reply OK only.", timeout=0.001)
        turn("interrupt-after-id", "Reply OK only.", interrupt_on="thread.started")
    verified = bool(new["success"] and resumed and resumed["success"] and
                    new["sessionId"] == resumed["sessionId"] and
                    token in resumed["response"] and file_before is not None and
                    new_file.exists() and "RESUMED" in new_file.read_text())
    summary.update(status="VERIFIED" if verified else "NOT_VERIFIED",
                   reason="new/resume/context/file checks passed" if verified else "inspect raw evidence; one or more required checks failed",
                   new=new, resume=resumed, invalidModel=invalid,
                   scope="new/resume in worktree only; not the full Phase 0 completion gate")
    save(root / "summary.json", summary)
    print(root)
    return 0 if verified else 2


if __name__ == "__main__":
    sys.exit(main())
