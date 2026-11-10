package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * De index van de documentenkluis, zonder Android: herkomst en koppeling van een document (meta), een regel uit een backup
 * schoonmaken (clean) en welke versleutelde bestanden bij een document horen (blobs: het document en zijn oudere versies).
 */
final class KluisIndex {
    private KluisIndex() { }
    static final int MAX_VERSIONS = 20;
    static final String[] SOURCES={"pdf","bewerkt","idkopie","formulier"};
    static final String[] LINK_KEYS={"kind","ref","soort","ontvanger","doel","datum"};

    /** Alleen bekende velden, korte tekst zonder stuurtekens: {source, link:{kind, ref, soort, ontvanger, doel, datum}}. */
    static JSONObject meta(JSONObject in) throws Exception {
        JSONObject out=new JSONObject();if(in==null)return out;
        String source=in.optString("source","");
        if(java.util.Arrays.asList(SOURCES).contains(source))out.put("source",source);
        JSONObject l=in.optJSONObject("link");
        if(l!=null&&"id".equals(l.optString("kind"))&&l.optString("ref").matches("[A-Za-z0-9_-]{1,64}")){
            JSONObject link=new JSONObject();
            for(String k:LINK_KEYS){String v=l.optString(k,"");if(!v.isEmpty())link.put(k,DocumentMeta.text(v,120,false,"Koppeling"));}
            out.put("link",link);
        }
        return out;
    }

    /** Zelfde controle voor elke blob-id (document of versie). */
    interface Has { boolean blob(String id); }

    /**
     * Een indexregel uit een backup schoonmaken: alleen bekende velden, versies alleen als hun bestand er is.
     * Geeft null als het document zelf ontbreekt.
     */
    static JSONObject clean(JSONObject d,Has has) throws Exception {
        String id=d.optString("id");
        if(!id.matches("[a-z0-9]{6,24}")||!has.blob(id))return null;
        JSONObject o=new JSONObject().put("id",id).put("name",cut(d.optString("name","document"),120))
                .put("mime",cut(d.optString("mime","application/octet-stream"),120)).put("size",Math.max(0,d.optLong("size")))
                .put("t",d.optLong("t",System.currentTimeMillis())).put("category",DocumentMeta.category(cut(d.optString("category",""),60)));
        if(d.has("note"))o.put("note",DocumentMeta.note(cut(d.optString("note"),1000)));
        if(d.optBoolean("favorite"))o.put("favorite",true);
        if(d.has("vnote"))try{o.put("vnote",DocumentMeta.note(cut(d.optString("vnote"),1000)));}catch(IllegalArgumentException ignored){ }
        try{JSONObject m=meta(d);java.util.Iterator<String> k=m.keys();while(k.hasNext()){String key=k.next();o.put(key,m.get(key));}}catch(Exception ignored){ }
        JSONArray v=d.optJSONArray("versions"),keep=new JSONArray();
        if(v!=null)for(int i=0;i<v.length()&&i<MAX_VERSIONS*2;i++){
            JSONObject x=v.optJSONObject(i);if(x==null)continue;String b=x.optString("blob");
            if(!b.matches("[a-z0-9]{6,24}")||b.equals(id)||!has.blob(b))continue;
            JSONObject y=new JSONObject().put("blob",b).put("t",x.optLong("t")).put("size",Math.max(0,x.optLong("size"))).put("name",cut(x.optString("name",""),120));
            try{y.put("note",DocumentMeta.note(cut(x.optString("note",""),1000)));}catch(IllegalArgumentException ignored){y.put("note","");}
            keep.put(y);
        }
        while(keep.length()>MAX_VERSIONS)keep.remove(0);
        if(keep.length()>0)o.put("versions",keep);
        return o;
    }

    /** Alle versleutelde bestanden van een document: eerst het document zelf, dan de versies. */
    static java.util.List<String> blobs(JSONObject d){
        java.util.List<String> out=new java.util.ArrayList<>();String id=d.optString("id");
        if(id.matches("[a-z0-9]{6,24}"))out.add(id);
        JSONArray v=d.optJSONArray("versions");
        if(v!=null)for(int i=0;i<v.length();i++){JSONObject x=v.optJSONObject(i);String b=x==null?"":x.optString("blob");if(b.matches("[a-z0-9]{6,24}")&&!out.contains(b))out.add(b);}
        return out;
    }

    static String cut(String s,int n){return s.length()>n?s.substring(0,n):s;}

}
