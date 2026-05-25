# scripts

Personal utility scripts for managing a Windows photo/video library from WSL.

The intended workflow is:

1. New photos/videos land in `C:\Users\oomme\Pictures\download\` (camera dumps, downloads, etc.).
2. **`organizephotos.sc`** files them by EXIF date into `YYYY/YYYY_MM_DD/` folders, splitting photos and videos into separate trees.
3. **`backuptosan.sc`** copies the organized library to the Synology SAN at `\\Media-Server\store`.

Both scripts are run from WSL bash. They expect to be executed manually (no cron/daemon), although `backuptosan.sc` is well-suited to Windows Task Scheduler.

---

## Prerequisites

| Tool | Used by | Install (Debian/Ubuntu/WSL) |
| --- | --- | --- |
| `exiftool` | `organizephotos.sc` | `sudo apt install libimage-exiftool-perl` |
| `robocopy.exe` | `backuptosan.sc` | Ships with Windows — invoked via WSL interop |
| `rsync` | (not used currently) | — |

---

## `organizephotos.sc`

Reorganizes photos and videos into `YYYY/YYYY_MM_DD/` folders based on EXIF metadata.

**Date resolution** follows a fallback chain so files without proper EXIF still get placed sensibly:

1. `DateTimeOriginal` (the moment the camera shutter fired — preferred)
2. `CreateDate`
3. `FileModifyDate`

Photos and videos go to separate trees. By default, videos land in a sibling `Videos/` folder next to the photos destination.

### Usage

```bash
./organizephotos.sc <source_dir> [dest_dir] [--videos <dir>] [--dry-run]
```

| Argument | Meaning |
| --- | --- |
| `<source_dir>` | Directory to scan recursively |
| `[dest_dir]` | Photo destination root. Defaults to `<source_dir>` (in-place reorganization) |
| `--videos <dir>` | Video destination root. Defaults to `$(dirname dest_dir)/Videos` |
| `--dry-run` | Print the proposed moves without changing anything |

### Examples

```bash
# Reorganize Pictures in place; videos go to sibling Videos/ folder
./organizephotos.sc /mnt/c/Users/oomme/Pictures

# Preview before committing
./organizephotos.sc /mnt/c/Users/oomme/Pictures --dry-run

# Send the organized output to a different tree
./organizephotos.sc /mnt/c/Users/oomme/Pictures/download \
                    /mnt/c/Users/oomme/Pictures
```

### File types

- **Photos**: `jpg jpeg png heic heif gif tif tiff webp bmp raw cr2 nef arw dng`
- **Videos**: `mp4 mov avi mkv m4v 3gp mts m2ts`

### Notes

- The script uses `exiftool`'s `-Directory<...` mechanism to move files, so the operation is atomic per-file and respects EXIF on `.MOV`/`.MP4` (which carry QuickTime/MP4 creation metadata).
- `-api LargeFileSupport=1` is enabled so videos larger than 4 GB are handled correctly.
- Files are **moved**, not copied. Run `--dry-run` first on any directory you care about.

---

## `backuptosan.sc`

Backs up Documents, Pictures, and Videos from the local Windows profile to the Synology SAN.

**Backup behavior is additive** — files deleted locally are **never** deleted on the SAN. This is a safety-net backup, not a mirror.

### Destinations

| Source | Destination on SAN |
| --- | --- |
| `C:\Users\oomme\Documents` | `\\Media-Server\store\Documents\oommen` |
| `C:\Users\oomme\Pictures` | `\\Media-Server\store\Media\Pictures` |
| `C:\Users\oomme\Videos` | `\\Media-Server\store\Media\Videos` |

The Pictures job **excludes `C:\Users\oomme\Pictures\download\`** — that's the staging folder for new files awaiting `organizephotos.sc`.

UNC paths are used (rather than the `Z:` mapping) so the script works under Windows Task Scheduler's "run whether user is logged on or not" mode, where per-user drive mappings are not available.

### Usage

```bash
./backuptosan.sc [--dry-run] [--verbose]
```

| Flag | Meaning |
| --- | --- |
| `--dry-run` | List what would be copied without writing anything (robocopy `/L`) |
| `--verbose` | Show per-file actions (default: per-job summary only) |

### Examples

```bash
./backuptosan.sc --dry-run     # preview
./backuptosan.sc               # run backup
./backuptosan.sc --verbose     # noisy run, lists every file
```

### Robocopy flags

The script invokes `robocopy.exe` with the following options:

| Flag | Effect |
| --- | --- |
| `/E` | Recurse including empty directories |
| `/COPY:DAT` | Copy Data, Attributes, Timestamps (no ACLs — SMB-safe) |
| `/DCOPY:T` | Preserve directory timestamps |
| `/R:2 /W:5` | Retry twice with 5-second waits on transient errors |
| `/MT:8` | 8 worker threads |
| `/XO` | Skip files where the destination is newer than the source |
| `/XF Thumbs.db desktop.ini ~$*` | Exclude Windows junk and Office lock files |
| `/NDL /NP` | Quiet output — no directory list, no per-file % progress |
| `/NFL` | Added unless `--verbose` — silences the per-file action list |
| `/L` | Added with `--dry-run` |

Pass-through extras per job are supported via the 5th column of the `JOBS` array — that's how the Pictures `/XD` exclusion is wired.

### Exit codes

Robocopy uses a bitwise exit code: values below 8 are success or partial-success (copies/skips/extras); values 8 and above are real failures. The script preserves this convention and exits non-zero only on real failures. `set -e` is intentionally disabled.

### Running under Task Scheduler

- **Program/script**: `wsl.exe`
- **Arguments**: `-- /home/oommen/work/git/scripts/backuptosan.sc`
- **Account**: your normal Windows user, with "Run whether user is logged on or not" and "Run with highest privileges" checked.
- The task account needs read access to `\\Media-Server\store`.

---

## Workflow example

```bash
# 1. Dump phone/camera into C:\Users\oomme\Pictures\download

# 2. Organize: moves files into Pictures\YYYY\YYYY_MM_DD\
#    and videos into Videos\YYYY\YYYY_MM_DD\
./organizephotos.sc /mnt/c/Users/oomme/Pictures

# 3. Push the organized library to the SAN
./backuptosan.sc
```
while true; do
    printf '%s  ' "$(date +%H:%M:%S)"
    /mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe -NoProfile -Command "\$r = Get-ChildItem -LiteralPath '\\Media-Server\store\Media\Pictures' -Recurse -File -ErrorAction SilentlyContinue | Measure-Object -Sum -Property Length; '{0} files, {1:N2} GB' -f \$r.Count, (\$r.Sum / 1GB)" 2>/dev/null | tr -d '\r'
    sleep 2
done