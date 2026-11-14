#!/usr/bin/env python3
# Prepare a game for the Android launcher on this computer, in a terminal interface (android/README.md, "Pack a game"): Windows (Windows Terminal,
# PowerShell, cmd.exe on Windows 10 and 11), Linux and the Mac, with nothing beyond the Python the packer needs (standard library only; dm/tools/prepare_tui).
# Its screens, in order:
#   1. what the packer needs (Python 3.14, Capstone 5, lz4, the ZBIC decoder built and loaded with this computer's C compiler, the .NET 10 runtime running the
#      committed translator, spirv-val, this copy's line ends), each found or missing with the fix for this system; a missing one stops here (exit code 1)
#   2. the game: a file browser for a .nup file or a dump folder (arrows, Enter, Backspace / Left up, Home / End / PgUp / PgDn, typing filters, Ctrl+L or / types a
#      path, Tab the places: home, recent folders, drives or volumes), the pick checked with the packer's own code (pack_android.identify); the last folder is kept
#      in the user's config folder (prepare_tui/browser.py)
#   3. the plan: game, version, pack folder, workers (from the CPU count and free memory), the disk space it needs against what is free, what an earlier run
#      left that is kept, a rough time
#   4. the run: scripts/pack_android.py as a child process, its steps, a bar for the step and one for the whole job, the rate, the time left and the clock time it
#      should be done at (the phone's estimator: prepare_tui/eta.py), the last lines, the warnings; the whole output in <pack folder>/../prepare-logs/
#      prepare-<time>.log. q or Ctrl+C asks, then stops it cleanly; starting again goes on from what was finished
#   5. the end: where the pack is, its size, the time, and what to do on the phone; or the failure in plain words (dm/tools/pack_errors.py's codes), its last lines
#      and the log
# When stdin or stdout is not a terminal, TERM is dumb, the console takes no escape sequences, or with --plain, the same questions and progress come as lines.
# NO_COLOR (or --no-color) turns the colours off; --ascii draws with ASCII only (the default when the console's encoding cannot print the box characters).
# Usage: prepare.py [<game: .nup file or dump folder>] [--version N] [--out DIR] [-J N] [-j N] [--shader-cache DIR] [--shader-pkg DIR] [--folder DIR] [--plain]
#                   [--yes] [--ascii] [--no-color]
# Exit code: 0 the pack is ready, 1 the run failed or something it needs is missing, 2 left before a run, 3 the run was stopped, 128 + the signal when SIGHUP (a
# closed window) or SIGTERM ended it (its run stopped first).
import argparse, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'dm/tools'))
from prepare_tui import app   # noqa: E402


def main():
    ap = argparse.ArgumentParser(description='Prepare a game for the Android launcher on this computer: a terminal interface around pack_android.py')
    ap.add_argument('game', nargs='?', help='the game: a .nup file or a dump folder (otherwise picked on screen)')
    ap.add_argument('--version', type=int, help='the update version to pack (default: the newest; 0 = the base game)')
    ap.add_argument('--out', help='the pack folder (default: <title ID>-work/android-pack beside the game)')
    ap.add_argument('-J', type=int, help='shaders translated at once (default: from the CPU threads and the free memory)')
    ap.add_argument('-j', type=int, help="modules rewritten at once (default: the packer's)")
    ap.add_argument('--shader-cache', help='the folder of shader libraries (pack_android.py --shader-cache)')
    ap.add_argument('--shader-pkg', help="the game's shader package (pack_android.py --shader-pkg)")
    ap.add_argument('--folder', dest='start_folder', help='the folder the browser opens in (default: the last one used)')
    ap.add_argument('--plain', action='store_true', help='questions and progress as lines, not full-screen')
    ap.add_argument('--yes', action='store_true', help='plain mode: start without asking (the game must be given)')
    ap.add_argument('--ascii', action='store_true', help='draw with ASCII characters only')
    ap.add_argument('--no-color', action='store_true', help='no colours (as NO_COLOR)')
    sys.exit(app.main(ap.parse_args()))


if __name__ == '__main__':
    main()
