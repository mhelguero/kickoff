# Working on Kickoff

## Workflow

- After making changes, run a code review. If the review finds no bugs and there are no decisions
  that need the user's input, commit and push to GitHub automatically, without asking first.
  If it finds bugs, fix them and review again. If a decision is needed, ask before pushing.
- After pushing app changes to a branch, start the "Build APK" workflow (`.github/workflows/build.yml`)
  on that branch, so a new APK is published for the user to install and test. Then watch the run
  and report whether it succeeded, with the release link.
