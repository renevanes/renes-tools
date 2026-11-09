package nl.rene.tools;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Contact koppelen: wat er uit een contact wordt overgenomen en hoe telefoon en e-mail vers uit Contacten komen. */
public final class MasterContactsTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static String[] row(String mime,String... data){String[] r=new String[11];r[0]=mime;for(int i=0;i<data.length&&i<10;i++)r[i+1]=data[i];return r;}
    public static void main(String[] args)throws Exception{
        List<String[]> rows=new ArrayList<>();
        rows.add(row(MasterContacts.NAME,"Rene van Es","Rene","Es",null,"van"));
        rows.add(row(MasterContacts.NICK,"Reen"));
        rows.add(row(MasterContacts.PHONE,"010-1234567","1"));
        rows.add(row(MasterContacts.PHONE,"+31 6 12345678","2"));
        rows.add(row(MasterContacts.EMAIL,"rene@example.org"));
        rows.add(row(MasterContacts.EMAIL,"werk@example.org"));
        rows.add(row(MasterContacts.POSTAL,"Coolsingel 40\n3011 AD Rotterdam",null,null,"Coolsingel 40",null,null,"Rotterdam",null,"3011 AD","Nederland"));
        rows.add(row(MasterContacts.EVENT,"1980-04-02","3"));
        rows.add(row(MasterContacts.EVENT,"2005-06-01","1"));
        JSONObject f=MasterContacts.fields(rows);
        check("Rene".equals(f.getString("voornamen"))&&"van Es".equals(f.getString("naam")),"tussenvoegsel uit de tweede voornaam bij de achternaam");
        check("Reen".equals(f.getString("roepnaam")),"bijnaam als roepnaam");
        check("+31 6 12345678".equals(f.getString("telefoon"))&&f.getJSONArray("telefoons").length()==2,"mobiel nummer gaat voor");
        check("rene@example.org".equals(f.getString("email"))&&f.getJSONArray("emails").length()==2,"eerste (primaire) e-mail");
        check("Coolsingel 40".equals(f.getString("adres"))&&"3011 AD".equals(f.getString("postcode"))&&"Rotterdam".equals(f.getString("plaats"))&&"Nederland".equals(f.getString("land")),"adres in delen");
        check("1980-04-02".equals(f.getString("geboortedatum")),"verjaardag als geboortedatum, geen andere gebeurtenis");
        rows.clear();rows.add(row(MasterContacts.NAME,"Juf Anna","Anna","Jansen",null,"Maria"));rows.add(row(MasterContacts.EVENT,"--04-02","3"));rows.add(row(MasterContacts.PHONE,"06-1","7"));
        f=MasterContacts.fields(rows);
        check("Anna Maria".equals(f.getString("voornamen"))&&"Jansen".equals(f.getString("naam")),"gewone tweede voornaam blijft bij de voornamen");
        check(!f.has("geboortedatum")&&"06-1".equals(f.getString("telefoon"))&&!f.has("telefoons"),"verjaardag zonder jaar telt niet; één nummer");
        rows.clear();rows.add(row(MasterContacts.NAME,"Bakker Hoek"));f=MasterContacts.fields(rows);check("Bakker Hoek".equals(f.getString("naam"))&&!f.has("voornamen"),"alleen een weergavenaam");
        rows.clear();rows.add(row(MasterContacts.NAME,"x","Lange\nnaam\u0001"));check("Lange naam".equals(MasterContacts.fields(rows).getString("voornamen")),"geen regeleinden of stuurtekens");
        // Vers uit Contacten: telefoon en e-mail gaan voor, de rest blijft zoals bewaard
        JSONObject person=new JSONObject().put("type","persoon").put("fields",new JSONObject().put("telefoon","oud").put("email","oud@x").put("naam","Es").put("contact","0r1-AB"));
        JSONObject now=MasterContacts.overlay(person,new JSONObject().put("gevonden",true).put("telefoon","06-nieuw"));
        check("06-nieuw".equals(now.getJSONObject("fields").getString("telefoon"))&&"oud@x".equals(now.getJSONObject("fields").getString("email"))&&"oud".equals(person.getJSONObject("fields").getString("telefoon")),"actueel nummer, bewaarde gegevens onveranderd");
        check(MasterContacts.overlay(person,new JSONObject().put("gevonden",false).put("telefoon","x"))==person&&MasterContacts.overlay(person,null)==person,"contact weg of geen toegang: de bewaarde waarden");
        check(MasterContacts.validKey("0r1-3A5C.1234i9f2a%20b")&&!MasterContacts.validKey("")&&!MasterContacts.validKey("a b")&&!MasterContacts.validKey("x'--")&&!MasterContacts.validKey(new String(new char[201]).replace('\0','a')),"opzoeksleutel");
        boolean failed=false;try{MasterSchema.clean(new JSONObject().put("type","persoon").put("label","x").put("fields",new JSONObject().put("contact","a b")));}catch(MasterError e){failed=true;}check(failed,"ongeldige sleutel geweigerd");
        check(MasterSchema.clean(new JSONObject().put("type","persoon").put("label","x").put("fields",new JSONObject().put("contact","0r1-AB").put("delen","ja"))).getJSONObject("fields").getString("delen").equals("ja"),"persoon met contact en delen");
        failed=false;try{MasterSchema.clean(new JSONObject().put("type","profiel").put("label","x").put("fields",new JSONObject().put("persoon","11111111-1111-1111-1111-111111111111").put("aiStatus","misschien")));}catch(MasterError e){failed=true;}check(failed,"vinkje alleen ja of nee");
        check(MasterContacts.ACTIONS.contains("bel")&&MasterContacts.ACTIONS.contains("bericht")&&MasterContacts.ACTIONS.contains("contact")&&MasterContacts.ACTIONS.size()==3,"alleen bellen, bericht en Contacten openen");
        check(MasterContacts.dialable("+31 6 12345678").equals("+31612345678")&&MasterContacts.dialable("0031 (6) 123-45678").equals("+31612345678")&&MasterContacts.dialable("14010").equals("14010"),"nummer om te bellen: cijfers met + vooraan");
        check(MasterContacts.dialable("Tel: +31 (0)6 12345678").equals("+31612345678")&&MasterContacts.dialable("112").equals("112")&&MasterContacts.dialable("06 1234+5678").equals("0612345678"),"nummer om te bellen: voorvoegsel en (0) eraf");
        check(MasterContacts.dialable("*#06#").isEmpty()&&MasterContacts.dialable("*31#0612345678").isEmpty()&&MasterContacts.dialable("00").isEmpty()&&MasterContacts.dialable("000").isEmpty()&&MasterContacts.dialable("+").isEmpty()
                &&MasterContacts.dialable("geen").isEmpty()&&MasterContacts.dialable(null).isEmpty()&&MasterContacts.dialable("1234567890123456").isEmpty(),"geen codes, te korte of te lange nummers in de telefoon-app");
        System.out.println("Contact koppelen, actuele telefoon en e-mail en vinkjes van de assistent gecontroleerd");
    }
}
