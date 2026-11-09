package nl.rene.tools;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Vaste formuliergegevens: een stamgegeven dat alleen naar andere stamgegevens verwijst (personen,
 * identiteitsdocumenten, organisaties) plus een paar vaste keuzes. Bij gebruik wordt het opnieuw
 * opgebouwd uit de actuele stamgegevens, via dezelfde rolprojecties als het handmatig overnemen.
 * Zo hoeft iemand per reis alleen nog de reisdagen en de bestemming in te vullen.
 */
final class MasterFormProfile {
    private MasterFormProfile(){ }
    static final String[] FIELDS={"formulier","kind","kindDocument","alleen","begeleider1","begeleider1Document","begeleider1Relatie",
            "begeleider2","begeleider2Document","begeleider2Relatie","gever1","gever1Document","gever1Instelling",
            "gever2","gever2Document","gever2Instelling","bsnInvullen","tekenplaats","handtekening"};
    static final Set<String> FORMS=Collections.unmodifiableSet(new LinkedHashSet<>(Collections.singletonList("reistoestemming")));
    static final List<String> ROLES=Arrays.asList("kind","begeleider1","begeleider2","gever1","gever2");
    static final List<String> CHOICES=Arrays.asList("alleen","bsnInvullen");
    /** Bij welke begeleider de eigen bewaarde handtekening (PdfSignature) komt; nooit bij een toestemminggever. */
    static final List<String> SIGNATURE=Arrays.asList("nee","begeleider1","begeleider2");
    interface Lookup{JSONObject get(String id)throws Exception;}
    interface Use{void used(JSONObject record,String role,JSONArray fields)throws Exception;}
    /** Logboek: een gelezen relatie heet net als bij MasterStore.formRelation "relation_use". */
    interface Relation{void read(JSONObject record,String role)throws Exception;}

    /** Soort stamgegeven waar dit veld naar verwijst, of null als het geen verwijzing is. */
    static String reference(String key){
        if(ROLES.contains(key))return "persoon";
        if(key.endsWith("Document")&&ROLES.contains(key.substring(0,key.length()-8)))return "identiteit";
        if(key.endsWith("Instelling")&&key.startsWith("gever"))return "organisatie";
        return null;
    }
    static boolean relationField(String key){return "begeleider1Relatie".equals(key)||"begeleider2Relatie".equals(key);}

    /** Controle bij opslaan: verwijzingen bestaan, hebben de juiste soort en documenten horen bij de gekozen persoon. */
    static void validate(JSONObject fields,Lookup lookup)throws Exception{
        if(!FORMS.contains(fields.optString("formulier")))throw new MasterError(Taal.T("Kies voor welk formulier deze vaste gegevens zijn"));
        required(fields);
        for(String key:FIELDS){
            String type=reference(key),id=fields.optString(key);if(type==null||id.isEmpty())continue;
            JSONObject target=lookup.get(id);
            if(!type.equals(target.getString("type")))throw new MasterError(Taal.T("Een gekozen stamgegeven heeft niet de juiste soort"));
            if("identiteit".equals(type)){
                String owner=fields.optString(key.substring(0,key.length()-8));
                if(owner.isEmpty()||!owner.equals(target.getJSONObject("fields").optString("persoon")))throw new MasterError(Taal.T("Kies een reisdocument dat bij de gekozen persoon hoort"));
            }
        }
        for(String key:new String[]{"begeleider1","begeleider2","gever1","gever2"}){
            String id=fields.optString(key);if(id.isEmpty())continue;
            if(id.equals(fields.optString("kind")))throw new MasterError(Taal.T("Het kind kan niet ook begeleider of toestemminggever zijn"));
        }
        if(!fields.optString("begeleider2").isEmpty()&&fields.optString("begeleider2").equals(fields.optString("begeleider1")))throw new MasterError(Taal.T("Kies twee verschillende begeleiders"));
        if(!fields.optString("gever2").isEmpty()&&fields.optString("gever2").equals(fields.optString("gever1")))throw new MasterError(Taal.T("Kies twee verschillende toestemmingsgevers"));
        for(String g:new String[]{"gever1","gever2"})for(String b:new String[]{"begeleider1","begeleider2"}){
            String id=fields.optString(g);            if(!id.isEmpty()&&id.equals(fields.optString(b)))throw new MasterError(Taal.T("Een toestemminggever reist niet mee; kies een andere begeleider"));
        }
        String sign=fields.optString("handtekening");
        if(!sign.isEmpty()&&!"nee".equals(sign)&&signer(fields).isEmpty())throw new MasterError(Taal.T("Kies voor je handtekening een begeleider die meereist"));
    }
    /** De begeleider bij wie de handtekening komt, of "" als er geen handtekening komt of die begeleider niet meereist. */
    static String signer(JSONObject f){
        String role=f.optString("handtekening");
        if(!role.startsWith("begeleider")||"ja".equals(f.optString("alleen"))||f.optString(role).isEmpty())return "";
        return role;
    }

    /** "jjjj-mm-dd" plus een aantal maanden; de dag schuift terug naar het einde van een kortere maand. */
    static String plusMonths(String iso,int months){
        int y=Integer.parseInt(iso.substring(0,4)),m=Integer.parseInt(iso.substring(5,7))-1+months,d=Integer.parseInt(iso.substring(8,10));
        y+=m/12;m=m%12+1;int[] days={31,28,31,30,31,30,31,31,30,31,30,31};if(y%4==0&&(y%100!=0||y%400==0))days[1]=29;if(d>days[m-1])d=days[m-1];
        return String.format(java.util.Locale.US,"%04d-%02d-%02d",y,m,d);
    }
    static final int EXPIRY_MONTHS=6;
    /**
     * Per gekozen document: reist de persoon mee (kind, begeleider), dan "voor" als het verloopt vóór de laatste reisdag
     * en "kort" binnen zes maanden daarna (veel landen buiten Schengen eisen dat). Een toestemminggever reist niet mee:
     * alleen "verlopen" als het document vóór de eerste reisdag al verlopen is (ondertekenen met een geldig bewijs).
     * Geeft de documentnaam, de rol en het niveau; de datum alleen met toestemming voor identiteitsgegevens; nooit het nummer.
     */
    static JSONArray expiry(JSONObject profile,Lookup lookup,String van,String tot,boolean consent,Relation read)throws Exception{
        if(!"formulier".equals(profile.getString("type")))throw new MasterError(Taal.T("Kies vaste formuliergegevens"));
        JSONObject f=profile.getJSONObject("fields");boolean alone="ja".equals(f.optString("alleen"));JSONArray out=new JSONArray();
        String limit=plusMonths(tot,EXPIRY_MONTHS);
        for(String role:ROLES){
            if(alone&&role.startsWith("begeleider"))continue;
            String doc=f.optString(role+"Document"),owner=f.optString(role);if(doc.isEmpty()||owner.isEmpty())continue;
            JSONObject identity;try{identity=lookup.get(doc);}catch(MasterError gone){continue;}
            if(!"identiteit".equals(identity.getString("type"))||!owner.equals(identity.getJSONObject("fields").optString("persoon")))continue;
            String until=identity.getJSONObject("fields").optString("geldig");read.read(identity,role);
            if(until.isEmpty()||!MasterSchema.validDate(until))continue;
            String level=role.startsWith("gever")?(until.compareTo(van)<0?"verlopen":""):until.compareTo(tot)<0?"voor":until.compareTo(limit)<0?"kort":"";
            if(level.isEmpty())continue;
            JSONObject w=new JSONObject().put("role",role).put("label",identity.getString("label")).put("niveau",level);
            if(consent)w.put("geldig",until);out.put(w);
        }
        return out;
    }
    /** Zonder kind, toestemminggever en (als het kind niet alleen reist) begeleider kan het formulier niet worden ingevuld. */
    static void required(JSONObject f)throws Exception{
        if(f.optString("kind").isEmpty())throw new MasterError(Taal.T("Kies in de vaste gegevens het kind dat reist"));
        if(f.optString("gever1").isEmpty())throw new MasterError(Taal.T("Kies in de vaste gegevens wie toestemming geeft"));
        if(!"ja".equals(f.optString("alleen"))&&f.optString("begeleider1").isEmpty())throw new MasterError(Taal.T("Kies in de vaste gegevens wie meereist, of kies dat het kind alleen reist"));
    }
    /** "Anna Maria" → "A.M." Alleen als de persoon zelf geen initialen heeft opgeslagen. */
    static String initials(String names){
        StringBuilder out=new StringBuilder();
        for(String part:names.trim().split("\\s+")){if(part.isEmpty())continue;int c=part.codePointAt(0);if(Character.isLetter(c))out.appendCodePoint(Character.toUpperCase(c)).append('.');}
        return out.toString();
    }

    static void put(JSONObject values,JSONArray used,String role,JSONObject projected)throws Exception{
        java.util.Iterator<String> it=projected.keys();
        while(it.hasNext()){String k=it.next();values.put(role+"-"+k,projected.getString(k));used.put(k);}
    }

    /**
     * Bouwt de formulierwaarden opnieuw op uit de actuele stamgegevens. Identiteitsgegevens (documentnummer,
     * geldig tot, BSN) komen alleen mee met expliciete toestemming bij dit gebruik; zonder die toestemming
     * blijven ze leeg en meldt het antwoord dat ze zijn weggelaten.
     */
    static JSONObject resolve(JSONObject profile,Lookup lookup,JSONArray all,boolean consent,Use use,Relation relation)throws Exception{
        if(!"formulier".equals(profile.getString("type")))throw new MasterError(Taal.T("Kies vaste formuliergegevens"));
        JSONObject f=profile.getJSONObject("fields");
        if(!FORMS.contains(f.optString("formulier")))throw new MasterError(Taal.T("Kies voor welk formulier deze vaste gegevens zijn"));
        boolean alone="ja".equals(f.optString("alleen")),bsn="ja".equals(f.optString("bsnInvullen"));
        required(f);
        JSONObject values=new JSONObject(),relations=new JSONObject();boolean omitted=false;
        values.put("alleen",alone?"ja":"nee");
        values.put("tweedeBegeleider",!alone&&!f.optString("begeleider2").isEmpty()?"ja":"nee");
        values.put("tweedeGever",f.optString("gever2").isEmpty()?"nee":"ja");
        JSONArray vast=new JSONArray().put("formulier");
        String place=f.optString("tekenplaats"),signer=signer(f);
        if(!place.isEmpty()&&!alone)vast.put("tekenplaats");
        if(!signer.isEmpty()){vast.put("handtekening");values.put(signer+"-handtekening","ja");}
        use.used(profile,"vast",vast);
        String child=f.getString("kind");
        for(String role:ROLES){
            if(alone&&role.startsWith("begeleider"))continue;
            String id=f.optString(role);if(id.isEmpty())continue;
            JSONObject person=lookup.get(id);if(!"persoon".equals(person.getString("type")))throw new MasterError(Taal.T("Kies een gekoppelde persoon"));
            JSONObject projected=MasterSchema.projection(person,"formulieren",role);
            if(projected.has("bsn")&&!(bsn&&consent)){projected.remove("bsn");if(bsn)omitted=true;}
            // Bewuste uitzondering op de rolprojectie: begeleiders en toestemminggevers krijgen geen voornamen,
            // wel initialen. Ontbreken die, dan worden ze hier uit de voornamen afgeleid; alleen de initialen gaan mee.
            if(!"kind".equals(role)&&!projected.has("initialen")){
                String derived=initials(person.getJSONObject("fields").optString("voornamen"));if(!derived.isEmpty())projected.put("initialen",derived);
            }
            JSONArray fields=new JSONArray();put(values,fields,role,projected);use.used(person,role,fields);
            String doc=f.optString(role+"Document");
            if(!doc.isEmpty()){
                if(!consent)omitted=true;
                else{
                    JSONObject identity=lookup.get(doc);
                    if(!"identiteit".equals(identity.getString("type"))||!id.equals(identity.getJSONObject("fields").optString("persoon")))throw new MasterError(Taal.T("Kies een reisdocument dat bij de gekozen persoon hoort"));
                    JSONArray docFields=new JSONArray();put(values,docFields,role,MasterSchema.projection(identity,"formulieren",role));use.used(identity,role,docFields);
                }
            }
            if(role.startsWith("gever")&&!f.optString(role+"Instelling").isEmpty()){
                JSONObject org=lookup.get(f.getString(role+"Instelling"));
                if(!"organisatie".equals(org.getString("type")))throw new MasterError(Taal.T("Een gekozen stamgegeven heeft niet de juiste soort"));
                String name=MasterSchema.projection(org,"formulieren",role).optString("instelling");
                if(!name.isEmpty()){values.put(role+"-instelling",name);use.used(org,role,new JSONArray().put("instelling"));}
            }
            if(role.startsWith("begeleider")){
                if(!place.isEmpty())values.put(role+"-tekenplaats",place);
                String code=f.optString(role+"Relatie");
                if(code.isEmpty()){
                    Set<String> found=new LinkedHashSet<>();
                    for(int i=0;i<all.length();i++){
                        JSONObject r=all.getJSONObject(i);
                        if(!"relatie".equals(r.getString("type"))||!"ja".equals(r.getJSONObject("fields").optString("delen")))continue;
                        String c=MasterIdentity.between(r,child,id);if(!c.isEmpty()){found.add(c);relation.read(r,role);}
                    }
                    // Tegenstrijdige relaties: leeg laten, nooit raden.
                    if(found.size()==1)code=found.iterator().next();
                }
                if(!code.isEmpty())relations.put(role,code);
            }
        }
        return new JSONObject().put("values",values).put("relations",relations).put("omitted",omitted);
    }
}
