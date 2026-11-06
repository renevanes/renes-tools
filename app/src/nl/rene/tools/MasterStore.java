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
                try(Cursor setting=db.rawQuery("PRAGMA secure_delete=ON",null)){if(!setting.moveToFirst()||setting.getInt(0)!=1)throw new MasterError(Taal.T("Stamgegevens opslaan lukt niet"));}
                db.beginTransaction();
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
    static JSONArray records(SQLiteDatabase db,SecretKey key)throws Exception{JSONArray out=new JSONArray();try(Cursor c=db.rawQuery("SELECT id FROM records ORDER BY id",null)){while(c.moveToNext()){JSONObject r=record(db,key,c.getString(0));r.remove("images");out.put(r);}}return out;}
    static void person(SQLiteDatabase db,SecretKey key,String id)throws Exception{if(!id.isEmpty()&&!"persoon".equals(record(db,key,id).getString("type")))throw new MasterError(Taal.T("Kies een gekoppelde persoon"));}
    static JSONObject refreshContext(Context c,SecretKey key,MasterSession session,String token)throws Exception{
        JSONObject[] app={new JSONObject()};transaction(c,key,session,token,(db,k,h)->{
            app[0]=MasterIdentity.app(records(db,k));append(db,k,h,"app_context","masterdata","",null,null);return new JSONObject();
        },()->MasterAppContext.publish(c,app[0]));return app[0];
    }

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
            append(db,k,h,"view",source,role,r,names(f));JSONObject out=summary(r);out.put("fields",f);if("masterdata".equals(source)&&r.has("images")){JSONObject images=r.getJSONObject("images");out.put("images",new JSONObject().put("front",images.has("front")).put("back",images.has("back")));}return new JSONObject().put("record",out);
        });
    }
    static JSONObject save(Context c,SecretKey key,MasterSession session,String token,JSONObject input)throws Exception{
        JSONObject clean=MasterSchema.clean(input);
        return transaction(c,key,session,token,(db,k,h)->{
            boolean fresh=input.optString("id").isEmpty();JSONObject old=fresh?null:record(db,k,input.getString("id"));
            if(old!=null&&(old.getLong("revision")!=input.getLong("revision")||!old.getString("type").equals(clean.getString("type"))))throw new MasterError(Taal.T("Deze gegevens zijn intussen gewijzigd; laad ze opnieuw"));
            if(fresh&&number(db,"SELECT COUNT(*) FROM records")>=2000)throw new MasterError(Taal.T("Maximaal 2000 stamgegevens"));
            JSONObject fields=clean.getJSONObject("fields");
            person(db,k,fields.optString("persoon"));person(db,k,fields.optString("vanPersoon"));person(db,k,fields.optString("naarPersoon"));
            if("formulier".equals(clean.getString("type")))MasterFormProfile.validate(fields,ref->record(db,k,ref));
            if(clean.getString("type").equals("profiel")&&fresh){JSONArray all=records(db,k);for(int i=0;i<all.length();i++)if(all.getJSONObject(i).getString("type").equals("profiel"))throw new MasterError(Taal.T("Er mag maar één eigen app-profiel zijn"));}
            String id=fresh?java.util.UUID.randomUUID().toString():old.getString("id");long rev=fresh?1:old.getLong("revision")+1;
            JSONObject r=new JSONObject(clean.toString()).put("id",id).put("revision",rev).put("created",fresh?System.currentTimeMillis():old.getLong("created")).put("updated",System.currentTimeMillis());
            if(old!=null&&old.has("images"))r.put("images",old.getJSONObject("images"));
            ContentValues values=new ContentValues();values.put("id",id);values.put("rev",rev);values.put("body",MasterCipher.encrypt(k,"record/"+id+"/"+rev,bytes(r)));
            MasterAppContext.invalidate(c);
            if(fresh)db.insertOrThrow("records",null,values);else db.update("records",values,"id=?",new String[]{id});
            JSONArray changed=new JSONArray();for(String field:MasterSchema.fields(r.getString("type")))if(old==null||!old.getJSONObject("fields").optString(field).equals(r.getJSONObject("fields").optString(field)))changed.put(field);
            if(old==null||!old.getString("label").equals(r.getString("label")))changed.put("label");
            append(db,k,h,fresh?"create":"update","masterdata","",r,changed);return new JSONObject().put("record",summary(r));
        });
    }
    static JSONObject delete(Context c,SecretKey key,MasterSession session,String token,String id,long revision)throws Exception{
        return transaction(c,key,session,token,(db,k,h)->{
            JSONObject r=record(db,k,id);if(r.getLong("revision")!=revision)throw new MasterError(Taal.T("Deze gegevens zijn intussen gewijzigd; laad ze opnieuw"));
            try(Cursor cur=db.rawQuery("SELECT id FROM records",null)){while(cur.moveToNext()){JSONObject other=record(db,k,cur.getString(0)),f=other.getJSONObject("fields");if(id.equals(f.optString("persoon"))||id.equals(f.optString("vanPersoon"))||id.equals(f.optString("naarPersoon")))throw new MasterError(Taal.T("Verwijder of ontkoppel eerst gekoppelde documenten, relaties of het app-profiel"));
                if("formulier".equals(other.getString("type")))for(String field:MasterFormProfile.FIELDS)if(MasterFormProfile.reference(field)!=null&&id.equals(f.optString(field)))throw new MasterError(Taal.T("Dit stamgegeven wordt gebruikt in vaste formuliergegevens; pas die eerst aan"));}}
            MasterAppContext.invalidate(c);
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
    static JSONObject formProfile(Context c,SecretKey key,MasterSession session,String token,String id,long revision,boolean consent)throws Exception{
        return transaction(c,key,session,token,(db,k,h)->{
            JSONObject r=record(db,k,id);if(revision!=r.getLong("revision"))throw new MasterError(Taal.T("Deze gegevens zijn intussen gewijzigd; laad ze opnieuw"));
            JSONObject out=MasterFormProfile.resolve(r,ref->record(db,k,ref),records(db,k),consent,(rec,role,fields)->append(db,k,h,"use","formulieren",role,rec,fields));
            return out.put("record",summary(r));
        });
    }
    static void consumerEvent(Context c,SecretKey key,MasterSession session,String token,String source,String action,Commit commit)throws Exception{
        if(!"formulieren".equals(source)&&!"idcopy".equals(source))throw new MasterError(Taal.T("Dit onderdeel heeft geen toegang tot stamgegevens"));
        transaction(c,key,session,token,(db,k,h)->{append(db,k,h,action,source,"",null,null);return new JSONObject();},commit);
    }
    static JSONObject photo(Context c,SecretKey key,MasterSession session,String token,String id,long revision,String side,String value,boolean read)throws Exception{
        return photo(c,key,session,token,id,revision,side,value,read,()->{});
    }
    static JSONObject photo(Context c,SecretKey key,MasterSession session,String token,String id,long revision,String side,String value,boolean read,Commit commit)throws Exception{
        if(!side.equals("front")&&!side.equals("back"))throw new MasterError(Taal.T("Kies de voorzijde of achterzijde"));
        return transaction(c,key,session,token,(db,k,h)->{
            JSONObject r=record(db,k,id);if(!r.getString("type").equals("identiteit"))throw new MasterError(Taal.T("Kaartfoto’s horen bij een identiteitsdocument"));
            if(r.getLong("revision")!=revision)throw new MasterError(Taal.T("Deze gegevens zijn intussen gewijzigd; laad ze opnieuw"));
            JSONObject images=r.optJSONObject("images");if(images==null)images=new JSONObject();
            if(read){append(db,k,h,"photo_view","masterdata","",r,new JSONArray().put(side));return new JSONObject().put("image",images.optString(side)).put("id",id).put("revision",revision).put("side",side);}
            if(value==null)images.remove(side);else{if(!value.startsWith("data:image/jpeg;base64,")||value.length()>MasterPhotos.MAX_DATA)throw new MasterError(Taal.T("De kaartfoto is te groot"));images.put(side,value);}
            long next=revision+1;r.put("images",images).put("revision",next).put("updated",System.currentTimeMillis());
            ContentValues v=new ContentValues();v.put("rev",next);v.put("body",MasterCipher.encrypt(k,"record/"+id+"/"+next,bytes(r)));db.update("records",v,"id=?",new String[]{id});
            append(db,k,h,value==null?"photo_delete":"photo_add","masterdata","",r,new JSONArray().put(side));return new JSONObject().put("id",id).put("revision",next);
        },commit);
    }
    static JSONObject formRelation(Context c,SecretKey key,MasterSession session,String token,String child,String companion,String role)throws Exception{
        MasterSchema.context("formulieren",role);if(!role.startsWith("begeleider"))throw new MasterError(Taal.T("Deze gegevens passen niet bij dit onderdeel"));
        return transaction(c,key,session,token,(db,k,h)->{
            person(db,k,child);person(db,k,companion);java.util.Set<String> matches=new java.util.LinkedHashSet<>();JSONArray all=records(db,k);
            for(int i=0;i<all.length();i++){JSONObject r=all.getJSONObject(i);if(!r.getString("type").equals("relatie")||!r.getJSONObject("fields").optString("delen").equals("ja"))continue;String code=MasterIdentity.between(r,child,companion);if(!code.isEmpty()){matches.add(code);append(db,k,h,"relation_use","formulieren",role,r,new JSONArray().put("relatie"));}}
            return new JSONObject().put("relation",matches.size()==1?matches.iterator().next():"");
        });
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
        synchronized(LOCK){MasterSession.invalidateAll();MasterAppContext.reset(c);MasterKeys.remove();File dir=file(c).getParentFile();File[] files=dir.listFiles();boolean ok=true;if(files!=null)for(File f:files)if(!f.delete()&&f.exists())ok=false;if(dir.exists()&&!dir.delete())ok=false;if(!ok)throw new MasterError(Taal.T("Wissen is niet afgerond; probeer opnieuw"));}
    }
}
