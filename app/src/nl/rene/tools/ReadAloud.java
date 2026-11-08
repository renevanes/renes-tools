package nl.rene.tools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.*;
import android.os.*;
import org.json.*;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

/**
 * Eén eigenaar, één wachtrij en één lokale stem. Geen tekst of audio op schijf.
 *
 * Draden: WORK speelt af (één sessie tegelijk); SYNTH doet alles met de spraakmotor (laden, zinnen maken, sluiten),
 * zodat de volgende zin al klaar is terwijl de huidige klinkt en de motor nooit tegelijk gesloten en gebruikt wordt.
 * De motor blijft na een sessie nog IDLE_MS warm (snel antwoorden in een gesprek met de assistent).
 *
 * Gebeurtenissen (onSpeech): request, source, state, book, chapter, offset, end.
 * - playing: het stuk [offset, end) van de tekst (of het hoofdstuk) is nu hoorbaar (één zin, hooguit MAX tekens);
 * - progress: het stuk tot offset is uitgesproken (bij een boek is de leesplek dan bewaard);
 * - chapter: een nieuw hoofdstuk begint (offset 0); loading, paused, done, stopped, error (met error en code).
 */
final class ReadAloud {
    private ReadAloud(){ }
    static final Object LOCK=new Object();
    static final ExecutorService WORK=Executors.newSingleThreadExecutor();
    /** Alles met de spraakmotor. Wordt vervangen als een zin vastloopt in de native code (zie wedged). */
    static volatile ScheduledExecutorService SYNTH=Executors.newSingleThreadScheduledExecutor();
    static final int MAX=220;
    /** Gepauzeerd langer dan dit: de sessie stopt (zoals PodcastService), zodat er geen dienst en motor blijven hangen. */
    static final long PAUSE_KEEP_MS=15*60_000L;
    /** Pauze door een gesprek (audiofocus tijdelijk weg) telt niet mee voor PAUSE_KEEP_MS; wel deze ruime grens. */
    static final long FOCUS_KEEP_MS=2*60*60_000L;
    /** De klank staat zo lang stil terwijl er niet gepauzeerd is: de audio-uitvoer werkt niet, stoppen met een fout. */
    static final long STALL_MS=10_000L;
    /** Zo lang mag één zin (of het laden van de motor) duren voordat het als vastgelopen geldt. */
    static final long SYNTH_MS=120_000L;
    static final long WAKE_MS=10*60_000L;
    /** Na een sessie blijft de motor zo lang geladen. */
    static final long IDLE_MS=60_000L;
    static final String WEDGED=Taal.T("Herstart Tools om voor te lezen");
    /** Een zin bleef in de native spraakmotor hangen: die draad is niet te stoppen, dus tot een herstart geen voorlezen. */
    static volatile boolean wedged;
    static volatile Job current;
    interface Sink{void event(JSONObject event);}
    static final class Job {
        final Context context;volatile WeakReference<MainActivity> activity;volatile String owner;final String request,source,book;volatile Sink sink;
        final boolean background;volatile String text="",state="loading";volatile int chapter,offset,end;volatile PowerManager.WakeLock wake;volatile boolean cancelled,paused;volatile AudioTrack track;
        /** Pauze door een tijdelijk verlies van audiofocus (gesprek, navigatie): bij terugkrijgen vanzelf verder. */
        volatile boolean focusPaused,focusLost;volatile long pausedAt,wakeAt;
        volatile AudioAttributes attrs;volatile Object focusRequest;
        /** De stem van deze sessie (bij het starten gekozen); verwijderen van een andere stem laat de sessie door. */
        volatile String voiceId;
        final AudioManager.OnAudioFocusChangeListener listener;
        Job(MainActivity a,String request,String source,String text,String book,int chapter,int offset,boolean background,Sink sink){this.context=a.getApplicationContext();this.activity=new WeakReference<>(a);this.owner=a.speechOwner;this.request=request;this.source=source;this.text=text;this.book=book;this.chapter=chapter;this.offset=offset;this.end=offset;this.background=background;this.sink=sink;this.listener=change->focusChanged(this,change);}
        boolean alive(){MainActivity a=activity.get();return !cancelled&&current==this&&(background||a!=null&&a.masterForeground&&!a.isDestroyed());}
    }
    static final List<String> SHOWN=Arrays.asList("Download eerst een lokale voorleesstem","Deze tekst is te lang om in één keer voor te lezen","Voorlezen kan nu niet: er is iets anders bezig met geluid (bijvoorbeeld een gesprek).",WEDGED);
    static JSONObject json(Job job,String state,String error)throws JSONException {
        JSONObject e=new JSONObject().put("request",job.request).put("source",job.source).put("state",state).put("book",job.book).put("chapter",job.chapter).put("offset",job.offset).put("end",Math.max(job.offset,job.end));
        if(error!=null)e.put("error",Taal.t(job.context,error)).put("code",error.equals("Download eerst een lokale voorleesstem")?"voice_missing":"speech_error");
        return e;
    }
    static void event(Job job,String state,String error){synchronized(LOCK){
        if((job.cancelled||current!=job)&&!state.equals("stopped"))return;if(job.paused&&Arrays.asList("playing","progress","chapter","loading").contains(state))state="paused";
        try{job.state=state;job.sink.event(json(job,state,error));if(job.background)ReadAloudService.changed(job.context,job.request);}catch(Throwable ignored){ }
    }}
    /** Een aanvraag die niet start (te lang, ongeldige id, motor vastgelopen): altijd een error, zodat niets op "laden" blijft staan. */
    static void reject(MainActivity a,String request,String source,String book,String error,Sink sink){
        try{JSONObject e=new JSONObject().put("request",request==null?"":request.length()>90?request.substring(0,90):request).put("source",source==null?"":source).put("state","error").put("book",book==null?"":book).put("chapter",0).put("offset",0).put("end",0)
            .put("error",Taal.t(a,error)).put("code",error.equals("Download eerst een lokale voorleesstem")?"voice_missing":"speech_error");sink.event(e);}catch(Throwable ignored){ }
    }
    static boolean validId(String id){return id!=null&&id.matches("[a-zA-Z0-9_-]{1,90}");}
    static void start(MainActivity a,String request,String source,String text,String book,int chapter,int offset,boolean background,Sink sink){
        if(!validId(request)||!validId(source)){reject(a,request,source,book,"Lokaal voorlezen lukt niet; probeer een korter stuk tekst",sink);return;}
        if(wedged){reject(a,request,source,book,WEDGED,sink);return;}
        Job job=new Job(a,request,source,text,book,chapter,offset,background,sink);
        if(!a.masterForeground){event(job,"stopped",null);return;}
        VoiceModels.Voice voice=VoiceModels.active(a);job.voiceId=voice==null?null:voice.id;
        synchronized(LOCK){Job old=current;if(old!=null)cancel(old);current=job;}
        event(job,"loading",null);
        if(background){ReadAloudService.begin(a,request);return;}synchronized(LOCK){if(current==job)WORK.execute(()->run(job));}
    }
    static void startService(String request){synchronized(LOCK){Job j=current;if(j!=null&&j.background&&j.request.equals(request))WORK.execute(()->run(j));}}
    static void cancel(Job job){job.cancelled=true;job.paused=false;job.text="";releaseWake(job);AudioTrack t=job.track;if(t!=null)try{t.pause();t.flush();}catch(Throwable ignored){ }synchronized(job){job.notifyAll();}event(job,"stopped",null);}
    static void stop(String owner,String request){synchronized(LOCK){Job j=current;if(j!=null&&(owner==null||j.owner.equals(owner))&&(request==null||j.request.equals(request))){current=null;cancel(j);}}}
    /**
     * Stem verwijderen: alleen een sessie met díe stem stopt. De bestanden gaan pas weg als de motor ermee gesloten is
     * (na de sessie, op SYNTH); is de motor vastgelopen, dan meteen.
     */
    static void deleteVoice(String id,Runnable erase){synchronized(LOCK){
        Job j=current;boolean uses=j!=null&&(j.voiceId==null||id.equals(j.voiceId));
        Runnable task=()->{try{SYNTH.execute(()->{try{if(warmKey!=null&&warmKey.startsWith(id+"|"))closeWarm();}catch(Throwable ignored){ }try{erase.run();}catch(Throwable ignored){ }});}catch(Throwable e){try{erase.run();}catch(Throwable ignored){ }}};
        if(uses){stop(null,j.request);WORK.execute(task);}else task.run();
    }}
    static void leaving(String owner){Job j=current;if(j!=null&&j.owner.equals(owner)&&!j.background)stop(owner,j.request);}
    /** Pauzeren of verder door Rene (knop, melding, headset). Verder vraagt zo nodig opnieuw de audiofocus. */
    static void pause(String owner,String request,boolean pause){synchronized(LOCK){Job j=current;if(j==null||j.cancelled||!j.owner.equals(owner)||!j.request.equals(request))return;
        j.focusPaused=false;
        if(!pause&&j.focusLost){if(!requestFocus(j)){event(j,"paused","Voorlezen kan nu niet: er is iets anders bezig met geluid (bijvoorbeeld een gesprek).");return;}j.focusLost=false;}
        setPaused(j,pause);}}
    static void setPaused(Job j,boolean pause){synchronized(LOCK){if(j.cancelled||current!=j)return;
        if(pause&&!j.paused)j.pausedAt=SystemClock.elapsedRealtime();j.paused=pause;if(pause)releaseWake(j);
        try{AudioTrack t=j.track;if(t!=null){if(pause)t.pause();else t.play();}}catch(Throwable ignored){ }
        synchronized(j){j.notifyAll();}event(j,pause?"paused":"playing",null);}}
    static void pauseNotification(String request,boolean pause){Job j=current;if(j!=null&&j.request.equals(request))pause(j.owner,request,pause);}
    /** Gesprek of navigatie: pauzeren en daarna vanzelf verder; een andere app die gaat spelen: pauzeren tot Rene verder kiest. */
    static void focusChanged(Job j,int change){try{synchronized(LOCK){if(j.cancelled||current!=j)return;
        if(change==AudioManager.AUDIOFOCUS_LOSS){j.focusLost=true;j.focusPaused=false;if(!j.paused)setPaused(j,true);}
        else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK){if(!j.paused){j.focusPaused=true;setPaused(j,true);}}
        else if(change==AudioManager.AUDIOFOCUS_GAIN){j.focusLost=false;if(j.focusPaused){j.focusPaused=false;setPaused(j,false);}}
    }}catch(Throwable ignored){ }}
    /** Oortjes of bluetooth los: pauzeren (niet vanzelf verder), zodat de stem niet ineens door de luidspreker klinkt. */
    static final class Noisy extends BroadcastReceiver {
        final WeakReference<Job> job;Noisy(Job j){job=new WeakReference<>(j);}
        @Override public void onReceive(Context c,Intent i){try{Job j=job.get();if(j==null||i==null||!AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction()))return;synchronized(LOCK){if(current==j&&!j.cancelled&&!j.paused){j.focusPaused=false;setPaused(j,true);}}}catch(Throwable ignored){ }}
    }
    static boolean requestFocus(Job j){
        try{AudioManager audio=(AudioManager)j.context.getSystemService(Context.AUDIO_SERVICE);int gain=j.background?AudioManager.AUDIOFOCUS_GAIN:AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,granted;
            if(Build.VERSION.SDK_INT>=26){AudioFocusRequest r=new AudioFocusRequest.Builder(gain).setAudioAttributes(j.attrs).setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(j.listener,new Handler(Looper.getMainLooper())).build();j.focusRequest=r;granted=audio.requestAudioFocus(r);}
            else granted=audio.requestAudioFocus(j.listener,AudioManager.STREAM_MUSIC,gain);
            return granted==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }catch(Throwable e){return false;}
    }
    static void abandonFocus(Job j){try{AudioManager audio=(AudioManager)j.context.getSystemService(Context.AUDIO_SERVICE);if(Build.VERSION.SDK_INT>=26){if(j.focusRequest!=null)audio.abandonAudioFocusRequest((AudioFocusRequest)j.focusRequest);}else audio.abandonAudioFocus(j.listener);}catch(Throwable ignored){ }}
    /** Wacht tijdens een pauze (zonder CPU-slot); na PAUSE_KEEP_MS stopt de sessie (gesprek: FOCUS_KEEP_MS). False = sessie voorbij. */
    static boolean waitFor(Job job)throws InterruptedException {
        while(true){
            boolean expired=false;
            synchronized(job){
                if(!job.alive())return false;
                if(!job.paused)break;
                releaseWake(job);
                if(SystemClock.elapsedRealtime()-job.pausedAt>(job.focusPaused?FOCUS_KEEP_MS:PAUSE_KEEP_MS))expired=true;else job.wait(200);
            }
            if(expired){stop(null,job.request);return false;}
        }
        holdWake(job);return job.alive();
    }
    /** Boek op de achtergrond: CPU wakker houden tijdens laden, zinnen maken en afspelen; om de minuut verlengen. */
    static void holdWake(Job job){try{PowerManager.WakeLock w=job.wake;if(w==null||job.paused)return;long now=SystemClock.elapsedRealtime();if(!w.isHeld()||now-job.wakeAt>60_000L){w.acquire(WAKE_MS);job.wakeAt=now;}}catch(Throwable ignored){ }}
    static void releaseWake(Job job){try{if(job.wake!=null&&job.wake.isHeld())job.wake.release();}catch(Throwable ignored){ }}

    // ----- Tekst (zuiver, getest in ReadAloudTextTest)

    /** Alleen wat de stem kan uitspreken: geen besturingstekens, emoji of andere tekens buiten het basisvlak. */
    static String speakable(String text){StringBuilder out=new StringBuilder(text.length());for(int i=0;i<text.length();){int cp=text.codePointAt(i);i+=Character.charCount(cp);if(cp<=0xffff&&(cp<0xd800||cp>0xdfff)&&(cp=='\n'||cp=='\t'||!Character.isISOControl(cp)&&Character.getType(cp)!=Character.FORMAT))out.append((char)cp);}return out.toString();}
    /** Bevat een Latijnse letter of een cijfer (zonder \p{IsLatin}: dat kent de reguliere expressie van Android 7 niet). */
    static boolean sayable(String text){for(int i=0;i<text.length();){int cp=text.codePointAt(i);i+=Character.charCount(cp);if(Character.isDigit(cp)||Character.isLetter(cp)&&latin(cp))return true;}return false;}
    static boolean latin(int cp){return cp<0x250||cp>=0x1d00&&cp<=0x1dbf||cp>=0x1e00&&cp<=0x1eff||cp>=0x2c60&&cp<=0x2c7f||cp>=0xa720&&cp<=0xa7ff||cp>=0xab30&&cp<=0xab6f||cp>=0xff21&&cp<=0xff5a;}
    static final String ENDS=".!?…",CLOSERS="\"'”’)»]";
    /**
     * Zinnen vanaf `from` als [begin, eind) in de oorspronkelijke tekst, zonder witruimte eromheen en hooguit `max`
     * tekens (langer: knippen bij een spatie, anders hard). Een zin eindigt bij een regeleinde of bij .!?… gevolgd door
     * witruimte of het einde (eventueel na een aanhalingsteken of haakje), dus "3.5" en "www.x.nl" blijven heel.
     */
    static List<int[]> segments(String text,int from,int max){
        List<int[]> out=new ArrayList<>();int n=text.length(),s=Math.max(0,Math.min(from,n)),i=s;
        while(i<n){char c=text.charAt(i);
            if(c=='\n'){add(out,text,s,i,max);s=++i;continue;}
            if(ENDS.indexOf(c)>=0){int j=i+1;while(j<n&&ENDS.indexOf(text.charAt(j))>=0)j++;while(j<n&&CLOSERS.indexOf(text.charAt(j))>=0)j++;
                if(j>=n||Character.isWhitespace(text.charAt(j))){add(out,text,s,j,max);s=j;}i=j;continue;}
            i++;}
        add(out,text,s,n,max);return out;
    }
    static void add(List<int[]> out,String text,int s,int e,int max){
        while(s<e&&Character.isWhitespace(text.charAt(s)))s++;while(e>s&&Character.isWhitespace(text.charAt(e-1)))e--;
        while(e-s>max){int cut=s+max;for(int k=s+max;k>s+max/2;k--)if(Character.isWhitespace(text.charAt(k-1))){cut=k;break;}
            if(cut==s+max&&Character.isHighSurrogate(text.charAt(cut-1)))cut--;
            int ce=cut;while(ce>s&&Character.isWhitespace(text.charAt(ce-1)))ce--;out.add(new int[]{s,ce});s=cut;while(s<e&&Character.isWhitespace(text.charAt(s)))s++;}
        if(e>s)out.add(new int[]{s,e});
    }

    // ----- De motor (alleen op SYNTH; warm gehouden per stem)

    private static VoiceEngine warm;private static String warmKey;private static int warmUsers;private static long warmIdleSince;
    /** Op SYNTH: dezelfde motor als de stem en map gelijk zijn, anders de oude sluiten en een nieuwe laden. */
    static VoiceEngine engineFor(VoiceModels.Voice voice,File dir){
        String key=voice.id+"|"+dir.getPath();
        if(warm!=null&&!key.equals(warmKey)){if(warmUsers>0)throw new IllegalStateException("motor in gebruik");closeWarm();}
        if(warm==null){synchronized(VoiceModels.FILES){warm=new VoiceEngine(dir,voice);}warmKey=key;}
        warmUsers++;return warm;
    }
    /** Op SYNTH: sessie klaar; na IDLE_MS zonder nieuwe sessie gaat de motor dicht. */
    static void releaseEngine(VoiceEngine e){
        if(e!=warm){try{e.close();}catch(Throwable ignored){ }return;}
        warmUsers=Math.max(0,warmUsers-1);warmIdleSince=SystemClock.elapsedRealtime();
        if(warmUsers==0)try{SYNTH.schedule(ReadAloud::idleCheck,IDLE_MS+50,TimeUnit.MILLISECONDS);}catch(Throwable ignored){ }
    }
    static void idleCheck(){if(warm!=null&&warmUsers==0&&SystemClock.elapsedRealtime()-warmIdleSince>=IDLE_MS)closeWarm();}
    static void closeWarm(){if(warm==null||warmUsers>0)return;VoiceEngine e=warm;warm=null;warmKey=null;try{e.close();}catch(Throwable ignored){ }}
    /** Geheugen vrijmaken (onTrimMemory) of een andere stem gekozen: een ongebruikte motor sluiten. */
    static void dropEngine(){try{SYNTH.execute(ReadAloud::closeWarm);}catch(Throwable ignored){ }}
    /** Native code hangt: die draad loslaten, een nieuwe SYNTH voor opruimen, en tot een herstart niet meer voorlezen. */
    static void wedge(Context c){
        synchronized(LOCK){if(wedged)return;wedged=true;ScheduledExecutorService old=SYNTH;SYNTH=Executors.newSingleThreadScheduledExecutor();warm=null;warmKey=null;warmUsers=0;try{old.shutdownNow();}catch(Throwable ignored){ }}
        App.log(c,"AI","voorlezen: spraakmotor reageert niet meer; herstart nodig");
    }

    // ----- Afspelen

    /** Resultaat van de spraakmotor zonder de bediening te blokkeren; null = sessie intussen voorbij. Te lang = vastgelopen. */
    static <T> T await(Job job,Future<T> f)throws Exception {
        long until=SystemClock.elapsedRealtime()+SYNTH_MS;
        while(true){
            if(!job.alive())return null;
            try{return f.get(200,TimeUnit.MILLISECONDS);}
            catch(TimeoutException e){if(SystemClock.elapsedRealtime()>until&&!job.paused){wedge(job.context);throw new Exception(WEDGED);}if(job.paused)until=SystemClock.elapsedRealtime()+SYNTH_MS;holdWake(job);}
            catch(ExecutionException e){throw e.getCause() instanceof Exception?(Exception)e.getCause():e;}
        }
    }
    static Future<float[]> synth(Job job,VoiceEngine engine,String text,int[] seg){
        String say=speakable(text.substring(seg[0],seg[1]));
        if(!sayable(say))return CompletableFuture.completedFuture(new float[0]); // alleen tekens als "* * *": overslaan
        return SYNTH.submit(()->job.alive()?engine.generate(say,VoiceModels.speed(job.context)):new float[0]);
    }
    /** Hoorbaar-meldingen: per stuk {beginframe, eindframe, begin, eind, al gemeld}. */
    static void report(Job job,AudioTrack track,ArrayDeque<long[]> marks){
        long head=track.getPlaybackHeadPosition()&0xffffffffL;
        while(!marks.isEmpty()){long[] m=marks.peek();
            if(m[4]==0&&m[1]>m[0]&&head>=m[0]){m[4]=1;synchronized(LOCK){if(!job.alive())return;job.offset=(int)m[2];job.end=(int)m[3];event(job,"playing",null);}}
            if(head<m[1])break;marks.poll();progress(job,(int)m[3]);}
    }
    static void progress(Job job,int cursor){
        // Boekimport houdt Ereader.class vast: wacht daarop zonder de bedieningslock.
        synchronized(job.book.isEmpty()?LOCK:Ereader.class){synchronized(LOCK){if(!job.alive())return;
            job.offset=cursor;job.end=cursor;String text=job.text;
            if(!job.book.isEmpty())try{Ereader.position(job.context,job.book,job.chapter,text.isEmpty()?0:(double)cursor/text.length(),cursor);}catch(Throwable ignored){ }
            event(job,"progress",null);
        }}
    }
    /** Niet-blokkerend schrijven: pauze, stoppen en de watchdog blijven werken. False = sessie voorbij. */
    static boolean write(Job job,AudioTrack track,short[] samples,int n,ArrayDeque<long[]> marks,long[] stall)throws Exception {
        int p=0;
        while(p<n){if(!waitFor(job))return false;int sent=track.write(samples,p,n-p,AudioTrack.WRITE_NON_BLOCKING);if(sent<0)throw new IOException("audiotrack "+sent);
            p+=sent;report(job,track,marks);if(sent==0){stalled(job,track,stall);Thread.sleep(20);}}
        return true;
    }
    static void run(Job job){
        VoiceEngine engine=null;Future<VoiceEngine> loading=null;Future<float[]> next=null;BroadcastReceiver noisy=null;
        try{
            if(job.background){PowerManager.WakeLock w=((PowerManager)job.context.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"renestools:voorlezen");w.setReferenceCounted(false);job.wake=w;}
            if(!waitFor(job))return;VoiceModels.Voice voice=VoiceModels.model(job.voiceId);if(voice==null||!VoiceModels.ready(job.context,voice))throw new Exception(Taal.T("Download eerst een lokale voorleesstem"));
            File dir=VoiceModels.dir(job.context,voice);
            loading=SYNTH.submit(()->job.alive()?engineFor(voice,dir):null);
            engine=await(job,loading);if(engine==null){if(job.alive())throw new IOException("motor");return;}loading=null; // null: finally geeft de motor vrij zodra hij geladen is
            // Pauze of stoppen tijdens het laden: niet alsnog gaan praten.
            if(!waitFor(job))return;
            int usage=job.source.equals("ai")&&Build.VERSION.SDK_INT>=26?AudioAttributes.USAGE_ASSISTANT:AudioAttributes.USAGE_MEDIA;
            job.attrs=new AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            // Een boek op de achtergrond heeft hier al een voorgronddienst (ReadAloudService vóór run): nodig op Android 15+.
            if(!requestFocus(job))throw new Exception(Taal.T("Voorlezen kan nu niet: er is iets anders bezig met geluid (bijvoorbeeld een gesprek)."));
            noisy=new Noisy(job);IntentFilter nf=new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            if(Build.VERSION.SDK_INT>=33)job.context.registerReceiver(noisy,nf,Context.RECEIVER_NOT_EXPORTED);else job.context.registerReceiver(noisy,nf);
            int rate=engine.rate;if(rate<8000||rate>48000)throw new IOException("samplerate");
            // Klein buffer (±0,25 s): snel starten. Een stream-AudioTrack begint pas als het buffer vol is, dus aan het eind
            // komt stilte erachter (zie hieronder), anders blijft een kort antwoord als "Ja." stil hangen.
            int buffer=Math.max(rate/2,AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT));
            buffer=(buffer+1)/2*2; // AudioTrack vereist hele mono-16bitframes (2 bytes).
            job.track=new AudioTrack(job.attrs,new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),buffer,AudioTrack.MODE_STREAM,AudioManager.AUDIO_SESSION_ID_GENERATE);
            AudioTrack track=job.track;if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IOException("audiotrack");
            synchronized(LOCK){if(!job.alive())return;if(!job.paused)track.play();}
            long written=0;short[] samples=new short[4096];
            do{
                String text=job.text;
                if(!job.book.isEmpty()){JSONObject chapter=Ereader.chapter(job.context,job.book,job.chapter);text=chapter.getString("text");job.text=text;if(job.offset<0||job.offset>text.length())throw new IOException("leesplek");}
                if(text.length()>2_000_000)throw new Exception(Taal.T("Deze tekst is te lang om in één keer voor te lezen"));
                List<int[]> segs=segments(text,job.offset,MAX);ArrayDeque<long[]> marks=new ArrayDeque<>();
                next=segs.isEmpty()?null:synth(job,engine,text,segs.get(0));
                for(int i=0;i<segs.size();i++){
                    float[] pcm=await(job,next);next=null;if(pcm==null)return;
                    if(i+1<segs.size())next=synth(job,engine,text,segs.get(i+1)); // volgende zin alvast maken: geen stilte tussen zinnen
                    if(pcm.length>rate*90L)throw new IOException("te lang");
                    marks.add(new long[]{written,written+pcm.length,segs.get(i)[0],segs.get(i)[1],0});
                    long[] stall={track.getPlaybackHeadPosition()&0xffffffffL,SystemClock.elapsedRealtime()};
                    for(int p=0;p<pcm.length;p+=samples.length){int n=Math.min(samples.length,pcm.length-p);for(int k=0;k<n;k++)samples[k]=(short)(Math.max(-1,Math.min(1,pcm[p+k]))*32767);
                        if(!write(job,track,samples,n,marks,stall))return;written+=n;}
                    report(job,track,marks);
                }
                // Stilte erachter, één buffer lang: zo start ook een kort stuk en klinkt de laatste zin helemaal uit.
                if(!marks.isEmpty()){Arrays.fill(samples,(short)0);long[] stall={track.getPlaybackHeadPosition()&0xffffffffL,SystemClock.elapsedRealtime()};
                    for(int left=Math.max(track.getBufferSizeInFrames(),buffer/2)+rate/20;left>0;){int n=Math.min(samples.length,left);if(!write(job,track,samples,n,marks,stall))return;written+=n;left-=n;}}
                long[] stall={track.getPlaybackHeadPosition()&0xffffffffL,SystemClock.elapsedRealtime()};
                while(!marks.isEmpty()){if(!waitFor(job))return;report(job,track,marks);if(marks.isEmpty())break;stalled(job,track,stall);Thread.sleep(20);}
                if(!job.alive())return;
                if(job.book.isEmpty())break;
                JSONObject book=Ereader.meta(job.context,job.book);JSONArray names=book.getJSONArray("chapters"),linear=book.optJSONArray("linear");int nextChapter=job.chapter+1;while(nextChapter<names.length()&&linear!=null&&!linear.optBoolean(nextChapter,true))nextChapter++;
                if(nextChapter>=names.length())break;synchronized(LOCK){if(!job.alive())return;job.chapter=nextChapter;job.offset=0;job.end=0;event(job,"chapter",null);}
            }while(job.alive());
            if(job.alive())event(job,"done",null);
        }catch(Throwable e){android.util.Log.w("Voorlezen",where(e));if(job.alive())event(job,"error",e instanceof Exception&&e.getMessage()!=null&&SHOWN.contains(e.getMessage())?e.getMessage():Taal.T("Lokaal voorlezen lukt niet; probeer een korter stuk tekst"));}
        finally{
            if(noisy!=null)try{job.context.unregisterReceiver(noisy);}catch(Throwable ignored){ }
            AudioTrack track=job.track;job.track=null;if(track!=null)try{track.pause();track.flush();track.release();}catch(Throwable ignored){ }
            // Vrijgeven op SYNTH: na een zin die nog bezig is, nooit tegelijk met de motor. Hij blijft nog even warm.
            final VoiceEngine used=engine;final Future<VoiceEngine> pending=loading;
            if(!wedged)try{SYNTH.execute(()->{try{VoiceEngine e=used!=null?used:pending!=null?pending.get():null;if(e!=null)releaseEngine(e);}catch(Throwable ignored){ }});}catch(Throwable ignored){ }
            releaseWake(job);job.wake=null;abandonFocus(job);
            job.text="";synchronized(LOCK){if(current==job)current=null;}if(job.background)ReadAloudService.finished(job.context,job.request);
        }
    }
    /** Watchdog: de afspeelpositie moet vooruitgaan zolang er niet gepauzeerd is. */
    static void stalled(Job job,AudioTrack track,long[] stall)throws IOException {
        long head=track.getPlaybackHeadPosition()&0xffffffffL,now=SystemClock.elapsedRealtime();
        if(head!=stall[0]||job.paused||job.pausedAt>=stall[1]){stall[0]=head;stall[1]=now;return;}
        if(now-stall[1]>STALL_MS)throw new IOException("audio staat stil");
    }
    /** Soort fout en plek in de code voor logcat; nooit de foutmelding (die kan tekst of paden bevatten). */
    static String where(Throwable e){StringBuilder out=new StringBuilder();for(Throwable t=e;t!=null&&out.length()<1500;t=t.getCause()){out.append(out.length()==0?"":" | oorzaak ").append(t.getClass().getName());StackTraceElement[] st=t.getStackTrace();for(int i=0;i<Math.min(6,st.length);i++)out.append(" @ ").append(st[i].getClassName()).append('.').append(st[i].getMethodName()).append(':').append(st[i].getLineNumber());if(t.getCause()==t)break;}return out.toString();}
    static void attach(MainActivity a){synchronized(LOCK){Job j=current;if(j==null||!j.background||j.owner.equals(a.speechOwner))return;MainActivity old=j.activity.get();if(old!=null&&!old.isDestroyed()&&old.masterForeground)return;if(!a.masterForeground)return;j.owner=a.speechOwner;j.activity=new WeakReference<>(a);j.sink=ReadAloudBridge.sink(a,"onSpeech");}}
    static JSONObject state(String owner)throws Exception {Job j=current;if(j==null||!j.owner.equals(owner))return new JSONObject().put("active",false);return new JSONObject().put("active",true).put("request",j.request).put("source",j.source).put("state",j.state).put("book",j.book).put("chapter",j.chapter).put("offset",j.offset).put("end",Math.max(j.offset,j.end));}
}
