package nl.rene.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;

/**
 * De machineleesbare strook (MRZ, ICAO 9303) van een identiteitskaart (TD1: 3 regels van 30), een kleiner document
 * (TD2: 2 × 36) of een paspoort (TD3: 2 × 44): ontleden, controlecijfers nakijken en twijfelachtige tekens verbeteren
 * met de tweede keus van de tekenherkenning. Zuiver: geen Android, getest in MrzTest.
 */
final class Mrz {
    private Mrz(){ }
    static final String ALPHABET="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ<";
    static final char D='9',A='A',X='*',C='c',S='s'; // soort per positie: cijfer, letter, beide, controlecijfer (cijfer of <), geslacht (M, F, X of <)
    /** Een positie die niet gelezen is (teken ontbreekt): hoort bij geen enkele controle, en wordt nooit "gegokt". */
    static final char MISSING='?';

    /** Per positie de kandidaten van de herkenning, beste eerst, met hun kosten (lager is beter). */
    static final class Pos {
        final char[] chars;final double[] cost;
        Pos(char[] chars,double[] cost){this.chars=chars;this.cost=cost;}
        static Pos sure(char c){return new Pos(new char[]{c},new double[]{0});}
        static Pos unknown(){return new Pos(new char[]{MISSING},new double[]{Result.LIMIT*2});}
    }
    /** Een gecontroleerd veld: posities (regel, van, tot exclusief) en de plek van het controlecijfer. */
    static final class Check {
        final int[][] parts;final int line,at;final boolean optional;
        Check(int[][] parts,int line,int at,boolean optional){this.parts=parts;this.line=line;this.at=at;this.optional=optional;}
    }
    static final class Format {
        final String name;final int lines,length;final String[] types;final Check doc,birth,expiry,composite;final List<Check> extra;
        Format(String name,int lines,int length,String[] types,Check doc,Check birth,Check expiry,Check composite,List<Check> extra){
            this.name=name;this.lines=lines;this.length=length;this.types=types;this.doc=doc;this.birth=birth;this.expiry=expiry;this.composite=composite;this.extra=extra;
        }
    }
    static String rep(char c,int n){char[] a=new char[n];Arrays.fill(a,c);return new String(a);}
    static final Format TD1=new Format("TD1",3,30,new String[]{
            "AA"+"AAA"+rep(X,9)+"c"+rep(X,15),
            rep(D,6)+"c"+"s"+rep(D,6)+"c"+"AAA"+rep(X,11)+"c",
            rep(A,30)},
            new Check(new int[][]{{0,5,14}},0,14,false),new Check(new int[][]{{1,0,6}},1,6,false),new Check(new int[][]{{1,8,14}},1,14,false),
            new Check(new int[][]{{0,5,30},{1,0,7},{1,8,15},{1,18,29}},1,29,false),new ArrayList<>());
    static final Format TD2=new Format("TD2",2,36,new String[]{
            "AA"+"AAA"+rep(A,31),
            rep(X,9)+"c"+"AAA"+rep(D,6)+"c"+"s"+rep(D,6)+"c"+rep(X,7)+"c"},
            new Check(new int[][]{{1,0,9}},1,9,false),new Check(new int[][]{{1,13,19}},1,19,false),new Check(new int[][]{{1,21,27}},1,27,false),
            new Check(new int[][]{{1,0,10},{1,13,20},{1,21,35}},1,35,false),new ArrayList<>());
    static final Format TD3=new Format("TD3",2,44,new String[]{
            "AA"+"AAA"+rep(A,39),
            rep(X,9)+"c"+"AAA"+rep(D,6)+"c"+"s"+rep(D,6)+"c"+rep(X,14)+"c"+"c"},
            new Check(new int[][]{{1,0,9}},1,9,false),new Check(new int[][]{{1,13,19}},1,19,false),new Check(new int[][]{{1,21,27}},1,27,false),
            new Check(new int[][]{{1,0,10},{1,13,20},{1,21,43}},1,43,false),
            new ArrayList<>(Arrays.asList(new Check(new int[][]{{1,28,42}},1,42,true))));
    static final Format[] FORMATS={TD1,TD2,TD3};
    static Format format(int lines,int length){for(Format f:FORMATS)if(f.lines==lines&&f.length==length)return f;return null;}

    static int value(char c){if(c>='0'&&c<='9')return c-'0';if(c>='A'&&c<='Z')return c-'A'+10;return 0;}
    static int digit(String s){int[] w={7,3,1};int sum=0;for(int i=0;i<s.length();i++)sum+=value(s.charAt(i))*w[i%3];return sum%10;}
    static String field(char[][] t,Check c){StringBuilder b=new StringBuilder();for(int[] p:c.parts)b.append(t[p[0]],p[1],p[2]-p[1]);return b.toString();}
    static boolean ok(char[][] t,Check c){
        char d=t[c.line][c.at];String f=field(t,c);if(d==MISSING||f.indexOf(MISSING)>=0)return false;
        if(d=='<')return c.optional&&f.replace("<","").isEmpty()||c.optional&&digit(f)==0;
        return d>='0'&&d<='9'&&digit(f)==d-'0';
    }
    static boolean allowed(char type,char c){
        if(type==D)return c>='0'&&c<='9';
        if(type==C)return c>='0'&&c<='9'||c=='<';
        if(type==A)return c>='A'&&c<='Z'||c=='<';
        if(type==S)return c=='M'||c=='F'||c=='X'||c=='<';
        return c!=MISSING;
    }
    /** Kandidaten van één positie beperkt tot wat daar mag staan; leeg = alles toegestaan als onbekend. */
    static Pos restrict(Pos p,char type){
        List<Character> cs=new ArrayList<>();List<Double> ks=new ArrayList<>();
        if(p.chars.length==1&&p.chars[0]==MISSING)return p;
        for(int i=0;i<p.chars.length;i++)if(allowed(type,p.chars[i])){cs.add(p.chars[i]);ks.add(p.cost[i]);}
        if(cs.isEmpty())for(char c:ALPHABET.toCharArray())if(allowed(type,c)){cs.add(c);ks.add(Result.LIMIT*2);}
        char[] c=new char[cs.size()];double[] k=new double[cs.size()];for(int i=0;i<c.length;i++){c[i]=cs.get(i);k[i]=ks.get(i);}
        return new Pos(c,k);
    }

    static final class Result {
        final Format format;final char[][] text;final boolean docOk,birthOk,expiryOk,compositeOk,extraOk;final double cost,mean;
        /** Velden waar een teken met de tweede keus is verbeterd ("doc", "birth", "expiry", "extra", "composite"): niet zeker. */
        final java.util.Set<String> repaired;
        Result(Format format,char[][] text,double cost,java.util.Set<String> repaired){
            this.format=format;this.text=text;this.cost=cost;this.mean=cost/(format.lines*format.length);this.repaired=repaired;
            docOk=ok(text,format.doc);birthOk=ok(text,format.birth);expiryOk=ok(text,format.expiry);compositeOk=ok(text,format.composite);
            boolean e=true;for(Check c:format.extra)e&=ok(text,c);extraOk=e;
        }
        int valid(){return (docOk?1:0)+(birthOk?1:0)+(expiryOk?1:0)+(compositeOk?1:0);}
        /** Gemiddelde afwijking per teken waarboven een lezing te onzeker is (gemeten op de testbeelden: goed 2–6, onzin 15+). */
        static final double LIMIT=10;
        /** Bruikbaar: de tekens lijken echt op OCR-B en documentnummer, geboortedatum en vervaldatum kloppen met hun controlecijfer. */
        boolean usable(){return mean<LIMIT&&docOk&&birthOk&&expiryOk;}
        /** Zeker: alles klopt zonder dat er iets verbeterd hoefde te worden. */
        boolean sure(){return usable()&&compositeOk&&extraOk&&repaired.isEmpty();}
        String line(int i){return new String(text[i]);}
    }

    /** Beste lezing binnen de regels van het formaat, met verbeteringen waar een controlecijfer niet klopt. */
    static Result decode(Format f,Pos[][] lines){
        if(lines.length!=f.lines)return null;
        Pos[][] r=new Pos[f.lines][];
        char[][] t=new char[f.lines][];double cost=0;
        for(int l=0;l<f.lines;l++){
            if(lines[l].length!=f.length)return null;
            r[l]=new Pos[f.length];t[l]=new char[f.length];
            for(int i=0;i<f.length;i++){r[l][i]=restrict(lines[l][i],f.types[l].charAt(i));t[l][i]=r[l][i].chars[0];cost+=r[l][i].cost[0];}
        }
        List<Check> checks=new ArrayList<>(Arrays.asList(f.doc,f.birth,f.expiry));checks.addAll(f.extra);
        String[] names={"doc","birth","expiry","extra"};java.util.Set<String> repaired=new java.util.HashSet<>();
        for(int i=0;i<checks.size();i++){double extra=repair(t,r,checks.get(i),java.util.Collections.<Check>emptyList());if(extra>0){cost+=extra;repaired.add(names[Math.min(i,3)]);}}
        // De controle over de hele strook mag niets breken wat per veld al klopt.
        List<Check> keep=new ArrayList<>();for(Check c:checks)if(ok(t,c))keep.add(c);
        double extra=repair(t,r,f.composite,keep);if(extra>0){cost+=extra;repaired.add("composite");}
        return new Result(f,t,cost,repaired);
    }
    /** Alleen echte twijfel mag verbeterd worden: de tweede keus scheelt weinig met de eerste. */
    static final double MAX_MARGIN=6,MAX_EXTRA=8;
    /**
     * Probeert één of twee twijfelachtige tekens te vervangen door hun tweede keus zodat het controlecijfer klopt (en de
     * controles in keep blijven kloppen); geeft de extra kosten, 0 als er niets veranderd is. Een ontbrekend teken wordt
     * nooit ingevuld: een controlecijfer is mod 10 en klopt anders bij één op de tien gokken.
     */
    static double repair(char[][] t,Pos[][] r,Check c,List<Check> keep){
        if(ok(t,c))return 0;
        List<int[]> spots=new ArrayList<>();
        for(int[] p:c.parts)for(int i=p[1];i<p[2];i++)spots.add(new int[]{p[0],i});
        spots.add(new int[]{c.line,c.at});
        for(int[] s:spots)if(t[s[0]][s[1]]==MISSING)return 0;
        spots.removeIf(s->margin(r[s[0]][s[1]])>MAX_MARGIN);
        spots.sort((a,b)->Double.compare(margin(r[a[0]][a[1]]),margin(r[b[0]][b[1]])));
        double best=MAX_EXTRA;int[] bs=null;char[] bc=null;
        for(int i=0;i<spots.size();i++){
            int[] s=spots.get(i);Pos p=r[s[0]][s[1]];char old=t[s[0]][s[1]];
            for(int k=1;k<p.chars.length;k++){
                double extra=p.cost[k]-p.cost[0];if(extra>=best||extra>MAX_MARGIN)break;
                t[s[0]][s[1]]=p.chars[k];if(fine(t,c,keep)){best=extra;bs=new int[]{i};bc=new char[]{p.chars[k]};}
            }
            t[s[0]][s[1]]=old;
        }
        int m=Math.min(spots.size(),8);
        for(int i=0;i<m;i++)for(int j=i+1;j<m;j++){
            int[] s1=spots.get(i),s2=spots.get(j);Pos p1=r[s1[0]][s1[1]],p2=r[s2[0]][s2[1]];char o1=t[s1[0]][s1[1]],o2=t[s2[0]][s2[1]];
            for(int a=1;a<Math.min(p1.chars.length,3);a++)for(int b=1;b<Math.min(p2.chars.length,3);b++){
                double e1=p1.cost[a]-p1.cost[0],e2=p2.cost[b]-p2.cost[0];if(e1>MAX_MARGIN||e2>MAX_MARGIN||e1+e2>=best)continue;
                t[s1[0]][s1[1]]=p1.chars[a];t[s2[0]][s2[1]]=p2.chars[b];
                if(fine(t,c,keep)){best=e1+e2;bs=new int[]{i,j};bc=new char[]{p1.chars[a],p2.chars[b]};}
            }
            t[s1[0]][s1[1]]=o1;t[s2[0]][s2[1]]=o2;
        }
        if(bs==null)return 0;
        for(int i=0;i<bs.length;i++){int[] s=spots.get(bs[i]);t[s[0]][s[1]]=bc[i];}
        return Math.max(best,1e-6);
    }
    static boolean fine(char[][] t,Check c,List<Check> keep){if(!ok(t,c))return false;for(Check k:keep)if(!ok(t,k))return false;return true;}
    static double margin(Pos p){return p.chars.length<2?Double.MAX_VALUE:p.cost[1]-p.cost[0];}

    /** Uit tekst (bijvoorbeeld in tests of bij handmatig plakken): regels zonder twijfel. */
    static Result parse(String... lines){
        if(lines.length<2)return null;Format f=format(lines.length,lines[0].length());if(f==null)return null;
        Pos[][] p=new Pos[lines.length][];
        for(int l=0;l<lines.length;l++){String s=lines[l];if(s.length()!=f.length)return null;p[l]=new Pos[s.length()];for(int i=0;i<s.length();i++)p[l][i]=Pos.sure(s.charAt(i));}
        return decode(f,p);
    }

    // ---------- naar stamgegevens ----------
    static String clean(String s){return s.replace('<',' ').trim().replaceAll(" +"," ");}
    static final List<String> PARTICLES=Arrays.asList("van","de","der","den","het","ter","ten","te","in","op","la","le","du","da","di","von","zu","al","el","bin");
    static String words(String upper,boolean particles){
        StringBuilder b=new StringBuilder();String[] ws=clean(upper).toLowerCase(java.util.Locale.ROOT).split(" ");
        for(int i=0;i<ws.length;i++){String w=ws[i];
            if(w.isEmpty())continue;if(b.length()>0)b.append(' ');
            // Tussenvoegsel klein, maar alleen als er nog een woord achter staat ("le" als hele achternaam blijft "Le").
            if(particles&&PARTICLES.contains(w)&&i<ws.length-1){b.append(w);continue;}
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }
    /** Geboortedatum (today = jjjj-mm-dd in de app-zone): een datum na vandaag hoort bij de vorige eeuw. */
    static String birth(String yymmdd,String today){
        if(!yymmdd.matches("[0-9]{6}"))return "";int y=2000+Integer.parseInt(yymmdd.substring(0,2));
        String s=y+"-"+yymmdd.substring(2,4)+"-"+yymmdd.substring(4,6);if(s.compareTo(today)>0)s=(y-100)+s.substring(4);
        return MasterSchema.validDate(s)?s:"";
    }
    static String expiry(String yymmdd){
        if(!yymmdd.matches("[0-9]{6}"))return "";String s="20"+yymmdd.substring(0,2)+"-"+yymmdd.substring(2,4)+"-"+yymmdd.substring(4,6);return MasterSchema.validDate(s)?s:"";
    }
    static boolean bsn(String s){
        if(!s.matches("[0-9]{9}"))return false;int sum=0;for(int i=0;i<8;i++)sum+=(9-i)*(s.charAt(i)-'0');sum-=s.charAt(8)-'0';return sum%11==0&&!s.equals("000000000");
    }
    // taal-vast: gegevens in de stamgegevens staan in het Nederlands, net als wat Rene zelf invult
    static final String[][] COUNTRIES={{"NLD","Nederland","Nederlandse"},{"BEL","België","Belgische"},{"D","Duitsland","Duitse"},{"DEU","Duitsland","Duitse"},
        {"FRA","Frankrijk","Franse"},{"GBR","Verenigd Koninkrijk","Britse"},{"ESP","Spanje","Spaanse"},{"ITA","Italië","Italiaanse"},{"PRT","Portugal","Portugese"},
        {"POL","Polen","Poolse"},{"TUR","Turkije","Turkse"},{"MAR","Marokko","Marokkaanse"},{"SUR","Suriname","Surinaamse"},{"USA","Verenigde Staten","Amerikaanse"},
        {"LUX","Luxemburg","Luxemburgse"},{"AUT","Oostenrijk","Oostenrijkse"},{"CHE","Zwitserland","Zwitserse"},{"IRL","Ierland","Ierse"},{"GRC","Griekenland","Griekse"}};
    static String country(String code,int which){code=clean(code);for(String[] c:COUNTRIES)if(c[0].equals(code))return c[which];return code;}

    /**
     * Velden voor Stamgegevens. Document: documentsoort, document, geldig, uitgifteland. Persoon: naam, voornamen,
     * geboortedatum, geslacht, nationaliteit en, alleen als het er echt staat en de 11-proef klopt, bsn.
     */
    static JSONObject fields(Result r,String today)throws Exception{
        Format f=r.format;JSONObject doc=new JSONObject(),person=new JSONObject();
        String code=new String(r.text[0],0,2),state=new String(r.text[0],2,3),names,docnum,birth,sex,exp,nat,optional;
        if(f==TD1){
            docnum=new String(r.text[0],5,9);optional=new String(r.text[0],15,15);
            birth=new String(r.text[1],0,6);sex=String.valueOf(r.text[1][7]);exp=new String(r.text[1],8,6);nat=new String(r.text[1],15,3);
            names=new String(r.text[2]);
        }else{
            names=new String(r.text[0],5,f.length-5);docnum=new String(r.text[1],0,9);nat=new String(r.text[1],10,3);birth=new String(r.text[1],13,6);
            sex=String.valueOf(r.text[1][20]);exp=new String(r.text[1],21,6);optional=new String(r.text[1],28,f==TD3?14:7);
        }
        // P = paspoort, I/A/C = identiteitskaart; een verblijfsvergunning heeft in Nederland soort IR of RP, de rest is "anders".
        char k=code.charAt(0);doc.put("documentsoort",k=='P'?"paspoort":code.equals("IR")||code.startsWith("R")?"verblijfsdocument":k=='I'||k=='A'||k=='C'?"identiteitskaart":"anders");
        if(r.docOk)doc.put("document",clean(docnum).replace(" ",""));
        if(r.expiryOk){String e=expiry(exp);if(!e.isEmpty())doc.put("geldig",e);}
        String land=country(state,1);if(!land.isEmpty())doc.put("uitgifteland",land);
        int split=names.indexOf("<<");String surname=split<0?names:names.substring(0,split),given=split<0?"":names.substring(split+2);
        // Een naamregel met een ontbrekend teken geven we niet door: daar is geen controlecijfer voor.
        if(names.indexOf(MISSING)<0){
            if(!clean(surname).isEmpty())person.put("naam",words(surname,true));
            if(!clean(given).isEmpty())person.put("voornamen",words(given,false));
        }
        if(r.birthOk){String b=birth(birth,today);if(!b.isEmpty())person.put("geboortedatum",b);}
        if("M".equals(sex)||"F".equals(sex)||"<".equals(sex)||"X".equals(sex))person.put("geslacht","M".equals(sex)?"M":"F".equals(sex)?"V":"X");
        String n=country(nat,2);if(!n.isEmpty())person.put("nationaliteit",n);
        String number=clean(optional).replace(" ","");if(bsn(number)&&r.sure())person.put("bsn",number);
        // Velden die met de tweede keus zijn verbeterd: niet vooraf aanvinken.
        org.json.JSONArray twijfel=new org.json.JSONArray();
        if(r.repaired.contains("doc"))twijfel.put("document");if(r.repaired.contains("birth"))twijfel.put("geboortedatum");if(r.repaired.contains("expiry"))twijfel.put("geldig");
        return new JSONObject().put("document",doc).put("persoon",person).put("soort",f.name).put("zeker",r.sure()).put("twijfel",twijfel);
    }
}
