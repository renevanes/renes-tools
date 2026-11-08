package nl.rene.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Leest de machineleesbare strook (OCR-B) uit een grijswaardenbeeld, helemaal op de telefoon. Stappen: zwart-wit maken
 * (aanpassende drempel), losse tekens vinden (verbonden vlakken), tekens tot regels rijgen, regels van 30, 36 of 44 tekens
 * met dezelfde tekenafstand zoeken, elk teken vergelijken met de sjablonen in {@link MrzGlyphs} en de beste lezing kiezen
 * met de controlecijfers ({@link Mrz}). Probeert vier draairichtingen. Zuiver Java: getest in MrzTest.
 */
final class MrzOcr {
    private MrzOcr(){ }
    static final int GW=12,GH=18,MAX_SIDE=1800;

    static final class Box {
        int x0,y0,x1,y1,n,label;
        double cx(){return (x0+x1)/2.0;} double cy(){return (y0+y1)/2.0;} int w(){return x1-x0+1;} int h(){return y1-y0+1;}
    }
    static final class Gray {
        final int[] p;final int w,h;Gray(int[] p,int w,int h){this.p=p;this.w=w;this.h=h;}
        Gray rotate(int quarter){
            if(quarter%4==0)return this;int nw=quarter%2==1?h:w,nh=quarter%2==1?w:h;int[] o=new int[nw*nh];
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){int v=p[y*w+x],nx,ny;
                if(quarter==1){nx=h-1-y;ny=x;}else if(quarter==2){nx=w-1-x;ny=h-1-y;}else{nx=y;ny=w-1-x;}
                o[ny*nw+nx]=v;}
            return new Gray(o,nw,nh);
        }
        /** Verkleinen tot de langste zijde precies max is (gemiddelde over het bijbehorende vlak, ook bij een gebroken factor). */
        Gray shrink(int max){
            if(w<=max&&h<=max)return this;double f=Math.max(w,h)/(double)max;int nw=Math.max(1,(int)(w/f)),nh=Math.max(1,(int)(h/f));int[] o=new int[nw*nh];
            for(int y=0;y<nh;y++){int y0=(int)(y*f),y1=Math.max(y0+1,Math.min(h,(int)((y+1)*f)));
                for(int x=0;x<nw;x++){int x0=(int)(x*f),x1=Math.max(x0+1,Math.min(w,(int)((x+1)*f)));long sum=0;
                    for(int yy=y0;yy<y1;yy++)for(int xx=x0;xx<x1;xx++)sum+=p[yy*w+xx];o[y*nw+x]=(int)(sum/((long)(y1-y0)*(x1-x0)));}}
            return new Gray(o,nw,nh);
        }
    }
    /** Eén gevonden regel: tekens (posities) met per positie de pixels van die vlakken. */
    static final class Line {
        final List<Box> boxes,extra=new ArrayList<>();final double pitch,height,start;final int length;double[] axis;
        Line(List<Box> boxes,double pitch,double height,int length,double start){this.boxes=boxes;this.pitch=pitch;this.height=height;this.length=length;this.start=start;}
        double y(){return boxes.get(boxes.size()/2).cy();} double x0(){return start;}
    }
    static final class Page {final Gray g;final boolean[] dark;final int[] label;Page(Gray g,boolean[] dark,int[] label){this.g=g;this.dark=dark;this.label=label;}}

    /** Zwart-wit met een aanpassende drempel (Bradley): donkerder dan 15% onder het gemiddelde in de buurt. */
    static boolean[] binarize(Gray g){
        int w=g.w,h=g.h,r=Math.max(6,Math.min(w,h)/24);long[] in=new long[(w+1)*(h+1)];
        for(int y=0;y<h;y++){long row=0;for(int x=0;x<w;x++){row+=g.p[y*w+x];in[(y+1)*(w+1)+x+1]=in[y*(w+1)+x+1]+row;}}
        boolean[] d=new boolean[w*h];
        for(int y=0;y<h;y++){int y0=Math.max(0,y-r),y1=Math.min(h-1,y+r);
            for(int x=0;x<w;x++){int x0=Math.max(0,x-r),x1=Math.min(w-1,x+r);long area=(long)(x1-x0+1)*(y1-y0+1);
                long sum=in[(y1+1)*(w+1)+x1+1]-in[y0*(w+1)+x1+1]-in[(y1+1)*(w+1)+x0]+in[y0*(w+1)+x0];
                d[y*w+x]=(long)g.p[y*w+x]*area*100<sum*85;}}
        return d;
    }
    static List<Box> components(boolean[] d,int w,int h,int[] label){
        List<Box> out=new ArrayList<>();int[] stack=new int[w*h];int next=0;
        for(int i=0;i<w*h;i++){
            if(!d[i]||label[i]!=0)continue;next++;Box b=new Box();b.label=next;b.x0=b.x1=i%w;b.y0=b.y1=i/w;int sp=0;stack[sp++]=i;label[i]=next;
            while(sp>0){int c=stack[--sp],cx=c%w,cy=c/w;b.n++;if(cx<b.x0)b.x0=cx;if(cx>b.x1)b.x1=cx;if(cy<b.y0)b.y0=cy;if(cy>b.y1)b.y1=cy;
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){int nx=cx+dx,ny=cy+dy;if(nx<0||ny<0||nx>=w||ny>=h)continue;int ni=ny*w+nx;if(d[ni]&&label[ni]==0){label[ni]=next;stack[sp++]=ni;}}}
            if(b.n>=6&&b.h()>=5&&b.h()<h/5&&b.w()<w/4)out.add(b);
        }
        return out;
    }
    static double median(double[] v){if(v.length==0)return 0;double[] s=v.clone();Arrays.sort(s);return s[s.length/2];}

    /** Rijgt vlakken van links naar rechts tot regels met een gelijke tekenafstand. */
    static List<Line> lines(List<Box> boxes){
        List<Box> sorted=new ArrayList<>(boxes);sorted.sort((a,b)->Double.compare(a.cx(),b.cx()));
        boolean[] used=new boolean[sorted.size()];List<Line> out=new ArrayList<>();
        for(int s=0;s<sorted.size();s++){
            if(used[s])continue;Box start=sorted.get(s);double hh=start.h();
            List<Integer> chain=new ArrayList<>();chain.add(s);double slope=0;
            int cur=s;
            while(true){
                Box c=sorted.get(cur);int best=-1;double bd=Double.MAX_VALUE;
                for(int j=cur+1;j<sorted.size();j++){
                    Box o=sorted.get(j);double dx=o.cx()-c.cx();if(dx>2.6*hh)break;if(used[j]||dx<0.25*hh)continue;
                    if(o.h()>1.7*hh||o.h()<0.25*hh)continue;
                    double py=c.cy()+slope*dx;if(Math.abs(o.cy()-py)>0.45*hh)continue;
                    double score=dx+3*Math.abs(o.cy()-py);if(score<bd){bd=score;best=j;}
                }
                if(best<0)break;chain.add(best);cur=best;
                Box nb=sorted.get(best);if(nb.h()>hh&&nb.h()<1.7*hh)hh=Math.max(hh,nb.h()*0.9+hh*0.1);
                if(chain.size()>=6){Box a=sorted.get(chain.get(0));slope=(nb.cy()-a.cy())/Math.max(1,nb.cx()-a.cx());}
            }
            if(chain.size()<20)continue;
            List<Box> all=new ArrayList<>();for(int i:chain)all.add(sorted.get(i));
            double[] d0=new double[all.size()-1];for(int i=1;i<all.size();i++)d0[i-1]=all.get(i).cx()-all.get(i-1).cx();
            double p0=median(d0);
            // Losse vlakken vóór of na de regel (andere tekst op de kaart) afknippen bij een groot gat; het langste stuk telt.
            int from=0,bestFrom=0,bestTo=all.size();int bestLen=0;
            for(int i=1;i<=all.size();i++)if(i==all.size()||all.get(i).cx()-all.get(i-1).cx()>2.6*p0){if(i-from>bestLen){bestLen=i-from;bestFrom=from;bestTo=i;}from=i;}
            for(int i:chain)used[i]=true;
            if(bestLen<20)continue;
            List<Box> bs=new ArrayList<>(all.subList(bestFrom,bestTo));
            double[] dxs=new double[bs.size()-1];for(int i=1;i<bs.size();i++)dxs[i-1]=bs.get(i).cx()-bs.get(i-1).cx();
            double pitch=median(dxs);if(pitch<3)continue;
            // tekenafstand verfijnen over de hele regel: (laatste - eerste) / (aantal posities - 1)
            // Plek per teken stap voor stap tellen (kleine fouten in de afstand stapelen zo niet op), daarna begin en
            // tekenafstand met alle tekens samen (kleinste kwadraten): smalle tekens als 1 en I staan niet midden in hun plek.
            int[] ks=new int[bs.size()];for(int i=1;i<ks.length;i++)ks[i]=ks[i-1]+Math.max(1,(int)Math.round((bs.get(i).cx()-bs.get(i-1).cx())/pitch));
            int n=ks[ks.length-1]+1;
            double first=bs.get(0).cx(),sk=0,sx=0,skk=0,skx=0;int m=bs.size();
            for(int i=0;i<m;i++){double k=ks[i];Box b=bs.get(i);sk+=k;sx+=b.cx();skk+=k*k;skx+=k*b.cx();}
            double den=m*skk-sk*sk,x0=first;
            if(den>0){pitch=(m*skx-sk*sx)/den;x0=(sx-pitch*sk)/m;}
            // Letterhoogte: hoog in de verdeling, want '<' is bijna even hoog en komt in de strook vaak voor.
            double[] hs=new double[bs.size()];for(int i=0;i<hs.length;i++)hs[i]=bs.get(i).h();Arrays.sort(hs);double cap=hs[Math.min(hs.length-1,(int)(hs.length*0.85))];
            out.add(new Line(bs,pitch,cap,n,x0));
        }
        // Stukjes van gebroken tekens die niet in de ketting kwamen, toch bij de regel nemen.
        java.util.Set<Box> inLine=new java.util.HashSet<>();for(Line l:out)inLine.addAll(l.boxes);
        for(Box b:boxes){
            if(inLine.contains(b))continue;
            for(Line l:out){Box f=l.boxes.get(0),z=l.boxes.get(l.boxes.size()-1);
                if(b.cx()<f.x0-l.pitch/2||b.cx()>z.x1+l.pitch/2||b.h()>1.3*l.height)continue;
                double t=(b.cx()-f.cx())/Math.max(1,z.cx()-f.cx()),y=f.cy()+t*(z.cy()-f.cy());
                if(Math.abs(b.cy()-y)<0.5*l.height){l.extra.add(b);break;}}
        }
        return out;
    }

    /** Middellijn van de regel (y = a + b·x), gemeten aan de hoge tekens; zo volgen de vakjes een scheve foto. */
    /** In OCR-B staat het midden van < een tiende letterhoogte boven het midden van letters en cijfers. */
    static final double LT_OFFSET=-0.10;
    static double[] axis(Line ln){
        // Rechte lijn door de middens van alle tekens; bij < eerst de vaste verschuiving eraf. (Zelf schatten gaat mis als
        // de letters links en de < rechts staan: dan zijn schuinte en verschuiving niet uit elkaar te halen.)
        double n=0,sx=0,sy=0,sxx=0,sxy=0;
        for(Box b:ln.boxes){
            if(b.h()<0.6*ln.height||b.h()>1.15*ln.height)continue;double y=b.cy()-(b.h()<0.9*ln.height?LT_OFFSET*ln.height:0);
            n++;sx+=b.cx();sy+=y;sxx+=b.cx()*b.cx();sxy+=b.cx()*y;
        }
        if(n<2)return new double[]{ln.y(),0};
        double den=n*sxx-sx*sx,slope=den>0?(n*sxy-sx*sy)/den:0;return new double[]{(sy-slope*sx)/n,slope};
    }
    /** Kenmerken van één teken: aandeel donkere pixels (van de eigen vlakken) per vakje, in een vak van tekenafstand × letterhoogte langs de regel. */
    static double[] cell(Page pg,Line ln,int pos,List<Box> own){
        double[] ax=ln.axis!=null?ln.axis:(ln.axis=axis(ln));
        double cx=ln.x0()+pos*ln.pitch,cy=ax[0]+ax[1]*cx,th=Math.atan(ax[1]),cos=Math.cos(th),sin=Math.sin(th);
        double cw=ln.pitch/cos,ch=ln.height*1.2;
        double[] v=new double[GW*GH];int W=pg.g.w,H=pg.g.h,S=3;
        java.util.Set<Integer> labels=new java.util.HashSet<>();for(Box b:own)labels.add(b.label);
        for(int gy=0;gy<GH;gy++)for(int gx=0;gx<GW;gx++){
            int hit=0;
            for(int sy=0;sy<S;sy++)for(int sx=0;sx<S;sx++){
                double u=((gx+(sx+0.5)/S)/GW-0.5)*cw,w=((gy+(sy+0.5)/S)/GH-0.5)*ch;
                int x=(int)Math.round(cx+u*cos-w*sin),y=(int)Math.round(cy+u*sin+w*cos);
                if(x<0||y<0||x>=W||y>=H)continue;int i=y*W+x;if(pg.dark[i]&&labels.contains(pg.label[i]))hit++;
            }
            v[gy*GW+gx]=hit/(double)(S*S);
        }
        return v;
    }
    /** Vlakken per positie; een vlak dat over meer posities loopt (aan elkaar gegroeide tekens) telt bij elk daarvan. */
    static List<List<Box>> slots(Line ln){
        List<List<Box>> out=new ArrayList<>();for(int i=0;i<ln.length;i++)out.add(new ArrayList<>());
        List<Box> all=new ArrayList<>(ln.boxes);all.addAll(ln.extra);
        for(Box b:all){
            int a=(int)Math.round((b.x0-ln.x0()+ln.pitch/2)/ln.pitch-0.5),z=(int)Math.round((b.x1-ln.x0()+ln.pitch/2)/ln.pitch-0.5);
            int c=(int)Math.round((b.cx()-ln.x0())/ln.pitch);
            if(z-a<1){a=z=c;}
            for(int i=Math.max(0,a);i<=Math.min(ln.length-1,z);i++)out.get(i).add(b);
        }
        return out;
    }
    static Mrz.Pos classify(double[] v){
        String abc=Mrz.ALPHABET;double[] best=new double[abc.length()];Arrays.fill(best,Double.MAX_VALUE);
        double[][] t=MrzGlyphs.templates();int per=t.length/abc.length();
        for(int c=0;c<abc.length();c++)for(int k=0;k<per;k++){double[] tp=t[c*per+k];double d=0;for(int i=0;i<v.length;i++){double e=v[i]-tp[i];d+=e*e;}if(d<best[c])best[c]=d;}
        Integer[] order=new Integer[abc.length()];for(int i=0;i<order.length;i++)order[i]=i;Arrays.sort(order,(a,b)->Double.compare(best[a],best[b]));
        char[] cs=new char[order.length];double[] ks=new double[order.length];for(int i=0;i<order.length;i++){cs[i]=abc.charAt(order[i]);ks[i]=best[order[i]];}
        return new Mrz.Pos(cs,ks);
    }
    static Mrz.Pos[] read(Page pg,Line ln,int length,int shift){
        Mrz.Pos[] out=new Mrz.Pos[length];List<List<Box>> sl=slots(ln);
        for(int i=0;i<length;i++){
            int at=i-shift;
            if(at<0||at>=ln.length){out[i]=Mrz.Pos.unknown();continue;}
            List<Box> own=sl.get(at);
            out[i]=own.isEmpty()?classify(new double[GW*GH]):classify(cell(pg,ln,at,own));
        }
        return out;
    }

    /** Zoekt in één draairichting de beste strook. */
    /** Meer vlakken dan dit = ruis of een foto vol tekst: niet verder zoeken (tijd en geheugen op de telefoon). */
    static final int MAX_BOXES=20000;
    static Mrz.Result find(Gray g){
        boolean[] dark=binarize(g);int[] label=new int[g.w*g.h];List<Box> boxes=components(dark,g.w,g.h,label);
        if(boxes.size()>MAX_BOXES)return null;
        Page pg=new Page(g,dark,label);List<Line> ls=lines(boxes);
        ls.sort((a,b)->Double.compare(a.y(),b.y()));
        Mrz.Result best=null;
        for(Mrz.Format f:Mrz.FORMATS){
            List<Line> fit=new ArrayList<>();for(Line l:ls)if(l.length>=f.length-1&&l.length<=f.length)fit.add(l);
            for(int i=0;i+f.lines<=fit.size();i++){
                List<Line> group=fit.subList(i,i+f.lines);if(!together(group))continue;
                // Mist er één teken (vaag of afgesneden), probeer het dan aan het begin en aan het eind.
                int[][] shifts=shifts(group,f.length);
                for(int[] sh:shifts){
                    Mrz.Pos[][] p=new Mrz.Pos[f.lines][];for(int l=0;l<f.lines;l++)p[l]=read(pg,group.get(l),f.length,sh[l]);
                    Mrz.Result r=Mrz.decode(f,p);if(r!=null&&better(r,best))best=r;
                }
            }
        }
        return best;
    }
    static int[][] shifts(List<Line> group,int length){
        List<int[]> out=new ArrayList<>();out.add(new int[group.size()]);
        for(int l=0;l<group.size();l++)if(group.get(l).length==length-1){List<int[]> more=new ArrayList<>();for(int[] s:out){int[] a=s.clone();a[l]=1;more.add(a);}out.addAll(more);}
        return out.toArray(new int[0][]);
    }
    static boolean together(List<Line> g){
        for(int i=1;i<g.size();i++){
            Line a=g.get(i-1),b=g.get(i);double dy=b.y()-a.y(),hh=Math.max(a.height,b.height);
            if(dy<1.1*hh||dy>3.5*hh)return false;
            if(Math.abs(a.pitch-b.pitch)>0.15*Math.max(a.pitch,b.pitch))return false;
            if(Math.abs(a.x0()-b.x0())>2.5*a.pitch)return false;
        }
        return true;
    }
    static boolean better(Mrz.Result r,Mrz.Result best){
        if(best==null)return true;if(r.usable()!=best.usable())return r.usable();
        if(r.usable()&&r.valid()!=best.valid())return r.valid()>best.valid();return r.mean<best.mean;
    }

    /** Grijswaarden (0–255, rij voor rij) → beste lezing, of null als er geen strook te vinden is. */
    static Mrz.Result read(int[] gray,int w,int h){
        Gray g=new Gray(gray,w,h).shrink(MAX_SIDE);Mrz.Result best=null;
        for(int q=0;q<4;q++){
            Mrz.Result r=find(g.rotate(q));if(r!=null&&better(r,best))best=r;
            // Alleen eerder stoppen bij een overtuigende lezing (onzin uit een verkeerde draairichting zit rond 20).
            if(best!=null&&best.sure()&&best.mean<Mrz.Result.LIMIT*0.6)break;
        }
        return best;
    }
}
