package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dezelfde volgorde als in Notities: hoofditem met subitems; geheel afgevinkte groepen onderaan. */
final class NoteTree {
    static final class Row {
        final int index, depth, root;
        boolean groupDone;
        Row(int i, int d, int r) { index=i; depth=d; root=r; }
        String indent() { StringBuilder s=new StringBuilder(); for(int i=0;i<Math.min(depth,20);i++)s.append("  ");return s.toString(); }
    }

    static List<Row> order(String[] ids, String[] parents, boolean[] done) {
        Map<String,Integer> byId=new HashMap<>();
        for(int i=0;i<ids.length;i++)byId.put(ids[i],i);
        Map<Integer,List<Integer>> children=new HashMap<>();
        for(int i=0;i<ids.length;i++){
            Integer parent=byId.get(parents[i]);
            Set<Integer> seen=new HashSet<>();seen.add(i);Integer p=parent;
            while(p!=null){if(!seen.add(p)){parent=null;break;}p=byId.get(parents[p]);}
            int key=parent==null?-1:parent;
            List<Integer> siblings=children.get(key);if(siblings==null){siblings=new ArrayList<>();children.put(key,siblings);}siblings.add(i);
        }
        List<Row> rows=new ArrayList<>(),stack=new ArrayList<>();
        List<Integer> roots=children.get(-1);
        if(roots!=null)for(int i=roots.size()-1;i>=0;i--){int r=roots.get(i);stack.add(new Row(r,0,r));}
        Set<Integer> openRoots=new HashSet<>();
        while(!stack.isEmpty()){
            Row row=stack.remove(stack.size()-1);rows.add(row);if(!done[row.index])openRoots.add(row.root);
            List<Integer> kids=children.get(row.index);
            if(kids!=null)for(int i=kids.size()-1;i>=0;i--)stack.add(new Row(kids.get(i),row.depth+1,row.root));
        }
        List<Row> out=new ArrayList<>(),closed=new ArrayList<>();
        for(Row row:rows){row.groupDone=!openRoots.contains(row.root);if(row.groupDone)closed.add(row);else out.add(row);}
        out.addAll(closed);return out;
    }

    static List<Row> rows(JSONArray items) {
        int count=items==null?0:items.length();String[] ids=new String[count],parents=new String[count];boolean[] done=new boolean[count];
        for(int i=0;i<count;i++){
            JSONObject it=items.optJSONObject(i);
            ids[i]=it==null?"":it.optString("id");parents[i]=it==null?"":it.optString("parentId");done[i]=it!=null&&it.optBoolean("done");
        }
        return order(ids,parents,done);
    }
}
