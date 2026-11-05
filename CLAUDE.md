# Working on Kickoff

## Workflow

- After making changes, run a code review. If the review finds no bugs and there are no decisions
  that need the user's input, commit and push to GitHub automatically, without asking first.
  If it finds bugs, fix them and review again. If a decision is needed, ask before pushing.
- Push changes to the working branch and to `main` too, so `main` always matches the latest
  download (the release link is shared with other people). The push to `main` starts the
  "Build APK" workflow (`.github/workflows/build.yml`) by itself, so don't also start it by hand.
  Then watch the run and report whether it succeeded, with the release link.
