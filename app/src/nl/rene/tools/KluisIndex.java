package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * De index van de documentenkluis, zonder Android: herkomst en koppeling van een document (meta), zijn eigenschappen (props), een regel uit een backup
 * schoonmaken (clean) en welke versleutelde bestanden bij een document horen (blobs: het document en zijn oudere versies).
 */
final class KluisIndex {
    private KluisIndex() { }
    static final int MAX_VERSIONS = 20;
    static final String[] SOURCES={"pdf","bewerkt","idkopie","formulier","betalen","factuur","scan","foto"};
    static final String[] LINK_KEYS={"kind","ref","soort","ontvanger","doel","datum"};

    /** Een mappad netjes (zoals klPad in de interface): delen zonder spaties eromheen, geen lege delen. */
    static String pad(String v) {
        StringBuilder b = new StringBuilder();
        if (v != null) for (String deel : v.split("/")) { String d = deel.trim(); if (d.isEmpty()) continue; if (b.length() > 0) b.append('/'); b.append(d); }
        return b.toString();
    }

    /**
     * Map hernoemen (of verplaatsen): elk document in de map oud of een map daaronder komt onder nieuw, in één keer.
     * Geeft het aantal verplaatste documenten; gooit IllegalArgumentException (met een te vertalen tekst) als het niet kan,
     * en verandert dan niets.
     */
    static int mapHernoem(JSONArray docs, String oud, String nieuw) throws Exception {
        String o = pad(oud), n = pad(nieuw);
        if (o.isEmpty() || n.isEmpty()) throw new IllegalArgumentException(Taal.T("De map ontbreekt"));
        if (n.equals(o)) return 0;
        if (n.startsWith(o + "/")) throw new IllegalArgumentException(Taal.T("Een map kan niet in zichzelf"));
        java.util.List<JSONObject> mee = new java.util.ArrayList<>(); java.util.List<String> naar = new java.util.ArrayList<>();
        for (int i = 0; i < docs.length(); i++) {
            JSONObject d = docs.optJSONObject(i); if (d == null) continue;
            String p = pad(d.optString("category", ""));
            if (!p.equals(o) && !p.startsWith(o + "/")) continue;
            String nu = n + p.substring(o.length());
            if (nu.length() > 60) throw new IllegalArgumentException(Taal.T("Die naam is te lang voor de mappen die erin zitten"));
            mee.add(d); naar.add(DocumentMeta.category(nu));
        }
        for (int i = 0; i < mee.size(); i++) mee.get(i).put("category", naar.get(i));
        return mee.size();
    }

    /** Bekende velden voor koppelingen met een identiteitsdocument, betaling of factuur. */
    static JSONObject meta(JSONObject in) throws Exception {
        JSONObject out=new JSONObject();if(in==null)return out;
        String source=in.optString("source","");
        if(java.util.Arrays.asList(SOURCES).contains(source))out.put("source",source);
        JSONObject l=in.optJSONObject("link");
        if (l == null) return out;
        String kind = l.optString("kind"), ref = l.optString("ref");
        if ("factuur".equals(kind)) {
            if (l.opt("ref") instanceof String && ref.matches("[0-9a-f]{64}")) out.put("link", factuurLink(l, ref));
        } else if (("id".equals(kind) && ref.matches("[A-Za-z0-9_-]{1,64}"))
                || ("betaling".equals(kind) && ref.matches("b[0-9a-f]{12}"))) {
            JSONObject link = new JSONObject();
            for (String key : LINK_KEYS) {
                String value = l.optString(key, "");
                if (!value.isEmpty()) link.put(key, DocumentMeta.text(value, 120, false, "Koppeling"));
            }
            out.put("link", link);
        }
        return out;
    }

    private static JSONObject factuurLink(JSONObject in, String ref) throws Exception {
        JSONObject link = new JSONObject().put("kind", "factuur").put("ref", ref);
        if (in.opt("leverancier") instanceof String) {
            try {
                String leverancier = DocumentMeta.text(in.getString("leverancier"), 120, false, "Leverancier");
                if (!leverancier.isEmpty()) link.put("leverancier", leverancier);
            } catch (IllegalArgumentException ignored) {
                // Een ongeldige optionele leverancier kost de factuurkoppeling niet.
            }
        }
        String datum = in.optString("datum");
        if (in.opt("datum") instanceof String && datum(datum)) link.put("datum", datum);
        return link;
    }

    static final int MAX_LABELS = 12;
    /**
     * Eigenschappen van een document (documentbeheer): {soort, datum, partij, bedrag, verloopt, labels}. Alleen bekende
     * velden; lege velden vallen weg. Datums als JJJJ-MM-DD, een bedrag als getal met een punt (1234.56).
     */
    static JSONObject props(JSONObject in) {
        JSONObject out=new JSONObject();if(in==null)return out;
        try{
            String soort=DocumentMeta.text(tekst(in,"soort"),40,false,"Soort");if(!soort.isEmpty())out.put("soort",soort);
            String partij=DocumentMeta.text(tekst(in,"partij"),120,false,"Partij");if(!partij.isEmpty())out.put("partij",partij);
            for(String k:new String[]{"datum","verloopt"}){
                String d=tekst(in,k).trim();if(d.isEmpty())continue;
                if(!datum(d))throw new IllegalArgumentException("Ongeldige datum");
                out.put(k,d);
            }
            String bedrag=tekst(in,"bedrag").trim();
            if(!bedrag.isEmpty()){if(!bedrag.matches("-?\\d{1,9}(\\.\\d{1,2})?"))throw new IllegalArgumentException("Ongeldig bedrag");out.put("bedrag",bedrag);}
            JSONArray l=in.optJSONArray("labels"),keep=new JSONArray();java.util.List<String> gezien=new java.util.ArrayList<>();
            if(l!=null)for(int i=0;i<l.length()&&i<MAX_LABELS*4&&keep.length()<MAX_LABELS;i++){
                String v=DocumentMeta.text(l.isNull(i)?"":l.optString(i,""),30,false,"Label");
                if(v.isEmpty()||gezien.contains(v.toLowerCase(java.util.Locale.ROOT)))continue;
                gezien.add(v.toLowerCase(java.util.Locale.ROOT));keep.put(v);
            }
            if(keep.length()>0)out.put("labels",keep);
        }catch(org.json.JSONException e){throw new IllegalArgumentException("Ongeldige documentgegevens");}
        return out;
    }
    /** Een veld als tekst; ontbreekt het of is het null, dan leeg. */
    private static String tekst(JSONObject o,String k){return o.isNull(k)?"":o.optString(k,"");}
    /** Een bestaande kalenderdag, JJJJ-MM-DD (1900 tot en met 2199). */
    static boolean datum(String d){
        if(!d.matches("(19|20|21)\\d{2}-\\d{2}-\\d{2}"))return false;
        int j=Integer.parseInt(d.substring(0,4)),m=Integer.parseInt(d.substring(5,7)),dag=Integer.parseInt(d.substring(8,10));
        if(m<1||m>12||dag<1)return false;
        int[] len={31,(j%4==0&&j%100!=0)||j%400==0?29:28,31,30,31,30,31,31,30,31,30,31};
        return dag<=len[m-1];
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
                .put("t",d.optLong("t",System.currentTimeMillis()));
        try{o.put("category",DocumentMeta.category(cut(d.optString("category",""),60)));}catch(IllegalArgumentException e){o.put("category","");}
        if(d.has("note"))try{o.put("note",DocumentMeta.note(cut(d.optString("note"),1000)));}catch(IllegalArgumentException ignored){ } // een kapotte toelichting kost niet het hele document
        if(d.optBoolean("favorite"))o.put("favorite",true);
        if(d.has("vnote"))try{o.put("vnote",DocumentMeta.note(cut(d.optString("vnote"),1000)));}catch(IllegalArgumentException ignored){ }
        try{JSONObject pr=props(d.optJSONObject("props"));if(pr.length()>0)o.put("props",pr);}catch(IllegalArgumentException ignored){ } // kapotte eigenschappen kosten niet het hele document
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
