package nl.rene.tools;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import javax.crypto.SecretKey;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Versleutelde centrale repository. Gegevensactie, auditregel en geauthenticeerd controlepunt vormen één transactie. */
final class MasterStore {
    private MasterStore(){ }
    static final Object LOCK=new Object();
    static final String DIRECTORY="masterdata";
    static File file(Context c){return new File(new File(c.getFilesDir(),DIRECTORY),"master.db");}
    static boolean exists(Context c){return file(c).isFile();}
    static final class PreserveCorruption implements android.database.DatabaseErrorHandler {
        @Override public void onCorruption(SQLiteDatabase db){throw new android.database.sqlite.SQLiteDatabaseCorruptException();}
    }
    interface Commit { void run()throws Exception; }
    interface Work { JSONObject run(SQLiteDatabase db,SecretKey key,JSONObject head)throws Exception; }

    static JSONObject transaction(Context c,SecretKey key,MasterSession session,String token,Work work)throws Exception{
        return transaction(c,key,session,token,work,()->{});
    }
    static JSONObject transaction(Context c,SecretKey key,MasterSession session,String token,Work work,Commit commit)throws Exception{
        synchronized(LOCK){
            session.require(token);File f=file(c);File parent=f.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new MasterError(Taal.T("Stamgegevens opslaan lukt niet"));
            SQLiteDatabase db=SQLiteDatabase.openDatabase(f.getPath(),null,SQLiteDatabase.CREATE_IF_NECESSARY,new PreserveCorruption());
            try{
                db.execSQL("PRAGMA secure_delete=ON");db.beginTransaction();
                db.execSQL("CREATE TABLE IF NOT EXISTS records(id TEXT PRIMARY KEY,rev INTEGER NOT NULL,body BLOB NOT NULL)");
                db.execSQL("CREATE TABLE IF NOT EXISTS events(seq INTEGER PRIMARY KEY,body BLOB NOT NULL)");
                db.execSQL("CREATE TABLE IF NOT EXISTS control(id INTEGER PRIMARY KEY CHECK(id=1),body BLOB NOT NULL)");
                JSONObject head=verify(db,key);JSONObject result=work.run(db,key,head);verify(db,key);
                synchronized(session){session.touch(token);commit.run();db.setTransactionSuccessful();db.endTransaction();}
                return result;
            }finally{try{if(db.inTransaction())db.endTransaction();}finally{db.close();}}
        }
    }
    static JSONObject decode(byte[] bytes)throws Exception{return new JSONObject(new String(bytes,StandardCharsets.UTF_8));}
    static byte[] bytes(JSONObject o){return o.toString().getBytes(StandardCharsets.UTF_8);}
    static long number(SQLiteDatabase db,String sql){try(Cursor c=db.rawQuery(sql,null)){return c.moveToFirst()?c.getLong(0):0;}}
    static byte[] eventBlob(SQLiteDatabase db,long seq){try(Cursor c=db.rawQuery("SELECT body FROM events WHERE seq=?",new String[]{Long.toString(seq)})){return c.moveToFirst()?c.getBlob(0):null;}}
    static String digest(SQLiteDatabase db)throws Exception{
        java.security.MessageDigest hash=java.security.MessageDigest.getInstance("SHA-256");
        try(Cursor c=db.rawQuery("SELECT id,rev,body FROM records ORDER BY id",null)){
            while(c.moveToNext()){
                hash.update((c.getString(0)+"/"+c.getLong(1)+"/").getBytes(StandardCharsets.UTF_8));hash.update(java.security.MessageDigest.getInstance("SHA-256").digest(c.getBlob(2)));
            }
        }
        return MasterCipher.hash(hash.digest());
    }
    static String eventsDigest(SQLiteDatabase db)throws Exception{
        java.security.MessageDigest hash=java.security.MessageDigest.getInstance("SHA-256");
        try(Cursor c=db.rawQuery("SELECT seq,body FROM events ORDER BY seq",null)){while(c.moveToNext()){hash.update((c.getLong(0)+"/").getBytes(StandardCharsets.UTF_8));hash.update(java.security.MessageDigest.getInstance("SHA-256").digest(c.getBlob(1)));}}
        return MasterCipher.hash(hash.digest());
    }
    static String schemaDigest(SQLiteDatabase db)throws Exception{
        StringBuilder schema=new StringBuilder();
        try(Cursor c=db.rawQuery("SELECT type,name,sql FROM sqlite_master ORDER BY type,name",null)){while(c.moveToNext())schema.append(c.getString(0)).append('/').append(c.getString(1)).append('/').append(c.getString(2)).append('\n');}
        return MasterCipher.hash(schema.toString().getBytes(StandardCharsets.UTF_8));
    }
    static JSONObject verify(SQLiteDatabase db,SecretKey key)throws Exception{
        JSONObject head=null;
        try(Cursor c=db.rawQuery("SELECT body FROM control WHERE id=1",null)){if(c.moveToFirst())head=decode(MasterCipher.decrypt(key,"control",c.getBlob(0)));}
        long seq=number(db,"SELECT COALESCE(MAX(seq),0) FROM events"),count=number(db,"SELECT COUNT(*) FROM events");
        String state=digest(db);
        if(head==null){
            if(MasterKeys.has(MasterKeys.READY)||seq!=0||number(db,"SELECT COUNT(*) FROM records")!=0)throw integrity();
            head=new JSONObject().put("seq",0).put("hash","").put("state",state).put("events",eventsDigest(db)).put("schema",schemaDigest(db));
        }
        if(!head.getString("schema").equals(schemaDigest(db))||!head.getString("events").equals(eventsDigest(db))||head.getLong("seq")!=seq||count!=seq||!head.getString("state").equals(state)||seq>0&&!head.getString("hash").equals(MasterCipher.hash(eventBlob(db,seq))))throw integrity();
        return head;
    }
    static Exception integrity(){return new MasterError(Taal.T("Stamgegevens of logboek zijn beschadigd; er worden geen gegevens vervangen"));}
    static void append(SQLiteDatabase db,SecretKey key,JSONObject head,String action,String source,String role,JSONObject record,JSONArray fields)throws Exception{
        MasterSchema.source(source);long seq=head.getLong("seq")+1;
        JSONObject event=new JSONObject().put("seq",seq).put("t",System.currentTimeMillis()).put("action",action).put("source",source).put("role",role)
                .put("id",record==null?"":record.getString("id")).put("label",record==null?"":record.getString("label"))
                .put("revision",record==null?0:record.getLong("revision")).put("fields",fields==null?new JSONArray():fields).put("prev",head.getString("hash"));
        byte[] sealed=MasterCipher.encrypt(key,"event/"+seq,bytes(event));ContentValues values=new ContentValues();values.put("seq",seq);values.put("body",sealed);db.insertOrThrow("events",null,values);
        head.put("seq",seq).put("hash",MasterCipher.hash(sealed)).put("state",digest(db)).put("events",eventsDigest(db));
        ContentValues control=new ContentValues();control.put("id",1);control.put("body",MasterCipher.encrypt(key,"control",bytes(head)));db.insertWithOnConflict("control",null,control,SQLiteDatabase.CONFLICT_REPLACE);
    }
    static JSONObject record(SQLiteDatabase db,SecretKey key,String id)throws Exception{
        if(id==null||!id.matches("[a-f0-9-]{36}"))throw new MasterError(Taal.T("Ongeldige gegevensverwijzing"));
        try(Cursor c=db.rawQuery("SELECT rev,body FROM records WHERE id=?",new String[]{id})){
            if(!c.moveToFirst())throw new MasterError(Taal.T("Deze stamgegevens bestaan niet meer"));
            JSONObject o=decode(MasterCipher.decrypt(key,"record/"+id+"/"+c.getLong(0),c.getBlob(1)));
            if(!id.equals(o.getString("id"))||c.getLong(0)!=o.getLong("revision"))throw integrity();return o;
        }
    }
    static JSONArray names(JSONObject fields){JSONArray a=new JSONArray();java.util.Iterator<String> it=fields.keys();while(it.hasNext())a.put(it.next());return a;}
    static JSONObject summary(JSONObject r)throws Exception{return new JSONObject().put("id",r.getString("id")).put("label",r.getString("label")).put("type",r.getString("type")).put("revision",r.getLong("revision")).put("person",r.getJSONObject("fields").optString("persoon"));}

    static JSONObject unlock(Context c,SecretKey key,MasterSession session,String token)throws Exception{
        return transaction(c,key,session,token,(db,k,h)->{append(db,k,h,"unlock","masterdata","",null,null);return new JSONObject().put("ok",true);});
    }
    static JSONObject list(Context c,SecretKey key,MasterSession session,String token,String source,String role)throws Exception{
        return transaction(c,key,session,token,(db,k,h)->{
            MasterSchema.context(source,role);JSONArray result=new JSONArray();
            try(Cursor cursor=db.rawQuery("SELECT id FROM records ORDER BY id",null)){
                while(cursor.moveToNext()){
                    JSONObject r=record(db,k,cursor.getString(0));
                    if(!"masterdata".equals(source))try{if(MasterSchema.permitted(source,role,r.getString("type")).isEmpty())continue;}catch(Exception denied){continue;}
                    result.put(summary(r));
                }
            }
            append(db,k,h,"list",source,role,null,null);return new JSONObject().put("records",result);
        });
    }
    static JSONObject read(Context c,SecretKey key,MasterSession session,String token,String id,String source,String role)throws Exception{
        return transaction(c,key,session,token,(db,k,h)->{
            MasterSchema.context(source,role);JSONObject r=record(db,k,id),f="masterdata".equals(source)?r.getJSONObject("fields"):MasterSchema.projection(r,source,role);
            append(db,k,h,"view",source,role,r,names(f));JSONObject out=summary(r);out.put("fields",f);return new JSONObject().put("record",out);
        });
    }
    static JSONObject save(Context c,SecretKey key,MasterSession session,String token,JSONObject input)throws Exception{
        JSONObject clean=MasterSchema.clean(input);
        return transaction(c,key,session,token,(db,k,h)->{
            boolean fresh=input.optString("id").isEmpty();JSONObject old=fresh?null:record(db,k,input.getString("id"));
            if(old!=null&&(old.getLong("revision")!=input.getLong("revision")||!old.getString("type").equals(clean.getString("type"))))throw new MasterError(Taal.T("Deze gegevens zijn intussen gewijzigd; laad ze opnieuw"));
            if(fresh&&number(db,"SELECT COUNT(*) FROM records")>=2000)throw new MasterError(Taal.T("Maximaal 2000 stamgegevens"));
            String person=clean.getJSONObject("fields").optString("persoon");
            if(!person.isEmpty()&&!"persoon".equals(record(db,k,person).getString("type")))throw new MasterError(Taal.T("Kies een gekoppelde persoon"));
            String id=fresh?java.util.UUID.randomUUID().toString():old.getString("id");long rev=fresh?1:old.getLong("revision")+1;
            JSONObject r=new JSONObject(clean.toString()).put("id",id).put("revision",rev).put("created",fresh?System.currentTimeMillis():old.getLong("created")).put("updated",System.currentTimeMillis());
            ContentValues values=new ContentValues();values.put("id",id);values.put("rev",rev);values.put("body",MasterCipher.encrypt(k,"record/"+id+"/"+rev,bytes(r)));
            if(fresh)db.insertOrThrow("records",null,values);else db.update("records",values,"id=?",new String[]{id});
            JSONArray changed=new JSONArray();for(String field:MasterSchema.fields(r.getString("type")))if(old==null||!old.getJSONObject("fields").optString(field).equals(r.getJSONObject("fields").optString(field)))changed.put(field);
            if(old==null||!old.getString("label").equals(r.getString("label")))changed.put("label");
            append(db,k,h,fresh?"create":"update","masterdata","",r,changed);return new JSONObject().put("record",summary(r));
        });
    }
    static JSONObject delete(Context c,SecretKey key,MasterSession session,String token,String id,long revision)throws Exception{
        return transaction(c,key,session,token,(db,k,h)->{
            JSONObject r=record(db,k,id);if(r.getLong("revision")!=revision)throw new MasterError(Taal.T("Deze gegevens zijn intussen gewijzigd; laad ze opnieuw"));
            try(Cursor cur=db.rawQuery("SELECT id FROM records",null)){while(cur.moveToNext())if(id.equals(record(db,k,cur.getString(0)).getJSONObject("fields").optString("persoon")))throw new MasterError(Taal.T("Verwijder of ontkoppel eerst de gekoppelde identiteitsdocumenten"));}
            db.delete("records","id=?",new String[]{id});append(db,k,h,"delete","masterdata","",r,null);return new JSONObject().put("ok",true);
        });
    }
    static JSONObject use(Context c,SecretKey key,MasterSession session,String token,String id,long revision,String source,String role,JSONArray wanted,boolean consent)throws Exception{
        if("masterdata".equals(source))throw new MasterError(Taal.T("Kies een toepassingsonderdeel voor het overnemen"));
        return transaction(c,key,session,token,(db,k,h)->{
            JSONObject r=record(db,k,id);if(revision!=r.getLong("revision"))throw new MasterError(Taal.T("Deze gegevens zijn intussen gewijzigd; laad ze opnieuw"));
            JSONObject projected=MasterSchema.selected(r,source,role,wanted);
            for(int i=0;i<wanted.length();i++)if(MasterSchema.sensitive(wanted.getString(i))&&!consent)throw new MasterError(Taal.T("Bevestig het overnemen van identiteitsgegevens"));
            append(db,k,h,"use",source,role,r,names(projected));return new JSONObject().put("fields",projected).put("source",source).put("role",role).put("record",summary(r));
        });
    }
    static void consumerEvent(Context c,SecretKey key,MasterSession session,String token,String source,String action,Commit commit)throws Exception{
        if(!"formulieren".equals(source)&&!"idcopy".equals(source))throw new MasterError(Taal.T("Dit onderdeel heeft geen toegang tot stamgegevens"));
        transaction(c,key,session,token,(db,k,h)->{append(db,k,h,action,source,"",null,null);return new JSONObject();},commit);
    }
    static JSONObject audit(Context c,SecretKey key,MasterSession session,String token,long before)throws Exception{
        return transaction(c,key,session,token,(db,k,h)->{
            if(before<=0)append(db,k,h,"audit","masterdata","",null,null);
            long end=before<=0?h.getLong("seq"):Math.min(before-1,h.getLong("seq"));long start=Math.max(1,end-99);
            JSONArray result=new JSONArray();String previous=start<=1?"":MasterCipher.hash(eventBlob(db,start-1));long expected=start;
            try(Cursor cur=db.rawQuery("SELECT seq,body FROM events WHERE seq>=? AND seq<=? ORDER BY seq",new String[]{Long.toString(start),Long.toString(end)})){
                while(cur.moveToNext()){
                    long seq=cur.getLong(0);JSONObject e=decode(MasterCipher.decrypt(k,"event/"+seq,cur.getBlob(1)));
                    if(seq!=expected++||!e.getString("prev").equals(previous)||e.getLong("seq")!=seq)throw integrity();previous=MasterCipher.hash(cur.getBlob(1));result.put(e);
                }
            }
            return new JSONObject().put("events",result).put("before",start).put("more",start>1);
        });
    }
    static void reset(Context c)throws Exception{
        synchronized(LOCK){MasterSession.invalidateAll();MasterKeys.remove();File dir=file(c).getParentFile();File[] files=dir.listFiles();boolean ok=true;if(files!=null)for(File f:files)if(!f.delete()&&f.exists())ok=false;if(dir.exists()&&!dir.delete())ok=false;if(!ok)throw new MasterError(Taal.T("Wissen is niet afgerond; probeer opnieuw"));}
    }
}
