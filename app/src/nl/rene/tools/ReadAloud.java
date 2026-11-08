package nl.rene.tools;

import android.content.Context;
import android.media.*;
import android.os.*;
import org.json.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

/** Eén eigenaar, één wachtrij en één lokale stem. Geen tekst of audio op schijf. */
final class ReadAloud {
    private ReadAloud(){ }
    static final Object LOCK=new Object();
    static final ExecutorService WORK=Executors.newSingleThreadExecutor();
    static volatile Job current;
    interface Sink{void event(JSONObject event);}
    static final class Job {
        final Context context;volatile WeakReference<MainActivity> activity;volatile String owner;final String request,source,book;volatile Sink sink;
        final boolean background;volatile String text="",state="loading";volatile int chapter,offset;volatile PowerManager.WakeLock wake;volatile boolean cancelled,paused;volatile AudioTrack track;
        Job(MainActivity a,String request,String source,String text,String book,int chapter,int offset,boolean background,Sink sink){this.context=a.getApplicationContext();this.activity=new WeakReference<>(a);this.owner=a.speechOwner;this.request=request;this.source=source;this.text=text;this.book=book;this.chapter=chapter;this.offset=offset;this.background=background;this.sink=sink;}
        boolean alive(){MainActivity a=activity.get();return !cancelled&&current==this&&(background||a!=null&&a.masterForeground&&!a.isDestroyed());}
    }
    static void event(Job job,String state,String error){synchronized(LOCK){
        if((job.cancelled||current!=job)&&!state.equals("stopped"))return;if(job.paused&&Arrays.asList("playing","progress","chapter","loading").contains(state))state="paused";
        try{job.state=state;JSONObject e=new JSONObject().put("request",job.request).put("source",job.source).put("state",state).put("book",job.book).put("chapter",job.chapter).put("offset",job.offset);
            if(error!=null)e.put("error",Taal.t(job.context,error)).put("code",error.equals("Download eerst een lokale voorleesstem")?"voice_missing":"speech_error");job.sink.event(e);if(job.background)ReadAloudService.changed(job.context,job.request);
        }catch(Throwable ignored){ }
    }}
    static boolean validId(String id){return id!=null&&id.matches("[a-zA-Z0-9_-]{1,90}");}
    static void start(MainActivity a,String request,String source,String text,String book,int chapter,int offset,boolean background,Sink sink){
        if(!validId(request)||!validId(source))return;
        Job job=new Job(a,request,source,text,book,chapter,offset,background,sink);
        if(!a.masterForeground){event(job,"stopped",null);return;}
        synchronized(LOCK){Job old=current;if(old!=null)cancel(old);current=job;}
        event(job,"loading",null);
        if(background){ReadAloudService.begin(a,request);return;}synchronized(LOCK){if(current==job)WORK.execute(()->run(job));}
    }
    static void startService(String request){synchronized(LOCK){Job j=current;if(j!=null&&j.background&&j.request.equals(request))WORK.execute(()->run(j));}}
    static void cancel(Job job){job.cancelled=true;job.paused=false;job.text="";releaseWake(job);AudioTrack t=job.track;if(t!=null)try{t.pause();t.flush();}catch(Throwable ignored){ }synchronized(job){job.notifyAll();}event(job,"stopped",null);}
    static void stop(String owner,String request){synchronized(LOCK){Job j=current;if(j!=null&&(owner==null||j.owner.equals(owner))&&(request==null||j.request.equals(request))){current=null;cancel(j);}}}
    static void whenIdle(Runnable action){synchronized(LOCK){stop(null,null);WORK.execute(action);}}
    static void leaving(String owner){Job j=current;if(j!=null&&j.owner.equals(owner)&&!j.background)stop(owner,j.request);}
    static void pause(String owner,String request,boolean pause){synchronized(LOCK){Job j=current;if(j==null||j.cancelled||!j.owner.equals(owner)||!j.request.equals(request))return;j.paused=pause;if(pause)releaseWake(j);try{AudioTrack t=j.track;if(t!=null){if(pause)t.pause();else t.play();}}catch(Throwable ignored){ }synchronized(j){j.notifyAll();}event(j,pause?"paused":"playing",null);}}
    static void pauseNotification(String request,boolean pause){Job j=current;if(j!=null&&j.request.equals(request))pause(j.owner,request,pause);}
    static boolean waitFor(Job job)throws InterruptedException {synchronized(job){while(job.alive()&&job.paused){releaseWake(job);job.wait(200);}}if(job.alive()&&job.wake!=null&&!job.wake.isHeld())job.wake.acquire(600000);return job.alive();}
    static void releaseWake(Job job){try{if(job.wake!=null&&job.wake.isHeld())job.wake.release();}catch(Throwable ignored){ }}
    static String speakable(String text){StringBuilder out=new StringBuilder();text.codePoints().forEach(cp->{if(cp<=0xffff&&(cp<0xd800||cp>0xdfff)&&(cp=='\n'||cp=='\t'||!Character.isISOControl(cp)&&Character.getType(cp)!=Character.FORMAT))out.append((char)cp);});return out.toString();}
    static void run(Job job){
        VoiceEngine engine=null;AudioManager audio=(AudioManager)job.context.getSystemService(Context.AUDIO_SERVICE);AudioFocusRequest focus=null;PowerManager.WakeLock wake=null;
        AudioManager.OnAudioFocusChangeListener listener=change->{if(change==AudioManager.AUDIOFOCUS_LOSS||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)stop(job.owner,job.request);};
        try{
            if(!waitFor(job))return;VoiceModels.Voice voice=VoiceModels.active(job.context);if(voice==null)throw new Exception(Taal.T("Download eerst een lokale voorleesstem"));
            synchronized(VoiceModels.FILES){if(!job.alive())return;engine=new VoiceEngine(VoiceModels.dir(job.context,voice),voice);}
            if(!waitFor(job))return;
            int usage=job.source.equals("ai")&&Build.VERSION.SDK_INT>=26?AudioAttributes.USAGE_ASSISTANT:AudioAttributes.USAGE_MEDIA;
            AudioAttributes attrs=new AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();int gain=job.background?AudioManager.AUDIOFOCUS_GAIN:AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,granted;
            if(Build.VERSION.SDK_INT>=26){focus=new AudioFocusRequest.Builder(gain).setAudioAttributes(attrs).setOnAudioFocusChangeListener(listener,new Handler(Looper.getMainLooper())).build();granted=audio.requestAudioFocus(focus);}else granted=audio.requestAudioFocus(listener,AudioManager.STREAM_MUSIC,gain);
            if(granted!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)throw new Exception(Taal.T("Voorlezen kan nu niet: er is iets anders bezig met geluid (bijvoorbeeld een gesprek)."));
            int rate=engine.rate();if(rate<8000||rate>48000)throw new Exception();
            int buffer=Math.max(rate/2,AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT));
            buffer=(buffer+1)/2*2; // AudioTrack vereist hele mono-16bitframes (2 bytes).
            job.track=new AudioTrack(attrs,new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),buffer,AudioTrack.MODE_STREAM,AudioManager.AUDIO_SESSION_ID_GENERATE);
            if(job.track.getState()!=AudioTrack.STATE_INITIALIZED)throw new Exception();job.track.play();
            if(job.background){wake=((PowerManager)job.context.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"renestools:voorlezen");wake.setReferenceCounted(false);job.wake=wake;wake.acquire(600000);}
            long written=0;
            do{
                if(!job.book.isEmpty()){JSONObject chapter=Ereader.chapter(job.context,job.book,job.chapter);job.text=chapter.getString("text");if(job.offset<0||job.offset>job.text.length())throw new Exception();}
                if(job.text.length()>2_000_000)throw new Exception(Taal.T("Deze tekst is te lang om in één keer voor te lezen"));
                int cursor=job.offset;
                for(String part:AiSpeech.chunks(job.text.substring(cursor),220)){
                    if(!waitFor(job))return;int at=job.text.indexOf(part,cursor);if(at<cursor)at=cursor;job.offset=at;
                    String say=speakable(part);if(!say.matches("(?s).*[\\p{IsLatin}\\p{Nd}].*")){cursor=at+part.length();continue;}
                    float[] pcm=engine.generate(say,VoiceModels.speed(job.context));if(!waitFor(job))return;if(pcm.length==0)throw new Exception();
                    if(pcm.length>rate*90L)throw new Exception();event(job,"playing",null);
                    short[] samples=new short[Math.min(4096,pcm.length)];int p=0;
                    while(p<pcm.length){if(!waitFor(job))return;int n=Math.min(samples.length,pcm.length-p);for(int k=0;k<n;k++)samples[k]=(short)(Math.max(-1,Math.min(1,pcm[p+k]))*32767);
                        int sent=job.track.write(samples,0,n,AudioTrack.WRITE_BLOCKING);if(sent<=0)throw new Exception();p+=sent;written+=sent;}
                    while((job.track.getPlaybackHeadPosition()&0xffffffffL)<written){if(!waitFor(job))return;Thread.sleep(20);}
                    // Boekimport houdt Ereader.class vast: wacht daarop zonder de bedieningslock.
                    synchronized(job.book.isEmpty()?LOCK:Ereader.class){synchronized(LOCK){if(!job.alive())return;
                    cursor=at+part.length();job.offset=cursor;
                    if(!job.book.isEmpty())Ereader.position(job.context,job.book,job.chapter,job.text.isEmpty()?0:(double)cursor/job.text.length(),cursor);
                    event(job,"progress",null);
                    }}
                }
                if(job.book.isEmpty())break;
                JSONObject book=Ereader.meta(job.context,job.book);JSONArray names=book.getJSONArray("chapters"),linear=book.optJSONArray("linear");int next=job.chapter+1;while(next<names.length()&&linear!=null&&!linear.optBoolean(next,true))next++;
                if(next>=names.length())break;job.chapter=next;job.offset=0;event(job,"chapter",null);
            }while(job.alive());
            if(job.alive())event(job,"done",null);
        }catch(Throwable e){android.util.Log.w("Voorlezen",where(e));if(job.alive())event(job,"error",e instanceof Exception&&e.getMessage()!=null&&Arrays.asList("Download eerst een lokale voorleesstem","Deze tekst is te lang om in één keer voor te lezen","Voorlezen kan nu niet: er is iets anders bezig met geluid (bijvoorbeeld een gesprek).").contains(e.getMessage())?e.getMessage():Taal.T("Lokaal voorlezen lukt niet; probeer een korter stuk tekst"));}
        finally{
            AudioTrack track=job.track;job.track=null;if(track!=null)try{track.pause();track.flush();track.release();}catch(Throwable ignored){ }
            if(engine!=null)try{engine.close();}catch(Throwable ignored){ }if(wake!=null&&wake.isHeld())try{wake.release();}catch(Throwable ignored){ }
            try{if(Build.VERSION.SDK_INT>=26&&focus!=null)audio.abandonAudioFocusRequest(focus);else audio.abandonAudioFocus(listener);}catch(Throwable ignored){ }
            job.text="";synchronized(LOCK){if(current==job)current=null;}if(job.background)ReadAloudService.finished(job.context,job.request);
        }
    }
    /** Soort fout en plek in de code voor logcat; nooit de foutmelding (die kan tekst of paden bevatten). */
    static String where(Throwable e){StringBuilder out=new StringBuilder();for(Throwable t=e;t!=null&&out.length()<1500;t=t.getCause()){out.append(out.length()==0?"":" | oorzaak ").append(t.getClass().getName());StackTraceElement[] st=t.getStackTrace();for(int i=0;i<Math.min(6,st.length);i++)out.append(" @ ").append(st[i].getClassName()).append('.').append(st[i].getMethodName()).append(':').append(st[i].getLineNumber());if(t.getCause()==t)break;}return out.toString();}
    static void attach(MainActivity a){synchronized(LOCK){Job j=current;if(j==null||!j.background||j.owner.equals(a.speechOwner))return;MainActivity old=j.activity.get();if(old!=null&&!old.isDestroyed()&&old.masterForeground)return;if(!a.masterForeground)return;j.owner=a.speechOwner;j.activity=new WeakReference<>(a);j.sink=ReadAloudBridge.sink(a,"onSpeech");}}
    static JSONObject state(String owner)throws Exception {Job j=current;if(j==null||!j.owner.equals(owner))return new JSONObject().put("active",false);return new JSONObject().put("active",true).put("request",j.request).put("source",j.source).put("state",j.state).put("book",j.book).put("chapter",j.chapter).put("offset",j.offset);}
}
