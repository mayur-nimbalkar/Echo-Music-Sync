# Keeping This Fork Merge-Clean With Upstream

This fork tracks [EchoMusicApp/Echo-Music](https://github.com/EchoMusicApp/Echo-Music) (`upstream`).
Follow these rules and future upstream updates will merge with **zero conflicts**.

## Why conflicts happened before

The reel-import feature was merged to this fork's `main` as **squash commits** (PR #1, PR #2),
while the local checkout still held the original multi-commit history. Identical content,
different history — git then treats every touched line as changed on both sides and reports
spurious conflicts. The same trap fires if fork-only files are ever committed straight to
`main` while a topic branch edits the same lines.

Current structure (as of the v1.4.1 fix):

```
upstream/main  3eebb2c  Replace Echo Music logo and update description
        │
        └── origin/main (one linear chain)
             bb652a1  feat(reelimport): … (#1)   ← v1.4.0, squash of the feature branch
             f70c84e  … metadata-first … (#2)    ← v1.4.1, squash of the fix branch
```

One squash commit per merged PR = upstream stays a clean prefix of the fork's history.

## Rules that keep it clean

1. **Never build features directly on `main`.** Always branch, open a PR, merge via squash.
   `main` should contain *only* PR squash commits stacked on top of upstream — nothing else.
2. **Keep local `main` synced, not diverged.** After every merged PR:
   ```
   git checkout main
   git pull --ff-only origin main
   ```
   If git says "diverged", stop and reconcile before starting new work (see below).
3. **Sync upstream regularly, don't let it pile up.**
   ```
   git fetch upstream
   git log --oneline main..upstream/main        # what's new upstream
   git checkout main && git merge upstream/main # fast-forwards when rule 1 is respected
   git push origin main
   ```
   No conflicts are possible here as long as `main` is a linear stack of squash commits:
   upstream's changes only *extend* history, they never rewrite lines the squash commits own.
4. **Isolate fork-specific files.** Anything this fork adds that upstream doesn't have
   (`upcomingupdate.json`, this guide, fork-only modules) is safe: git auto-merges
   additions to different files. Conflicts only happen when both sides edit the *same*
   lines — which these files' names prevent.
5. **Feature branches must start from a synced `main`** — after rule 2, just:
   ```
   git checkout -b feat/my-feature main
   ```

## If local main ever diverges again (recovery)

Divergence means local `main` has commits that `origin/main` doesn't (usually because work
was committed locally instead of via a PR). Fix without losing content:

```
git fetch origin
git diff origin/main --stat              # 1. inspect — should be empty or trivial
git checkout main
git reset --hard origin/main             # 2. realign (destructive to LOCAL-only commits
                                         #    only — push them to a branch first if needed)
```

Destructive only to un-pushed local commits; always diff first.

## TL;DR

Branch → PR → squash-merge → `git pull --ff-only`. Repeat. Upstream merges stay conflict-free.
