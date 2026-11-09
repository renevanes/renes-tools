package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Centrale velddefinities en expliciete contracten: consumenten krijgen alleen passende, gekozen velden. */
final class MasterSchema {
    private MasterSchema(){ }
    /** contact = opzoeksleutel van een contact (telefoon en e-mail komen dan uit Contacten); delen = bekend bij de assistent. */
    static final String[] PERSON={"naam","voornamen","roepnaam","initialen","geboortedatum","geboorteplaats","geslacht","nationaliteit","bsn","adres","postcode","plaats","land","telefoon","email","context","contact","delen"};
    static final String[] ORGANISATION={"naam","registratienummer","adres","postcode","plaats","land","telefoon","email"};
    static final String[] ADDRESS={"naam","adres","postcode","plaats","land","telefoon"};
    static final String[] IDENTITY={"persoon","documentsoort","document","geldig","afgifte","afgifteplaats","uitgifteland","afgegevenDoor","categorieen"};
    static final String[] TRIP={"van","tot","alleen","tweedeBegeleider","tweedeGever","accommodatie","postcode","plaats","land","telefoon","reisgegevens"};
    /** Wat de assistent weet (Wat weet de assistent?): leeg = de standaard uit MasterIdentity.AI_DEFAULT. */
    static final String[] PROFILE={"persoon","delen","context","aiStatus","aiGeboorte","aiDocumenten","aiWoonplaats","aiContact"};
    static final java.util.List<String> YES_NO=Arrays.asList("alleen","tweedeBegeleider","tweedeGever","delen","aiStatus","aiGeboorte","aiDocumenten","aiWoonplaats","aiContact");
    static final String[] RELATION={"vanPersoon","naarPersoon","relatie","status","toelichting","delen"};
    static boolean sensitive(String key){return "bsn".equals(key)||"document".equals(key)||"geldig".equals(key)||"afgifte".equals(key);}
    static String[] fields(String type)throws Exception{
        if("persoon".equals(type))return PERSON;if("organisatie".equals(type))return ORGANISATION;if("adres".equals(type))return ADDRESS;if("identiteit".equals(type))return IDENTITY;if("reis".equals(type))return TRIP;if("profiel".equals(type))return PROFILE;if("relatie".equals(type))return RELATION;if("formulier".equals(type))return MasterFormProfile.FIELDS;
        throw new MasterError(Taal.T("Onbekend soort stamgegeven"));
    }
    static boolean validDate(String value){
        if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))return false;
        int y=Integer.parseInt(value.substring(0,4)),m=Integer.parseInt(value.substring(5,7)),d=Integer.parseInt(value.substring(8,10));
        if(y<1||m<1||m>12)return false;int[] days={31,28,31,30,31,30,31,31,30,31,30,31};if(y%4==0&&(y%100!=0||y%400==0))days[1]=29;return d>=1&&d<=days[m-1];
    }
    static JSONObject clean(JSONObject in)throws Exception{
        String type=in.getString("type"),label=in.optString("label").trim();String[] allowed=fields(type);
        if(label.isEmpty()||label.length()>120)throw new MasterError(Taal.T("Vul een naam voor het overzicht in"));
        JSONObject raw=in.getJSONObject("fields"),out=new JSONObject();Set<String> known=new HashSet<>(Arrays.asList(allowed));
        java.util.Iterator<String> it=raw.keys();while(it.hasNext()){
            String k=it.next();Object value=raw.get(k);if(!known.contains(k)||!(value instanceof String))throw new MasterError(Taal.T("Onbekend of ongeldig gegevensveld"));
            String s=((String)value).trim();if(s.length()>240||s.matches("(?s).*[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f].*"))throw new MasterError(Taal.T("Een gegevensveld is te lang of ongeldig"));
            s=s.replace('\n',' ').replace('\r',' ');
            if(("geboortedatum".equals(k)||"geldig".equals(k)||"afgifte".equals(k)||"van".equals(k)||"tot".equals(k))&&!s.isEmpty()&&!validDate(s))throw new MasterError(Taal.T("Vul een geldige datum in"));
            if(YES_NO.contains(k)&&!s.isEmpty()&&!s.equals("ja")&&!s.equals("nee"))throw new MasterError(Taal.T("Kies Ja of Nee"));
            if("contact".equals(k)&&!s.isEmpty()&&!MasterContacts.validKey(s))throw new MasterError(Taal.T("Kies het contact opnieuw"));
            // Geslacht zoals op het Nederlandse identiteitsbewijs (M, V of X). Gaat alleen mee naar een formulier dat erom vraagt.
            if("geslacht".equals(k)&&!s.isEmpty()&&!s.equals("M")&&!s.equals("V")&&!s.equals("X"))throw new MasterError(Taal.T("Kies Man, Vrouw of X"));
            if("bsn".equals(k)&&!s.isEmpty()&&!s.matches("[0-9]{8,9}"))throw new MasterError(Taal.T("Vul een geldig BSN in"));
            if(Arrays.asList("persoon","vanPersoon","naarPersoon").contains(k)&&!s.isEmpty()&&!s.matches("[a-f0-9-]{36}"))throw new MasterError(Taal.T("Kies een gekoppelde persoon"));
            if("formulier".equals(type)&&MasterFormProfile.reference(k)!=null&&!s.isEmpty()&&!s.matches("[a-f0-9-]{36}"))throw new MasterError(Taal.T("Ongeldige gegevensverwijzing"));
            if("formulier".equals(type)&&MasterFormProfile.CHOICES.contains(k)&&!s.isEmpty()&&!s.equals("ja")&&!s.equals("nee"))throw new MasterError(Taal.T("Kies Ja of Nee"));
            if(("relatie".equals(k)||"formulier".equals(type)&&MasterFormProfile.relationField(k))&&!s.isEmpty()&&!MasterIdentity.RELATIONS.contains(s))throw new MasterError(Taal.T("Kies een geldige relatie"));
            if(!s.isEmpty())out.put(k,s);
        }
        if(("identiteit".equals(type)||"profiel".equals(type))&&out.optString("persoon").isEmpty())throw new MasterError(Taal.T("Kies een gekoppelde persoon"));
        if("relatie".equals(type)&&(out.optString("vanPersoon").isEmpty()||out.optString("naarPersoon").isEmpty()||out.optString("relatie").isEmpty()))throw new MasterError(Taal.T("Kies beide personen en hun relatie"));
        if("relatie".equals(type)&&out.getString("vanPersoon").equals(out.getString("naarPersoon")))throw new MasterError(Taal.T("Een relatie hoort bij twee verschillende personen"));
        if("relatie".equals(type)&&out.has("status")&&!MasterIdentity.statuses(out.getString("relatie")).contains(out.getString("status")))throw new MasterError(Taal.T("Kies een status die bij deze relatie past"));
        if("identiteit".equals(type)&&out.has("afgifte")&&out.has("geldig")&&out.getString("afgifte").compareTo(out.getString("geldig"))>0)throw new MasterError(Taal.T("De afgiftedatum moet voor de vervaldatum liggen"));
        if("formulier".equals(type)&&!MasterFormProfile.FORMS.contains(out.optString("formulier")))throw new MasterError(Taal.T("Kies voor welk formulier deze vaste gegevens zijn"));
        if("reis".equals(type)&&out.has("van")&&out.has("tot")&&out.getString("van").compareTo(out.getString("tot"))>0)throw new MasterError(Taal.T("De laatste reisdag moet op of na de eerste reisdag vallen."));
        return new JSONObject().put("type",type).put("label",label).put("fields",out);
    }
    static String source(String source)throws Exception{
        if("masterdata".equals(source)||"formulieren".equals(source)||"idcopy".equals(source))return source;
        throw new MasterError(Taal.T("Dit onderdeel heeft geen toegang tot stamgegevens"));
    }
    static void context(String source,String role)throws Exception{
        source(source);
        boolean ok="masterdata".equals(source)&&role.isEmpty()||"idcopy".equals(source)&&"ontvanger".equals(role)
                ||"formulieren".equals(source)&&Arrays.asList("kind","begeleider1","begeleider2","gever1","gever2","bestemming","reis","vast").contains(role);
        if(!ok)throw new MasterError(Taal.T("Deze gegevens passen niet bij dit onderdeel"));
    }
    static Set<String> permitted(String source,String role,String type)throws Exception{
        context(source,role);Set<String> keys=new HashSet<>();
        if("masterdata".equals(source)){keys.addAll(Arrays.asList(fields(type)));return keys;}
        // Vaste formuliergegevens kiezen: alleen welk formulier; de inhoud wordt native opgebouwd (MasterFormProfile).
        if("formulieren".equals(source)&&"vast".equals(role)){if("formulier".equals(type))keys.add("formulier");return keys;}
        if("formulieren".equals(source)&&"reis".equals(role)){if("reis".equals(type))keys.addAll(Arrays.asList(TRIP));return keys;}
        if("idcopy".equals(source)&&"ontvanger".equals(role)&&("persoon".equals(type)||"organisatie".equals(type))){keys.add("ontvanger");return keys;}
        if("formulieren".equals(source)&&Arrays.asList("kind","begeleider1","begeleider2","gever1","gever2","bestemming").contains(role)){
            if("persoon".equals(type)&&!"bestemming".equals(role))keys.addAll(Arrays.asList("naam","initialen","geboortedatum","geboorteplaats","nationaliteit","bsn"));
            if("persoon".equals(type)&&"kind".equals(role)){keys.remove("initialen");keys.add("voornamen");}
            if("persoon".equals(type)&&role.startsWith("gever"))keys.addAll(Arrays.asList("adres","postcode","plaats","telefoon"));
            if("identiteit".equals(type)&&!"bestemming".equals(role))keys.addAll(Arrays.asList("document","geldig"));
            if(("organisatie".equals(type)||"adres".equals(type))&&(role.startsWith("gever")||"bestemming".equals(role)))keys.addAll(Arrays.asList("adres","postcode","plaats","land","telefoon"));
            if("organisatie".equals(type)&&role.startsWith("gever"))keys.add("instelling");
            if(("organisatie".equals(type)||"adres".equals(type))&&"bestemming".equals(role))keys.add("accommodatie");
            return keys;
        }
        throw new MasterError(Taal.T("Deze gegevens passen niet bij dit onderdeel"));
    }
    static JSONObject projection(JSONObject record,String source,String role)throws Exception{
        Set<String> allowed=permitted(source,role,record.getString("type"));JSONObject fields=record.getJSONObject("fields"),out=new JSONObject();
        if(allowed.contains("ontvanger")){
            String who=(fields.optString("voornamen")+" "+fields.optString("naam")).trim();out.put("ontvanger",who.isEmpty()?record.getString("label"):who);return out;
        }
        for(String k:allowed){boolean alias=!"reis".equals(record.getString("type"))&&("instelling".equals(k)||"accommodatie".equals(k));String value=fields.optString(alias?"naam":k);if(!value.isEmpty())out.put(k,value);}
        return out;
    }
    static JSONObject selected(JSONObject record,String source,String role,JSONArray wanted)throws Exception{
        JSONObject available=projection(record,source,role),out=new JSONObject();
        if(wanted==null||wanted.length()<1||wanted.length()>30)throw new MasterError(Taal.T("Kies welke velden je wilt overnemen"));
        for(int i=0;i<wanted.length();i++){String k=wanted.getString(i);if(!available.has(k))throw new MasterError(Taal.T("Dit veld mag niet worden overgenomen"));out.put(k,available.getString(k));}
        return out;
    }
}
