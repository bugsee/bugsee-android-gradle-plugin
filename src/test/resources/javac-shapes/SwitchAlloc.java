import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Provenance for SwitchAlloc.class (issue #42). Needs a Java 14+ compiler, but the unit tests
 * run on a JDK 11 toolchain, so the compiled class is checked in. Regenerate with
 * regen.sh in this directory (javac --release 17, then patch the class-file major version to
 * Java 11; safe because a plain int switch uses no post-11 constant-pool features).
 *
 * javac keeps the pending uninitialized `new FileInputStream` in locals across the try block
 * (an exception clears the operand stack), which is exactly the non-LIFO NEW/init shape.
 */
public class SwitchAlloc {
    public static Object open(int k, File f) throws IOException {
        try (FileInputStream i = new FileInputStream(switch (k) {
            case 0 -> {
                try {
                    yield f.getCanonicalFile();
                } catch (IOException e) {
                    yield f;
                }
            }
            default -> f;
        })) {
            return i.getClass().getName();
        }
    }
}
