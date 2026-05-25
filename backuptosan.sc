#!/usr/bin/env bash
# Back up Documents, Pictures, Videos from this Windows profile to the
# Synology SAN at \\Media-Server\store. Bash launcher; the actual copy
# is done by Windows' robocopy via WSL interop. Uses UNC paths instead
# of the Z: mapping so the script works under Task Scheduler "run
# whether user is logged on or not" (mapped drives are per-session).
#
# Behavior: ADDITIVE backup — files deleted locally are NEVER deleted
# on the SAN. (No /MIR, no /PURGE.)
#
# Usage:
#   backuptosan.sc [--dry-run] [--verbose] [--no-progress] [--interval N] [--no-organize]
#     --dry-run        List what would be copied without writing anything
#     --verbose        Show per-file actions (default: summary only)
#     --no-progress    Skip the periodic progress sidecar
#     --interval N     Progress poll interval in seconds (default 60)
#     --no-organize    Skip the organizephotos.sc pre-step on Pictures/download/

# Intentionally NO `set -e`: robocopy uses a bitwise exit code where
# values < 8 are success/partial-success; -e would treat them as failure.
set -uo pipefail

ROBOCOPY=/mnt/c/Windows/System32/robocopy.exe
POWERSHELL=/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe
[[ -x "$ROBOCOPY"   ]] || { echo "robocopy.exe not found at $ROBOCOPY" >&2; exit 1; }
[[ -x "$POWERSHELL" ]] || { echo "powershell.exe not found at $POWERSHELL" >&2; exit 1; }

dry_run=0
verbose=0
progress=1
interval=60
organize=1
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run)     dry_run=1 ;;
        --verbose)     verbose=1 ;;
        --no-progress) progress=0 ;;
        --no-organize) organize=0 ;;
        --interval)    [[ $# -ge 2 ]] || { echo "--interval needs a value" >&2; exit 1; }
                       interval="$2"; shift ;;
        -h|--help)
            sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) echo "unknown arg: $1" >&2; exit 1 ;;
    esac
    shift
done

# Dry-run has nothing to track — disable progress in that mode.
(( dry_run )) && progress=0

# Each job: name | wsl-path-for-existence-check | windows-source | windows-dest | extra robocopy args
# Pictures excludes the staging "download" folder — that's where new
# photos land before organizephotos.sc files them into YYYY/YYYY_MM_DD/.
# Documents intentionally does NOT use /XA:O: we want every file on the
# SAN including OneDrive "online-only" placeholders. Reading them will
# cause OneDrive to hydrate (download) each file as robocopy touches it.
JOBS=(
    'Documents|/mnt/c/Users/oomme/OneDrive/Documents|C:\Users\oomme\OneDrive\Documents|\\Media-Server\store\Documents\oommen|'
    'Pictures|/mnt/c/Users/oomme/Pictures|C:\Users\oomme\Pictures|\\Media-Server\store\Media\Pictures|/XD C:\Users\oomme\Pictures\download'
    'Videos|/mnt/c/Users/oomme/Videos|C:\Users\oomme\Videos|\\Media-Server\store\Media\Videos|'
)

# Robocopy flags. NOT using /MIR or /PURGE — keeps backup additive.
#   /E          recurse including empty dirs
#   /COPY:DAT   data + attributes + timestamps (no ACLs — SMB-safe)
#   /DCOPY:T    preserve directory timestamps
#   /R:2 /W:5   retry twice with a 5s wait on transient errors
#   /MT:8       8 worker threads
#   /XO         skip if dest is newer than source (extra safety)
#   /XJD        skip directory junction points — Documents contains
#               "My Pictures"/"My Videos"/"My Music" legacy junctions
#               with deny ACLs that would otherwise fail the job (and
#               would also double-back-up the Pictures/Videos trees).
#   /XF ...     exclude Windows junk + Office lock files
#   /NDL /NP    no directory list, no per-file % progress
RC_OPTS=(
    /E /COPY:DAT /DCOPY:T
    /R:2 /W:5 /MT:8
    /XO
    /XJD
    /XF 'Thumbs.db' 'desktop.ini' '~$*'
    /NDL /NP
)
(( dry_run )) && RC_OPTS+=(/L)
(( verbose )) || RC_OPTS+=(/NFL)

human_bytes() {
    local b=${1:-0}
    awk -v b="$b" 'BEGIN{
        split("B KB MB GB TB PB", u);
        i=1; while (b >= 1024 && i < 6) { b/=1024; i++ }
        if (i == 1) printf "%d %s", b, u[i];
        else        printf "%.1f %s", b, u[i];
    }'
}

format_duration() {
    local s=${1:-0}
    if   (( s < 60 ));   then printf '%ds' "$s"
    elif (( s < 3600 )); then printf '%dm %02ds' $((s/60)) $((s%60))
    else                      printf '%dh %02dm' $((s/3600)) $(((s%3600)/60))
    fi
}

# Walk a WSL source tree; emit "<count> <bytes>". Honors the same exclusions
# robocopy uses (Thumbs.db, desktop.ini, ~$*, plus per-job /XD dirs).
scan_source() {
    local path="$1" extra="$2"
    local -a args=("$path" -type f
        -not -iname 'Thumbs.db' -not -iname 'desktop.ini' -not -iname '~$*')
    # Translate any /XD <win-path> tokens in $extra to /mnt/<drive>/... exclusions.
    local prev=""
    for tok in $extra; do
        if [[ "$prev" == "/XD" ]]; then
            local wsl_path
            wsl_path=$(echo "$tok" | sed -E 's|^([A-Za-z]):|/mnt/\L\1|; s|\\|/|g')
            args+=(-not -path "$wsl_path" -not -path "$wsl_path/*")
        fi
        prev="$tok"
    done
    find "${args[@]}" -printf '%s\n' 2>/dev/null \
        | awk 'BEGIN{n=0;s=0} {n++; s+=$1} END{print n+0, s+0}'
}

# Walk a UNC destination via PowerShell; emit "<count> <bytes>".
# Returns "0 0" if the path doesn't exist yet (first-run case).
scan_dest() {
    local path="$1"
    "$POWERSHELL" -NoProfile -Command "
        \$ErrorActionPreference='SilentlyContinue';
        \$r = Get-ChildItem -LiteralPath '$path' -Recurse -File | Measure-Object -Sum -Property Length;
        '{0} {1}' -f [int64](\$r.Count + 0), [int64](\$r.Sum + 0)
    " 2>/dev/null | tr -d '\r' | tail -1
    # Fall back to "0 0" if PowerShell printed nothing.
}

interpret() {
    local code=$1 name=$2
    if   (( code == 0 )); then echo "[$name] no changes"
    elif (( code <  8 )); then echo "[$name] OK (robocopy code $code)"
    else                       echo "[$name] FAILED (robocopy code $code)" >&2
    fi
}

started=$(date '+%Y-%m-%d %H:%M:%S')
suffix=""
(( dry_run )) && suffix="  (DRY RUN)"
echo "Backup started: $started$suffix"
echo

# Pre-step: run organizephotos.sc on the Pictures/download/ staging folder
# so newly-arrived files get filed into YYYY/YYYY_MM_DD/ before backup.
# Only the download/ folder is processed — running organize against the
# whole library would do a useless full-tree EXIF rescan on every backup.
ORGANIZE_SCRIPT="$(dirname "$0")/organizephotos.sc"
# Keep these in sync with the Pictures/Videos rows in JOBS above —
# they're the destinations the organize step files things into, and
# the sources the backup jobs read from immediately afterward.
PICTURES_ROOT=/mnt/c/Users/oomme/Pictures
VIDEOS_ROOT=/mnt/c/Users/oomme/Videos
DOWNLOAD_DIR="$PICTURES_ROOT/download"

if (( organize )) && (( ! dry_run )); then
    if [[ ! -x "$ORGANIZE_SCRIPT" ]]; then
        echo "skip organize: $ORGANIZE_SCRIPT not found or not executable" >&2
    elif [[ ! -d "$DOWNLOAD_DIR" ]]; then
        echo "skip organize: $DOWNLOAD_DIR does not exist" >&2
    elif [[ -z "$(find "$DOWNLOAD_DIR" -type f -print -quit 2>/dev/null)" ]]; then
        echo "=== Organize ==="
        echo "    $DOWNLOAD_DIR is empty, nothing to organize"
    else
        echo "=== Organize ==="
        echo "    source : $DOWNLOAD_DIR"
        echo "    photos -> $PICTURES_ROOT"
        echo "    videos -> $VIDEOS_ROOT"
        if ! "$ORGANIZE_SCRIPT" "$DOWNLOAD_DIR" "$PICTURES_ROOT" --videos "$VIDEOS_ROOT"; then
            echo "    organize step failed — continuing with backup anyway" >&2
        fi
    fi
    echo
fi

fail=0
for job in "${JOBS[@]}"; do
    IFS='|' read -r name src_wsl src_win dest_win extra <<<"$job"
    if [[ ! -d "$src_wsl" ]]; then
        echo "[$name] skip: source missing ($src_wsl)" >&2
        continue
    fi
    read -ra extra_args <<<"$extra"
    echo "=== $name ==="
    echo "    $src_win  ->  $dest_win"
    [[ -n "$extra" ]] && echo "    extra: $extra"

    src_count=0; src_bytes=0; base_count=0; base_bytes=0
    if (( progress )); then
        printf '    scanning source... '
        read -r src_count src_bytes < <(scan_source "$src_wsl" "$extra")
        printf '%s files, %s\n' "$src_count" "$(human_bytes "$src_bytes")"
        printf '    scanning destination baseline... '
        read -r base_count base_bytes < <(scan_dest "$dest_win")
        : "${base_count:=0}"; : "${base_bytes:=0}"
        printf '%s files, %s\n' "$base_count" "$(human_bytes "$base_bytes")"
    fi

    job_start=$(date +%s)
    "$ROBOCOPY" "$src_win" "$dest_win" "${RC_OPTS[@]}" "${extra_args[@]}" &
    rc_pid=$!

    poller_pid=""
    if (( progress )); then
        (
            # Files-to-copy denominator. If src already <= base (incremental
            # run with little to do), denom may be 0 — we handle that below.
            files_total=$(( src_count - base_count )); (( files_total < 0 )) && files_total=0
            bytes_total=$(( src_bytes - base_bytes )); (( bytes_total < 0 )) && bytes_total=0
            while kill -0 "$rc_pid" 2>/dev/null; do
                sleep "$interval"
                kill -0 "$rc_pid" 2>/dev/null || break
                read -r cur_count cur_bytes < <(scan_dest "$dest_win")
                : "${cur_count:=0}"; : "${cur_bytes:=0}"
                done_count=$(( cur_count - base_count )); (( done_count < 0 )) && done_count=0
                done_bytes=$(( cur_bytes - base_bytes )); (( done_bytes < 0 )) && done_bytes=0
                elapsed=$(( $(date +%s) - job_start ))
                pct=0; eta="--"
                if (( bytes_total > 0 )); then
                    pct=$(( done_bytes * 100 / bytes_total ))
                    if (( done_bytes > 0 )); then
                        remaining=$(( elapsed * bytes_total / done_bytes - elapsed ))
                        (( remaining > 0 )) && eta=$(format_duration "$remaining")
                    fi
                fi
                printf '    [%s] +%d/%d files  %s/%s  (%d%%)  elapsed %s  ETA ~%s\n' \
                    "$name" "$done_count" "$files_total" \
                    "$(human_bytes "$done_bytes")" "$(human_bytes "$bytes_total")" \
                    "$pct" "$(format_duration "$elapsed")" "$eta"
            done
        ) &
        poller_pid=$!
    fi

    wait "$rc_pid"
    code=$?
    if [[ -n "$poller_pid" ]]; then
        kill "$poller_pid" 2>/dev/null || true
        wait "$poller_pid" 2>/dev/null || true
    fi
    interpret "$code" "$name"
    (( code >= 8 )) && fail=1
    echo
done

echo "Backup finished: $(date '+%Y-%m-%d %H:%M:%S')   (started $started)"
exit $fail
