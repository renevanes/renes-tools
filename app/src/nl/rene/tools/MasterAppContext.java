package nl.rene.tools;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import javax.crypto.SecretKey;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

/** Door de gebruiker gedeelde, beperkte app-context; apart van de afgeschermde stamgegevens. Geen backups. */
final class MasterAppContext {
    private MasterAppContext(){ }
    private static final String ALIAS="renes_app_identity_v1";
    static AtomicFile file(Context c){return new AtomicFile(new File(c.getNoBackupFilesDir(),"app-identiteit.bin"));}
    static SecretKey key(boolean create)throws Exception{
        KeyStore ks=MasterKeys.store();if(ks.containsAlias(ALIAS))return ((KeyStore.SecretKeyEntry)ks.getEntry(ALIAS,null)).getSecretKey();
        return create?MasterKeys.generate(ALIAS,0):null;
    }
    static synchronized void invalidate(Context c)throws Exception{
        AtomicFile f=file(c);f.delete();if(f.getBaseFile().exists()||new File(f.getBaseFile()+".bak").exists())throw new MasterError(Taal.T("De gedeelde app-context kan niet worden bijgewerkt"));
    }
    static synchronized void publish(Context c,JSONObject data)throws Exception{
        if(!data.has("self")){invalidate(c);return;}
        byte[] encrypted=MasterCipher.encrypt(key(true),"app-context",data.toString().getBytes(StandardCharsets.UTF_8));
        AtomicFile f=file(c);FileOutputStream out=null;
        try{out=f.startWrite();out.write(encrypted);f.finishWrite(out);}catch(Throwable e){if(out!=null)f.failWrite(out);throw e;}
    }
    static synchronized JSONObject read(Context c){
        try{AtomicFile f=file(c);SecretKey key=key(false);if(key==null)return new JSONObject();
            try(FileInputStream in=f.openRead()){
                long size=in.getChannel().size();if(size<33||size>1024*1024+33)return new JSONObject();
                byte[] bytes=new byte[(int)size];int at=0,n;while(at<bytes.length&&(n=in.read(bytes,at,bytes.length-at))>0)at+=n;
                if(at!=bytes.length)return new JSONObject();return new JSONObject(new String(MasterCipher.decrypt(key,"app-context",bytes),StandardCharsets.UTF_8));
            }
        }catch(Throwable ignored){return new JSONObject();}
    }
    static synchronized void reset(Context c)throws Exception{
        KeyStore ks=MasterKeys.store();if(ks.containsAlias(ALIAS))ks.deleteEntry(ALIAS);invalidate(c);
    }
}
