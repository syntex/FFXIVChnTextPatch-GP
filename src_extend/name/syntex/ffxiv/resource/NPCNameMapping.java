package name.syntex.ffxiv.resource;

import java.util.HashMap;

public class NPCNameMapping {
    HashMap<String, String> npcNameMap = new HashMap<String, String>();

    public NPCNameMapping() {
        npcNameMap.put("可露兒", "可露露");
        npcNameMap.put("皮平", "皮皮");
    }

    public String mapping(String desc) {
        if(desc==null)
            return desc;
        for (String key : npcNameMap.keySet()) {
            if (desc.contains(key))
            {
                System.out.println(desc);
                System.out.println(desc.replaceAll(key, npcNameMap.get(key)));
                return desc.replaceAll(key, npcNameMap.get(key));
            }
        }
        return desc;
    }
}
