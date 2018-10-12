package blackboard.neo4j;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.lang.reflect.Array;
import java.util.stream.Collectors;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.index.*;
import play.Logger;

import blackboard.*;

public class Neo4jKGraph extends Neo4jKEntity implements KGraph {
    final Neo4jBlackboard blackboard;
    final Label kgLabel;
    final Index<Node> nodeIndex;
    final Index<Relationship> edgeIndex;

    public Neo4jKGraph (Neo4jBlackboard blackboard, Node node) {
        this (blackboard, node, null);
    }
    
    public Neo4jKGraph (Neo4jBlackboard blackboard,
                        Node node, Map<String, Object> properties) {
        super (node, properties);
        kgLabel = Label.label("KG:"+node.getId());
        nodeIndex = graphDb.index().forNodes(kgLabel.name());
        edgeIndex = graphDb.index().forRelationships(kgLabel.name());
        this.blackboard = blackboard;
    }

    public long getNodeCount () {
        try (Transaction tx = graphDb.beginTx()) {
            return graphDb.findNodes(kgLabel).stream().count();
        }
    }

    public long getEdgeCount () {
        try (Transaction tx = graphDb.beginTx();
             Result result = graphDb.execute("match(n:`"+kgLabel.name()
                                             +"`)-[e]-(m:`"+kgLabel.name()
                                             +"`) return count(e) as COUNT")) {
            if (result.hasNext()) {
                Number n = (Number)result.next().get("COUNT");
                return n.longValue();
            }
        }
        return -1l;
    }

    Stream<KNode> _nodes () {
        return _nodes (n -> true);
    }
    
    Stream<KNode> _nodes (Predicate<KNode> predicate) {
        return graphDb.findNodes(kgLabel).stream()
            .map(n -> (KNode)new Neo4jKNode (n))
            .filter(predicate);
    }

    Stream<KEdge> _edges () {
        return _edges (e -> true);
    }
    
    Stream<KEdge> _edges (Predicate<KEdge> predicate) {
        return graphDb.getAllRelationships().stream()
            .filter(rel -> rel.getStartNode().hasLabel(kgLabel)
                    && rel.getEndNode().hasLabel(kgLabel))
            .map(rel -> (KEdge) new Neo4jKEdge
                 (rel, new Neo4jKNode (rel.getStartNode()),
                  new Neo4jKNode (rel.getEndNode())))
            .filter(predicate);
    }
    
    public KNode[] getNodes () {
        try (Transaction tx = graphDb.beginTx()) {
            return _nodes()
                .collect(Collectors.toList()).toArray(new KNode[0]);
        }
    }

    public KNode[] nodes (Predicate<KNode> predicate) {
        try (Transaction tx = graphDb.beginTx()) {
            List<KNode> nodes =
                _nodes (predicate).collect(Collectors.toList());
            return nodes.toArray(new KNode[0]);
        }
    }

    public void delete () {
        try (Transaction tx = graphDb.beginTx()) {
            // FIXME: remove indexes associated with properties 
            graphDb.findNodes(kgLabel).stream().forEach(n -> {
                    for (Relationship rel : n.getRelationships())
                        rel.delete();
                    n.delete();
                });
            
            ((Node)entity).delete();
            tx.success();
        }
    }

    public KEdge[] getEdges () {
        try (Transaction tx = graphDb.beginTx()) {
            return _edges()
                .collect(Collectors.toList()).toArray(new KEdge[0]);
        }
    }

    public KEdge[] edges (Predicate<KEdge> predicate) {
        try (Transaction tx = graphDb.beginTx()) {      
            return _edges(predicate)
                .collect(Collectors.toList()).toArray(new KEdge[0]);
        }
    }

    public KNode node (long id) {
        try (Transaction tx = graphDb.beginTx()) {
            Node n = graphDb.getNodeById(id);
            return new Neo4jKNode (n);
        }
        catch (NotFoundException ex) {
            Logger.warn("Unknown node: "+id);
        }
        return null;
    }

    public KEdge edge (long id) {
        try (Transaction tx = graphDb.beginTx()) {
            Relationship rel = graphDb.getRelationshipById(id);
            return new Neo4jKEdge (rel, new Neo4jKNode (rel.getStartNode()),
                                   new Neo4jKNode (rel.getEndNode()));
        }
        catch (NotFoundException ex) {
            Logger.warn("Unknown edge: "+id);
        }
        return null;
    }

     <T extends PropertyContainer> void index
                (Index<T> index, T e, Map<String, Object> props) {
         if (props != null) {
             for (Map.Entry<String, Object> me : props.entrySet()) {
                 Object val = me.getValue();
                 if (val.getClass().isArray()) {
                     int len = Array.getLength(val);
                     for (int i = 0; i < len; ++i)
                         index.add(e, me.getKey(), Array.get(val, i));
                 }
                 else 
                     index.add(e, me.getKey(), val);
             }
         }
    }

    /**
     * create implicit edges between entities based on synonyms or whatever
     * fields deem appropriate
     */
    void stitch (List<Relationship> edges, Node node,
                 String name, Object value) {
        if (value.getClass().isArray()) {
            int len = Array.getLength(value);
            for (int i = 0; i < len; ++i) {
                Object val = Array.get(value, i);
                stitch (edges, node, name, val);
            }
        }
        else {
            IndexHits<Node> hits = nodeIndex.get(SYNONYMS_P, value);
            while (hits.hasNext()) {
                Node n = hits.next();
                if (!n.equals(node)) {
                    Relationship rel = node.createRelationshipTo
                        (n, RelationshipType.withName("resolve"));
                    rel.setProperty("value", value);
                    if (edges != null)
                        edges.add(rel);
                }
            }
            hits.close();
        }
    }

    public KNode createNode (Map<String, Object> properties) {
        Neo4jKNode node = null;
        List<KEdge> edges = null;
        try (Transaction tx = graphDb.beginTx()) {
            Node n = properties.containsKey(TYPE_P)
                ? graphDb.createNode
                (kgLabel, Label.label((String)properties.get(TYPE_P)))
                : graphDb.createNode(kgLabel);
            n.setProperty(KGRAPH_P, entity.getId()); // parent kgraph
            n.setProperty(CREATED_P, System.currentTimeMillis());
            index (nodeIndex, n, properties);       
            node = new Neo4jKNode (n, properties);
            
            Object syn = properties.get(SYNONYMS_P);
            if (syn != null) {
                List<Relationship> rels = new ArrayList<>();
                stitch (rels, n, SYNONYMS_P, syn);
                if (!rels.isEmpty()) {
                    edges = new ArrayList<>();
                    for (Relationship rel : rels) {
                        if (n.equals(rel.getStartNode())) {
                            KEdge e = new Neo4jKEdge
                                (rel, node, new Neo4jKNode (rel.getEndNode()));
                            edges.add(e);
                        }
                        else {
                            KEdge e = new Neo4jKEdge
                                (rel, new Neo4jKNode (rel.getStartNode()), node);
                            edges.add(e);
                        }
                    }
                }
                Logger.debug("node "+n.getId()
                             +" has "+rels.size()+" stitch(es)!");
            }
            tx.success();
        }

        if (node != null) {
            blackboard.fireEvent
                (KNode.class, new KEvent<>(this, node, KEvent.Oper.ADD));
            if (edges != null)
                for (KEdge e : edges)
                    blackboard.fireEvent
                        (KEdge.class, new KEvent<>(this, e, KEvent.Oper.ADD));
        }

        return node;
    }

    public KNode createNodeIfAbsent
        (Map<String, Object> properties, String key) {
        KNode node = null;
        if (properties.containsKey(key)) {
            Object val = properties.get(key);
            if (val == null) {
            }
            else {
                if (val.getClass().isArray())
                    Logger.warn("Key \""+key+"\" has multiple values!");
            
                try (Transaction tx = graphDb.beginTx();
                     IndexHits<Node> hits = nodeIndex.get(key, val)) {
                    if (hits.hasNext()) {
                        Node n = hits.next();
                        node = new Neo4jKNode (n);
                    }
                }
            }
        }

        if (node == null)
            node = createNode (properties);

        return node;
    }

    public KEdge createEdge (KNode source, KNode target, String type,
                             Map<String, Object> properties) {
        if (type == null)
            throw new IllegalArgumentException
                ("Can't create edge with null type!");
        
        if (source == null || target == null)
            throw new IllegalArgumentException
                ("Either source or target is null");
        
        KEdge edge = null;
        try (Transaction tx = graphDb.beginTx()) {
            Neo4jKNode s = (Neo4jKNode)source;
            Neo4jKNode t = (Neo4jKNode)target;
            Relationship rel = s.node().createRelationshipTo
                (t.node(), RelationshipType.withName(type));
            index (edgeIndex, rel, properties);
            edge = new Neo4jKEdge (rel, s, t, properties);
            tx.success();
        }

        if (edge != null) {
            blackboard.fireEvent
                (KEdge.class, new KEvent<>(this, edge, KEvent.Oper.ADD));
        }
            
        return edge;
    }

    public KEdge createEdgeIfAbsent (KNode source, KNode target, String type) {
        try (Transaction tx = graphDb.beginTx()) {
            Neo4jKNode s = (Neo4jKNode)source;
            Neo4jKNode t = (Neo4jKNode)target;
            RelationshipType etype = RelationshipType.withName(type);
            for (Relationship rel : s.node().getRelationships()) {
                Node other = rel.getOtherNode(s.node());
                if (other.equals(t.node()) && rel.isType(etype)) {
                    return new Neo4jKEdge (rel, s, t);
                }
            }
        }
        
        return createEdge (source, target, type, null);
    }
    
    public KEdge createEdgeIfAbsent (KNode source, KNode target, String type,
                                     Map<String, Object> properties,
                                     String key) {
        KEdge edge = null; 
        if (key == null || properties == null
            || !properties.containsKey(key)
            || null == properties.get(key)) {
            edge = createEdgeIfAbsent (source, target, type);
        }
        else {
            Object val = properties.get(key);
            if (val.getClass().isArray())
                Logger.warn("Key \""+key+"\" has multiple values!");
            
            try (Transaction tx = graphDb.beginTx();
                 IndexHits<Relationship> hits = edgeIndex.get(key, val)) {
                if (hits.hasNext()) {
                    RelationshipType etype = RelationshipType.withName(type);
                    Relationship rel = hits.next();
                    Neo4jKNode s = (Neo4jKNode)source;
                    Neo4jKNode t = (Neo4jKNode)target;
                    if (rel.getStartNode().equals(s.node())
                        && rel.getEndNode().equals(t.node())
                        && rel.isType(etype))
                        edge = new Neo4jKEdge (rel, s, t);
                }
            }

            if (edge == null)
                edge = createEdgeIfAbsent (source, target, type);
        }

        if (!properties.isEmpty())
            edge.putAll(properties);
        
        return edge;
    }

    public KNode[] findNodes (String property, Object value) {
        List<KNode> nodes = new ArrayList<>();
        try (Transaction tx = graphDb.beginTx();
             IndexHits<Node> hits = nodeIndex.get(property, value)) {
            while (hits.hasNext()) {
                Node n = hits.next();
                nodes.add(new Neo4jKNode (n));
            }
        }
        
        return nodes.toArray(new KNode[0]);
    }
    
    public Blackboard blackboard () { return blackboard; }
}
