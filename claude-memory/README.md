# Claude Code memory for this project

This folder is not app source code — it's Claude's notes about this project's
history and decisions, carried here so they survive a laptop migration via a
normal `git clone` instead of a manual file copy.

## One-time setup on a new machine

After cloning the repo, run this once (PowerShell) to drop these files into
Claude Code's own memory folder for this project:

```powershell
Copy-Item "claude-memory\*.md" -Destination "$env:USERPROFILE\.claude\projects\D--Dev-FootballSim\memory\" -Force
```

Run it from the repo root, and make sure the repo was cloned to `D:\Dev\FootballSim`
(the memory folder name is derived from that exact path).

You can delete this `claude-memory/` folder from the working tree afterward —
it has no effect on the app, build, or tests.
