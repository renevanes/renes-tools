package nl.rene.tools;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RecordingFilesTest {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void rejected(File folder, String name) throws Exception {
        try { RecordingFiles.resolve(folder, name); throw new AssertionError("Toegang toegestaan: " + name); }
        catch (java.io.IOException expected) { }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("recording-files-test-");
        String name = "opname-1791244800000-0123456789abcdef0123456789abcdef.m4a";
        try {
            Path folder = Files.createDirectory(root.resolve("recordings"));
            Files.write(folder.resolve(name), new byte[]{1, 2, 3});
            check(RecordingFiles.validName(name), "geldige opname");
            check(RecordingFiles.resolve(folder.toFile(), name).length() == 3, "eigen afgeronde opname leesbaar");
            rejected(folder.toFile(), "../" + name);
            rejected(folder.toFile(), name + ".part");
            rejected(folder.toFile(), "ander-bestand.m4a");
            rejected(folder.toFile(), null);
            rejected(folder.toFile(), "opname-1791244800001-0123456789abcdef0123456789abcdef.m4a");
            Files.delete(folder.resolve(name));
            Path outside = Files.write(root.resolve("private-data"), new byte[]{4});
            Files.createSymbolicLink(folder.resolve(name), outside);
            rejected(folder.toFile(), name);
            System.out.println("Alle 8 opnamebestand-tests geslaagd");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (Exception e) { throw new RuntimeException(e); } });
            }
        }
    }
}
