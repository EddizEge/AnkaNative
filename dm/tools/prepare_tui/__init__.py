# The terminal interface of scripts/prepare.py: preparing a game on a computer (android/README.md, "Pack a game") without remembering the packer's
# command line. Standard library only, on Windows, Linux and the Mac. The modules:
#   texts.py     every text the interface shows, in one table (translations would be one more table each)
#   term.py      the terminal: keys (escape sequences and the Windows console's scan codes), raw mode, the alternate screen, and putting it all back
#   screen.py    an in-memory screen the views draw into, its glyphs (Unicode or ASCII) and its output as escape sequences
#   progress.py  the packer's output lines: steps, progress in the tools' three spellings, error: and [RED] lines
#   eta.py       the time left, the phone's estimator (android/app ondevice Eta.java) on the computer's steps and figures
#   browser.py   the file browser's folder listing and the state file (the last folder, recent folders)
#   checks.py    what the packer needs, found or missing, with the fix for this system
#   runner.py    pack_android.py as a child process: its lines, its log file, stopping it
#   model.py     what the screens show: the pick (pack_android.identify), the plan, the run as its lines tell it, the time in words
#   app.py       the screens (checks, pick, confirm, run, done) in full-screen and plain line mode
