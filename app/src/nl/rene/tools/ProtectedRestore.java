package nl.rene.tools;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.*;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;

/** Gegevens uit een beschermde backup blijven op schijf vergrendeld tot de eigen tool is ontgrendeld.
 * De tijdelijke AES-sleutel wordt alleen met een toestelgebonden RSA-sleutel na systeemontgrendeling leesbaar.
 * Geen herstelsleutel of leesbare gegevens in SharedPreferences, cache of gewone appbestanden. */
final class ProtectedRestore {
    private ProtectedRestore() { }
    static final String FILE = "backup-herstel.rtp", PREFIX = "renes_herstel_";
    static final long MAX = AppBackup.MAX_ENTRY;
    static File pending(Context c, String id) { return new File(new File(c.getFilesDir(), id), FILE); }
    static boolean has(Context c, String id) { return pending(c, id).isFile(); }
    static String receipt(Context c, String id) throws Exception { try (DataInputStream in = new DataInputStream(new FileInputStream(pending(c, id)))) { return alias(in); } }
    static final class Tee extends FilterInputStream {
        private final OutputStream out;
        private long copied;
        Tee(InputStream in, OutputStream out) { super(in); this.out = out; }
        @Override public int read() throws IOException { int n = in.read(); if (n >= 0) { if (++copied > MAX) throw new IOException(Taal.T("Gegevens zijn te groot")); out.write(n); } return n; }
        @Override public int read(byte[] b, int off, int len) throws IOException { int n = in.read(b, off, len); if (n > 0) { copied += n; if (copied > MAX) throw new IOException(Taal.T("Gegevens zijn te groot")); out.write(b, off, n); } return n; }
    }
    static final class Staged {
        final AppData.Entry entry; final boolean valid;
        Staged(AppData.Entry entry, boolean valid) { this.entry = entry; this.valid = valid; }
    }
    static String alias(DataInputStream in) throws Exception {
        if (in.readInt() != 0x52545031) throw new IOException(Taal.T("De klaargezette gegevens zijn niet te lezen"));
        String alias = in.readUTF();
        if (!alias.matches(PREFIX + "[a-f0-9-]{36}")) throw new IOException(Taal.T("De klaargezette gegevens zijn niet te lezen"));
        return alias;
    }
    static KeyStore store() throws Exception { KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); return ks; }
    static KeyPair pair(Context c, String alias) throws Exception {
        if (!Lock.deviceSecure(c)) throw new IOException(Taal.T("Stel eerst een schermvergrendeling in op je telefoon"));
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setDigests(KeyProperties.DIGEST_SHA1).setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(false);
        if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(600, KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
        else b.setUserAuthenticationValidityDurationSeconds(600);
        if (Build.VERSION.SDK_INT >= 28) b.setUnlockedDeviceRequired(true);
        g.initialize(b.build()); return g.generateKeyPair();
    }
    static Staged stage(Context c, File out, InputStream in, String name) throws Exception {
        String alias = PREFIX + java.util.UUID.randomUUID(); boolean done = false;
        try {
            KeyPair pair = pair(c, alias);
            if (!out.getParentFile().isDirectory() && !out.getParentFile().mkdirs()) throw new IOException(Taal.T("Klaarzetten lukt niet"));
            try (OutputStream raw = new BufferedOutputStream(new FileOutputStream(out))) {
                Staged result = envelope(raw, in, name, alias, pair.getPublic()); done = result.valid; return result;
            }
        } finally { if (!done) { out.delete(); try { store().deleteEntry(alias); } catch (Exception ignored) { } } }
    }
    /** Dezelfde versleutelde staging wordt op de JVM met twee verschillende RSA-sleutels gecontroleerd. */
    static Staged envelope(OutputStream output, InputStream input, String name, String alias, java.security.PublicKey publicKey) throws Exception {
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key); boolean valid = true;
        try {
            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding"); rsa.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] wrapped = rsa.doFinal(key); AppBackup.DigestIn digest = new AppBackup.DigestIn(input);
            DataOutputStream raw = new DataOutputStream(output);
            raw.writeInt(0x52545031); raw.writeUTF(alias); raw.writeInt(wrapped.length); raw.write(wrapped);
            try (Vault.Out encrypted = new Vault.Out(raw, new Vault.Key(key, new byte[16], 1000, 1))) {
                Tee tee = new Tee(digest, encrypted);
                try { PrivateData.validate(PrivateBackup.target(name), tee); } catch (Exception invalid) { valid = false; }
                byte[] buf = new byte[64 * 1024]; while (tee.read(buf) >= 0) { /* volledige entry, ook als afgebroken gemarkeerd */ }
            }
            return new Staged(new AppData.Entry(name, digest.n, AppData.hex(digest.md.digest())), valid);
        } finally { Arrays.fill(key, (byte)0); }
    }
    static InputStream decrypt(DataInputStream raw, java.security.PrivateKey privateKey) throws Exception {
        int n = raw.readInt(); if (n != 256) throw new IOException(Taal.T("De klaargezette gegevens zijn niet te lezen"));
        byte[] wrapped = new byte[n]; raw.readFully(wrapped);
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding"); rsa.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] key = rsa.doFinal(wrapped);
        try { return new Vault.In(raw, Vault.readHeader(raw), new Vault.Key(key, new byte[16], 1000, 1)); }
        finally { Arrays.fill(key, (byte)0); }
    }
    static InputStream open(Context c, String id) throws Exception {
        DataInputStream raw = new DataInputStream(new BufferedInputStream(new FileInputStream(pending(c, id)))); boolean done = false;
        try {
            String alias = alias(raw); InputStream in = decrypt(raw, (java.security.PrivateKey)store().getKey(alias, null)); done = true; return in;
        } finally { if (!done) raw.close(); }
    }
    static void remove(File file) {
        String name = null;
        try (DataInputStream raw = new DataInputStream(new FileInputStream(file))) { name = alias(raw); } catch (Exception ignored) { }
        if (file.delete() && name != null) try { store().deleteEntry(name); } catch (Exception ignored) { }
    }
    static void done(Context c, String id) { remove(pending(c, id)); }
    static void clear(File root) { for (String id : new String[]{"bank", "masterdata"}) { File f = new File(new File(root, id), FILE); if (f.isFile()) remove(f); } }
}
