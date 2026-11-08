package nl.rene.tools;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

public class RijbewijsTest {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static List<Rijbewijs.Line> card(String...text){List<Rijbewijs.Line> lines=new ArrayList<>();for(String s:text)lines.add(new Rijbewijs.Line(s,96,20,lines.size()*40,700,lines.size()*40+30));return lines;}
    static JSONObject read(String...text)throws Exception{return Rijbewijs.read(card(text),"2026-11-08");}
    public static void main(String[] args)throws Exception{
        JSONObject r=read("NEDERLAND RIJBEWIJS","1. DE VRIES","2. ANNA JOSEFINA","3. 01.02.1980 ROTTERDAM","4a. 03.04.2020 4b. 03.04.2030","4c. RDW","5. 0123456789","9. AM/B/BE");
        JSONObject d=r.getJSONObject("document"),p=r.getJSONObject("persoon");
        check(d.getString("documentsoort").equals("rijbewijs")&&d.getString("document").equals("0123456789"),"rijbewijs en nummer uit genummerde voorkant");
        check(p.getString("naam").equals("DE VRIES")&&p.getString("voornamen").equals("ANNA JOSEFINA")&&p.getString("geboortedatum").equals("1980-02-01"),"namen en geboortedatum uit veld 1, 2, 3");
        check(d.getString("afgifte").equals("2020-04-03")&&d.getString("geldig").equals("2030-04-03")&&d.getString("afgegevenDoor").equals("RDW"),"twee naast elkaar staande datumvelden gescheiden");
        check(d.getString("categorieen").equals("AM, B, BE")&&!p.has("bsn")&&!p.has("nationaliteit"),"categorieën letterlijk; geen BSN of nationaliteit afgeleid");
        check(!r.getBoolean("zeker")&&r.getString("bron").equals("tekst"),"tekstherkenning wordt geen gecontroleerde MRZ-lezing genoemd");
        check(Rijbewijs.date("31.02.2024").isEmpty()&&Rijbewijs.date("29.02.2023").isEmpty()&&Rijbewijs.date("29.02.2024").equals("2024-02-29"),"echte kalenderdagen inclusief schrikkeljaar");
        check(Rijbewijs.date("O1.02.1980").isEmpty(),"onzekere letter wordt niet als een nul weggegooid");
        check(Rijbewijs.categories("BLAUW").isEmpty()&&Rijbewijs.categories("AM B T").equals("AM, B, T"),"alleen rijbewijscategorieën");
        r=read("DRIVING LICENCE","1. O’NEILL","2. ÉVA","3. 2-3-1979","4b. 2-3-2031","5. DVL123456789");
        check(r!=null&&r.getJSONObject("persoon").getString("naam").equals("O’NEILL")&&r.getJSONObject("persoon").getString("voornamen").equals("ÉVA"),"accenten en apostrof blijven zoals gelezen");
        check(r.getJSONObject("persoon").getString("geboortedatum").equals("1979-03-02"),"een datum met een eencijferige dag is geen extra veldnummer");
        check(read("BESTELBON","1. DE VRIES","2. ANNA","3. 01.02.1980","5. 0123456789")==null,"een bestelbon wordt geen rijbewijs");
        check(read("RIJBEWIJS","1. DE VRIES")==null,"een titel alleen geeft geen persoonsgegevensvoorstel");
        List<Rijbewijs.Line> uncertain=card("RIJBEWIJS","1. DE VRIES","3. 01.02.1980","5. 0123456789");uncertain.set(1,new Rijbewijs.Line("1. DE VRIES",62,20,40,700,70));
        r=Rijbewijs.read(uncertain,"2026-11-08");check(r.getJSONArray("twijfel").toString().contains("naam"),"onzekere namen expliciet gemarkeerd");
        uncertain.add(new Rijbewijs.Line("1. DE VRIESEN",97,20,180,700,210));r=Rijbewijs.read(uncertain,"2026-11-08");
        check(r.getJSONArray("twijfel").toString().contains("naam"),"tegenstrijdige lezingen worden niet zeker verklaard");
        r=read("RIJBEWIJS","1. DE VRIES","3. 01.02.2030","4a. 01.01.2024","4b. 01.01.2020","5. 0123456789");
        check(!r.getJSONObject("persoon").has("geboortedatum")&&!r.getJSONObject("document").has("geldig"),"toekomstige geboorte en omgewisselde geldigheidsdata niet overgenomen");
        System.out.println("RijbewijsTest OK");
    }
}
