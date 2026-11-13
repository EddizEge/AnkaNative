package dev.nxnative.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * InputStream.readAllBytes for every Android the app installs on: Android's java.io has it from API 33, the app's minSdk is 31 (android/README.md "Android
 * versions"). Reads to the end of the stream and leaves it open, as readAllBytes does. No Android classes: the JVM tests compile it with the classes that use it.
 */
final class Streams {
    private Streams() {}

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 << 10];
        for (int n; (n = in.read(buf)) != -1; ) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
