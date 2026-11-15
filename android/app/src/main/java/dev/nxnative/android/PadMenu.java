package dev.nxnative.android;

import java.util.HashMap;

/**
 * Which gamepad keys open and close the in-game panel (GameActivity.dispatchKeyEvent), without Android classes so the JVM test runs it
 * (dm/tools/tests/test_android_padmenu.py).
 * <ul>
 * <li>The Home / guide / PS key: Android's key layouts give it KEYCODE_BUTTON_MODE (Generic.kl: BTN_MODE; the DualShock 4's own layouts,
 *     Vendor_054c_Product_05c4 / 09cc / 0ba0, over Bluetooth and USB: BTN_MODE 0x13c). Its down is consumed and its up toggles the panel. It must be consumed
 *     either way: Generic.kcm gives BUTTON_MODE the fallback HOME, so a BUTTON_MODE the app leaves unhandled comes back as HOME, and the system goes to the home
 *     screen. A pad whose Home is a HID "AC Home" usage (KEY_HOMEPAGE, 172) without a layout of its own gets KEYCODE_HOME from Generic.kl, which the system
 *     takes before any app (KeyEvent.KEYCODE_HOME is never delivered to an app): no app can see that key, hence the next item.</li>
 * <li>Both stick buttons together (L3 + R3), when the player turned it on (Prefs.stickMenu, off by default): for a controller whose Home key does not reach the
 *     app. The second stick button's down toggles the panel; the first one's down has already reached the game, so the caller lets it go there ({@link #release});
 *     both keys' further events (repeats, ups) are consumed until both are up.</li>
 * </ul>
 */
final class PadMenu {
    /** KeyEvent.KEYCODE_BUTTON_MODE, _BUTTON_THUMBL, _BUTTON_THUMBR */
    static final int BUTTON_MODE = 110, THUMBL = 106, THUMBR = 107;
    /** {@link #key}'s bits: the event is consumed (the game and the view system do not get it); the panel opens or closes */
    static final int CONSUME = 1, TOGGLE = 2;

    private static final int L = 1, R = 2, TAKEN = 4;
    private final HashMap<Integer, Integer> sticks = new HashMap<>();   // per device: the stick buttons held (L, R), TAKEN once they toggled the panel
    /** After a TOGGLE by the stick buttons: the stick button the game saw go down, which the caller lets go there; 0 otherwise */
    int release;

    /** One gamepad key event: down or up, its repeat count, whether the stick buttons' combination is on. Returns CONSUME and TOGGLE bits; 0 = not the panel's */
    int key(int device, int keyCode, boolean down, int repeat, boolean sticksOn) {
        release = 0;
        if (keyCode == BUTTON_MODE) return down ? CONSUME : CONSUME | TOGGLE;
        int bit = keyCode == THUMBL ? L : keyCode == THUMBR ? R : 0;
        if (bit == 0) return 0;
        Integer h = sticks.get(device);
        int held = h == null ? 0 : h;
        if (down) {
            if (repeat > 0 || (held & bit) != 0) return (held & TAKEN) != 0 ? CONSUME : 0;
            held |= bit;
            int r = 0;
            if ((held & (L | R)) == (L | R) && sticksOn && (held & TAKEN) == 0) { held |= TAKEN; release = bit == L ? THUMBR : THUMBL; r = CONSUME | TOGGLE; }
            else if ((held & TAKEN) != 0) r = CONSUME;
            sticks.put(device, held);
            return r;
        }
        boolean taken = (held & TAKEN) != 0;
        held &= ~bit;
        if ((held & (L | R)) == 0) held = 0;   // both up: the next press starts over
        if (held == 0) sticks.remove(device); else sticks.put(device, held);
        return taken ? CONSUME : 0;
    }

    /** A device gone: what it held is forgotten */
    void removed(int device) { sticks.remove(device); }
}
