package nl.rene.tools;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** JSON veilig vervangen, met een vorige leesbare versie; onleesbaar is nooit hetzelfde als leeg. */
final class JsonFileStore {
    private JsonFileStore() { }
    private static final long MAX = 30_000_000L;
    interface Validator { void check(String text) throws Exception; }
    private static File backup(File f) { return new File(f.getPath() + ".bak"); }
    static boolean exists(File file) {
        for (String suffix : new String[]{"", ".bak", ".tmp", ".bak.tmp", ".beschadigd"})
            if (new File(file.getPath() + suffix).exists()) return true;
        return false;
    }

    /** Een bewuste wisactie moet ook alle herstel- en tijdelijke kopieën verwijderen. */
    static void delete(File file) throws IOException {
        for (String suffix : new String[]{".bak", ".bak.tmp", ".tmp", ".beschadigd", ""}) {
            File part = new File(file.getPath() + suffix);
            if (part.exists() && !part.delete()) throw new IOException("Wissen lukt niet: " + part.getName());
        }
    }

    static String read(File file, Validator validator) throws Exception {
        File bak = backup(file);
        if (!file.exists() && !bak.exists()) return "{}";
        try { return checked(file, validator); }
        catch (Exception original) {
            try { return checked(bak, validator); }
            catch (Exception unavailable) { throw new IOException("De opgeslagen gegevens zijn niet leesbaar; het bestand is bewaard", original); }
        }
    }

    private static String checked(File file, Validator validator) throws Exception {
        String text = WebUpdateStore.text(file, MAX); validator.check(text); return text;
    }

    static void write(File file, String value, Validator validator) throws Exception {
        validator.check(value);
        boolean existed = file.exists() || backup(file).exists();
        String previous = existed ? read(file, validator) : null; // weigert een onleesbaar bestand door een lege verzameling te vervangen
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Opslagmap maken lukt niet");
        if (file.exists()) {
            try { checked(file, validator); }
            catch (Exception corrupt) {
                File aside = new File(file.getPath() + ".beschadigd");
                if (aside.exists()) throw new IOException("Er is een beschadigd bestand bewaard; herstel eerst de opgeslagen gegevens");
                if (!file.renameTo(aside)) throw new IOException("Het beschadigde bestand kan niet veilig worden bewaard");
            }
        }
        if (previous != null) replace(backup(file), previous);
        replace(file, value);
    }

    /** Tijdelijke waarnemingen: ook de herstelkopie bevat uitsluitend de huidige, al gesnoeide inhoud. */
    static void writeCurrent(File file, String value, Validator validator) throws Exception {
        validator.check(value);
        if (exists(file)) read(file, validator); // onleesbare opslag nooit als leeg behandelen
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Opslagmap maken lukt niet");
        for (String suffix : new String[]{".beschadigd", ".tmp", ".bak.tmp"}) {
            File stale = new File(file.getPath() + suffix);
            if (stale.exists() && !stale.delete()) throw new IOException("Oude waarnemingen opruimen lukt niet");
        }
        if (same(file, value) && same(backup(file), value)) return;
        // Eerst een gesnoeide herstelkopie: ook bij een onderbreking wordt nooit een verlopen backup teruggezet.
        replace(backup(file), value);
        replace(file, value);
    }

    private static boolean same(File file, String value) {
        try { return WebUpdateStore.text(file, MAX).equals(value); } catch (Exception e) { return false; }
    }

    private static void replace(File file, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX) throw new IOException("De opgeslagen gegevens zijn te groot");
        File tmp = new File(file.getPath() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(bytes); out.getFD().sync(); }
            if (!tmp.renameTo(file)) throw new IOException("Opslaan lukt niet; de vorige gegevens zijn bewaard");
        } finally { tmp.delete(); }
    }
}
