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
        // Echte foto's: de kleine veldnummers worden vaak niet gelezen. Dan de regel onderaan (D1NLD…, met controlecijfer)
        // voor het nummer, en de datums op hun onderlinge afstand (afgifte en geldig tot liggen 10 jaar uit elkaar).
        String line="D1NLD150949621115M2Z6KC47X2W71";check(Mrz.digit(line.substring(0,29))==1,"voorbeeldregel klopt (controlecijfer over 29 tekens)");
        r=read("RIJBEWIJS","DE VRIES","ANNA","17.02.1981 AMSTERDAM","01.11.2014","BURG. VAN AMSTERDAM","01.11.2024","5094962111",line);
        check(r!=null,"zonder veldnummers toch gelezen");d=r.getJSONObject("document");p=r.getJSONObject("persoon");
        check(d.getString("document").equals("5094962111")&&!r.getJSONArray("twijfel").toString().contains("\"document\""),"nummer uit de regel onderaan, zeker (controlecijfer)");
        check(d.getString("geldig").equals("2024-11-01")&&d.getString("afgifte").equals("2014-11-01")&&p.getString("geboortedatum").equals("1981-02-17"),"datums op hun afstand herkend");
        check(r.getJSONArray("twijfel").toString().contains("geldig")&&r.getJSONArray("twijfel").toString().contains("geboortedatum"),"datums zonder veldnummer: niet zeker (uitgevinkt)");
        r=read("RIJBEWIJS","D1NLD15O9496211I5M2Z6KC47X2W71");
        check(r!=null&&r.getJSONObject("document").getString("document").equals("5094962111"),"O en I op cijferplekken worden 0 en 1 (controlecijfer beslist); alleen het nummer is genoeg");
        r=read("D1NLD150949621115M2Z6KC47X2W79","RIJBEWIJS","1. DE VRIES","3. 17.02.1981");
        check(r!=null&&r.getJSONArray("twijfel").toString().contains("\"document\""),"fout controlecijfer: nummer als twijfel");
        r=read("RIJBEWIJS","1. DE VRIES","3. 17.02.1981","5. 5094962112",line);
        check(r.getJSONObject("document").getString("document").equals("5094962111")&&r.getJSONArray("twijfel").toString().contains("\"document\""),"veld 5 en de regel verschillen: de regel met controlecijfer, als twijfel");
        List<Rijbewijs.Line> laag=card("RIJBEWIJS","1. DE VRIES","3. 17.02.1981","4b. 01.11.2024","5. 5094962111");laag.set(3,new Rijbewijs.Line("4b. 01.11.2024",41,20,120,700,150));
        r=Rijbewijs.read(laag,"2026-11-08");check(r.getJSONObject("document").getString("geldig").equals("2024-11-01")&&r.getJSONArray("twijfel").toString().contains("geldig"),"lage zekerheid: wel voorgesteld, als twijfel");
        check(read("BESTELBON","17.02.1981","01.11.2014","01.11.2024","5094962111")==null,"zonder rijbewijs geen datums of nummers raden");
        List<Rijbewijs.Line> vaag=card("RIJBEWIJS",line);vaag.set(1,new Rijbewijs.Line(line,60,20,40,700,70));r=Rijbewijs.read(vaag,"2026-11-08");
        check(r!=null&&r.getJSONArray("twijfel").toString().contains("\"document\""),"regel met lage zekerheid: controlecijfer alleen is niet genoeg (verwisselde cijfers)");
        String wissel="D1NLD105949621115M2Z6KC47X2W71";check(Mrz.digit(wissel.substring(0,29))==1,"verwisseld (0 en 5): controlecijfer klopt toch");
        r=read("RIJBEWIJS","5. 5094962111",wissel);check(r.getJSONArray("twijfel").toString().contains("\"document\""),"veld 5 zegt iets anders dan de regel: twijfel");
        r=read("AM 01.03.1997 01.03.2027","B 12.05.2001 12.05.2031",line);
        check(r!=null&&!r.getJSONObject("persoon").has("geboortedatum")&&!r.getJSONObject("document").has("geldig"),"achterkant (geen titel): geen datums per categorie als geboorte of geldig");
        check(Rijbewijs.term("2014-11-01","2024-11-01")&&Rijbewijs.term("2014-11-01","2024-10-31")&&Rijbewijs.term("2020-02-29","2030-02-28")&&!Rijbewijs.term("2014-11-01","2021-11-01"),"looptijd 5/10/15 jaar, ook een dag eerder");
        // Afgiftedatum zonder leesbaar "4a." (echte foto): de datum die precies 10 jaar voor veld 4b ligt, als twijfel
        r=read("RIJBEWIJS","1. DE VRIES","2. ANNA","3. 17.02.1981 AMSTERDAM","01.11.2014 4c. BURG. VAN AMSTERDAM","4b. 01.11.2024","5. 5094962111");
        d=r.getJSONObject("document");
        check(d.getString("afgifte").equals("2014-11-01")&&d.getString("geldig").equals("2024-11-01")&&d.getString("afgegevenDoor").equals("BURG. VAN AMSTERDAM"),"afgifte uit de datum 10 jaar voor geldig tot");
        check(r.getJSONArray("twijfel").toString().contains("afgifte")&&!r.getJSONArray("twijfel").toString().contains("geldig"),"afgeleide afgifte als twijfel, gelezen veld 4b niet");
        r=read("1. DE VRIES","2. ANNA","3. 17.02.1981","4a. O1.11.2014","01.11.2014","4b. 31.10.2024","5. 5094962111");
        check(r!=null&&r.getJSONObject("document").getString("afgifte").equals("2014-11-01"),"zonder titel, veld 4a onleesbaar: afgifte een dag voor 10 jaar terug");
        r=read("RIJBEWIJS","1. DE VRIES","3. 17.02.1981","4a, 01.11.2014","5. 5094962111");
        check(r.getJSONObject("document").getString("afgifte").equals("2014-11-01")&&!r.getJSONArray("twijfel").toString().contains("afgifte"),"veldnummer met een komma (zo leest de herkenning de punt soms)");
        r=read("RIJBEWIJS","1. DE VRIES","3. 17.02.1981","4a. 01.11.2014","01.11.2024","5. 5094962111");
        check(r.getJSONObject("document").getString("geldig").equals("2024-11-01")&&r.getJSONArray("twijfel").toString().contains("geldig"),"en andersom: geldig tot uit de afgifte");
        r=read("RIJBEWIJS","1. DE VRIES","3. 17.02.1981","4b. 01.11.2024","01.11.2017","5. 5094962111");
        check(!r.getJSONObject("document").has("afgifte"),"een datum die geen 5, 10 of 15 jaar ervoor ligt, wordt geen afgifte");
        r=read("RIJBEWIJS","1. DE VRIES","3. 17.02.1981","4b. 01.11.2024","01.11.2009","5. 5094962111");
        check(!r.getJSONObject("document").has("afgifte"),"15 jaar ervoor (een categoriedatum) wordt geen afgifte");
        r=read("RIJBEWIJS","1. DE VRIES","3. 17.02.1951","4b. 01.11.2024","01.11.2014","01.11.2019","5. 5094962111");
        check(r.getJSONObject("document").getString("afgifte").equals("2014-11-01"),"10 jaar gaat voor 5 jaar");
        Rijbewijs.Stand st=new Rijbewijs.Stand();Rijbewijs.read(card("WINKELBON","TOTAAL 12,50"),"2026-11-08",st);check(st.regels==2&&!st.rijbewijs&&!st.onderregel,"stand: tekst gelezen, geen rijbewijs");
        // Samenvoegen van twee lezingen: wat ontbreekt aanvullen, zeker gaat voor twijfel
        JSONObject a1=read("RIJBEWIJS","1. DE VRIES","3. 17.02.1981","5. 5094962111"),b1=read("RIJBEWIJS","4b. 01.11.2024","3. 17.02.1981","D1NLD150949621115M2Z6KC47X2W71");
        JSONObject m=Rijbewijs.merge(a1,b1);check(m.getJSONObject("document").getString("geldig").equals("2024-11-01")&&m.getJSONObject("persoon").getString("naam").equals("DE VRIES")&&m.getInt("gelezen")>=4,"samengevoegd: naam uit de ene, einddatum uit de andere");
        check(Rijbewijs.merge(null,b1)==b1&&Rijbewijs.merge(a1,null)==a1,"samenvoegen met niets");
        System.out.println("RijbewijsTest OK");
    }
}
