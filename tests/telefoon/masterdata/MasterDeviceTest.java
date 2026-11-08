package nl.rene.tools.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import org.json.JSONObject;
import org.json.JSONArray;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

/** CI-instrumentatie tegen echte Android SQLite; geen ingang in de productie-app. */
public final class MasterDeviceTest extends Instrumentation {
    private Context target,context;
    private Class<?> store,sessionType,keys;
    private Object session;
    private SecretKey key;
    private String token;
    private File root,dbFile;
    static final class Isolated extends ContextWrapper {
        private final File files;
        Isolated(Context base,File files){super(base);this.files=files;}
        @Override public File getFilesDir(){return files;}
        @Override public File getNoBackupFilesDir(){File dir=new File(files,"nobackup");dir.mkdirs();return dir;}
    }
    interface Checked {void run()throws Exception;}
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    static void rejected(Checked action)throws Exception{boolean denied=false;try{action.run();}catch(Exception expected){denied=true;}check(denied,"action must be refused");}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle r=new Bundle();try{target=getTargetContext();runChecks();r.putString("masterdata","ok");finish(Activity.RESULT_OK,r);}catch(Throwable e){r.putString("masterdata","failed: "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,r);}finally{if(root!=null)erase(root);}}
    static void erase(File f){File[] children=f.listFiles();if(children!=null)for(File child:children)erase(child);f.delete();}
    Class<?> type(String name)throws Exception{return target.getClassLoader().loadClass("nl.rene.tools."+name);}
    Object call(Class<?> owner,Object instance,String name,Class<?>[] signature,Object... args)throws Exception{
        Method method=owner.getDeclaredMethod(name,signature);method.setAccessible(true);try{return method.invoke(instance,args);}catch(InvocationTargetException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new Exception(cause);}
    }
    JSONObject operation(String name,Class<?>[] extra,Object... args)throws Exception{
        Class<?>[] signature=new Class<?>[extra.length+4];signature[0]=Context.class;signature[1]=SecretKey.class;signature[2]=sessionType;signature[3]=String.class;System.arraycopy(extra,0,signature,4,extra.length);
        Object[] values=new Object[args.length+4];values[0]=context;values[1]=key;values[2]=session;values[3]=token;System.arraycopy(args,0,values,4,args.length);
        return (JSONObject)call(store,null,name,signature,values);
    }
    JSONObject save(JSONObject r)throws Exception{return operation("save",new Class<?>[]{JSONObject.class},r);}
    static byte[] read(File f)throws Exception{try(FileInputStream in=new FileInputStream(f);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
    static void write(File f,byte[] data)throws Exception{try(FileOutputStream out=new FileOutputStream(f)){out.write(data);}}
    void privacyFrames()throws Exception {
        Activity activity=startActivitySync(new android.content.Intent().setClassName(target.getPackageName(),"nl.rene.tools.MainActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        Throwable[] problem={null};runOnMainSync(()->{try{
            Class<?> main=activity.getClass();java.lang.reflect.Field counter=main.getDeclaredField("masterSecurityGeneration"),screen=main.getDeclaredField("masterScreen"),wanted=main.getDeclaredField("masterWanted"),view=main.getDeclaredField("masterView");
            counter.setAccessible(true);screen.setAccessible(true);wanted.setAccessible(true);view.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicLong)counter.get(activity)).set(8);screen.setBoolean(activity,true);wanted.setBoolean(activity,false);view.set(activity,"");activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            Class<?> drawn=type("MainActivity$MasterDrawn");java.lang.reflect.Constructor<?> ctor=drawn.getDeclaredConstructor(main,String.class,long.class,boolean.class);ctor.setAccessible(true);
            Method complete=drawn.getDeclaredMethod("onComplete",long.class);complete.setAccessible(true);
            complete.invoke(ctor.newInstance(activity,"",7L,false),0L);check(screen.getBoolean(activity),"old locked frame cannot release new protection");
            complete.invoke(ctor.newInstance(activity,"",8L,false),0L);check(!screen.getBoolean(activity),"matching locked frame releases protection after leaving central screen");
            screen.setBoolean(activity,true);wanted.setBoolean(activity,true);view.set(activity,"new-private-view");activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            complete.invoke(ctor.newInstance(activity,"old-private-view",8L,false),0L);check(screen.getBoolean(activity),"wrong view cannot release protection");
        }catch(Throwable e){problem[0]=e;}finally{activity.finish();}});if(problem[0]!=null)throw new Exception(problem[0]);
    }
    void photoAndProfileChecks(JSONObject identity,String docId,String me)throws Exception {
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(1800,1000,android.graphics.Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.BLUE);
        java.io.ByteArrayOutputStream original=new java.io.ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,original);bitmap.recycle();
        String image=(String)call(type("MasterPhotos"),null,"normalize",new Class<?>[]{java.io.InputStream.class},new java.io.ByteArrayInputStream(original.toByteArray()));
        byte[] jpeg=android.util.Base64.decode(image.substring(image.indexOf(',')+1),android.util.Base64.DEFAULT);check(jpeg.length<=256000,"bounded reencoded image");
        android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,bounds);check(bounds.outWidth<=1600&&bounds.outHeight<=1600,"bounded image dimensions");
        Class<?>[] sig={String.class,long.class,String.class,String.class,boolean.class};
        operation("photo",sig,docId,1L,"front",image,false);operation("photo",sig,docId,2L,"back",image,false);
        JSONObject read=operation("read",new Class<?>[]{String.class,String.class,String.class},docId,"masterdata","").getJSONObject("record");check(read.getJSONObject("images").getBoolean("front")&&read.getJSONObject("images").getBoolean("back")&&!read.toString().contains("base64"),"editor receives presence flags only");
        JSONObject consumer=operation("read",new Class<?>[]{String.class,String.class,String.class},docId,"formulieren","kind").getJSONObject("record");check(!consumer.has("images")&&!consumer.toString().contains("base64"),"consumer receives no image data");
        identity.put("revision",3).put("images",new JSONObject().put("front","INJECTED"));identity.getJSONObject("fields").put("afgegevenDoor","Testgemeente");save(identity);
        check(operation("photo",sig,docId,4L,"front",null,true).getString("image").equals(image),"text saves preserve native photos and ignore incoming image fields");
        rejected(()->operation("photo",sig,docId,1L,"front",null,false));
        Class<?> commit=type("MasterStore$Commit");Object cancel=java.lang.reflect.Proxy.newProxyInstance(commit.getClassLoader(),new Class<?>[]{commit},(proxy,m,args)->{throw new Exception("cancelled");});
        rejected(()->operation("photo",new Class<?>[]{String.class,long.class,String.class,String.class,boolean.class,commit},docId,4L,"front",null,false,cancel));check(operation("photo",sig,docId,4L,"front",null,true).getString("image").equals(image),"cancelled import transaction rolls back");
        operation("photo",sig,docId,4L,"back",null,false);check(operation("photo",sig,docId,5L,"back",null,true).getString("image").isEmpty(),"delete side");
        check(!new String(read(dbFile),"ISO-8859-1").contains(image.substring(0,80)),"photos encrypted at rest");
        JSONObject child=new JSONObject().put("type","persoon").put("label","Testkind").put("fields",new JSONObject().put("naam","Testkind"));String kid=save(child).getJSONObject("record").getString("id");
        JSONObject relation=new JSONObject().put("type","relatie").put("label","Testrelatie").put("fields",new JSONObject().put("vanPersoon",me).put("naarPersoon",kid).put("relatie","kind").put("delen","ja"));save(relation);
        check(operation("formRelation",new Class<?>[]{String.class,String.class,String.class},kid,me,"begeleider1").getString("relation").equals("ouder"),"form uses explicitly stored reverse relationship");
        JSONObject profile=new JSONObject().put("type","profiel").put("label","Testprofiel").put("fields",new JSONObject().put("persoon",me).put("delen","ja"));JSONObject saved=save(profile).getJSONObject("record");profile.put("id",saved.getString("id")).put("revision",1);
        operation("refreshContext",new Class<?>[]{});Class<?> cache=type("MasterAppContext");JSONObject shared=(JSONObject)call(cache,null,"read",new Class<?>[]{Context.class},context);check(shared.has("self")&&shared.getJSONArray("people").length()==1&&!shared.toString().contains("123456782")&&!shared.toString().contains("DOC123456"),"limited profile context");
        File cacheFile=new File(context.getNoBackupFilesDir(),"app-identiteit.bin");check(cacheFile.isFile()&&!new String(read(cacheFile),"ISO-8859-1").contains("SecretTestPerson"),"shared context is encrypted outside backups");
        rejected(()->save(new JSONObject().put("type","profiel").put("label","Duplicaat").put("fields",profile.getJSONObject("fields"))));
        profile.getJSONObject("fields").put("delen","nee");save(profile);check(!((JSONObject)call(cache,null,"read",new Class<?>[]{Context.class},context)).has("self"),"sharing revoked before save completes");
        operation("refreshContext",new Class<?>[]{});check(!cacheFile.exists(),"revoked cache remains absent after refresh");
        call(cache,null,"reset",new Class<?>[]{Context.class},context);
    }
    void backupReceiptsAndSecrets()throws Exception {
        Class<?> bank=type("Bank"),protectedRestore=type("ProtectedRestore"),appBackup=type("AppBackup"),secure=type("Secure");
        File pending=new File(root,"bank/backup-herstel.rtp");pending.getParentFile().mkdirs();
        java.security.KeyPairGenerator generator=java.security.KeyPairGenerator.getInstance("RSA");generator.initialize(2048);java.security.KeyPair pair=generator.generateKeyPair();
        String receipt="renes_herstel_"+java.util.UUID.randomUUID();JSONObject data=new JSONObject().put("v",1).put("tx",new JSONArray());
        try(java.io.FileOutputStream raw=new java.io.FileOutputStream(pending)){
            call(protectedRestore,null,"envelope",new Class<?>[]{java.io.OutputStream.class,java.io.InputStream.class,String.class,String.class,java.security.PublicKey.class},raw,new java.io.ByteArrayInputStream(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),"Appgegevens/geheim/hoog-risico/bank",receipt,pair.getPublic());
        }
        call(bank,null,"open",new Class<?>[]{});
        try{
            call(bank,null,"restoreBackup",new Class<?>[]{Context.class,JSONObject.class},context,data);
            String committed=(String)call(bank,null,"load",new Class<?>[]{Context.class},context);
            check(receipt.equals(new JSONObject(committed).getString("_backupRestore")),"Bank committed an encrypted receipt before pending cleanup");
            call(bank,null,"restoreBackup",new Class<?>[]{Context.class,JSONObject.class},context,new JSONObject(data.toString()).put("budget",new JSONObject().put("mustNotReplace",1)));
            check(committed.equals((String)call(bank,null,"load",new Class<?>[]{Context.class},context)),"Bank resumes after commit without replacing data twice");
        }finally{call(bank,null,"wipe",new Class<?>[]{Context.class},context);}
        Class<?> secrets=type("ToolSecrets");
        call(secrets,null,"set",new Class<?>[]{Context.class,String.class,String.class,String.class},context,"notes","transferTest","PORTABLE_TEST_TOKEN");
        JSONObject portable=new JSONObject();JSONObject prefs=(JSONObject)call(appBackup,null,"prefsSnapshot",new Class<?>[]{Context.class,JSONObject.class,type("AppData$Manifest")},context,portable,manifest());
        check("PORTABLE_TEST_TOKEN".equals(portable.getJSONObject("prefs").getJSONObject("notes_geheim").getString("transferTest")),"tool secrets have a portable encrypted-archive representation");
        java.security.KeyStore keyStore=java.security.KeyStore.getInstance("AndroidKeyStore");keyStore.load(null);keyStore.deleteEntry("rt-backup");
        JSONObject ready=(JSONObject)call(appBackup,null,"readyPrefs",new Class<?>[]{byte[].class,byte[].class},prefs.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),portable.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String sealed=ready.getJSONObject("notes_geheim").getJSONObject("transferTest").getString("v");
        context.getSharedPreferences("notes_geheim",Context.MODE_PRIVATE).edit().putString("transferTest",sealed).commit();
        check("PORTABLE_TEST_TOKEN".equals(call(secrets,null,"get",new Class<?>[]{Context.class,String.class,String.class},context,"notes","transferTest")),"secret readable under new installation key");
        context.getSharedPreferences("notes_geheim",Context.MODE_PRIVATE).edit().remove("transferTest").commit();
    }
    Object manifest()throws Exception{java.lang.reflect.Constructor<?> ctor=type("AppData$Manifest").getDeclaredConstructor();ctor.setAccessible(true);return ctor.newInstance();}
    void runChecks()throws Exception {
        store=type("MasterStore");sessionType=type("MasterSession");keys=type("MasterKeys");
        // Stop op een installatie met echte centrale gegevens; uitsluitend geisoleerde testopslag.
        check(!(Boolean)call(keys,null,"has",new Class<?>[]{String.class},"renes_masterdata_ready_v1"),"requires fresh CI installation");
        root=new File(target.getCacheDir(),"masterdata-ci-"+java.util.UUID.randomUUID());check(root.mkdirs(),"isolated files");context=new Isolated(target,root);
        java.lang.reflect.Constructor<?> ctor=sessionType.getDeclaredConstructor();ctor.setAccessible(true);session=ctor.newInstance();token=(String)call(sessionType,session,"open",new Class<?>[]{});
        KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);key=generator.generateKey();
        operation("unlock",new Class<?>[]{});dbFile=new File(root,"masterdata/master.db");
        JSONObject person=new JSONObject().put("type","persoon").put("label","CENTRAL_PRIVATE_NAME_856201").put("fields",new JSONObject().put("naam","SecretTestPerson").put("bsn","123456782"));
        JSONObject saved=save(person).getJSONObject("record");String id=saved.getString("id");person.put("id",id).put("revision",1);
        JSONObject result=operation("read",new Class<?>[]{String.class,String.class,String.class},id,"masterdata","");check("123456782".equals(result.getJSONObject("record").getJSONObject("fields").getString("bsn")),"encrypted roundtrip");
        String fileText=new String(read(dbFile),java.nio.charset.StandardCharsets.ISO_8859_1);check(!fileText.contains("SecretTestPerson")&&!fileText.contains("123456782")&&!fileText.contains("CENTRAL_PRIVATE_NAME"),"no plaintext record or audit values in database");
        JSONObject recipient=operation("read",new Class<?>[]{String.class,String.class,String.class},id,"idcopy","ontvanger");check(recipient.getJSONObject("record").getJSONObject("fields").length()==1,"recipient projection only");
        rejected(()->operation("use",new Class<?>[]{String.class,long.class,String.class,String.class,JSONArray.class,boolean.class},id,1L,"idcopy","ontvanger",new JSONArray().put("bsn"),true));
        rejected(()->operation("use",new Class<?>[]{String.class,long.class,String.class,String.class,JSONArray.class,boolean.class},id,1L,"formulieren","kind",new JSONArray().put("bsn"),false));
        operation("use",new Class<?>[]{String.class,long.class,String.class,String.class,JSONArray.class,boolean.class},id,1L,"formulieren","kind",new JSONArray().put("bsn"),true);
        save(person);rejected(()->save(person)); // stale revision cannot overwrite or append an audit event
        JSONObject identity=new JSONObject().put("type","identiteit").put("label","Test document").put("fields",new JSONObject().put("persoon",id).put("document","DOC123456").put("geldig","2030-06-30"));JSONObject docSaved=save(identity).getJSONObject("record");String docId=docSaved.getString("id");identity.put("id",docId).put("revision",1);
        photoAndProfileChecks(identity,docId,id);
        // Beschermde backup: snapshot bevat alle gegevens; na overdracht beschermt een andere sleutel dezelfde rijen en auditregels.
        java.io.ByteArrayOutputStream snapshot=new java.io.ByteArrayOutputStream();
        call(type("PrivateBackup"),null,"writeMaster",new Class<?>[]{Context.class,SecretKey.class,java.io.OutputStream.class},context,key,snapshot);
        byte[] transferred=snapshot.toByteArray();
        call(type("PrivateData"),null,"validate",new Class<?>[]{String.class,java.io.InputStream.class},"masterdata",new java.io.ByteArrayInputStream(transferred));
        KeyGenerator nextGenerator=KeyGenerator.getInstance("AES");nextGenerator.init(256);SecretKey nextKey=nextGenerator.generateKey();
        File restored=new File(root,"restored-master.db");
        call(type("PrivateBackup"),null,"importMaster",new Class<?>[]{java.io.Reader.class,File.class,SecretKey.class,String.class},new java.io.StringReader(new String(transferred,java.nio.charset.StandardCharsets.UTF_8)),restored,nextKey,"restore-receipt-test");
        check((Boolean)call(type("PrivateBackup"),null,"masterReceipt",new Class<?>[]{File.class,SecretKey.class,String.class},restored,nextKey,"restore-receipt-test"),"restart after commit recognises receipt");
        check(!(Boolean)call(type("PrivateBackup"),null,"masterReceipt",new Class<?>[]{File.class,SecretKey.class,String.class},restored,nextKey,"another-receipt"),"different pending restore cannot replace committed data");
        SQLiteDatabase restoredDb=SQLiteDatabase.openDatabase(restored.getPath(),null,SQLiteDatabase.OPEN_READONLY);
        try{
            JSONObject restoredHead=(JSONObject)call(store,null,"verify",new Class<?>[]{SQLiteDatabase.class,SecretKey.class},restoredDb,nextKey);
            check("restore-receipt-test".equals(restoredHead.getString("restore")),"durable encrypted restore receipt");
            JSONObject restoredRecord=(JSONObject)call(store,null,"record",new Class<?>[]{SQLiteDatabase.class,SecretKey.class,String.class},restoredDb,nextKey,id);
            check(restoredRecord.getJSONObject("fields").getString("bsn").equals("123456782"),"all fields restored under new key");
            rejected(()->call(store,null,"verify",new Class<?>[]{SQLiteDatabase.class,SecretKey.class},restoredDb,key));
        }finally{restoredDb.close();restored.delete();}
        check(!new String(read(dbFile),"ISO-8859-1").contains("restore-receipt-test"),"export never changes original data");
        File invalidTarget=new File(root,"invalid-restore.db");String incompleteSnapshot=new String(transferred,java.nio.charset.StandardCharsets.UTF_8);incompleteSnapshot=incompleteSnapshot.substring(0,incompleteSnapshot.lastIndexOf("{\"end\""));final String partial=incompleteSnapshot;
        rejected(()->call(type("PrivateBackup"),null,"importMaster",new Class<?>[]{java.io.Reader.class,File.class,SecretKey.class,String.class},new java.io.StringReader(partial),invalidTarget,nextKey,"incomplete"));invalidTarget.delete();
        check(operation("read",new Class<?>[]{String.class,String.class,String.class},id,"masterdata","").getJSONObject("record").getJSONObject("fields").getString("bsn").equals("123456782"),"failed restore cannot change original source");
        Arrays.fill(transferred,(byte)0);

        rejected(()->operation("delete",new Class<?>[]{String.class,long.class},id,2L));
        JSONObject audit=operation("audit",new Class<?>[]{long.class},0L);check(!audit.getJSONArray("events").toString().contains("123456782"),"audit has field names without field values");
        // Transaction rollback when the session is withdrawn during work.
        Class<?> work=type("MasterStore$Work"),commit=type("MasterStore$Commit");boolean[] published={false};
        Object worker=java.lang.reflect.Proxy.newProxyInstance(work.getClassLoader(),new Class<?>[]{work},(proxy,m,args)->{call(sessionType,session,"close",new Class<?>[]{});return new JSONObject();});
        Object publisher=java.lang.reflect.Proxy.newProxyInstance(commit.getClassLoader(),new Class<?>[]{commit},(proxy,m,args)->{published[0]=true;return null;});
        rejected(()->call(store,null,"transaction",new Class<?>[]{Context.class,SecretKey.class,sessionType,String.class,work,commit},context,key,session,token,worker,publisher));check(!published[0],"revoked session never publishes");token=(String)call(sessionType,session,"open",new Class<?>[]{});
        // Passing the deadline during an already authorised commit must not report rollback afterwards.
        Object noWork=java.lang.reflect.Proxy.newProxyInstance(work.getClassLoader(),new Class<?>[]{work},(proxy,m,args)->new JSONObject());
        Object expires=java.lang.reflect.Proxy.newProxyInstance(commit.getClassLoader(),new Class<?>[]{commit},(proxy,m,args)->{java.lang.reflect.Field limit=sessionType.getDeclaredField("limit");limit.setAccessible(true);limit.setLong(session,android.os.SystemClock.elapsedRealtime()-1);return null;});
        call(store,null,"transaction",new Class<?>[]{Context.class,SecretKey.class,sessionType,String.class,work,commit},context,key,session,token,noWork,expires);
        check(!(Boolean)call(sessionType,session,"isOpen",new Class<?>[]{}),"deadline passed during completed commit");token=(String)call(sessionType,session,"open",new Class<?>[]{});
        byte[] good=read(dbFile);
        for(String sql:new String[]{"UPDATE records SET body=X'00010203'", "DELETE FROM events WHERE seq=1", "CREATE TABLE surprise(secret TEXT)","DELETE FROM records"}){
            SQLiteDatabase db=SQLiteDatabase.openDatabase(dbFile.getPath(),null,0);try{db.execSQL(sql);}finally{db.close();}
            rejected(()->operation("list",new Class<?>[]{String.class,String.class},"masterdata",""));write(dbFile,good);
        }
        byte[] corrupt="Invalid SQLite database; preserve original bytes".getBytes("UTF-8");write(dbFile,corrupt);
        rejected(()->operation("list",new Class<?>[]{String.class,String.class},"masterdata",""));check(Arrays.equals(corrupt,read(dbFile)),"corruption never deletes/replaces original database");
        privacyFrames();
        backupReceiptsAndSecrets();
        // An emulator without a configured phone lock must refuse an auth-bound key.
        android.app.KeyguardManager kg=(android.app.KeyguardManager)target.getSystemService(Context.KEYGUARD_SERVICE);
        if(!kg.isDeviceSecure())rejected(()->call(keys,null,"authenticated",new Class<?>[]{Context.class},context));
    }
}
