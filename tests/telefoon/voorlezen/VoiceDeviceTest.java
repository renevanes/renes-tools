package nl.rene.tools.test;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.Bundle;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.Arrays;

/** Echte Android JNI, modelinstallatie en bediening; geen test-ingang in de gewone app. */
public final class VoiceDeviceTest extends Instrumentation {
    Context target,isolated;File root;String prefName;Class<?> models,voiceType,engineType,core,jobType,sinkType;Object engine;
    interface Checked{void run()throws Exception;}
    static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    static final class Isolated extends ContextWrapper {
        final File root;final String prefName;
        Isolated(Context c,File root,String name){super(c);this.root=root;this.prefName=name;}
        @Override public File getNoBackupFilesDir(){File d=new File(root,"no-backup");d.mkdirs();return d;}
        @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(prefName,mode);}
    }
    static void erase(File f){File[] all=f.listFiles();if(all!=null)for(File x:all)erase(x);f.delete();}
    Class<?> type(String name)throws Exception{return target.getClassLoader().loadClass("nl.rene.tools."+name);}
    Object call(Class<?> type,Object object,String name,Class<?>[] sig,Object...args)throws Exception{
        Method m=type.getDeclaredMethod(name,sig);m.setAccessible(true);try{return m.invoke(object,args);}catch(InvocationTargetException e){throw new Exception(e.getCause());}
    }
    Field field(Class<?> c,String name)throws Exception{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{target=getTargetContext();runChecks();result.putString("voorlezen","ok");finish(Activity.RESULT_OK,result);}catch(Throwable e){result.putString("voorlezen",android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}finally{if(core!=null)try{call(core,null,"stop",new Class<?>[]{String.class,String.class},null,null);((java.util.concurrent.ExecutorService)field(core,"WORK").get(null)).submit(()->{}).get(15,TimeUnit.SECONDS);}catch(Throwable ignored){}if(engine!=null)try{call(engineType,engine,"close",new Class<?>[]{});}catch(Throwable ignored){}if(root!=null)erase(root);if(prefName!=null)target.deleteSharedPreferences(prefName);}}
    void runChecks()throws Exception{
        models=type("VoiceModels");voiceType=type("VoiceModels$Voice");engineType=type("VoiceEngine");
        java.io.File modelRoot=(java.io.File)call(models,null,"root",new Class<?>[]{Context.class},target);check(modelRoot.listFiles()==null||modelRoot.listFiles().length==0,"requires clean CI install without downloaded voices");check(target.getSharedPreferences("voorlezen",Context.MODE_PRIVATE).getAll().isEmpty(),"requires fresh voice settings");prefName="voorlezen";root=modelRoot;isolated=target;
        Object voice=call(models,null,"model",new Class<?>[]{String.class},"tts-nl-ronnie");File archive=(File)call(models,null,"part",new Class<?>[]{Context.class,voiceType},isolated,voice);
        try(InputStream in=getContext().getAssets().open("voice.tar.bz2");OutputStream out=new FileOutputStream(archive)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        call(models,null,"install",new Class<?>[]{Context.class,voiceType,BooleanSupplier.class},isolated,voice,(BooleanSupplier)()->false);
        check((Boolean)call(models,null,"ready",new Class<?>[]{Context.class,voiceType},isolated,voice),"verified model installed");
        File dir=(File)call(models,null,"dir",new Class<?>[]{Context.class,voiceType},isolated,voice);Constructor<?> ctor=engineType.getDeclaredConstructor(File.class,voiceType);ctor.setAccessible(true);engine=ctor.newInstance(dir,voice);
        int rate=field(engineType,"rate").getInt(engine);float[] samples=(float[])call(engineType,engine,"generate",new Class<?>[]{String.class,float.class},"Dit is een lokale voorleesproef.",1f);
        double energy=0;for(float x:samples){check(!Float.isNaN(x)&&!Float.isInfinite(x),"finite audio");energy+=(double)x*x;}
        check(rate==22050&&samples.length>rate/2&&energy/samples.length>.000001,"real non-silent Dutch speech");call(engineType,engine,"close",new Class<?>[]{});engine=null;
        ownership();
        playback();
        foreground();
        shortClips();
    }
    interface Until{boolean ok()throws Exception;}
    static void await(Until until,String message)throws Exception{for(int i=0;i<250;i++){if(until.ok())return;Thread.sleep(40);}throw new AssertionError(message);}
    void playback()throws Exception{
        Activity a=startActivitySync(new Intent().setClassName(target.getPackageName(),"nl.rene.tools.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        core=type("ReadAloud");jobType=type("ReadAloud$Job");sinkType=type("ReadAloud$Sink");Field current=field(core,"current");String owner=(String)field(a.getClass(),"speechOwner").get(a);
        java.util.concurrent.atomic.AtomicReference<String> error=new java.util.concurrent.atomic.AtomicReference<>();java.util.concurrent.atomic.AtomicReference<org.json.JSONObject> heard=new java.util.concurrent.atomic.AtomicReference<>();
        Object sink=Proxy.newProxyInstance(sinkType.getClassLoader(),new Class<?>[]{sinkType},(p,m,args)->{org.json.JSONObject e=(org.json.JSONObject)args[0];if(e.optString("state").equals("error"))error.set(e.optString("error"));if(e.optString("state").equals("playing")&&heard.get()==null)heard.set(e);return null;});
        try{
            call(core,null,"start",new Class<?>[]{a.getClass(),String.class,String.class,String.class,String.class,int.class,int.class,boolean.class,sinkType},a,"background-ci","reader","Dit is een langere lokale voorleesproef. De stem blijft voorlezen met het scherm uit. Daarna pauzeren we en gaan we weer verder.","",0,0,true,sink);
            await(()->{Object j=current.get(null);return error.get()!=null||j!=null&&field(jobType,"state").get(j).equals("playing");},"local playback starts");check(error.get()==null,"audio engine: "+error.get());Object j=current.get(null);
            Class<?> service=type("ReadAloudService");java.lang.ref.WeakReference<?> live=(java.lang.ref.WeakReference<?>)field(service,"live").get(null);Object instance=live.get();check(instance!=null,"foreground service active");
            android.media.session.MediaSession session=(android.media.session.MediaSession)field(service,"session").get(instance);android.media.session.MediaController controller=new android.media.session.MediaController(target,session.getSessionToken());
            org.json.JSONObject first=heard.get();check(first!=null&&first.getInt("offset")==0&&first.getInt("end")==40,"first sentence [0,40) is reported as audible: "+first);
            controller.getTransportControls().pause();await(()->(Boolean)field(jobType,"paused").get(j),"media pause");
            check(live.get()==instance&&field(service,"request").get(instance).equals("background-ci"),"service stays in the foreground while paused");
            call(core,null,"event",new Class<?>[]{jobType,String.class,String.class},j,"progress",null);check(field(jobType,"state").get(j).equals("paused"),"late progress preserves paused state");
            check(((AudioTrack)field(jobType,"track").get(j)).getPlayState()==AudioTrack.PLAYSTATE_PAUSED,"audio actually paused");android.os.PowerManager.WakeLock wake=(android.os.PowerManager.WakeLock)field(jobType,"wake").get(j);check(wake==null||!wake.isHeld(),"pause releases CPU lock");
            target.startService(new Intent().setClassName(target.getPackageName(),"nl.rene.tools.ReadAloudService").setAction("stop").putExtra("request","previous-request"));Thread.sleep(100);check(current.get(null)==j,"stale notification does not stop new session");
            runOnMainSync(()->a.moveTaskToBack(true));controller.getTransportControls().play();await(()->!(Boolean)field(jobType,"paused").get(j),"background resume");check(current.get(null)==j,"book survives background transition");
            Class<?>[] focusSig={jobType,int.class};
            call(core,null,"focusChanged",focusSig,j,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);check((Boolean)field(jobType,"paused").get(j)&&(Boolean)field(jobType,"focusPaused").get(j),"transient focus loss (call) pauses");
            call(core,null,"focusChanged",focusSig,j,AudioManager.AUDIOFOCUS_GAIN);check(!(Boolean)field(jobType,"paused").get(j)&&current.get(null)==j,"focus regained resumes the same session");
            call(core,null,"focusChanged",focusSig,j,AudioManager.AUDIOFOCUS_LOSS);check((Boolean)field(jobType,"paused").get(j)&&(Boolean)field(jobType,"focusLost").get(j),"permanent focus loss pauses");
            call(core,null,"focusChanged",focusSig,j,AudioManager.AUDIOFOCUS_GAIN);check((Boolean)field(jobType,"paused").get(j),"after a permanent loss only Rene resumes");
            controller.getTransportControls().play();await(()->!(Boolean)field(jobType,"paused").get(j),"resume requests focus again");
            controller.getTransportControls().stop();await(()->current.get(null)==null,"media stop");
        }finally{call(core,null,"stop",new Class<?>[]{String.class,String.class},owner,null);java.util.concurrent.ExecutorService worker=(java.util.concurrent.ExecutorService)field(core,"WORK").get(null);worker.submit(()->{}).get(15,TimeUnit.SECONDS);runOnMainSync(a::finish);}
    }
    void foreground()throws Exception{
        Activity a=startActivitySync(new Intent().setClassName(target.getPackageName(),"nl.rene.tools.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Field current=field(core,"current");String owner=(String)field(a.getClass(),"speechOwner").get(a);
        java.util.concurrent.atomic.AtomicReference<String> error=new java.util.concurrent.atomic.AtomicReference<>();
        Object sink=Proxy.newProxyInstance(sinkType.getClassLoader(),new Class<?>[]{sinkType},(p,m,args)->{org.json.JSONObject e=(org.json.JSONObject)args[0];if(e.optString("state").equals("error"))error.set(e.optString("error"));return null;});
        try{
            org.json.JSONObject settings=(org.json.JSONObject)call(models,null,"json",new Class<?>[]{Context.class},target);check(!settings.getBoolean("background"),"books default to foreground only");
            call(core,null,"start",new Class<?>[]{a.getClass(),String.class,String.class,String.class,String.class,int.class,int.class,boolean.class,sinkType},a,"foreground-ci","reader","Dit boek blijft alleen voorlezen zolang Tools open is. Als je de app verlaat stopt de stem direct en blijft je laatste leesplek bewaard.","",0,0,false,sink);
            await(()->{Object j=current.get(null);return error.get()!=null||j!=null&&field(jobType,"state").get(j).equals("playing");},"foreground playback starts");check(error.get()==null,"foreground engine: "+error.get());
            runOnMainSync(()->a.moveTaskToBack(true));await(()->current.get(null)==null,"leaving Tools stops the book by default");
        }finally{call(core,null,"stop",new Class<?>[]{String.class,String.class},owner,null);((java.util.concurrent.ExecutorService)field(core,"WORK").get(null)).submit(()->{}).get(15,TimeUnit.SECONDS);runOnMainSync(a::finish);}
    }
    /** Een kort antwoord ("Ja.") moet echt klinken en done geven (het handsfree gesprek wacht daarop), net als een langer stuk. */
    void shortClips()throws Exception{
        Activity a=startActivitySync(new Intent().setClassName(target.getPackageName(),"nl.rene.tools.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Field current=field(core,"current");String owner=(String)field(a.getClass(),"speechOwner").get(a);
        try{
            for(String[] c:new String[][]{{"short-ci","Ja.","5000"},{"okay-ci","Oké.","5000"},{"long-ci","Dit is een iets langere zin om te controleren dat alles klinkt. En dan nog een tweede zin.","30000"}}){
                java.util.concurrent.atomic.AtomicReference<String> last=new java.util.concurrent.atomic.AtomicReference<>("");java.util.concurrent.atomic.AtomicInteger heard=new java.util.concurrent.atomic.AtomicInteger();
                Object sink=Proxy.newProxyInstance(sinkType.getClassLoader(),new Class<?>[]{sinkType},(p,m,args)->{org.json.JSONObject e=(org.json.JSONObject)args[0];String st=e.optString("state");if(st.equals("playing"))heard.incrementAndGet();if(Arrays.asList("done","error","stopped").contains(st))last.set(st+":"+e.optString("error"));return null;});
                long t0=android.os.SystemClock.elapsedRealtime();
                call(core,null,"start",new Class<?>[]{a.getClass(),String.class,String.class,String.class,String.class,int.class,int.class,boolean.class,sinkType},a,c[0],"note",c[1],"",0,0,false,sink);
                long limit=Long.parseLong(c[2]);while(last.get().isEmpty()&&android.os.SystemClock.elapsedRealtime()-t0<limit)Thread.sleep(40);
                check(last.get().equals("done:")&&heard.get()>0&&current.get(null)==null,"\""+c[1]+"\" reaches done within "+limit+" ms: "+last.get()+" after "+(android.os.SystemClock.elapsedRealtime()-t0)+" ms");
            }
        }finally{call(core,null,"stop",new Class<?>[]{String.class,String.class},owner,null);((java.util.concurrent.ExecutorService)field(core,"WORK").get(null)).submit(()->{}).get(15,TimeUnit.SECONDS);runOnMainSync(a::finish);}
    }
    void ownership()throws Exception{
        Activity a=startActivitySync(new Intent().setClassName(target.getPackageName(),"nl.rene.tools.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        core=type("ReadAloud");jobType=type("ReadAloud$Job");sinkType=type("ReadAloud$Sink");Field current=field(core,"current");Object lock=field(core,"LOCK").get(null);String owner=(String)field(a.getClass(),"speechOwner").get(a);
        java.util.concurrent.atomic.AtomicInteger events=new java.util.concurrent.atomic.AtomicInteger();Object sink=Proxy.newProxyInstance(sinkType.getClassLoader(),new Class<?>[]{sinkType},(p,m,args)->{events.incrementAndGet();return null;});
        Constructor<?> ctor=jobType.getDeclaredConstructor(a.getClass(),String.class,String.class,String.class,String.class,int.class,int.class,boolean.class,sinkType);ctor.setAccessible(true);
        Object old=ctor.newInstance(a,"old-request","note","test","",0,0,false,sink),next=ctor.newInstance(a,"next-request","note","test","",0,0,false,sink);
        AudioTrack track=new AudioTrack(AudioManager.STREAM_MUSIC,22050,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,8192,AudioTrack.MODE_STREAM);track.play();track.pause();field(jobType,"track").set(old,track);current.set(null,old);
        CountDownLatch began=new CountDownLatch(1);Throwable[] error={null};Thread resume=new Thread(()->{began.countDown();try{call(core,null,"pause",new Class<?>[]{String.class,String.class,boolean.class},owner,"old-request",false);}catch(Throwable e){error[0]=e;}});
        try{
            synchronized(lock){resume.start();check(began.await(2,TimeUnit.SECONDS),"resume thread started");for(int n=0;n<100&&resume.getState()!=Thread.State.BLOCKED;n++)Thread.sleep(20);check(resume.getState()==Thread.State.BLOCKED,"resume checks ownership under the shared lock");call(core,null,"stop",new Class<?>[]{String.class,String.class},owner,"old-request");current.set(null,next);}
            resume.join(2000);check(!resume.isAlive()&&error[0]==null,"resume returned");check(track.getPlayState()!=AudioTrack.PLAYSTATE_PLAYING&&current.get(null)==next,"stale resume cannot play old audio or affect new job");
            int before=events.get();call(core,null,"event",new Class<?>[]{jobType,String.class,String.class},old,"progress",null);check(events.get()==before,"stale progress never reaches the reader");
            call(core,null,"stop",new Class<?>[]{String.class,String.class},"other-owner","next-request");check(current.get(null)==next,"another window cannot stop the new job");
        }finally{call(core,null,"stop",new Class<?>[]{String.class,String.class},owner,"next-request");track.release();runOnMainSync(a::finish);}
    }
}
