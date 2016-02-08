package org.nustaq.reallive.records;

import org.nustaq.reallive.interfaces.*;
import org.nustaq.reallive.impl.RLUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by ruedi on 04.08.2015.
 */
public class MapRecord<K> implements Record<K> {

    public Map<String,Object> map = new HashMap<>(); // debug
    String fields[];
    K key;

    public MapRecord(K key) {
        this.key = key;
    }

    public MapRecord(K key, Object ... values) {
        this(key);
        RLUtil.get().buildRecord(this,values);
    }

    @Override
    public K getKey() {
        return key;
    }

//    @Override
    public void key(K key) {
        this.key = key;
    }

    @Override
    public String[] getFields() {
        if (fields==null) {
            fields = new String[map.size()];
            map.keySet().toArray(fields);
        }
        return fields;
    }

    @Override
    public Object get(String field) {
        return map.get(field);
    }

    @Override
    public MapRecord put(String field, Object value) {
        field=field.intern();
        if ( map.put(field, value) == null ) {
            fields = null;
        }
        if (value == null)
            map.remove(field);
        return this;
    }

    @Override
    public String toString() {
        return "MapRecord{" + asString() + '}';
    }

    /**
     * @return a shallow copy
     */
    public MapRecord<K> copied() {
        MapRecord<K> newReq = new MapRecord<K>(getKey());
        map.forEach( (k,v) -> newReq.put(k,v) );
        return newReq;
    }
}
