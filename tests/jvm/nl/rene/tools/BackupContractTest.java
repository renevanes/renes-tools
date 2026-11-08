package nl.rene.tools;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import org.json.JSONObject;

/** Nieuwe opslag gaat mee; beschermde overdracht valideert vóór vervangen en blijft zonder privésleutel gesloten. */
public class BackupContractTest {
    interface Checked { void run() throws Exception; }
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static void rejected(Checked action) throws Exception { boolean failed = false; try { action.run(); } catch (Exception expected) { failed = true; } check(failed, "invoer moet geweigerd worden"); }
    static byte[] decrypt(byte[] bytes, KeyPair pair) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes)); ProtectedRestore.alias(in);
        try (InputStream plain = ProtectedRestore.decrypt(in, pair.getPrivate())) { return AppBackup.small(plain, new byte[8192], 2 * 1024 * 1024); }
    }
    public static void main(String[] args) throws Exception {
        check(AppData.supportedCode("10",11)&&!AppData.supportedCode("12",11),"eerst bijwerken voor nieuwe schema’s en risicoclassificaties");
        for (String part : AllBackup.PARTS) check(AllBackup.defaultPart(part, true), "standaard versleuteld mee: " + part);
        check(!AllBackup.defaultPart("ai", false) && !AllBackup.defaultPart("notifications", false), "geen nieuwe toestemming voor leesbare privégegevens");
        check(AppData.keepPref("nieuwtool", "eigenKeuze") && AppData.fileRule("nieuwtool/notities.json") == null, "toekomstige gewone opslag standaard mee");
        check(AppData.secretPrefsFile("nieuwtool_geheim") && !AppData.keepPrefsFile("nieuwtool_geheim"), "geheimen apart overdraagbaar");
        check(!ToolData.keepFile("nieuwtool/notities.json") && !ToolData.keepPrefsFile("nieuwtool"), "onbekende nieuwe gegevens nooit stilzwijgend leesbaar exporteren");
        check(ToolRegister.ADAPTERS.get("bank").equals("bank") && ToolRegister.ADAPTERS.get("masterdata").equals("masterdata"), "beide vaste uitzonderingen geregistreerd");
        check(ToolRegister.PREFS.get("bank_geheim").equals("bank") && ToolRegister.CONSENT.get("bank").length() == 64, "toestemming dekt de geregistreerde opslag en policyversie");
        check(AppData.fileRule("bank/gegevens.bin", true) != null && AppData.fileRule("masterdata/master.db", true) != null, "toestelgebonden ciphertext nooit onbruikbaar als gewoon bestand kopiëren");
        check("protected:bank".equals(AppData.target(PrivateBackup.DIR + "/bank")), "beschermde entry herkend");
        check(AppData.target(PrivateBackup.DIR + "/../bank") == null, "geen padontsnapping");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); KeyPair first = generator.generateKeyPair(), second = generator.generateKeyPair();
        String text = "{\"v\":1,\"tx\":[],\"eigen\":[{\"iban\":\"TEST\",\"naam\":\"PRIVATE_TEST_VALUE\"}]}";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProtectedRestore.Staged staged = ProtectedRestore.envelope(out, new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), PrivateBackup.DIR + "/bank", ProtectedRestore.PREFIX + java.util.UUID.randomUUID(), first.getPublic());
        byte[] encrypted = out.toByteArray();
        check(staged.valid && staged.entry.size == text.getBytes(StandardCharsets.UTF_8).length, "schema en volledige grootte gecontroleerd vóór toepassen");
        check(!new String(encrypted, StandardCharsets.ISO_8859_1).contains("PRIVATE_TEST_VALUE"), "geen leesbare gegevens in staging");
        check(text.equals(new String(decrypt(encrypted, first), StandardCharsets.UTF_8)), "beschermde overdracht met juiste ontgrendelsleutel");
        rejected(() -> decrypt(encrypted, second));
        byte[] changed = encrypted.clone(); changed[changed.length - 1] ^= 1; rejected(() -> decrypt(changed, first));
        rejected(() -> decrypt(Arrays.copyOf(encrypted, encrypted.length - 1), first));
        out.reset(); ProtectedRestore.Staged invalid = ProtectedRestore.envelope(out, new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), PrivateBackup.DIR + "/bank", ProtectedRestore.PREFIX + java.util.UUID.randomUUID(), first.getPublic());
        check(!invalid.valid, "geldig versleutelde maar onbruikbare bankgegevens niet toepassen");
        String master = "{\"format\":\"renes-tools-stamgegevens\",\"version\":1}\n{\"end\":true,\"records\":0,\"events\":0}\n";
        PrivateData.validate("masterdata", new ByteArrayInputStream(master.getBytes(StandardCharsets.UTF_8)));
        rejected(() -> PrivateData.validate("masterdata", new ByteArrayInputStream(master.replace("\"records\":0", "\"records\":1").getBytes(StandardCharsets.UTF_8))));
        rejected(() -> PrivateData.validate("masterdata", new ByteArrayInputStream(master.substring(0, master.indexOf('\n')).getBytes(StandardCharsets.UTF_8))));
        // Een nieuwe SQLite-implementatie kan geen herstelbare backup krijgen door de losse DB/WAL-bestanden te kopiëren.
        for (File source : new File("app/src/nl/rene/tools").listFiles((dir, name) -> name.endsWith(".java"))) {
            String code = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            if (code.contains("SQLiteDatabase") || code.contains("SQLiteOpenHelper")) check(Arrays.asList("MasterStore.java", "PrivateBackup.java", "NotificationHistory.java", "WaChats.java").contains(source.getName()), "nieuwe database vereist consistente snapshot-adapter en hersteltest: " + source.getName());
        }
        // Geen laat auth-antwoord uit een ander venster na uit/aan of een sleutelwissel.
        long request = BackupRisk.EPOCH.ticket(); BackupRisk.EPOCH.changed(); BackupRisk.EPOCH.changed();
        check(!BackupRisk.EPOCH.current(request), "uit en weer aan maakt oude aanvragen ongeldig");
        long fresh = BackupRisk.EPOCH.ticket(); check(BackupRisk.canMutate(fresh), "nieuwe aanvraag bij stabiel beleid");
        check(AllBackup.tryBegin(), "backup neemt dezelfde policyvergrendeling");
        check(!BackupRisk.canMutate(fresh), "geen toestemming of keycommit tijdens lopende backup"); AllBackup.endRunning();
        BackupRisk.EPOCH.changed(); check(!BackupRisk.canMutate(fresh), "oude PBKDF- of auth-aanvraag kan na keywissel niet committen");
        AppData.Manifest manifest = new AppData.Manifest();
        manifest.skipped.add(new String[]{"ai-models",AppData.fileRule("ai-models")}); check(!AppData.incomplete(manifest),"opnieuw te downloaden modellen zijn bewust uitgesloten");
        manifest.skipped.add(new String[]{"kluis","kluis niet te lezen"}); check(AppData.incomplete(manifest),"ontbrekende kluissleutel niet compleet noemen");
        manifest.skipped.clear();manifest.skipped.add(new String[]{"handtekening","niet te lezen"});check(AppData.incomplete(manifest),"ontbrekende handtekening niet compleet noemen");
        manifest.skipped.clear();manifest.skipped.add(new String[]{"gebruik-foto/shot-1-proef.jpg","niet te lezen"});check(AppData.incomplete(manifest),"ontbrekende gebruiksfoto niet compleet noemen");
        manifest.skipped.clear();manifest.broken.add("Appgegevens/bestanden/nieuwtool/gegevens.json");check(AppData.incomplete(manifest),"afgebroken gewone data niet compleet noemen");
        manifest.broken.clear();manifest.skipped.add(new String[]{"nieuwtool/a:b","vreemde naam"});check(AppData.incomplete(manifest),"onveilige duurzame bestandsnaam nooit stil overslaan");
        for(String name:AppData.PREFS_SKIP.keySet())check(!AppData.keepPrefsFile(name)&&!AppData.secretPrefsFile(name),"expliciete technische uitzondering wint altijd: "+name);
        rejected(()->PrivateData.bank("{\"v\":1,\"tx\":[null]}"));
        rejected(()->PrivateData.bank("{\"v\":1,\"tx\":[],\"eigen\":[null]}"));
        rejected(()->PrivateData.bank("{\"v\":1,\"tx\":[{\"id\":\"x\",\"acc\":\"x\",\"d\":\"2026-01-01\",\"a\":\"geen bedrag\"}]}"));
        JSONObject person = new JSONObject().put("id","aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa").put("type","persoon").put("label","Testpersoon").put("fields",new JSONObject().put("naam","Testpersoon")).put("revision",1).put("created",1).put("updated",1);
        PrivateData.record(person);
        char[] large = new char[1_100_000];Arrays.fill(large,'x');JSONObject oversized = new JSONObject(person.toString()).put("extra",new String(large));rejected(()->PrivateData.record(oversized));
        String header = "{\"format\":\"renes-tools-stamgegevens\",\"version\":1}\n";
        JSONObject identity = new JSONObject(person.toString()).put("id","bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb").put("type","identiteit").put("fields",new JSONObject().put("persoon",person.getString("id")));
        String dangling = header + new JSONObject().put("record",identity)+"\n{\"end\":true,\"records\":1,\"events\":0}\n";
        rejected(()->PrivateData.validate("masterdata",new ByteArrayInputStream(dangling.getBytes(StandardCharsets.UTF_8))));
        JSONObject wrongPerson=new JSONObject(person.toString()).put("type","organisatie");
        String wrongType=header+new JSONObject().put("record",wrongPerson)+"\n"+new JSONObject().put("record",identity)+"\n{\"end\":true,\"records\":2,\"events\":0}\n";
        rejected(()->PrivateData.validate("masterdata",new ByteArrayInputStream(wrongType.getBytes(StandardCharsets.UTF_8))));
        JSONObject profile1 = new JSONObject(person.toString()).put("id","cccccccc-cccc-cccc-cccc-cccccccccccc").put("type","profiel").put("fields",new JSONObject().put("persoon",person.getString("id")));
        JSONObject profile2 = new JSONObject(profile1.toString()).put("id","dddddddd-dddd-dddd-dddd-dddddddddddd");
        String doubleProfile = header + new JSONObject().put("record",person)+"\n"+new JSONObject().put("record",profile1)+"\n"+new JSONObject().put("record",profile2)+"\n{\"end\":true,\"records\":3,\"events\":0}\n";
        rejected(()->PrivateData.validate("masterdata",new ByteArrayInputStream(doubleProfile.getBytes(StandardCharsets.UTF_8))));
        System.out.println("Backupcontract, grenzen en beschermde overdracht geslaagd");
    }
}
