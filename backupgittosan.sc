#!/usr/bin/env bash
# Mirror every git repo under ~/work/git/ to the Synology SAN at
# \\Media-Server\store\source\Git\<relpath>.git, so accidental local
# branch deletions (git branch -D, bad rebase, etc.) can always be
# recovered from the SAN copy.
#
# Backup model: ADDITIVE git-mirror. After the initial `git clone
# --mirror`, the `remote.origin.mirror` flag is UNSET so subsequent
# `git remote update` runs do NOT prune. Refs that disappear from the
# local repo remain in the mirror forever.
#
# What is NOT backed up:
#   - Uncommitted working-tree changes (only commits + refs are mirrored)
#   - Stashes (refs/stash is local-only in clone --mirror semantics)
#   - Upstream commits the local repo hasn't fetched yet — the mirror's
#     "origin" points at the local WSL repo, not GitHub. Run `git fetch`
#     in the local repo first if you want upstream as-of-now.
#
# Why Windows git.exe via WSL interop (not WSL git):
# Same reason backuptosan.sc uses robocopy.exe — we write to a UNC path
# (\\Media-Server\store\source\...) so the script works under Task
# Scheduler's "run whether user is logged on or not" mode. Mapped Z: is
# per-session and won't resolve there.
#
# Usage:
#   backupgittosan.sc [--dry-run] [--verbose] [--root PATH]
#     --dry-run      Report what would happen, write nothing
#     --verbose      Show per-repo git output
#     --root PATH    Scan a different directory (default: ~/work/git)

set -uo pipefail

GIT_EXE='/mnt/c/Program Files/Git/cmd/git.exe'
POWERSHELL=/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe
[[ -x "$GIT_EXE"    ]] || { echo "git.exe not found at $GIT_EXE" >&2; exit 1; }
[[ -x "$POWERSHELL" ]] || { echo "powershell.exe not found at $POWERSHELL" >&2; exit 1; }

# UNC destination root. NOT using Z:\source — mapped drives aren't
# visible to Task Scheduler's non-interactive sessions.
DEST_UNC_ROOT='\\Media-Server\store\source\Git'

dry_run=0
verbose=0
root_dir="$HOME/work/git"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) dry_run=1 ;;
        --verbose) verbose=1 ;;
        --root)    [[ $# -ge 2 ]] || { echo "--root needs a value" >&2; exit 1; }
                   root_dir="$2"; shift ;;
        -h|--help)
            sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) echo "unknown arg: $1" >&2; exit 1 ;;
    esac
    shift
done

[[ -d "$root_dir" ]] || { echo "root not found: $root_dir" >&2; exit 1; }
# Strip any trailing slash so the later prefix-strip works cleanly.
root_dir="${root_dir%/}"

# Probe SAN reachability and create the Git/ root if first run.
# PowerShell because Linux tools can't see UNCs.
ps_test_path() {
    "$POWERSHELL" -NoProfile -Command "Test-Path -LiteralPath '$1'" 2>/dev/null \
        | tr -d '\r' | tail -1
}

if [[ "$(ps_test_path "$DEST_UNC_ROOT")" != "True" ]]; then
    if (( dry_run )); then
        echo "would create dest root: $DEST_UNC_ROOT"
    else
        if ! "$POWERSHELL" -NoProfile -Command \
                "New-Item -ItemType Directory -Force -Path '$DEST_UNC_ROOT' | Out-Null" 2>/dev/null
        then
            echo "cannot create $DEST_UNC_ROOT — is the SAN online?" >&2
            exit 1
        fi
    fi
fi

# git.exe complains about "dubious ownership" on \\wsl$\... and on
# SMB-mounted bare repos. Pass per-invocation rather than mutating the
# user's global git config.
GIT_SAFE=(-c 'safe.directory=*')

started=$(date '+%Y-%m-%d %H:%M:%S')
suffix=""
(( dry_run )) && suffix="  (DRY RUN)"
echo "Git mirror backup started: $started$suffix"
echo "  root: $root_dir"
echo "  dest: $DEST_UNC_ROOT"
echo

# Find repo roots: .git as a DIRECTORY (submodules use a .git FILE
# pointer, so they're naturally skipped). Sorted for stable log order.
mapfile -t REPOS < <(find "$root_dir" -type d -name .git 2>/dev/null | sort)

if (( ${#REPOS[@]} == 0 )); then
    echo "no git repos found under $root_dir" >&2
    exit 1
fi

new=0; refreshed=0; fail=0
for git_dir in "${REPOS[@]}"; do
    repo_path="${git_dir%/.git}"
    rel="${repo_path#$root_dir/}"
    rel_win="${rel//\//\\}"             # forward → backslash for Windows
    src_win=$(wslpath -w "$repo_path")  # /home/... → \\wsl$\Ubuntu\home\...
    dest_win="$DEST_UNC_ROOT\\${rel_win}.git"

    echo "=== $rel ==="
    echo "    src : $src_win"
    echo "    dest: $dest_win"

    # Bare-mirror layout has HEAD at the top of the .git/ dir; use that
    # as the "already initialized" signal.
    dest_exists=0
    [[ "$(ps_test_path "$dest_win\\HEAD")" == "True" ]] && dest_exists=1

    if (( dry_run )); then
        if (( dest_exists )); then
            echo "    would: git remote update  (additive, no prune)"
        else
            echo "    would: git clone --mirror  (then unset remote.origin.mirror)"
        fi
        echo
        continue
    fi

    if (( dest_exists )); then
        out=$("$GIT_EXE" "${GIT_SAFE[@]}" -C "$dest_win" remote update 2>&1)
        rc=$?
        if (( rc == 0 )); then
            echo "    refreshed"
            (( verbose )) && echo "$out" | sed 's/^/      /'
            refreshed=$((refreshed+1))
        else
            echo "    FAILED (exit $rc)" >&2
            echo "$out" | sed 's/^/      /' >&2
            fail=$((fail+1))
        fi
    else
        # Ensure intermediate dirs exist for nested repos (e.g.
        # orderplatform/bluejay → needs \\...\Git\orderplatform\ first).
        parent_win="${dest_win%\\*}"
        if [[ "$parent_win" != "$DEST_UNC_ROOT" ]]; then
            "$POWERSHELL" -NoProfile -Command \
                "New-Item -ItemType Directory -Force -Path '$parent_win' | Out-Null" 2>/dev/null || true
        fi

        out=$("$GIT_EXE" "${GIT_SAFE[@]}" clone --mirror "$src_win" "$dest_win" 2>&1)
        rc=$?
        if (( rc == 0 )); then
            # CRITICAL: unset mirror so future fetches don't prune. Without
            # this line, deleting a branch locally and re-running the backup
            # would delete it from the SAN too — defeating the whole point.
            "$GIT_EXE" "${GIT_SAFE[@]}" -C "$dest_win" \
                config --unset remote.origin.mirror 2>/dev/null || true
            echo "    NEW mirror created"
            (( verbose )) && echo "$out" | sed 's/^/      /'
            new=$((new+1))
        else
            echo "    FAILED to clone (exit $rc)" >&2
            echo "$out" | sed 's/^/      /' >&2
            fail=$((fail+1))
        fi
    fi
    echo
done

echo "Git mirror backup finished: $(date '+%Y-%m-%d %H:%M:%S')   (started $started)"
echo "  $new new, $refreshed refreshed, $fail failed"
exit $(( fail > 0 ? 1 : 0 ))
