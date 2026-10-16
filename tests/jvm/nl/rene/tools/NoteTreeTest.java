package nl.rene.tools;

import java.util.List;

public class NoteTreeTest {
    static void check(boolean ok) { if(!ok)throw new AssertionError("Sublijstvolgorde onjuist"); }
    public static void main(String[] args) {
        String[] ids={"a","b","c","d","e","f"},parents={"","","a","c","","e"};
        List<NoteTree.Row> rows=NoteTree.order(ids,parents,new boolean[]{true,false,false,true,true,true});
        int[] expected={0,2,3,1,4,5},depth={0,1,2,0,0,1};
        check(rows.size()==6);
        for(int i=0;i<6;i++){check(rows.get(i).index==expected[i]);check(rows.get(i).depth==depth[i]);check(rows.get(i).groupDone==(i>=4));}
        check(rows.get(2).indent().equals("    "));
        rows=NoteTree.order(new String[]{"a","b","c","d"},new String[]{"b","a","missing","d"},new boolean[4]);
        check(rows.size()==4);for(NoteTree.Row row:rows)check(row.depth==0);
        rows=NoteTree.order(new String[]{"child","root"},new String[]{"root",""},new boolean[2]);
        check(rows.get(0).index==1&&rows.get(1).index==0&&rows.get(1).depth==1);
        int count=2000;String[] deepIds=new String[count],deepParents=new String[count];
        for(int i=0;i<count;i++){deepIds[i]="i"+i;deepParents[i]=i==0?"":"i"+(i-1);}
        rows=NoteTree.order(deepIds,deepParents,new boolean[count]);
        check(rows.size()==count&&rows.get(count-1).depth==count-1&&rows.get(count-1).indent().length()==40);
        System.out.println("Sublijstjes: volgorde, vinkjes, ontbrekende ouders, cycli en diepe lijsten geslaagd");
    }
}
