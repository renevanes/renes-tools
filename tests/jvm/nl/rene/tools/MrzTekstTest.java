package nl.rene.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.json.JSONObject;

/** De strook lezen uit de tekst van de gewone tekstherkenning (MrzTekst): fouten van de tekstherkenning rechtzetten, nooit gokken. Verzonnen gegevens. */
public class MrzTekstTest {
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    static final String R1="P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<",R2="L898902C36UTO7408122F1204159ZE184226B<<<<<10";
    static Mrz.Result lees(String... regels){return MrzTekst.beste(Arrays.asList(regels),new MrzTekst.Telling());}
    static boolean zelfde(Mrz.Result r){return r!=null&&r.line(0).equals(R1)&&r.line(1).equals(R2);}

    /** Een verzonnen Nederlands paspoort met kloppende controlecijfers. */
    static String[] nl(){
        String doc="NXC4R7K12",geb="850315",verv="310728";
        String r2=doc+Mrz.digit(doc)+"NLD"+geb+Mrz.digit(geb)+"F"+verv+Mrz.digit(verv)+"<<<<<<<<<<<<<<"+"<";
        String heel=r2.substring(0,10)+r2.substring(13,20)+r2.substring(21,43);
        r2=r2+Mrz.digit(heel);
        return new String[]{"P<NLDDE<VRIES<<JOHANNA<MARIA<<<<<<<<<<<<<<<<",r2};
    }

    public static void main(String[] args)throws Exception{
        // Precies gelezen: meteen goed en zeker
        Mrz.Result r=lees("Republiek der Nederlanden",R1,R2,"handtekening");
        check(zelfde(r)&&r.sure(),"een precies gelezen strook is zeker");
        check(nl()[0].length()==44&&nl()[1].length()==44,"voorbeeldregels zijn 44 tekens");
        check(!r.afwijkend(),"gewone tekstherkenning is geen afwijkend lettertype");

        // Wat de tekstherkenning vaak doet: spaties, « voor <<, een < te veel of te weinig, kleine letters
        r=lees("P< UTO ERIKSSON«ANNA<MARIA <<<<<<<<<<<<<<<<<<<<<","L898902C36 UTO7408122F1204159ZE184226B<<<<<<10");
        check(zelfde(r),"spaties, « en een < te veel worden rechtgezet: "+(r==null?"niets":r.line(0)+" / "+r.line(1)));
        r=lees(R1.substring(0,40),R2.replace("B<<<<<1","B<<<<1"));
        check(zelfde(r),"te korte regels krijgen < bij in de langste rij");
        r=lees(R1.toLowerCase(),R2);
        check(zelfde(r),"kleine letters tellen als hoofdletters");

        // O voor 0 in een cijferveld: verbeterd; < gelezen als K tussen << : rechtgezet
        r=lees(R1.replace("<<<<<<<<<<<<<<<<<<<","<<<<<<<K<<<<<<<<<<<"),R2.replace("7408122","74O8122"));
        check(zelfde(r),"O in een cijferveld en een losse K tussen << worden rechtgezet");
        check(r.usable()&&r.plausibel(),"bruikbaar en echte datums");

        // Een verzonnen Nederlands paspoort, gelezen met een paar typische fouten
        String[] p=nl();
        r=lees(p[0].replace("<<JOHANNA","«JOHANNA"),p[1].replace("<<<<<<<<<<<<<<","<<<<<<<<<<<<<").replace("850315","85O3I5"));
        check(r!=null&&r.line(1).equals(p[1]),"Nederlands paspoort met fouten van de tekstherkenning: "+(r==null?"niets":r.line(1)));
        JSONObject f=Mrz.fields(r,"2026-11-26");
        check("NXC4R7K12".equals(f.getJSONObject("document").getString("document")),"documentnummer");
        check("1985-03-15".equals(f.getJSONObject("persoon").getString("geboortedatum")),"geboortedatum");
        check("2031-07-28".equals(f.getJSONObject("document").getString("geldig")),"geldig tot");
        check("paspoort".equals(f.getJSONObject("document").getString("documentsoort")),"documentsoort");
        check("De Vries".equals(f.getJSONObject("persoon").optString("naam"))||f.getJSONObject("persoon").optString("naam").toLowerCase().contains("vries"),"naam: "+f.getJSONObject("persoon").optString("naam"));

        // Regels die op de foto over elkaar in losse stukken staan, worden één regel
        List<MrzTekst.Stuk> stukken=new ArrayList<>();
        stukken.add(new MrzTekst.Stuk(R2.substring(20),500,210,900,240));
        stukken.add(new MrzTekst.Stuk(R1,0,150,900,180));
        stukken.add(new MrzTekst.Stuk(R2.substring(0,20),0,212,480,242));
        check(MrzTekst.regels(stukken).equals(Arrays.asList(R1,R2)),"stukken op dezelfde hoogte worden één regel, van boven naar onder");

        // Nooit gokken: een onbekend teken in een cijferveld blijft onbekend, en dan klopt het controlecijfer niet
        r=lees(R1,R2.replace("7408122","74#8122"));
        check(r==null||!r.birthOk||r.line(1).equals(R2)&&r.repaired.contains("birth"),"een # wordt geen willekeurig cijfer");
        check(lees(R1,R2.replace("7408122","7408?22").replace('?','X'))==null,"een X in een cijferveld wordt niet gegokt");
        // Een verkeerd controlecijfer zonder twijfel: niet bruikbaar
        // Een ander controlecijfer: hooguit een verbetering met een verwisseling (O/0, C/<), en dan in twijfel (niet vooraf aangevinkt)
        r=lees(R1,R2.replace("C36UTO","C35UTO"));
        check(r==null||r.repaired.contains("doc")&&!r.sure()&&Mrz.fields(r,"2026-11-26").getJSONArray("twijfel").toString().contains("document"),"een ander controlecijfer: documentnummer in twijfel");
        String vast="Y393939X3";int fout=(Mrz.digit(vast)+3)%10;
        check(lees(R1,vast+fout+R2.substring(10))==null,"zonder verwisselbare tekens geen verbetering");
        // Geen paspoortcode of geen echte datum: niet bruikbaar
        String[] q=nl();q[0]="V"+q[0].substring(1);
        check(lees(q)==null,"documentcode V (visum) wordt geen paspoort");
        // De P weggevallen: wel herkend, maar namen en land zijn een voorstel
        q=nl();r=lees(q[0].substring(1),q[1]);
        check(r!=null&&r.line(1).equals(q[1])&&r.naamTwijfel&&!r.sure(),"zonder P: herkend, namen in twijfel");

        // Voornamen van letters die ook een gelezen < kunnen zijn (KEES, CEES) blijven staan; rommel na de namen niet
        String kees="P<NLDDE<VRIES<<JAN<KEES<<<<<<<<<<<<<<<<<<<<<<";kees=kees.substring(0,44);
        check(MrzTekst.naam(kees,5).equals(kees),"KEES blijft: "+MrzTekst.naam(kees,5));
        String cees="P<NLDJANSEN<<CEES<<<<<<<<<<<<<<<<<<<<<<<<<<<<".substring(0,44);
        check(MrzTekst.naam(cees,5).equals(cees),"CEES blijft");
        String gr="P<NLDS<GRAVESANDE<<PIETER<<<<<<<<<<<<<<<<<<<<";gr=gr.substring(0,44);
        check(MrzTekst.naam(gr,5).equals(gr),"S<GRAVESANDE blijft");
        String vgr="P<NLDVAN<S<GRAVESANDE<<PIETER<<<<<<<<<<<<<<<<";
        check(MrzTekst.naam(vgr,5).equals(vgr),"VAN<S<GRAVESANDE blijft");
        String jm="P<NLDDE<VRIES<<JOHANNA<MARIA";
        check(MrzTekst.naam(jm+"<C<<<<KKKKKKKEKKK",5).equals(jm+Mrz.rep('<',17)),"losse C en KKK na de namen worden <");
        check(MrzTekst.naam(jm+"<<<KS5565E55KKS<",5).equals(jm+Mrz.rep('<',16)),"na <<< alleen nog <");
        q=nl();q[0]="P<NLDDE<VRIES<<JAN<KEES<<<<<<<<<<<<<<<<<<<<<<".substring(0,44);
        r=lees(q);
        check(r!=null&&r.sure()&&"Jan Kees".equals(Mrz.fields(r,"2026-11-26").getJSONObject("persoon").getString("voornamen")),"Jan Kees blijft zeker en heel");

        // Een verwisseling die het controlecijfer niet ziet (G/6, L/1, K/<): bij één lezing is het documentnummer een voorstel,
        // twee lezingen die het eens zijn bevestigen het
        String doc="NX61R7K12",geb="850315",verv="310728",r2=doc+Mrz.digit(doc)+"NLD"+geb+Mrz.digit(geb)+"F"+verv+Mrz.digit(verv)+"<<<<<<<<<<<<<<<";
        r2=r2+Mrz.digit(r2.substring(0,10)+r2.substring(13,20)+r2.substring(21,43));
        Mrz.Result goed=lees(nl()[0],r2),verwisseld=lees(nl()[0],r2.replace("NX61","NXG1"));
        check(goed!=null&&verwisseld!=null&&Mrz.digit("NXG1R7K12")==Mrz.digit(doc),"G/6 verandert het controlecijfer niet");
        Mrz.Result eenmaal=MrzTekst.kies(new ArrayList<>(Arrays.asList(verwisseld)));
        check(eenmaal.repaired.contains("doc")&&!eenmaal.sure(),"één lezing met G of 6 in het nummer: in twijfel");
        Mrz.Result oneens=MrzTekst.kies(new ArrayList<>(Arrays.asList(lees(nl()[0],r2.replace("NX61","NXG1")),lees(nl()[0],r2))));
        check(oneens.repaired.contains("doc"),"twee lezingen die verschillen: in twijfel");
        Mrz.Result eens=MrzTekst.kies(new ArrayList<>(Arrays.asList(lees(nl()[0],r2),lees(nl()[0],r2))));
        check(eens.sure()&&"NX61R7K12".equals(Mrz.fields(eens,"2026-11-26").getJSONObject("document").getString("document")),"twee lezingen die het eens zijn: zeker");
        check(MrzTekst.bevestigd(Arrays.asList(lees(nl()[0],r2),lees(nl()[0],r2)))&&!MrzTekst.bevestigd(Arrays.asList(goed,verwisseld)),"bevestigd alleen bij hetzelfde nummer");

        // ID-kaart (TD1, drie regels van 30), ook met fouten van de tekstherkenning
        String[] td1={"I<UTOD231458907<<<<<<<<<<<<<<<","7408122F1204159UTO<<<<<<<<<<<6","ERIKSSON<<ANNA<MARIA<<<<<<<<<<"};
        r=lees(td1);
        check(r!=null&&r.format==Mrz.TD1&&r.sure(),"ID-kaart precies gelezen: zeker");
        r=lees("I<UTOD23145890 7«<<<<<<<<<<<<","74O8122F1204159UTO<<<<<<<<<<<6","ERIKSSON«ANNA<MARIA<<<<<<<<<<<KK");
        check(r!=null&&r.format==Mrz.TD1&&"D23145890".equals(Mrz.fields(r,"2026-11-26").getJSONObject("document").getString("document")),"ID-kaart met fouten: herkend");

        // Twee regels onder elkaar die op een strook lijken: paar; een losse regel (zoals op een rijbewijs) niet
        MrzTekst.Telling tp=new MrzTekst.Telling();MrzTekst.beste(Arrays.asList("NAAM","4a. 01.02.2015 4b. 01.02.2025 <<","iets"),tp);
        check(!tp.paar,"een losse regel is geen paar");
        tp=new MrzTekst.Telling();MrzTekst.beste(Arrays.asList(R1,R2.replace('7','#')),tp);
        check(tp.paar,"twee strookregels onder elkaar zijn een paar");
        // Stoppen als de tijd op is
        check(MrzTekst.beste(Arrays.asList(R1,R2),new MrzTekst.Telling(),()->false)==null,"tijd op: niets");

        // Willekeurige regels van de juiste lengte: nooit een strook
        Random rnd=new Random(7);int vals=0;String abc=Mrz.ALPHABET;
        for(int n=0;n<3000;n++){
            StringBuilder a=new StringBuilder("P<"),b=new StringBuilder();
            for(int i=2;i<44;i++)a.append(abc.charAt(rnd.nextInt(abc.length())));
            for(int i=0;i<44;i++)b.append(abc.charAt(rnd.nextInt(abc.length())));
            if(lees(a.toString(),b.toString())!=null)vals++;
        }
        check(vals==0,"willekeurige regels gaven "+vals+" keer een strook");

        // Andere tekst op de pagina telt niet als kandidaat; de telling heeft alleen aantallen
        MrzTekst.Telling t=new MrzTekst.Telling();
        check(MrzTekst.beste(Arrays.asList("Nationaliteit / Nationality","Nederlandse","Geboortedatum 15 MAA/MAR 1985"),t)==null&&t.kandidaten==0,"gewone tekst is geen strook");
        System.out.println("MrzTekstTest: alles goed ("+vals+" vals van 3000 willekeurige)");
    }
}
