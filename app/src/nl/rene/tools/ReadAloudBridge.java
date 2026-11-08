package nl.rene.tools;

import android.webkit.JavascriptInterface;
import java.lang.ref.WeakReference;
import org.json.JSONObject;

public class ReadAloudBridge extends EreaderBridge {
    ReadAloudBridge(MainActivity a){super(a);}
    static ReadAloud.Sink sink(MainActivity owner,String callback){WeakReference<MainActivity> weak=new WeakReference<>(owner);return event->{MainActivity target=weak.get();if(target!=null&&!target.isDestroyed())target.js(callback,event.toString());};}
    static ReadAloud.Sink aiSink(MainActivity owner){WeakReference<MainActivity> weak=new WeakReference<>(owner);java.util.concurrent.atomic.AtomicBoolean begun=new java.util.concurrent.atomic.AtomicBoolean();return event->{
        MainActivity a=weak.get();if(a==null||a.isDestroyed())return;a.js("onSpeech",event.toString());String state=event.optString("state"),legacy="";
        if(state.equals("playing")&&begun.compareAndSet(false,true))legacy="start";else if(state.equals("done"))legacy="done";else if(state.equals("stopped"))legacy="stop";else if(state.equals("error"))legacy="error";
        if(!legacy.isEmpty())try{JSONObject out=new JSONObject().put("id",event.getString("request")).put("state",legacy);if(event.has("error"))out.put("error",event.getString("error"));if(event.has("code"))out.put("code",event.getString("code"));a.js("onAiSpeak",out.toString());}catch(Throwable ignored){ }
    };}
    @JavascriptInterface public String speechModels(){try{return VoiceModels.json(ctx).toString();}catch(Throwable ignored){return "{}";}}
    @JavascriptInterface public String speechConfigure(String voice,double speed,boolean background){try{VoiceModels.configure(ctx,voice,speed);VoiceModels.prefs(ctx).edit().putBoolean("background",background).apply();return "";}catch(Throwable e){return Taal.t(ctx,"Kies een stem en een snelheid tussen 0,6 en 1,8");}}
    @JavascriptInterface public String speechDownload(String id){try{VoiceModels.Voice v=VoiceModels.model(id);if(v==null)throw new Exception();VoiceModels.download(ctx,v);return "";}catch(Throwable e){return Taal.t(ctx,"De voorleesstem kan niet worden gedownload; probeer het opnieuw");}}
    @JavascriptInterface public void speechDownloadStop(String id){if(VoiceModels.model(id)!=null)VoiceModels.stop(ctx,id);}
    @JavascriptInterface public void speechDelete(String id){VoiceModels.Voice v=VoiceModels.model(id);if(v==null)return;VoiceModels.stop(ctx,id);ReadAloud.whenIdle(()->{try{synchronized(VoiceModels.FILES){VoiceArchive.erase(VoiceModels.dir(ctx,v));VoiceArchive.erase(VoiceModels.part(ctx,v));}MainActivity.jsAll("onAiModels","");}catch(Throwable ignored){ }});}
    @JavascriptInterface public void speechStart(String text,String request,String source,boolean markdown){if(text==null||text.length()>120000)return;ReadAloud.start(a,request,source,markdown?AiSpeech.plain(text):text,"",0,0,false,sink(a,"onSpeech"));}
    @JavascriptInterface public void speechBook(String book,int chapter,int offset,String request){if(book==null||!book.matches("[a-f0-9]{64}")||chapter<0||offset<0)return;ReadAloud.start(a,request,"reader","",book,chapter,offset,VoiceModels.prefs(ctx).getBoolean("background",false),sink(a,"onSpeech"));}
    @JavascriptInterface public void speechPause(String request,boolean paused){ReadAloud.pause(a.speechOwner,request,paused);}
    @JavascriptInterface public void speechStop(String request){ReadAloud.stop(a.speechOwner,request);}
    @JavascriptInterface public String speechState(){try{ReadAloud.attach(a);return ReadAloud.state(a.speechOwner).toString();}catch(Throwable ignored){return "{}";}}
}
