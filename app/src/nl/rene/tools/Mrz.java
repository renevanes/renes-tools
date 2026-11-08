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
    static final char D='9',A='A',X='*',C='c'; // soort per positie: cijfer, letter, beide, controlecijfer (cijfer of <)

    /** Per positie de kandidaten van de herkenning, beste eerst, met hun kosten (lager is beter). */
    static final class Pos {
        final char[] chars;final double[] cost;
        Pos(char[] chars,double[] cost){this.chars=chars;this.cost=cost;}
        static Pos sure(char c){return new Pos(new char[]{c},new double[]{0});}
        static Pos unknown(){char[] c=ALPHABET.toCharArray();double[] k=new double[c.length];Arrays.fill(k,1);return new Pos(c,k);}
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
            rep(D,6)+"c"+"A"+rep(D,6)+"c"+"AAA"+rep(X,11)+"c",
            rep(A,30)},
            new Check(new int[][]{{0,5,14}},0,14,false),new Check(new int[][]{{1,0,6}},1,6,false),new Check(new int[][]{{1,8,14}},1,14,false),
            new Check(new int[][]{{0,5,30},{1,0,7},{1,8,15},{1,18,29}},1,29,false),new ArrayList<>());
    static final Format TD2=new Format("TD2",2,36,new String[]{
            "AA"+"AAA"+rep(A,31),
            rep(X,9)+"c"+"AAA"+rep(D,6)+"c"+"A"+rep(D,6)+"c"+rep(X,7)+"c"},
            new Check(new int[][]{{1,0,9}},1,9,false),new Check(new int[][]{{1,13,19}},1,19,false),new Check(new int[][]{{1,21,27}},1,27,false),
            new Check(new int[][]{{1,0,10},{1,13,20},{1,21,35}},1,35,false),new ArrayList<>());
    static final Format TD3=new Format("TD3",2,44,new String[]{
            "AA"+"AAA"+rep(A,39),
            rep(X,9)+"c"+"AAA"+rep(D,6)+"c"+"A"+rep(D,6)+"c"+rep(X,14)+"c"+"c"},
            new Check(new int[][]{{1,0,9}},1,9,false),new Check(new int[][]{{1,13,19}},1,19,false),new Check(new int[][]{{1,21,27}},1,27,false),
            new Check(new int[][]{{1,0,10},{1,13,20},{1,21,43}},1,43,false),
            new ArrayList<>(Arrays.asList(new Check(new int[][]{{1,28,42}},1,42,true))));
    static final Format[] FORMATS={TD1,TD2,TD3};
    static Format format(int lines,int length){for(Format f:FORMATS)if(f.lines==lines&&f.length==length)return f;return null;}

    static int value(char c){if(c>='0'&&c<='9')return c-'0';if(c>='A'&&c<='Z')return c-'A'+10;return 0;}
    static int digit(String s){int[] w={7,3,1};int sum=0;for(int i=0;i<s.length();i++)sum+=value(s.charAt(i))*w[i%3];return sum%10;}
    static String field(char[][] t,Check c){StringBuilder b=new StringBuilder();for(int[] p:c.parts)b.append(t[p[0]],p[1],p[2]-p[1]);return b.toString();}
    static boolean ok(char[][] t,Check c){
        char d=t[c.line][c.at];String f=field(t,c);
        if(d=='<')return c.optional&&f.replace("<","").isEmpty()||c.optional&&digit(f)==0;
        return d>='0'&&d<='9'&&digit(f)==d-'0';
    }
    static boolean allowed(char type,char c){
        if(type==D)return c>='0'&&c<='9';
        if(type==C)return c>='0'&&c<='9'||c=='<';
        if(type==A)return c>='A'&&c<='Z'||c=='<';
        return true;
    }
    /** Kandidaten van één positie beperkt tot wat daar mag staan; leeg = alles toegestaan als onbekend. */
    static Pos restrict(Pos p,char type){
        List<Character> cs=new ArrayList<>();List<Double> ks=new ArrayList<>();
        for(int i=0;i<p.chars.length;i++)if(allowed(type,p.chars[i])){cs.add(p.chars[i]);ks.add(p.cost[i]);}
        if(cs.isEmpty())for(char c:ALPHABET.toCharArray())if(allowed(type,c)){cs.add(c);ks.add(2.0);}
        char[] c=new char[cs.size()];double[] k=new double[cs.size()];for(int i=0;i<c.length;i++){c[i]=cs.get(i);k[i]=ks.get(i);}
        return new Pos(c,k);
    }

    static final class Result {
        final Format format;final char[][] text;final boolean docOk,birthOk,expiryOk,compositeOk;final double cost,mean;
        Result(Format format,char[][] text,double cost){
            this.format=format;this.text=text;this.cost=cost;this.mean=cost/(format.lines*format.length);
            docOk=ok(text,format.doc);birthOk=ok(text,format.birth);expiryOk=ok(text,format.expiry);compositeOk=ok(text,format.composite);
        }
        int valid(){return (docOk?1:0)+(birthOk?1:0)+(expiryOk?1:0)+(compositeOk?1:0);}
        /** Gemiddelde afwijking per teken waarboven een lezing te onzeker is (gemeten op de testbeelden: goed 2–6, onzin 15+). */
        static final double LIMIT=10;
        /** Bruikbaar: de tekens lijken echt op OCR-B en documentnummer, geboortedatum en vervaldatum kloppen met hun controlecijfer. */
        boolean usable(){return mean<LIMIT&&docOk&&birthOk&&expiryOk;}
        boolean sure(){return usable()&&compositeOk;}
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
        for(Check c:checks)cost+=repair(t,r,c);
        cost+=repair(t,r,f.composite);
        return new Result(f,t,cost);
    }
    /** Probeert tot twee tekens te vervangen door een andere kandidaat zodat het controlecijfer klopt; geeft de extra kosten. */
    static double repair(char[][] t,Pos[][] r,Check c){
        if(ok(t,c))return 0;
        List<int[]> spots=new ArrayList<>();
        for(int[] p:c.parts)for(int i=p[1];i<p[2];i++)spots.add(new int[]{p[0],i});
        spots.add(new int[]{c.line,c.at});
        // Alleen de onzekerste posities: kleinste verschil tussen eerste en tweede keus.
        spots.sort((a,b)->Double.compare(margin(r[a[0]][a[1]]),margin(r[b[0]][b[1]])));
        double best=Double.MAX_VALUE;int[] bs=null;char[] bc=null;
        int n=Math.min(spots.size(),40);
        for(int i=0;i<n;i++){
            int[] s=spots.get(i);Pos p=r[s[0]][s[1]];char old=t[s[0]][s[1]];
            for(int k=1;k<Math.min(p.chars.length,6);k++){
                t[s[0]][s[1]]=p.chars[k];double extra=p.cost[k]-p.cost[0];
                if(extra<best&&ok(t,c)){best=extra;bs=new int[]{i};bc=new char[]{p.chars[k]};}
            }
            t[s[0]][s[1]]=old;
        }
        int m=Math.min(spots.size(),10);
        for(int i=0;i<m;i++)for(int j=i+1;j<m;j++){
            int[] s1=spots.get(i),s2=spots.get(j);Pos p1=r[s1[0]][s1[1]],p2=r[s2[0]][s2[1]];char o1=t[s1[0]][s1[1]],o2=t[s2[0]][s2[1]];
            for(int a=1;a<Math.min(p1.chars.length,4);a++)for(int b=1;b<Math.min(p2.chars.length,4);b++){
                double extra=p1.cost[a]-p1.cost[0]+p2.cost[b]-p2.cost[0];if(extra>=best)continue;
                t[s1[0]][s1[1]]=p1.chars[a];t[s2[0]][s2[1]]=p2.chars[b];
                if(ok(t,c)){best=extra;bs=new int[]{i,j};bc=new char[]{p1.chars[a],p2.chars[b]};}
            }
            t[s1[0]][s1[1]]=o1;t[s2[0]][s2[1]]=o2;
        }
        if(bs==null)return 0;
        for(int i=0;i<bs.length;i++){int[] s=spots.get(bs[i]);t[s[0]][s[1]]=bc[i];}
        return best;
    }
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
        StringBuilder b=new StringBuilder();
        for(String w:clean(upper).toLowerCase(java.util.Locale.ROOT).split(" ")){
            if(w.isEmpty())continue;if(b.length()>0)b.append(' ');
            if(particles&&PARTICLES.contains(w)){b.append(w);continue;}
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }
    /** Geboortedatum: een jaar in de toekomst hoort bij de vorige eeuw. */
    static String birth(String yymmdd,int thisYear){
        if(!yymmdd.matches("[0-9]{6}"))return "";int yy=Integer.parseInt(yymmdd.substring(0,2)),y=2000+yy;if(y>thisYear)y-=100;
        String s=y+"-"+yymmdd.substring(2,4)+"-"+yymmdd.substring(4,6);return MasterSchema.validDate(s)?s:"";
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
    static JSONObject fields(Result r,int thisYear)throws Exception{
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
        doc.put("documentsoort",code.charAt(0)=='P'?"paspoort":"identiteitskaart");
        if(r.docOk)doc.put("document",clean(docnum).replace(" ",""));
        if(r.expiryOk){String e=expiry(exp);if(!e.isEmpty())doc.put("geldig",e);}
        String land=country(state,1);if(!land.isEmpty())doc.put("uitgifteland",land);
        int split=names.indexOf("<<");String surname=split<0?names:names.substring(0,split),given=split<0?"":names.substring(split+2);
        if(!clean(surname).isEmpty())person.put("naam",words(surname,true));
        if(!clean(given).isEmpty())person.put("voornamen",words(given,false));
        if(r.birthOk){String b=birth(birth,thisYear);if(!b.isEmpty())person.put("geboortedatum",b);}
        person.put("geslacht","M".equals(sex)?"M":"F".equals(sex)?"V":"X");
        String n=country(nat,2);if(!n.isEmpty())person.put("nationaliteit",n);
        String number=clean(optional).replace(" ","");if(bsn(number)&&r.compositeOk)person.put("bsn",number);
        return new JSONObject().put("document",doc).put("persoon",person).put("soort",f.name).put("zeker",r.compositeOk);
    }
}
