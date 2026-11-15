package dev.nxnative.android;

/** PadMenu on the JVM (dm/tools/tests/test_android_padmenu.py): the Home / guide key and the stick buttons' combination. Prints "ok" or throws */
public final class PadMenuJvmTest {
    private static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    public static void main(String[] args) {
        final int C = PadMenu.CONSUME, T = PadMenu.TOGGLE, L = PadMenu.THUMBL, R = PadMenu.THUMBR, A = 96;
        PadMenu m = new PadMenu();
        // Home / guide: consumed both ways (an unhandled BUTTON_MODE falls back to HOME), the up toggles; with or without the combination
        check(m.key(1, PadMenu.BUTTON_MODE, true, 0, false) == C, "Home down: consumed, no toggle");
        check(m.key(1, PadMenu.BUTTON_MODE, true, 1, false) == C, "Home repeat: consumed");
        check(m.key(1, PadMenu.BUTTON_MODE, false, 0, false) == (C | T), "Home up: toggles");
        check(m.key(1, PadMenu.BUTTON_MODE, false, 0, true) == (C | T), "Home up with the combination on: toggles");
        check(m.key(1, A, true, 0, true) == 0 && m.key(1, A, false, 0, true) == 0, "another key is not the panel's");
        // The combination off: the stick buttons are the game's
        check(m.key(1, L, true, 0, false) == 0 && m.key(1, R, true, 0, false) == 0, "off: both stick buttons go to the game");
        check(m.key(1, R, false, 0, false) == 0 && m.key(1, L, false, 0, false) == 0, "off: their ups too");
        // On: L3 down reaches the game, R3 down toggles and lets L3 go in the game; everything after is consumed until both are up
        check(m.key(1, L, true, 0, true) == 0, "L3 alone goes to the game");
        check(m.key(1, R, true, 0, true) == (C | T) && m.release == L, "R3 with L3 held: toggles, L3 let go in the game");
        check(m.key(1, L, true, 3, true) == C && m.release == 0, "a repeat after the toggle is consumed");
        check(m.key(1, L, false, 0, true) == C, "L3 up after the toggle: consumed");
        check(m.key(1, R, true, 0, true) == C, "R3 still held: a second down does not toggle again");
        check(m.key(1, R, false, 0, true) == C, "R3 up: consumed");
        check(m.key(1, R, true, 0, true) == 0, "both were up: R3 alone is the game's again");
        check(m.key(1, R, false, 0, true) == 0, "and its up");
        // The other order, and devices apart
        check(m.key(2, R, true, 0, true) == 0, "device 2: R3 first");
        check(m.key(3, L, true, 0, true) == 0, "device 3: L3 does not pair with device 2's R3");
        check(m.key(2, L, true, 0, true) == (C | T) && m.release == R, "device 2: L3 second toggles, R3 let go");
        m.removed(2);
        check(m.key(2, L, true, 0, true) == 0, "a removed device starts over");
        // A repeat before the toggle is the game's (the key is held, not pressed again)
        PadMenu n = new PadMenu();
        check(n.key(1, L, true, 0, true) == 0 && n.key(1, L, true, 1, true) == 0, "L3's repeat before any toggle: the game's");
        System.out.println("ok");
    }
}
