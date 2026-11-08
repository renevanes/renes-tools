package nl.rene.tools;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import javax.imageio.ImageIO;
import org.json.JSONObject;

/** Strook (MRZ) lezen: controlecijfers, ontleden, verbeteren en herkennen in foto's (tests/jvm/mrz, verzonnen gegevens). */
public class MrzTest {
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    static Mrz.Result photo(String name)throws Exception{
        BufferedImage im=ImageIO.read(new File("tests/jvm/mrz/"+name));int w=im.getWidth(),h=im.getHeight();int[] p=new int[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int c=im.getRGB(x,y);p[y*w+x]=((c>>16&255)*30+(c>>8&255)*59+(c&255)*11)/100;}
        long t=System.nanoTime();Mrz.Result r=MrzOcr.read(p,w,h);System.out.println("  "+name+": "+(System.nanoTime()-t)/1_000_000+" ms"+(r==null?" (niets)":" "+r.format.name+" geldig "+r.valid()+"/4, afwijking "+String.format("%.1f",r.mean)));
        return r;
    }
    public static void main(String[] args)throws Exception{
        // ICAO 9303 deel 5 en 4: de voorbeelden uit de norm
        Mrz.Result td1=Mrz.parse("I<UTOD231458907<<<<<<<<<<<<<<<","7408122F1204159UTO<<<<<<<<<<<6","ERIKSSON<<ANNA<MARIA<<<<<<<<<<");
        check(td1!=null&&td1.usable()&&td1.compositeOk,"TD1-voorbeeld klopt");
        Mrz.Result td3=Mrz.parse("P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<","L898902C36UTO7408122F1204159ZE184226B<<<<<10");
        check(td3!=null&&td3.usable()&&td3.compositeOk,"TD3-voorbeeld klopt");
        check(Mrz.digit("D23145890")==7&&Mrz.digit("740812")==2&&Mrz.digit("120415")==9,"controlecijfers 7-3-1");
        JSONObject f=Mrz.fields(td1,2026);JSONObject doc=f.getJSONObject("document"),per=f.getJSONObject("persoon");
        check("D23145890".equals(doc.getString("document"))&&"2012-04-15".equals(doc.getString("geldig"))&&"identiteitskaart".equals(doc.getString("documentsoort")),"documentvelden TD1");
        check("Eriksson".equals(per.getString("naam"))&&"Anna Maria".equals(per.getString("voornamen"))&&"1974-08-12".equals(per.getString("geboortedatum"))&&"V".equals(per.getString("geslacht")),"persoonsvelden TD1");
        check("paspoort".equals(Mrz.fields(td3,2026).getJSONObject("document").getString("documentsoort")),"paspoort herkend");
        check(Mrz.parse("I<UTOD231458908<<<<<<<<<<<<<<<","7408122F1204159UTO<<<<<<<<<<<6","ERIKSSON<<ANNA<MARIA<<<<<<<<<<").docOk==false,"fout controlecijfer gezien");
        // Eeuw van de geboortedatum, achternaam met tussenvoegsel, BSN alleen met 11-proef
        check("2015-02-03".equals(Mrz.birth("150203",2026))&&"1980-01-01".equals(Mrz.birth("800101",2026))&&"".equals(Mrz.birth("991340",2026)),"geboortedatum");
        check("de Vries".equals(Mrz.words("DE<VRIES",true))&&"van der Berg".equals(Mrz.words("VAN<DER<BERG",true))&&"Willem Jan".equals(Mrz.words("WILLEM<JAN<<<",false)),"namen");
        check(Mrz.bsn("111222333")&&!Mrz.bsn("123456789")&&!Mrz.bsn("12345678"),"11-proef");
        check("Nederland".equals(Mrz.country("NLD",1))&&"Nederlandse".equals(Mrz.country("NLD",2))&&"UTO".equals(Mrz.country("UTO",2)),"landen");
        // Verbeteren: een twijfelgeval (8 of B) wordt door het controlecijfer beslist
        String[] l={"I<UTOD231458907<<<<<<<<<<<<<<<","7408122F1204159UTO<<<<<<<<<<<6","ERIKSSON<<ANNA<MARIA<<<<<<<<<<"};
        Mrz.Pos[][] p=new Mrz.Pos[3][30];for(int i=0;i<3;i++)for(int j=0;j<30;j++)p[i][j]=Mrz.Pos.sure(l[i].charAt(j));
        p[0][11]=new Mrz.Pos(new char[]{'B','8'},new double[]{1.0,1.2});
        Mrz.Result fixed=Mrz.decode(Mrz.TD1,p);check(fixed.usable()&&fixed.line(0).equals(l[0]),"documentnummer verbeterd met de tweede keus");
        p[1][3]=new Mrz.Pos(new char[]{'O','8'},new double[]{1.0,1.3});check(Mrz.decode(Mrz.TD1,p).line(1).equals(l[1]),"op een cijferpositie geen letter");

        // Herkennen in testbeelden (gemaakt met tools/mrz/MrzSjablonen.java)
        List<String> want=Files.readAllLines(new File("tests/jvm/mrz/verwacht.txt").toPath());
        for(String name:new String[]{"td1-recht.jpg","td1-scheef.jpg","td1-klein.jpg","td1-gedraaid.jpg","td1-ondersteboven.jpg"}){
            Mrz.Result r=photo(name);check(r!=null&&r.format==Mrz.TD1&&r.usable()&&r.compositeOk,name+": strook gelezen");
            for(int i=0;i<3;i++)check(r.line(i).equals(want.get(i)),name+" regel "+(i+1)+": "+r.line(i));
            JSONObject g=Mrz.fields(r,2026);
            check("Jansen".equals(g.getJSONObject("persoon").getString("naam"))&&"Zoe Anna".equals(g.getJSONObject("persoon").getString("voornamen"))&&"NX7P41KD9".equals(g.getJSONObject("document").getString("document"))&&"2031-06-11".equals(g.getJSONObject("document").getString("geldig")),name+": velden");
        }
        Mrz.Result r=photo("td3-paspoort.jpg");check(r!=null&&r.format==Mrz.TD3&&r.usable()&&r.compositeOk,"paspoort gelezen");
        check(r.line(0).equals(want.get(4))&&r.line(1).equals(want.get(5)),"paspoortregels: "+r.line(0)+" / "+r.line(1));
        JSONObject g=Mrz.fields(r,2026);check("de Vries".equals(g.getJSONObject("persoon").getString("naam"))&&"M".equals(g.getJSONObject("persoon").getString("geslacht"))&&"Nederlandse".equals(g.getJSONObject("persoon").getString("nationaliteit"))&&!g.getJSONObject("persoon").has("bsn"),"paspoortvelden");
        Mrz.Result none=photo("zonder-strook.jpg");check(none==null||!none.usable(),"zonder strook niets bruikbaars");
        System.out.println("Strook lezen: controlecijfers, ontleden, verbeteren en herkennen in foto's gecontroleerd");
    }
}
