package nl.rene.tools;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.KeyStore;

/** Geen onversleutelde sleutel op schijf, en nooit terugvallen op een sleutel zonder toestelontgrendeling. */
final class MasterKeys {
    private MasterKeys(){ }
    static final String ALIAS="renes_masterdata_v1",READY="renes_masterdata_ready_v1",PROOF="renes_masterdata_reset_v1";
    static KeyStore store()throws Exception{KeyStore s=KeyStore.getInstance("AndroidKeyStore");s.load(null);return s;}
    static boolean has(String alias)throws Exception{return store().containsAlias(alias);}
    static SecretKey generate(String alias,int seconds)throws Exception{
        KeyGenParameterSpec.Builder b=new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).setRandomizedEncryptionRequired(true);
        if(seconds>0){
            b.setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(false);
            if(Build.VERSION.SDK_INT>=30)b.setUserAuthenticationParameters(seconds,KeyProperties.AUTH_BIOMETRIC_STRONG|KeyProperties.AUTH_DEVICE_CREDENTIAL);
            else b.setUserAuthenticationValidityDurationSeconds(seconds);
            if(Build.VERSION.SDK_INT>=28)b.setUnlockedDeviceRequired(true);
        }
        KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(b.build());return g.generateKey();
    }
    static SecretKey authenticated(Context c)throws Exception{
        synchronized(MasterStore.LOCK){
        if(!Lock.deviceSecure(c))throw new MasterError(Taal.T("Stel eerst een schermvergrendeling in op je telefoon"));
        KeyStore ks=store();SecretKey key;
        if(ks.containsAlias(ALIAS))key=((KeyStore.SecretKeyEntry)ks.getEntry(ALIAS,null)).getSecretKey();
        else{
            if(MasterStore.exists(c)||ks.containsAlias(READY))throw new MasterError(Taal.T("De beveiligingssleutel ontbreekt; bestaande stamgegevens worden niet vervangen"));
            key=generate(ALIAS,600);
        }
        byte[] probe=MasterCipher.encrypt(key,"auth",new byte[]{1,2,3});MasterCipher.decrypt(key,"auth",probe);
        if(ks.containsAlias(READY)&&!MasterStore.exists(c))throw new MasterError(Taal.T("Het gegevensbestand ontbreekt; herstel wordt niet automatisch uitgevoerd"));
        return key;
        }
    }
    static void markReady()throws Exception{synchronized(MasterStore.LOCK){if(!has(READY))generate(READY,0);}}
    static void proveReset()throws Exception{
        KeyStore ks=store();if(ks.containsAlias(PROOF))ks.deleteEntry(PROOF);
        SecretKey key=generate(PROOF,15);byte[] probe=MasterCipher.encrypt(key,"reset",new byte[]{1});MasterCipher.decrypt(key,"reset",probe);
    }
    static void remove()throws Exception{KeyStore ks=store();if(ks.containsAlias(ALIAS))ks.deleteEntry(ALIAS);if(ks.containsAlias(READY))ks.deleteEntry(READY);}
}
