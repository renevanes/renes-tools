package nl.rene.tools;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Kluis: documenten (bijv. paspoort, polissen) versleuteld op deze telefoon, met een sleutel in de beveiligde
 * hardware van de telefoon. Openen alleen na vingerafdruk of pincode (in de app geregeld, zie MainActivity).
 * De sleutel verlaat de telefoon nooit: de kluis gaat dus niet mee in een gewone backup; wel (ontsleuteld)
 * in een versleutelde Alles-back-uppen, zodat je hem met je backup-wachtwoord terug hebt.
 *
 * Bestandsformaat: "RTK1", dan blokken van [12 bytes IV][4 bytes lengte][versleuteld + tag]; elk blok
 * heeft het bloknummer en "laatste blok" als extra gegevens, zodat omwisselen of afkappen opvalt.
 */
final class Kluis {

    private Kluis() { }

    static final String ALIAS = "renes_kluis";
    static final int CHUNK = 1 << 20;
    static final long MAX = 60L * 1024 * 1024;
    static final byte[] MAGIC = {'R', 'T', 'K', '1'};
    private static final Object KEY_LOCK=new Object();

    /** Ontgrendeld tot dit moment (na vingerafdruk), in het geheugen. */
    static volatile long openUntil = 0;

    static boolean isOpen() { return System.currentTimeMillis() < openUntil; }
    private static final android.os.Handler H = new android.os.Handler(android.os.Looper.getMainLooper());
    private static Context appCtx;
    private static final Runnable EXPIRE = () -> { if (!isOpen() && appCtx != null) clearViews(appCtx); };
    static void open(Context c) {
        appCtx = c.getApplicationContext();
        openUntil = System.currentTimeMillis() + 5 * 60_000L;
        H.removeCallbacks(EXPIRE);
        H.postDelayed(EXPIRE, 5 * 60_000L + 1000); // na het verlopen ook de tijdelijk geopende bestanden weg
    }
    static long remaining() { return Math.max(0, openUntil - System.currentTimeMillis()); }
    static void close(Context c) { openUntil = 0; clearViews(c); }

    /** Bestandsnaam met behoud van de extensie (anders weet een kijk-app niet wat het is). */
    static String fileName(JSONObject d) {
        String n = d.optString("name", "document"), ext = "";
        int dot = n.lastIndexOf('.');
        if (dot > 0 && n.length() - dot <= 9) { ext = Sms.safeName(n.substring(dot + 1)).toLowerCase(java.util.Locale.ROOT); n = n.substring(0, dot); }
        if (ext.isEmpty()) { String e = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(d.optString("mime")); if (e != null) ext = e; }
        String base = Sms.safeName(n);
        if (base.isEmpty()) base = "document";
        return ext.isEmpty() ? base : base + "." + ext;
    }

    static File dir(Context c) { File d = new File(c.getFilesDir(), "kluis"); d.mkdirs(); return d; }
    static File viewDir(Context c) { File d = new File(c.getCacheDir(), "kluis-open"); d.mkdirs(); return d; }
    static File index(Context c) { return new File(dir(c), "index.bin"); }

    static void clearViews(Context c) {
        File[] fs = viewDir(c).listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    static SecretKey key() throws Exception {
        synchronized(KEY_LOCK){
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return g.generateKey();
        }
    }

    static byte[] aad(int n, boolean last) { return new byte[]{(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n, (byte) (last ? 1 : 0)}; }

    static void encrypt(InputStream in, OutputStream raw) throws Exception {
        SecretKey k = key();
        DataOutputStream out = new DataOutputStream(raw);
        out.write(MAGIC);
        byte[] buf = new byte[CHUNK], next = new byte[CHUNK];
        int len = readFully(in, buf), n = 0;
        long total = 0;
        while (true) {
            int nlen = len == CHUNK ? readFully(in, next) : 0;
            boolean last = nlen <= 0;
            total += len;
            if (total > MAX) throw new Exception("Bestand is te groot (max 60 MB)");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, k);
            c.updateAAD(aad(n, last));
            byte[] ct = c.doFinal(buf, 0, Math.max(0, len));
            out.write(c.getIV());
            out.writeInt(ct.length);
            out.write(ct);
            if (last) break;
            byte[] t = buf; buf = next; next = t; len = nlen; n++;
        }
        out.flush();
    }

    static void decrypt(InputStream raw, OutputStream out) throws Exception {
        SecretKey k = key();
        DataInputStream in = new DataInputStream(raw.markSupported() ? raw : new java.io.BufferedInputStream(raw, 1 << 16));
        byte[] m = new byte[4];
        in.readFully(m);
        if (!java.util.Arrays.equals(m, MAGIC)) throw new Exception("Geen kluisbestand");
        int n = 0;
        while (true) {
            byte[] iv = new byte[12];
            in.readFully(iv);
            int len = in.readInt();
            if (len < 16 || len > CHUNK + 16) throw new Exception("Kluisbestand is beschadigd");
            byte[] ct = new byte[len];
            in.readFully(ct);
            boolean last = peekEnd(in);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, iv));
            c.updateAAD(aad(n, last));
            out.write(c.doFinal(ct)); // klopt de tag of het blok-nummer niet, dan een fout: niets halfs gebruiken
            if (last) break;
            n++;
        }
    }

    static boolean peekEnd(DataInputStream in) throws java.io.IOException {
        in.mark(1);
        int b = in.read();
        if (b < 0) return true;
        in.reset();
        return false;
    }

    static int readFully(InputStream in, byte[] b) throws java.io.IOException {
        int off = 0, r;
        while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
        return off;
    }

    // ---------- index (namen versleuteld) ----------

    static synchronized JSONArray list(Context c) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (InputStream in = new java.io.BufferedInputStream(new android.util.AtomicFile(index(c)).openRead())) { decrypt(in, b); }
        catch (java.io.FileNotFoundException e) { if(index(c).exists()||new File(index(c).getPath()+".bak").exists())throw e;return new JSONArray(); }
        return new JSONArray(new String(b.toByteArray(), StandardCharsets.UTF_8));
    }

    static synchronized void saveIndex(Context c, JSONArray a) throws Exception {
        writeEncrypted(index(c),a.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Eerst volledig schrijven, synchroniseren en teruglezen; alleen een geslaagde rename vervangt het oude bestand. */
    static void writeEncrypted(File file,byte[] bytes) throws Exception {
        File tmp=new File(file.getPath()+".writing");
        try{
            try(FileOutputStream out=new FileOutputStream(tmp)){
                encrypt(new java.io.ByteArrayInputStream(bytes),out);out.flush();out.getFD().sync();
            }
            ByteArrayOutputStream check=new ByteArrayOutputStream();
            try(InputStream in=new java.io.BufferedInputStream(new FileInputStream(tmp))){decrypt(in,check);}
            if(!java.util.Arrays.equals(bytes,check.toByteArray()))throw new Exception("Opslaan kon niet worden gecontroleerd");
            if(!tmp.renameTo(file))throw new Exception("Opslaan lukt niet; het eerdere bestand blijft behouden");
        }finally{tmp.delete();}
    }

    static String safeId(String id) {
        if (id == null || !id.matches("[a-z0-9]{6,24}")) throw new IllegalArgumentException("Onbekend document");
        return id;
    }

    /** Document toevoegen uit een gekozen bestand. */
    static synchronized JSONObject add(Context c, Uri u) throws Exception {
        String name = "document", mime = c.getContentResolver().getType(u);
        long size = -1;
        try (android.database.Cursor cur = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) { if (!cur.isNull(0)) name = cur.getString(0); if (!cur.isNull(1)) size = cur.getLong(1); }
        }
        if (size > MAX) throw new Exception("Bestand is te groot (max 60 MB)");
        try(InputStream in=c.getContentResolver().openInputStream(u)){return store(c,in,name,mime,"");}
    }

    static synchronized JSONObject addPdf(Context c,String name,String category) throws Exception {
        File source=PdfTools.output(c,name);
        try(InputStream in=new FileInputStream(source)){return store(c,in,source.getName(),"application/pdf",DocumentMeta.category(category));}
    }

    private static JSONObject store(Context c,InputStream in,String name,String mime,String category) throws Exception {
        if(!isOpen())throw new Exception("De kluis is op slot");
        if(in==null)throw new Exception("Bestand niet te openen");
        if(name==null||name.trim().isEmpty())name="document";
        JSONArray l=list(c); // eerst de bestaande index lezen; een leesfout nooit overschrijven
        String id=java.util.UUID.randomUUID().toString().replace("-","").substring(0,24);
        File out=new File(dir(c),id+".bin");boolean saved=false;
        try{
            CountingIn ci=new CountingIn(in);
            try(OutputStream o=new java.io.BufferedOutputStream(new FileOutputStream(out))){encrypt(ci,o);}
            JSONObject d=new JSONObject().put("id",id).put("name",name.length()>120?name.substring(0,120):name)
                .put("mime",mime==null?"application/octet-stream":mime).put("size",ci.n).put("t",System.currentTimeMillis()).put("category",category);
            if(!isOpen())throw new Exception("De kluis is weer op slot. Ontgrendel opnieuw om het document te bewaren.");
            l.put(d);saveIndex(c,l);saved=true;return d;
        }finally{if(!saved)out.delete();}
    }

    static synchronized void organize(Context c,String id,String json) throws Exception {
        if(!isOpen())throw new Exception("De kluis is op slot");safeId(id);
        if(json==null||json.length()>8000)throw new Exception("Documentgegevens zijn te groot");
        JSONObject patch=new JSONObject(json);
        String category=patch.has("category")?DocumentMeta.category(patch.getString("category")):null;
        String note=patch.has("note")?DocumentMeta.note(patch.getString("note")):null;
        if(patch.has("favorite")&&!(patch.get("favorite") instanceof Boolean))throw new Exception("Ongeldige favorietkeuze");
        JSONArray all=list(c);JSONObject found=null;
        for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).optString("id")))found=all.getJSONObject(i);
        if(found==null)throw new Exception("Document niet gevonden");
        if(category!=null)found.put("category",category);if(note!=null)found.put("note",note);
        if(patch.has("favorite"))found.put("favorite",patch.getBoolean("favorite"));
        saveIndex(c,all);
    }

    static final class CountingIn extends java.io.FilterInputStream {
        long n;
        CountingIn(InputStream in) { super(in); }
        @Override public int read() throws java.io.IOException { int r = super.read(); if (r >= 0) n++; return r; }
        @Override public int read(byte[] b, int o, int l) throws java.io.IOException { int r = in.read(b, o, l); if (r > 0) n += r; return r; }
    }

    static JSONObject find(Context c, String id) throws Exception {
        JSONArray l = list(c);
        for (int i = 0; i < l.length(); i++) if (l.getJSONObject(i).optString("id").equals(id)) return l.getJSONObject(i);
        return null;
    }

    /** Ontsleutelt naar de tijdelijke map om te bekijken. Geeft het bestand (wordt opgeruimd bij sluiten). */
    static File view(Context c, String id) throws Exception {
        JSONObject d = find(c, safeId(id));
        if (d == null) throw new Exception("Document niet gevonden");
        clearViews(c);
        File out = new File(viewDir(c), fileName(d));
        try (InputStream in = new java.io.BufferedInputStream(new FileInputStream(new File(dir(c), id + ".bin")), 1 << 16);
             OutputStream o = new FileOutputStream(out)) { decrypt(in, o); }
        catch (Exception e) { out.delete(); throw e; }
        return out;
    }

    static synchronized void delete(Context c, String id) throws Exception {
        safeId(id);
        JSONArray l = list(c), out = new JSONArray();
        for (int i = 0; i < l.length(); i++) if (!l.getJSONObject(i).optString("id").equals(id)) out.put(l.get(i));
        saveIndex(c, out);
        new File(dir(c), id + ".bin").delete();
    }

    static synchronized void rename(Context c, String id, String name) throws Exception {
        safeId(id);
        JSONArray l = list(c);
        for (int i = 0; i < l.length(); i++) if (l.getJSONObject(i).optString("id").equals(id)) l.getJSONObject(i).put("name", name.length() > 120 ? name.substring(0, 120) : name);
        saveIndex(c, l);
    }

    /** Voor een versleutelde Alles back-uppen: alle documenten (ontsleuteld) in de map Kluis van het archief. */
    static int export(Context c) throws Exception {
        JSONArray l = list(c);
        if (l.length() == 0) return -1;
        // Nooit onversleuteld naar de backup-map; zonder versleutelde backup is de kluis na een nieuwe telefoon weg: dat melden
        if (!Secure.on(c)) throw new Exception("Alleen mee in een versleutelde backup: zet Versleutelen aan");
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), WaBackup.destUri(c));
        if (dest.zip == null) return -1;
        WaBackup.DestDir dir = dest.dir("Kluis", true);
        int n = 0, bad = 0;
        JSONArray metadata=new JSONArray();
        File tmp = new File(c.getCacheDir(), "kluis-backup.tmp");
        for (int i = 0; i < l.length(); i++) {
            JSONObject d = l.getJSONObject(i);
            File f = new File(dir(c), d.optString("id") + ".bin");
            // Eerst helemaal ontsleutelen en controleren, pas dan in het archief (nooit een half document)
            try {
                try (InputStream in = new java.io.BufferedInputStream(new FileInputStream(f), 1 << 16); OutputStream o = new FileOutputStream(tmp)) { decrypt(in, o); }
                String category=d.optString("category","");
                String path=dir.path+(category.isEmpty()?"":"/"+Sms.safeName(category));
                try (OutputStream o = dest.zip.stream(path, fileName(d)); InputStream in = new FileInputStream(tmp)) {
                    byte[] b = new byte[1 << 16]; int r; while ((r = in.read(b)) > 0) o.write(b, 0, r);
                }
                metadata.put(new JSONObject(d.toString()).put("file",dest.zip.lastPath));
                n++;
            } catch (Exception e) { bad++; App.log(c, "KLUIS", "backup " + d.optString("id") + ": " + e.getMessage()); }
            finally { tmp.delete(); }
        }
        try(OutputStream o=dest.zip.stream(dir.path,"documenten.json")){o.write(new JSONObject().put("version",1).put("documents",metadata).toString().getBytes(StandardCharsets.UTF_8));}
        if (bad > 0) throw new Exception(n + " bewaard, " + bad + " niet te lezen");
        return n;
    }
}
