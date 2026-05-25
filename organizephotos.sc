#!/usr/bin/env bash
# Organize photos and videos into YYYY/YYYY_MM_DD/ folders using EXIF data.
# Date source priority:  DateTimeOriginal -> CreateDate -> FileModifyDate
#
# Photos go to <dest_dir>; videos go to a sibling "Videos" tree
# (override with --videos <dir>).
#
# Usage:
#   organizephotos.sc <source_dir> [dest_dir] [--videos <dir>] [--dry-run]
#
# Notes:
#   - dest_dir defaults to source_dir (in-place reorganization).
#   - videos_dir defaults to "$(dirname dest_dir)/Videos" — e.g. dest
#     "/mnt/c/Users/oomme/Pictures" sends videos to "/mnt/c/Users/oomme/Videos".
#   - Requires exiftool. Install:
#       Debian/Ubuntu/WSL:  sudo apt install libimage-exiftool-perl
#       macOS (Homebrew):   brew install exiftool

set -euo pipefail

if ! command -v exiftool >/dev/null 2>&1; then
    echo "exiftool not found. Install: sudo apt install libimage-exiftool-perl" >&2
    exit 1
fi

usage() {
    echo "Usage: $0 <source_dir> [dest_dir] [--videos <dir>] [--dry-run]" >&2
    exit 1
}

src=""
dest=""
videos_dest=""
dry_run=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) dry_run=1; shift ;;
        --videos)  [[ $# -ge 2 ]] || usage; videos_dest="$2"; shift 2 ;;
        -h|--help) usage ;;
        --)        shift; break ;;
        -*)        usage ;;
        *)
            if   [[ -z "$src"  ]]; then src="$1"
            elif [[ -z "$dest" ]]; then dest="$1"
            else usage
            fi
            shift ;;
    esac
done
[[ -n "$src" ]] || usage
dest="${dest:-$src}"
videos_dest="${videos_dest:-$(dirname "$dest")/Videos}"

[[ -d "$src" ]] || { echo "Source not found: $src" >&2; exit 1; }

PHOTO_EXTS=(-ext jpg -ext jpeg -ext png -ext heic -ext heif
            -ext gif -ext tif -ext tiff -ext webp -ext bmp -ext raw
            -ext cr2 -ext nef -ext arw -ext dng)
VIDEO_EXTS=(-ext mp4 -ext mov -ext avi -ext mkv -ext m4v -ext 3gp -ext mts -ext m2ts)

DATEFMT='%Y/%Y_%m_%d'

# Fallback chain trick: exiftool assigns to -Directory in order; later
# assignments override earlier ones ONLY if their source tag is defined.
# So put least-preferred first, most-preferred last.
run_pass() {
    local target="$1"; shift
    local exts=("$@")

    if (( dry_run )); then
        exiftool -r -q -m -api LargeFileSupport=1 "${exts[@]}" \
            -p '$Directory/$FileName -> '"$target"'/${DateTimeOriginal;
                  $_ ||= $self->GetValue("CreateDate");
                  $_ ||= $self->GetValue("FileModifyDate");
                  DateFmt("'"$DATEFMT"'")}/$FileName' \
            "$src"
    else
        mkdir -p "$target"
        exiftool -r -progress -m -api LargeFileSupport=1 "${exts[@]}" \
            '-Directory<FileModifyDate'   \
            '-Directory<CreateDate'       \
            '-Directory<DateTimeOriginal' \
            -d "$target/$DATEFMT" \
            "$src"
    fi
}

echo "=== Photos -> $dest ==="
run_pass "$dest"        "${PHOTO_EXTS[@]}"
echo
echo "=== Videos -> $videos_dest ==="
run_pass "$videos_dest" "${VIDEO_EXTS[@]}"
