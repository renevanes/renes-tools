package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** Downloadbare stemmen; tekst en geproduceerde spraak worden hier nooit bewaard. */
final class VoiceModels {
    private VoiceModels(){ }
    static final Object FILES=new Object();
    static final class Voice {
        final String id,label,language,folder,model,sha;final long size;
        Voice(String id,String label,String language,String folder,String model,long size,String sha){this.id=id;this.label=label;this.language=language;this.folder=folder;this.model=model;this.size=size;this.sha=sha;}
        String url(){return "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"+folder+".tar.bz2";}
    }
    static final Voice[] VOICES={
        new Voice("tts-nl-ronnie",Taal.T("Ronnie · Nederlands"),"nl","vits-piper-nl_NL-ronnie-medium-int8","nl_NL-ronnie-medium.onnx",21158108L,"eb16022c9c8ee48b75dc833e8a8b04e08730929ea68cde9b67e2dc712f988978"),
        new Voice("tts-en-ljspeech",Taal.T("LJ Speech · Engels"),"en","vits-piper-en_US-ljspeech-medium-int8","en_US-ljspeech-medium.onnx",21090429L,"24dc3bd77dd48c291e52c297878d3437c9492f245d823d7f6a06c4bbb67f4b6b")
    };
    static Voice model(String id){for(Voice v:VOICES)if(v.id.equals(id))return v;return null;}
    /** De spraakmotor staat in deze installatie (APK: alleen arm64-v8a; de emulatortest en Play ook x86_64). */
    static boolean supported(Context c){try{String d=c.getApplicationInfo().nativeLibraryDir;return d!=null&&new File(d,"libsherpa-onnx-jni.so").isFile()&&new File(d,"libonnxruntime.so").isFile();}catch(Throwable e){return false;}}
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("voorlezen",Context.MODE_PRIVATE);}
    static File root(Context c){File f=new File(c.getNoBackupFilesDir(),"voorleesmodellen");f.mkdirs();return f;}
    static File dir(Context c,Voice v){return new File(root(c),v.id);}
    static File part(Context c,Voice v){return new File(root(c),v.id+".part");}
    static boolean ready(Context c,Voice v){return new File(dir(c,v),"klaar").isFile()&&new File(dir(c,v),v.model).length()>1000000&&new File(dir(c,v),"tokens.txt").isFile()&&new File(dir(c,v),"espeak-ng-data/phondata").isFile();}
    static Voice active(Context c){Voice picked=model(prefs(c).getString("voice",""));if(picked!=null&&ready(c,picked))return picked;for(Voice v:VOICES)if(ready(c,v))return v;return null;}
    static float speed(Context c){float value=prefs(c).getFloat("speed",1);return Float.isNaN(value)||Float.isInfinite(value)?1:Math.max(.6f,Math.min(1.8f,value));}
    static void configure(Context c,String voice,double speed)throws Exception {if(!Double.isFinite(speed)||speed<.6||speed>1.8||!voice.isEmpty()&&model(voice)==null)throw new Exception(Taal.T("Kies een stem en een snelheid tussen 0,6 en 1,8"));SharedPreferences.Editor e=prefs(c).edit().putFloat("speed",(float)speed);if(!voice.isEmpty())e.putString("voice",voice);e.apply();}
    static boolean wanted(Context c,String id){return prefs(c).getBoolean("download_"+id,false);}
    static void failed(Context c,String id,String message){prefs(c).edit().putString("error_"+id,message).apply();MainActivity.jsAll("onAiModels", "");}
    static void download(Context c,Voice v)throws Exception {if(!supported(c))throw new Exception(Taal.T("Lokale voorleesstemmen werken op een 64-bits toestel"));if(ready(c,v))return;prefs(c).edit().putBoolean("download_"+v.id,true).remove("error_"+v.id).apply();ModelDownloadService.start(c,v.id);}
    static void stop(Context c,String id){ModelDownloadService.stop(id);prefs(c).edit().putBoolean("download_"+id,false).apply();}
    static void install(Context c,Voice v,BooleanSupplier stop)throws Exception {
        File archive=part(c,v),stage=new File(root(c),v.id+".new");
        if(archive.length()!=v.size||!VoiceArchive.hash(archive).equals(v.sha)){archive.delete();throw new IOException(Taal.T("Het controlegetal van de voorleesstem klopt niet; download de stem opnieuw"));}
        VoiceArchive.extract(archive,stage,v.folder,stop);
        try{
            if(new File(stage,v.model).length()<1000000||!new File(stage,"tokens.txt").isFile()||!new File(stage,"espeak-ng-data/phondata").isFile())throw new IOException();
            try(OutputStream out=new FileOutputStream(new File(stage,"klaar"))){out.write(v.sha.getBytes(StandardCharsets.US_ASCII));}
            synchronized(FILES){if(stop.getAsBoolean())throw new ModelFetch.Stopped();if(dir(c,v).exists())VoiceArchive.erase(dir(c,v));if(!stage.renameTo(dir(c,v)))throw new IOException();}
            archive.delete();SharedPreferences.Editor edit=prefs(c).edit().putBoolean("download_"+v.id,false).remove("error_"+v.id);if(prefs(c).getString("voice","").isEmpty())edit.putString("voice",v.id);edit.apply();
        }finally{if(stage.exists())VoiceArchive.erase(stage);}
    }
    static long bytes(File dir){if(dir.isFile())return dir.length();long size=0;File[] files=dir.listFiles();if(files!=null)for(File f:files)size+=bytes(f);return size;}
    static JSONObject json(Context c)throws Exception {
        JSONArray voices=new JSONArray();Voice active=active(c);
        for(Voice v:VOICES){long[] progress=ModelDownloadService.RUN.get(v.id);String state=ready(c,v)?"ready":ModelDownloadService.QUEUED.contains(v.id)?"queued":progress!=null?"running":part(c,v).isFile()?"paused":"none";
            voices.put(new JSONObject().put("id",v.id).put("label",Taal.t(c,v.label)).put("language",v.language).put("size",v.size).put("state",state).put("bytes",progress==null?part(c,v).length():progress[0]).put("error",Taal.tx(c,prefs(c).getString("error_"+v.id,""))).put("selected",active==v));}
        return new JSONObject().put("voices",voices).put("voice",active==null?"":active.id).put("speed",speed(c)).put("supported",supported(c)).put("background",prefs(c).getBoolean("background",false)).put("engine",VoiceEngine.ENGINE).put("wifiOnly",LocalAi.wifiOnly(c));
    }
}
