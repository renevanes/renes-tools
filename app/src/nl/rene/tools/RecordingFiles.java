package nl.rene.tools;

import java.io.File;
import java.io.IOException;

/** Alleen afgeronde, eigen opnames kunnen via de brug of provider worden geopend. */
final class RecordingFiles {
    private RecordingFiles() { }
    static boolean validName(String name) { return name != null && name.matches("opname-[0-9]{13}-[0-9a-f]{32}\\.m4a"); }
    static File resolve(File dir, String name) throws IOException {
        if (!validName(name)) throw new IOException("Opname niet gevonden");
        File file = new File(dir, name);
        if (!file.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile()) || !file.isFile()) throw new IOException("Opname niet gevonden");
        return file;
    }
}
