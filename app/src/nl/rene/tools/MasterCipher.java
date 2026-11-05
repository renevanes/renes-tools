package nl.rene.tools;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Iedere rij is geauthenticeerd versleuteld en aan tabel, id en revisie gebonden. */
final class MasterCipher {
    private MasterCipher() { }
    private static final byte[] MAGIC = {'R','T','M','D',1};
    static byte[] encrypt(SecretKey key, String purpose, byte[] plain) throws Exception {
        if (plain == null || plain.length > 1024 * 1024) throw new IllegalArgumentException();
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);
        c.updateAAD(("Tools.Masterdata.v1/"+purpose).getBytes(StandardCharsets.UTF_8));
        byte[] iv=c.getIV(), encrypted=c.doFinal(plain);
        if(iv==null||iv.length!=12)throw new IllegalStateException();
        byte[] out=new byte[MAGIC.length+12+encrypted.length];System.arraycopy(MAGIC,0,out,0,MAGIC.length);System.arraycopy(iv,0,out,MAGIC.length,12);System.arraycopy(encrypted,0,out,MAGIC.length+12,encrypted.length);return out;
    }
    static byte[] decrypt(SecretKey key,String purpose,byte[] blob)throws Exception{
        if(blob==null||blob.length<33||blob.length>1024*1024+33||!Arrays.equals(MAGIC,Arrays.copyOf(blob,5)))throw new IllegalArgumentException();
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(blob,5,17)));
        c.updateAAD(("Tools.Masterdata.v1/"+purpose).getBytes(StandardCharsets.UTF_8));return c.doFinal(blob,17,blob.length-17);
    }
    static String hash(byte[] bytes)throws Exception{
        byte[] h=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);char[] out=new char[h.length*2],hex="0123456789abcdef".toCharArray();for(int i=0;i<h.length;i++){out[i*2]=hex[(h[i]&255)>>>4];out[i*2+1]=hex[h[i]&15];}return new String(out);
    }
}
