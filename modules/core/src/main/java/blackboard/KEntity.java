package blackboard;

import java.util.Map;
import java.util.function.Supplier;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonProperty;

public interface KEntity {
    public static final String NAME_P = "name";
    public static final String TYPE_P = "@type";
    public static final String CREATED_P = "created";
    public static final String DIRECTED_P = "directed";
    public static final String URI_P = "uri";
    public static final String ID_P = "@id";
    public static final String SYNONYMS_P = "synonyms";
    public static final String KGRAPH_P = "kgraph";
    public static final String XREF_P = "xref";
    public static final String SOURCE_P = "source";

    @JsonAnyGetter
    Map<String, Object> getProperties ();

    @JsonProperty(value="@id")
    long getId ();
    long getCreated ();
    @JsonProperty(value="@type")
    String getType ();
    String getName ();
    void put (String prop, Object value);
    void putAll (Map<String, Object> properties);
    void putIfAbsent (String prop, Supplier supplier);
    Object get (String prop);
}
