package nl.rene.tools;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Hetzelfde begrensde schema vóór toepassen én bij consumptie van beschermde backupgegevens. */
final class PrivateData {
    private PrivateData() { }
    static final String FORMAT = "renes-tools-stamgegevens";
    static final int VERSION = 1, MAX_LINE = 1536 * 1024;
    static String line(Reader reader) throws IOException {
        StringBuilder out = new StringBuilder(); int c;
        while ((c = reader.read()) != -1 && c != '\n') { if (out.length() >= MAX_LINE) throw new IOException(Taal.T("Gegevens zijn te groot")); out.append((char)c); }
        return c == -1 && out.length() == 0 ? null : out.toString();
    }
    static void require(boolean valid) throws IOException { if (!valid) throw new IOException(Taal.T("De beschermde gegevens in deze backup zijn niet te lezen; er is niets vervangen")); }
    static void keys(JSONObject o, String... allowed) throws Exception {
        Set<String> known = new HashSet<>(Arrays.asList(allowed)); Iterator<String> it = o.keys(); while (it.hasNext()) require(known.contains(it.next()));
    }
    static boolean string(JSONObject o, String key, boolean required) { Object v = o.opt(key); return v instanceof String ? !required || !((String)v).isEmpty() : !required && !o.has(key); }
    static boolean number(Object o) { if (!(o instanceof Number)) return false; double n = ((Number)o).doubleValue(); return !Double.isNaN(n) && !Double.isInfinite(n) && n == Math.rint(n) && Math.abs(n) <= 9_007_199_254_740_991d; }
    static JSONArray array(JSONObject o, String key) throws Exception { if (!o.has(key)) return new JSONArray(); require(o.optJSONArray(key) != null); return o.getJSONArray(key); }
    static JSONObject bank(String text) throws Exception {
        // Nieuwe revisie en herstelbewijs moeten na overdracht ook binnen de versleutelde opslaglimiet passen.
        require(text.getBytes(StandardCharsets.UTF_8).length <= BankCrypt.MAX_PLAIN - 1024);
        JSONObject o = new JSONObject(text);
        require(o.optInt("v",1) == 1 && o.optJSONArray("tx") != null);
        keys(o,"v","rev","tx","imports","rules","cats","eigen","budget","rek","namen","_backupRestore");
        JSONArray tx = o.getJSONArray("tx"); require(tx.length() <= BankImport.MAX_ROWS);
        Set<String> ids = new HashSet<>();
        for (int i=0;i<tx.length();i++) {
            JSONObject t = tx.getJSONObject(i);
            keys(t,"id","acc","d","a","cp","ci","ds","ty","src","in","b","c","n");
            require(string(t,"id",true) && ids.add(t.getString("id")) && string(t,"acc",true) && string(t,"d",true) && MasterSchema.validDate(t.getString("d")) && number(t.opt("a")));
            for(String key:new String[]{"cp","ci","ds","ty","src","c","n"})require(string(t,key,false));
            for(String key:new String[]{"in","b"})if(t.has(key))require(number(t.opt(key)));
        }
        JSONArray own = array(o,"eigen"); Set<String> accounts = new HashSet<>();
        for(int i=0;i<own.length();i++){JSONObject e=own.getJSONObject(i);keys(e,"iban","naam","soort");require(string(e,"iban",true)&&accounts.add(e.getString("iban"))&&string(e,"naam",false)&&string(e,"soort",false));if(e.has("soort"))require(Arrays.asList("prive","zakelijk","").contains(e.getString("soort")));}
        JSONArray rules=array(o,"rules");for(int i=0;i<rules.length();i++){JSONObject r=rules.getJSONObject(i);keys(r,"p","cat","t","s");require(string(r,"p",true)&&string(r,"cat",true)&&string(r,"t",false)&&string(r,"s",false));if(r.has("t"))require(Arrays.asList("+","-","").contains(r.getString("t")));if(r.has("s"))require(Arrays.asList("prive","zakelijk","").contains(r.getString("s")));}
        JSONArray cats=array(o,"cats");for(int i=0;i<cats.length();i++){JSONObject c=cats.getJSONObject(i);keys(c,"naam","soort");require(string(c,"naam",true)&&string(c,"soort",true)&&Arrays.asList("uit","in","sparen","neutraal","tussen").contains(c.getString("soort")));}
        JSONArray imports=array(o,"imports");for(int i=0;i<imports.length();i++){JSONObject item=imports.getJSONObject(i);require(number(item.opt("t")));for(String key:new String[]{"bank","format","van","tot"})require(string(item,key,false));for(String key:new String[]{"nieuw","dubbel","overgeslagen"})if(item.has(key))require(number(item.opt(key)));JSONArray acc=array(item,"rekeningen");for(int j=0;j<acc.length();j++)require(acc.get(j) instanceof String);}
        for(String map:new String[]{"budget","rek","namen"})if(o.has(map)){
            require(o.optJSONObject(map)!=null);JSONObject values=o.getJSONObject(map);Iterator<String> it=values.keys();
            while(it.hasNext()){String key=it.next();Object value=values.get(key);if("budget".equals(map))require(number(value));else if("namen".equals(map))require(value instanceof String);else{require(value instanceof JSONObject);JSONObject account=(JSONObject)value;keys(account,"naam","soort");require(string(account,"naam",false)&&string(account,"soort",false));if(account.has("soort"))require(Arrays.asList("prive","zakelijk","").contains(account.getString("soort")));}}
        }
        return o;
    }
    static JSONObject record(JSONObject r) throws Exception {
        require(r.toString().getBytes(StandardCharsets.UTF_8).length <= MasterCipher.MAX_PLAIN);
        keys(r,"id","revision","created","updated","type","label","fields","images");
        require(string(r,"type",true)&&string(r,"label",true)&&r.optJSONObject("fields")!=null);
        require(r.optString("id").matches("[a-f0-9-]{36}") && number(r.opt("revision")) && r.getLong("revision")>=1);
        for(String key:new String[]{"created","updated"})require(number(r.opt(key)));
        MasterSchema.clean(r);
        if(r.has("images")) {
            require(r.optJSONObject("images")!=null && "identiteit".equals(r.getString("type")));JSONObject images=r.getJSONObject("images");Iterator<String> it=images.keys();
            while(it.hasNext()){String side=it.next();Object value=images.get(side);require(("front".equals(side)||"back".equals(side))&&value instanceof String);String data=(String)value;require(data.startsWith("data:image/jpeg;base64,")&&data.length()<=MasterPhotos.MAX_DATA);}
        }
        return r;
    }
    static void header(JSONObject o) throws Exception { require(FORMAT.equals(o.optString("format")) && o.optInt("version")==VERSION); }
    static final class MasterValidator {
        final Map<String,JSONObject> records = new LinkedHashMap<>(); long events; boolean end;
        void accept(JSONObject o) throws Exception {
            require(!end);
            if(o.has("record")) {
                keys(o,"record");JSONObject r=record(o.getJSONObject("record"));String id=r.getString("id");require(events==0&&!records.containsKey(id)&&records.size()<2000);
                records.put(id,new JSONObject().put("id",id).put("type",r.getString("type")).put("fields",r.getJSONObject("fields")));
            } else if(o.has("event")) {
                keys(o,"event");JSONObject e=o.getJSONObject("event");require(e.toString().getBytes(StandardCharsets.UTF_8).length<=MasterCipher.MAX_PLAIN);
                keys(e,"seq","t","action","source","role","id","label","revision","fields","prev");
                require(number(e.opt("seq"))&&e.getLong("seq")==++events&&e.getString("prev").matches(events==1?"":"[a-f0-9]{64}"));
                require(number(e.opt("t"))&&number(e.opt("revision"))&&string(e,"action",true)&&string(e,"role",false)&&string(e,"id",false)&&string(e,"label",false));
                MasterSchema.source(e.getString("source"));JSONArray fields=e.getJSONArray("fields");for(int i=0;i<fields.length();i++)require(fields.get(i) instanceof String);
            } else { keys(o,"end","records","events");require(o.optBoolean("end")&&o.getLong("records")==records.size()&&o.getLong("events")==events);end=true; }
        }
        void finish() throws Exception {
            require(end);int profiles=0;
            for(JSONObject r:records.values()){
                JSONObject fields=r.getJSONObject("fields");
                for(String key:new String[]{"persoon","vanPersoon","naarPersoon"}){String id=fields.optString(key);if(!id.isEmpty())require(records.containsKey(id)&&"persoon".equals(records.get(id).getString("type")));}
                if("profiel".equals(r.getString("type")))require(++profiles<=1);
                if("formulier".equals(r.getString("type")))MasterFormProfile.validate(fields,id->{JSONObject value=records.get(id);require(value!=null);return value;});
            }
        }
    }
    static void validate(String id, java.io.InputStream input) throws Exception {
        if("bank".equals(id)){byte[] data=AppBackup.small(input,new byte[64*1024],BankCrypt.MAX_PLAIN);try{bank(new String(data,StandardCharsets.UTF_8));}finally{Arrays.fill(data,(byte)0);}return;}
        require("masterdata".equals(id));Reader reader=new java.io.BufferedReader(new java.io.InputStreamReader(input,StandardCharsets.UTF_8));String row=line(reader);require(row!=null);header(new JSONObject(row));
        MasterValidator validator=new MasterValidator();while((row=line(reader))!=null)validator.accept(new JSONObject(row));validator.finish();
    }
}
