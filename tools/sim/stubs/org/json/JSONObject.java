package org.json;
import java.util.*;
public class JSONObject {
    Map<String, Object> m = new LinkedHashMap<>();
    public JSONObject put(String k, Object v) throws JSONException { m.put(k, v); return this; }
    public JSONObject put(String k, long v) throws JSONException { m.put(k, v); return this; }
    public String optString(String k) { Object v = m.get(k); return v == null ? "" : v.toString(); }
    public long optLong(String k) { Object v = m.get(k); return v instanceof Number ? ((Number) v).longValue() : 0; }
}
