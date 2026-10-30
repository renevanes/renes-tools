package nl.rene.tools;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;

/** Fouten bij sluiten, onderbroken activatie, beschadigde opslag en begrensd downloaden. */
public final class StorageUpdateTest {
    private static int checks;
    private static void check(boolean condition, String text) { checks++; if (!condition) throw new AssertionError(text); }
    static final class Target extends OutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final String fail;
        boolean closed;
        Target(String fail) { this.fail = fail; }
        @Override public void write(int b) throws IOException { if (fail.equals("schrijven")) throw new IOException(fail); bytes.write(b); }
        @Override public void flush() throws IOException { if (fail.equals("flushen")) throw new IOException(fail); }
        @Override public void close() throws IOException { closed = true; if (fail.equals("sluiten")) throw new IOException(fail); }
    }
    static final class Source extends ByteArrayInputStream {
        boolean closed;
        boolean failClose;
        Source() { super("backup".getBytes(StandardCharsets.UTF_8)); }
        @Override public void close() throws IOException { closed = true; if (failClose) throw new IOException("bron sluiten"); }
    }
    private static byte[] data(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void copyFailures() throws Exception {
        for (String fail : new String[]{"", "schrijven", "flushen", "sluiten"}) {
            Source in = new Source(); Target out = new Target(fail);
            boolean committed = false;
            try { DocumentWrite.copy(() -> in, () -> out); committed = true; }
            catch (IOException expected) { check(!fail.isEmpty(), "Onverwachte kopieerfout"); }
            check(committed == fail.isEmpty(), "Geen succes voordat " + fail + " geslaagd is");
            check(in.closed && out.closed, "Beide streams gesloten na " + fail);
        }
        Source in = new Source(); in.failClose = true;
        try { DocumentWrite.copy(() -> in, () -> new Target("")); throw new AssertionError("Bron-sluitfout verborgen"); }
        catch (IOException expected) { check(expected.getMessage().contains("bron"), "Ook de bron moet goed sluiten"); }
        Source opened = new Source();
        try { DocumentWrite.copy(() -> opened, () -> { throw new IOException("doel openen"); }); throw new AssertionError(); }
        catch (IOException expected) { check(opened.closed, "Bron gesloten als doel niet opent"); }
    }
    private static void versions(File root) throws Exception {
        WebUpdateStore store = new WebUpdateStore(root);
        WebUpdateStore.Snapshot first = store.stage(10, "1.10", 3, data("interface 10"), data("start 10"));
        check(store.current() == null, "Klaarzetten activeert niets");
        store.activate(first);
        WebUpdateStore.Snapshot next = store.stage(11, "1.11", 3, data("interface 11"), data("start 11"));
        WebUpdateStore restarted = new WebUpdateStore(root);
        check(restarted.current().code == 10, "Herstart tussen schrijven en activatie behoudt versie 10");
        // Een half geschreven pointer vervangt de actieve verwijzing niet.
        Files.write(new File(root, "actief.tmp").toPath(), data("versie-onderbroken"));
        check(new WebUpdateStore(root).current().code == 10, "Een tijdelijke pointer telt niet");
        store.activate(next);
        WebUpdateStore.Snapshot loaded = new WebUpdateStore(root).current();
        check(loaded.code == 11 && loaded.name.equals("1.11"), "Metadata wisselt met inhoud");
        check(WebUpdateStore.text(loaded.html(), 100).equals("interface 11") && WebUpdateStore.text(loaded.start(), 100).equals("start 11"), "Beide bestanden horen bij dezelfde versie");
        check(first.dir.isDirectory(), "Vorige versie blijft bestaan");
        Files.write(next.start().toPath(), data("beschadigd"));
        check(new WebUpdateStore(root).current().code == 10, "Beschadigde actieve versie valt terug op vorige volledige versie");
        try { store.activate(next); throw new AssertionError("Beschadigde versie geactiveerd"); }
        catch (IOException expected) { check(new WebUpdateStore(root).current().code == 10, "Mislukte activatie wijzigt niets"); }
        WebUpdateStore.Snapshot third = store.stage(12, "1.12", 3, data("interface 12"), data("start 12"));
        store.activate(third);
        check(first.dir.isDirectory(), "Activatie bewaart de werkelijk leesbare herstelversie, niet de beschadigde cache");
        Files.write(third.html().toPath(), data("kapot"));
        try { store.current().readHtml(); throw new AssertionError("Beschadigde gecachte inhoud geladen"); }
        catch (IOException expected) { check(store.refresh().code == 10, "Leesfout herstelt ook binnen hetzelfde proces"); }
        Files.write(new File(root, "actief").toPath(), data("../../buiten\n"));
        check(new WebUpdateStore(root).current() == null, "Een verwijzing buiten de versiemappen wordt geweigerd");
    }
    private static void json(File file) throws Exception {
        // Opslag en fouten zijn Android-onafhankelijk; de app gebruikt hier de JSONObject-parser als validator.
        JsonFileStore.Validator validator = text -> { if (!text.equals("{}") && !text.matches("\\{\"naam\":\"[a-z]+\"\\}")) throw new IOException("Ongeldige inhoud"); };
        JsonFileStore.write(file, "{\"naam\":\"eerste\"}", validator);
        JsonFileStore.write(file, "{\"naam\":\"tweede\"}", validator);
        check(JsonFileStore.read(file, validator).equals("{\"naam\":\"tweede\"}"), "Opgeslagen JSON is terug te lezen");
        Files.write(file.toPath(), data("{kapot"));
        check(JsonFileStore.read(file, validator).equals("{\"naam\":\"eerste\"}"), "Beschadigde JSON gebruikt de vorige leesbare kopie");
        JsonFileStore.write(file, "{\"naam\":\"hersteld\"}", validator);
        check(JsonFileStore.read(file, validator).equals("{\"naam\":\"hersteld\"}"), "Opslaan na herstel lukt");
        check(new File(file.getPath() + ".beschadigd").isFile(), "Beschadigd origineel blijft bewaard");
        Files.write(new File(file.getPath() + ".tmp").toPath(), data("tijdelijk"));
        JsonFileStore.delete(file);
        check(JsonFileStore.read(file, validator).equals("{}"), "Wissen laat geen herstelkopie opnieuw verschijnen");
        check(!new File(file.getPath() + ".bak").exists() && !new File(file.getPath() + ".beschadigd").exists()
                && !new File(file.getPath() + ".tmp").exists(), "Wissen ruimt ook alle afgeleide bestanden op");
        File broken = new File(file.getParentFile(), "onleesbaar.json"); Files.write(broken.toPath(), data("{kapot"));
        try { JsonFileStore.write(broken, "{}", validator); throw new AssertionError("Onleesbare inhoud overschreven"); }
        catch (IOException expected) { check(new String(Files.readAllBytes(broken.toPath()), StandardCharsets.UTF_8).equals("{kapot"), "Onleesbaar is niet leeg"); }
        File blocked = new File(file.getParentFile(), "map.json"); check(blocked.mkdir(), "Schrijffout voorbereiden");
        try { JsonFileStore.write(blocked, "{}", validator); throw new AssertionError("Opslagfout verborgen"); }
        catch (IOException expected) { check(blocked.isDirectory(), "Opslagfout bereikt de aanroeper"); }
        File history = new File(file.getParentFile(), "geschiedenis.json");
        JsonFileStore.writeCurrent(history, "{\"naam\":\"verlopen\"}", validator);
        JsonFileStore.writeCurrent(history, "{}", validator); // laatste verlopen waarneming gesnoeid
        check(WebUpdateStore.text(new File(history.getPath() + ".bak"), 100).equals("{}"), "Ook de herstelkopie bevat geen verlopen waarneming");
        Files.write(new File(history.getPath() + ".beschadigd").toPath(), data("verlopen waarneming"));
        JsonFileStore.writeCurrent(history, "{}", validator);
        check(!new File(history.getPath() + ".beschadigd").exists(), "Beschadigde tijdelijke waarnemingen blijven niet buiten de bewaartermijn staan");
        Files.write(history.toPath(), data("{kapot"));
        check(JsonFileStore.read(history, validator).equals("{}"), "Herstel laat de laatste verlopen waarneming niet terugkomen");
        File orphan = new File(file.getParentFile(), "wezen.json");
        File orphanTemp = new File(orphan.getPath() + ".bak.tmp");
        Files.write(orphanTemp.toPath(), data("verlopen waarneming"));
        check(JsonFileStore.exists(orphan), "Opruimen herkent ook uitsluitend een achtergebleven tijdelijk bestand");
        JsonFileStore.writeCurrent(orphan, "{}", validator);
        check(!orphanTemp.exists() && JsonFileStore.read(orphan, validator).equals("{}"), "Verlopen gegevens uit een onderbroken eerste opslag worden opgeruimd");
    }
    private static void download(File target) throws Exception {
        byte[] bytes = new byte[2 * 1024 * 1024 + 3]; Arrays.fill(bytes, (byte) 7);
        final long[] last = {0};
        String hash = UpdateDownload.copy(new ByteArrayInputStream(bytes), target, bytes.length, bytes.length, (done, total) -> last[0] = done);
        check(last[0] == bytes.length && target.length() == bytes.length, "Grotere download volledig gestreamd");
        StringBuilder expected = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) expected.append(String.format("%02x", b));
        check(hash.equals(expected.toString()), "Streaminghash klopt");
        for (long total : new long[]{bytes.length, 0}) {
            try { UpdateDownload.copy(new ByteArrayInputStream(bytes), target, total, 100, (done, size) -> {}); throw new AssertionError("Te grote download geaccepteerd"); }
            catch (IOException failure) { check(!target.exists(), "Te grote download verwijderd, ook zonder Content-Length"); }
        }
        try { UpdateDownload.copy(new ByteArrayInputStream(data("kort")), target, 100, 100, (done, size) -> {}); throw new AssertionError(); }
        catch (IOException expectedFailure) { check(!target.exists(), "Afgebroken download verwijderd"); }
        Source in = new Source(); in.failClose = true;
        try { UpdateDownload.copy(in, target, 6, 100, (done, size) -> {}); throw new AssertionError("Sluitfout als geslaagde download"); }
        catch (IOException expectedFailure) { check(!target.exists(), "Sluitfout wist de tijdelijke download"); }
    }
    public static void main(String[] args) throws Exception {
        File tmp = Files.createTempDirectory("tools-opslag-").toFile();
        try { copyFailures(); versions(new File(tmp, "web")); json(new File(tmp, "apparaten.json")); download(new File(tmp, "update.apk")); }
        finally { erase(tmp); }
        System.out.println("Alle " + checks + " opslag- en updatecontroles geslaagd");
    }
    private static void erase(File f) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) erase(k); f.delete(); }
}
