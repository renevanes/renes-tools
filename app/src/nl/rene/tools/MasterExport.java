package nl.rene.tools;

import android.content.Context;
import java.io.File;
import javax.crypto.SecretKey;

/** Een afgeleid document wordt pas zichtbaar na de laatste sessiecontrole en audittransactie. */
final class MasterExport {
    private MasterExport(){ }
    static File directory(Context c)throws Exception {
        File dir=new File(c.getCacheDir(),"master-export");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new MasterError(Taal.T("Stamgegevens opslaan lukt niet"));
        return dir;
    }
    static void attempt(Context c,MasterSession session,String token,String source)throws Exception {
        MasterStore.consumerEvent(c,MasterKeys.authenticated(c),session,token,source,"export_attempt",()->{});
    }
    static File publish(Context c,MasterSession session,String token,String source,File staged,String name)throws Exception {
        File[] output={null};File[] hold={null};
        try {
            SecretKey key=MasterKeys.authenticated(c);
            // De vrijgave is duurzaam gelogd vóór het bestand zichtbaar kan worden, ook bij processtop.
            MasterStore.consumerEvent(c,key,session,token,source,"export_release",()->{});
            MasterStore.consumerEvent(c,key,session,token,source,"export",()->{
                do {output[0]=new File(PdfTools.outDir(c),PdfTools.base(name)+"-"+java.util.UUID.randomUUID().toString()+".pdf");hold[0]=new File(directory(c),output[0].getName()+".hold");}while(output[0].exists()||!hold[0].mkdir());
                if(!staged.renameTo(output[0]))throw new MasterError(Taal.T("Stamgegevens opslaan lukt niet"));
            });
            return output[0];
        }catch(Throwable e){if(output[0]!=null)output[0].delete();throw e;}
        finally{staged.delete();if(hold[0]!=null)hold[0].delete();}
    }
}
