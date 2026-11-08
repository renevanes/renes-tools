package nl.rene.tools;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;

/**
 * Maakt app/src/nl/rene/tools/MrzGlyphs.java (sjablonen per teken) en de testbeelden in tests/jvm/mrz/.
 * Het OCR-B-lettertype gaat niet mee in de repository; gebruik bijvoorbeeld OCRB.TTF uit github.com/bcssupp0rt/ocrbfont
 * (GPL-3.0). De sjablonen worden gemeten met dezelfde stappen als de herkenning zelf (MrzOcr), zodat ze precies passen.
 *
 *   ./build.sh   (of alleen de Java-classes compileren naar build/classes)
 *   javac -cp build/classes -d build/mrz tools/mrz/MrzSjablonen.java
 *   java -Djava.awt.headless=true -cp build/mrz:build/classes:$TOOLS/ap/android-36/android.jar nl.rene.tools.MrzSjablonen OCRB.TTF
 */
public class MrzSjablonen {
    static Font ocrb;
    static final String ABC=Mrz.ALPHABET;

    public static void main(String[] args)throws Exception{
        ocrb=Font.createFont(Font.TRUETYPE_FONT,new File(args[0]));
        String root=args.length>1?args[1]:".";
        templates(root+"/app/src/nl/rene/tools/MrzGlyphs.java");
        fixtures(root+"/tests/jvm/mrz");
    }

    // ---------- sjablonen ----------
    static int[] gray(BufferedImage im){
        int w=im.getWidth(),h=im.getHeight();int[] p=new int[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int c=im.getRGB(x,y);p[y*w+x]=((c>>16&255)*30+(c>>8&255)*59+(c&255)*11)/100;}
        return p;
    }
    static BufferedImage lines(String[] text,float size,int pad){
        Font f=ocrb.deriveFont(size);BufferedImage probe=new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB);
        int adv=probe.createGraphics().getFontMetrics(f).charWidth('A');int lh=Math.round(size*1.25f);
        int w=pad*2+adv*text[0].length(),h=pad*2+lh*text.length;
        BufferedImage im=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=im.createGraphics();
        g.setColor(Color.WHITE);g.fillRect(0,0,w,h);g.setColor(new Color(25,25,25));g.setFont(f);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        for(int i=0;i<text.length;i++)g.drawString(text[i],pad,pad+lh*i+Math.round(size*0.9f));
        return im;
    }
    /** Dikker (min-filter) of dunner (max-filter) maken: zo lijken drukinkt, onscherpte en belichting minder uit. */
    static int[] morph(int[] p,int w,int h,int mode){
        if(mode==0)return p;int[] o=new int[p.length];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int v=p[y*w+x];
            for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){int nx=x+dx,ny=y+dy;if(nx<0||ny<0||nx>=w||ny>=h)continue;int q=p[ny*w+nx];v=mode==1?Math.min(v,q):Math.max(v,q);}
            o[y*w+x]=v;}
        return o;
    }
    static int[] blur(int[] p,int w,int h){
        int[] o=new int[p.length];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int s=0,n=0;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){int nx=x+dx,ny=y+dy;if(nx<0||ny<0||nx>=w||ny>=h)continue;s+=p[ny*w+nx];n++;}o[y*w+x]=s/n;}
        return o;
    }
    static void templates(String out)throws Exception{
        int n=MrzOcr.GW*MrzOcr.GH,variants=3;double[][] sum=new double[ABC.length()*variants][n];int[] count=new int[ABC.length()*variants];
        Random rnd=new Random(9303);
        for(int variant=0;variant<variants;variant++)for(float size:new float[]{22f,30f,40f,54f})for(int rep=0;rep<6;rep++){
            String[] text=new String[3];
            for(int l=0;l<3;l++){StringBuilder b=new StringBuilder();
                // Begin en eind altijd met een letter: zo vindt de herkenning de hele regel, net als bij een echte strook.
                b.append((char)('A'+rnd.nextInt(26)));
                for(int i=1;i<29;i++)b.append(ABC.charAt(rnd.nextInt(ABC.length())));
                b.append((char)('A'+rnd.nextInt(26)));text[l]=b.toString();}
            BufferedImage im=lines(text,size,30);int w=im.getWidth(),h=im.getHeight();int[] p=gray(im);
            if(size>=30)p=blur(p,w,h);p=morph(p,w,h,variant==1&&size>=30?1:variant==2&&size>=40?2:0);
            MrzOcr.Gray g=new MrzOcr.Gray(p,w,h);boolean[] dark=MrzOcr.binarize(g);int[] label=new int[w*h];
            List<MrzOcr.Box> boxes=MrzOcr.components(dark,w,h,label);List<MrzOcr.Line> ls=MrzOcr.lines(boxes);
            ls.sort((a,b)->Double.compare(a.y(),b.y()));
            if(ls.size()!=3)throw new IllegalStateException("regels gevonden: "+ls.size()+" bij "+size);
            MrzOcr.Page pg=new MrzOcr.Page(g,dark,label);
            for(int l=0;l<3;l++){MrzOcr.Line ln=ls.get(l);if(ln.length!=30)throw new IllegalStateException("lengte "+ln.length);
                List<List<MrzOcr.Box>> sl=MrzOcr.slots(ln);
                for(int i=0;i<30;i++){int c=ABC.indexOf(text[l].charAt(i))*variants+variant;double[] v=MrzOcr.cell(pg,ln,i,sl.get(i));for(int j=0;j<n;j++)sum[c][j]+=v[j];count[c]++;}
            }
        }
        StringBuilder data=new StringBuilder();
        for(int c=0;c<sum.length;c++){if(count[c]==0)throw new IllegalStateException("geen voorbeelden voor "+ABC.charAt(c/variants));
            for(int j=0;j<n;j++)data.append(Character.forDigit((int)Math.round(sum[c][j]/count[c]*15),16));}
        StringBuilder src=new StringBuilder();
        src.append("package nl.rene.tools;\n\n/** Sjablonen voor de tekens van de strook; gemaakt door tools/mrz/MrzSjablonen.java (niet met de hand wijzigen).\n")
           .append(" *  Per teken (volgorde Mrz.ALPHABET) ").append(variants).append(" varianten (gewoon, dikker, dunner) van ").append(MrzOcr.GW).append("×").append(MrzOcr.GH)
           .append(" vakjes, 0–f = aandeel inkt. Gemeten op OCR-B (ISO 1073-2). */\nfinal class MrzGlyphs {\n    private MrzGlyphs(){ }\n    static final int VARIANTS=").append(variants).append(";\n    static final String DATA=\"");
        src.append(data).append("\";\n");
        src.append("    private static double[][] cache;\n    static synchronized double[][] templates(){\n        if(cache!=null)return cache;int n=MrzOcr.GW*MrzOcr.GH,count=Mrz.ALPHABET.length()*VARIANTS;double[][] t=new double[count][n];\n        for(int i=0;i<count&&(i+1)*n<=DATA.length();i++)for(int j=0;j<n;j++)t[i][j]=Character.digit(DATA.charAt(i*n+j),16)/15.0;\n        return cache=t;\n    }\n}\n");
        Files.write(new File(out).toPath(),src.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("sjablonen: "+out+" ("+data.length()+" tekens)");
    }

    // ---------- testbeelden (verzonnen gegevens) ----------
    static String pad(String s,int n){StringBuilder b=new StringBuilder(s);while(b.length()<n)b.append('<');return b.toString();}
    static String c(String s){return String.valueOf(Mrz.digit(s));}
    static String[] td1(){
        String doc="NX7P41KD9",l1=pad("I<NLD"+doc+c(doc),30);String birth="150203",exp="310611";
        String l2=birth+c(birth)+"F"+exp+c(exp)+"NLD"+"<<<<<<<<<<<";l2=l2+c(l1.substring(5,30)+l2.substring(0,7)+l2.substring(8,15)+l2.substring(18,29));
        return new String[]{l1,l2,pad("JANSEN<<ZOE<ANNA",30)};
    }
    static String[] td3(){
        String doc="NW3RP8K25",birth="800101",exp="340315",pn="<<<<<<<<<<<<<<";
        String l2=doc+c(doc)+"NLD"+birth+c(birth)+"M"+exp+c(exp)+pn+"<";l2=l2+c(l2.substring(0,10)+l2.substring(13,20)+l2.substring(21,43));
        return new String[]{pad("P<NLDDE<VRIES<<WILLEM<JAN",44),l2};
    }
    static BufferedImage card(String[] mrz,int w,int h,float size,boolean passport){
        BufferedImage im=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        for(int y=0;y<h;y++){int v=236+(int)(8*Math.sin(y/23.0));g.setColor(new Color(v-6,v-2,v));g.drawLine(0,y,w,y);}
        g.setColor(new Color(200,210,225));g.setStroke(new BasicStroke(1.5f));for(int i=0;i<w;i+=17)g.drawLine(i,0,i+h/3,h);
        g.setColor(new Color(60,60,90));g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,Math.round(h*0.06f)));g.drawString(passport?"PASPOORT / PASSPORT":"IDENTITEITSKAART",Math.round(w*0.05f),Math.round(h*0.1f));
        g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,Math.round(h*0.035f)));
        String[] labels={"Naam / Surname  JANSEN","Voornamen  Zoë Anna","Geboortedatum  03 02 2015","Geboorteplaats  Rotterdam","Geldig tot  11 06 2031"};
        for(int i=0;i<labels.length;i++)g.drawString(labels[i],Math.round(w*0.36f),Math.round(h*(0.2f+i*0.075f)));
        g.setColor(new Color(120,120,130));g.fillRect(Math.round(w*0.05f),Math.round(h*0.16f),Math.round(w*0.26f),Math.round(h*0.42f));
        Font f=ocrb.deriveFont(size);g.setFont(f);g.setColor(new Color(20,20,30));
        int adv=g.getFontMetrics().charWidth('A'),x=(w-adv*mrz[0].length())/2,lh=Math.round(size*1.35f),y0=h-Math.round(size*0.6f)-lh*(mrz.length-1);
        for(int i=0;i<mrz.length;i++)g.drawString(mrz[i],x,y0+i*lh);
        return im;
    }
    /** Kaart op een tafel, gedraaid, met ruis en onscherpte, als JPEG zoals een telefoonfoto. */
    static void photo(BufferedImage card,double degrees,int out,double noise,boolean blur,File file)throws Exception{
        int cw=card.getWidth(),ch=card.getHeight();double r=Math.toRadians(degrees);
        int w=(int)(Math.abs(cw*Math.cos(r))+Math.abs(ch*Math.sin(r)))+120,h=(int)(Math.abs(cw*Math.sin(r))+Math.abs(ch*Math.cos(r)))+120;
        BufferedImage im=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.setColor(new Color(110,95,80));g.fillRect(0,0,w,h);
        AffineTransform t=new AffineTransform();t.translate(w/2.0,h/2.0);t.rotate(r);t.translate(-cw/2.0,-ch/2.0);g.drawImage(card,t,null);
        double scale=(double)out/Math.max(w,h);int ow=(int)(w*scale),oh=(int)(h*scale);
        BufferedImage small=new BufferedImage(ow,oh,BufferedImage.TYPE_INT_RGB);Graphics2D sg=small.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);sg.drawImage(im,0,0,ow,oh,null);
        Random rnd=new Random(file.getName().hashCode());int[] p=gray(small);if(blur)p=blur(p,ow,oh);
        BufferedImage o=new BufferedImage(ow,oh,BufferedImage.TYPE_BYTE_GRAY);
        for(int y=0;y<oh;y++)for(int x=0;x<ow;x++){int v=(int)Math.round(p[y*ow+x]+rnd.nextGaussian()*noise);v=Math.max(0,Math.min(255,v));o.getRaster().setSample(x,y,0,v);}
        ImageWriter wr=ImageIO.getImageWritersByFormatName("jpeg").next();ImageWriteParam pr=wr.getDefaultWriteParam();pr.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);pr.setCompressionQuality(0.8f);
        try(javax.imageio.stream.ImageOutputStream s=ImageIO.createImageOutputStream(file)){wr.setOutput(s);wr.write(null,new IIOImage(o,null,null),pr);}
        System.out.println("testbeeld: "+file+" ("+file.length()/1024+" KB)");
    }
    static void fixtures(String dir)throws Exception{
        new File(dir).mkdirs();
        BufferedImage id=card(td1(),1700,1070,58f,false);
        photo(id,0,1300,3,false,new File(dir,"td1-recht.jpg"));
        photo(id,4,1000,6,true,new File(dir,"td1-scheef.jpg"));
        photo(id,-7,760,8,true,new File(dir,"td1-klein.jpg"));
        photo(id,90,1200,4,false,new File(dir,"td1-gedraaid.jpg"));
        photo(id,181.5,1200,4,false,new File(dir,"td1-ondersteboven.jpg"));
        photo(card(td3(),1900,1340,46f,true),-2,1400,5,true,new File(dir,"td3-paspoort.jpg"));
        BufferedImage none=card(new String[]{""},1700,1070,58f,false);photo(none,0,1100,3,false,new File(dir,"zonder-strook.jpg"));
        List<String> lines=new ArrayList<>();for(String s:td1())lines.add(s);lines.add("");for(String s:td3())lines.add(s);
        Files.write(new File(dir,"verwacht.txt").toPath(),lines,StandardCharsets.UTF_8);
    }
}
