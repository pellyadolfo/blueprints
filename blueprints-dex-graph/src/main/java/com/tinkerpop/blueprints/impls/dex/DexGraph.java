package com.tinkerpop.blueprints.impls.dex;

import com.sparsity.dex.gdb.AttributeKind;
import com.sparsity.dex.gdb.ObjectType;
import com.tinkerpop.blueprints.CloseableIterable;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Element;
import com.tinkerpop.blueprints.Features;
import com.tinkerpop.blueprints.KeyIndexableGraph;
import com.tinkerpop.blueprints.MetaGraph;
import com.tinkerpop.blueprints.TransactionalGraph;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.util.ExceptionFactory;
import com.tinkerpop.blueprints.util.MultiIterable;
import com.tinkerpop.blueprints.util.PropertyFilteredIterable;
import com.tinkerpop.blueprints.util.StringFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dex is a graph database developed by Sparsity Technologies.
 * <p/>
 * Dex natively supports the property graph data model defined by Blueprints.
 * However, there are a few peculiarities. No user defined element identifiers:
 * Dex is the gatekeeper and creator of vertex and edge identifiers. Thus, when
 * creating a new vertex or edge instance, the provided object identifier is
 * ignored.
 * <p/>
 * Vertices are labeled too: When adding vertices, the user can set
 * {@link DexGraph#label} to be used as the label of the vertex to be created.
 * Also, the label of a vertex (or even an element) can be retrieved through the
 * {@link StringFactory#LABEL} property.
 * <p/>
 * DexGraph implements {@link KeyIndexableGraph} with some particularities on
 * the way it can be used. As both vertices and edges are labeled when working
 * with Dex, the use of some APIs may require previously setting the label (by
 * means of {@link DexGraph#label}). Those APIs are:
 * {@link #getVertices(String, Object)}, {@link #getEdges(String, Object)}, and
 * {@link #createKeyIndex(String, Class)}.
 * <p/>
 * When working with DexGraph, all methods having as a result a collection
 * actually return a {@link CloseableIterable} collection. Thus users can
 * {@link CloseableIterable#close()} the collection to free resources.
 * Otherwise, all those collections will automatically be closed when the
 * transaction is stopped ({@link #stopTransaction(com.tinkerpop.blueprints.TransactionalGraph.Conclusion)}
 * or if the database is stopped ( {@link #shutdown()}).
 * 
 * @author <a href="http://www.sparsity-technologies.com">Sparsity
 *         Technologies</a>
 */
public class DexGraph implements MetaGraph<com.sparsity.dex.gdb.Graph>, KeyIndexableGraph, TransactionalGraph {

    /**
     * Default Vertex label.
     */
    public static final String DEFAULT_DEX_VERTEX_LABEL = "VERTEX_LABEL";

    /**
     * This is a "bypass" to set the Dex vertex label (node type).
     * <p/>
     * Dex vertices belong to a vertex/node type (thus all of them have a label).
     * By default, all vertices will have the {@link #DEFAULT_DEX_VERTEX_LABEL} label.
     * The user may set a different vertex label by setting this property when calling
     * {@link #addVertex(Object)}.
     * <p/>
     * Moreover, this value will also be used for the KeyIndex-related methods.
     *
     * @see #addVertex(Object)
     * @see #createKeyIndex(String, Class)
     * @see #getVertices(String, Object)
     * @see #getEdges(String, Object)
     */
    public ThreadLocal<String> label = new ThreadLocal<String>() {
        @Override
        protected String initialValue() {
            return null;
        }
    };

    /**
     * Database persistent file.
     */
    private File dbFile = null;

    private com.sparsity.dex.gdb.Dex dex = null;
    private com.sparsity.dex.gdb.Database db = null;
    
    private ThreadLocal<com.sparsity.dex.gdb.Session> session = new ThreadLocal<com.sparsity.dex.gdb.Session>() {
        @Override
        protected com.sparsity.dex.gdb.Session initialValue() {
            return null;
        }
    };

    private static final Features FEATURES = new Features();

    static {
        FEATURES.supportsDuplicateEdges = true;
        FEATURES.supportsSelfLoops = true;
        FEATURES.isPersistent = true;
        FEATURES.isRDFModel = false;
        FEATURES.supportsVertexIteration = true;
        FEATURES.supportsEdgeIteration = true;
        FEATURES.supportsVertexIndex = false;
        FEATURES.supportsEdgeIndex = false;
        FEATURES.ignoresSuppliedIds = true;
        FEATURES.supportsEdgeRetrieval = true;
        FEATURES.supportsVertexProperties = true;
        FEATURES.supportsEdgeProperties = true;
        FEATURES.supportsTransactions = false;
        FEATURES.supportsIndices = false;

        FEATURES.supportsSerializableObjectProperty = false;
        FEATURES.supportsBooleanProperty = true;
        FEATURES.supportsDoubleProperty = true;
        FEATURES.supportsFloatProperty = true;
        FEATURES.supportsIntegerProperty = true;
        FEATURES.supportsPrimitiveArrayProperty = false;
        FEATURES.supportsUniformListProperty = false;
        FEATURES.supportsMixedListProperty = false;
        FEATURES.supportsLongProperty = true;
        FEATURES.supportsMapProperty = false;
        FEATURES.supportsStringProperty = true;

        FEATURES.isWrapper = false;
        FEATURES.supportsKeyIndices = true;
        FEATURES.supportsVertexKeyIndex = true;
        FEATURES.supportsEdgeKeyIndex = true;
        FEATURES.supportsThreadedTransactions = false;
    }

    /**
     * Gets the Dex raw graph.
     *
     * @return Dex raw graph.
     */
    public com.sparsity.dex.gdb.Graph getRawGraph() {
        com.sparsity.dex.gdb.Session sess = getRawSession(false);
        if (sess == null) {
            throw new IllegalStateException("Transaction has not been started");
        }
        return sess.getGraph();
    }

    /**
     * Gets the Dex Session
     *
     * @return The Dex Session
     */
    com.sparsity.dex.gdb.Session getRawSession() {
        return getRawSession(true);
    }

    /**
     * Gets the Dex Session
     *
     * @return The Dex Session
     */
    com.sparsity.dex.gdb.Session getRawSession(boolean exception) {
        com.sparsity.dex.gdb.Session sess = session.get();
        if (sess == null && exception) {
            throw new IllegalStateException("Transaction has not been started");
        }
        return sess;
    }

    /**
     * All iterables are registered here to be automatically closed when the
     * database is stopped (at {@link #shutdown()}).
     */
    private Map<com.sparsity.dex.gdb.Session, List<DexIterable<? extends Element>>> sessCollections =
            new HashMap<com.sparsity.dex.gdb.Session, List<DexIterable<? extends Element>>>();


    /**
     * Registers a collection.
     *
     * @param col Collection to be registered.
     */
    protected synchronized void register(final DexIterable<? extends Element> col) {
        com.sparsity.dex.gdb.Session sess = getRawSession();
        List<DexIterable<? extends Element>> list = sessCollections.get(sess);
        if (list == null) {
            list = new ArrayList<DexIterable<? extends Element>>();
            sessCollections.put(sess, list);
        }
        list.add(col);
        //System.out.println("> register " + sess + ":" + col);
    }

    /**
     * Unregisters a collection.
     *
     * @param col Collection to be unregistered
     */
    protected synchronized void unregister(final DexIterable<? extends Element> col) {
        com.sparsity.dex.gdb.Session sess = getRawSession();
        List<DexIterable<? extends Element>> list = sessCollections.get(sess);
        if (list == null) {
            throw new IllegalStateException("Session with no collections");
        }
        list.remove(col);
        //System.out.println("< unregister " + sess + ":" + col);
    }

    /**
     * Creates a new instance.
     *
     * @param fileName Database persistent file.
     */
    public DexGraph(final String fileName) {
        this(fileName, null);
    }
    
        /**
         * Creates a new instance.
         *
         * @param fileName Database persistent file.
         * @param config Dex configuration file.
         */
    public DexGraph(final String fileName, final String config) {
        try {
            this.dbFile = new File(fileName);
            final File dbPath = dbFile.getParentFile();

            if (!dbPath.exists()) {
                if (!dbPath.mkdirs()) {
                    throw new RuntimeException("Could not create directory");
                }
            }

            final boolean create = !dbFile.exists();

            if (config != null) com.sparsity.dex.gdb.DexProperties.load(config);
            dex = new com.sparsity.dex.gdb.Dex(new com.sparsity.dex.gdb.DexConfig());
            db = (create ? dex.create(dbFile.getPath(), dbFile.getName()) : dex.open(dbFile.getPath(), false));
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Creates a new Vertex.
     * <p/>
     * Given identifier is ignored.
     * <p/>
     * Use {@link #label} to specify the label for the new Vertex.
     * If no label is given, {@value #DEFAULT_DEX_VERTEX_LABEL} will be used.
     *
     * @param id It is ignored.
     * @return Added Vertex.
     * @see com.tinkerpop.blueprints.Graph#addVertex(java.lang.Object)
     * @see #label
     */
    @Override
    public Vertex addVertex(final Object id) {
        autoStartTransaction();
        
        String label = this.label.get() == null ? DEFAULT_DEX_VERTEX_LABEL : this.label.get();
        com.sparsity.dex.gdb.Graph rawGraph = getRawGraph();
        int type = rawGraph.findType(label);
        if (type == com.sparsity.dex.gdb.Type.InvalidType) {
            // First instance of this type, let's create it
            type = rawGraph.newNodeType(label);
        }
        assert type != com.sparsity.dex.gdb.Type.InvalidType;
        // create object instance
        long oid = rawGraph.newNode(type);
        return new DexVertex(this, oid);
    }

    /*
      * (non-Javadoc)
      *
      * @see com.tinkerpop.blueprints.Graph#getVertex(java.lang.Object)
      */
    @Override
    public Vertex getVertex(final Object id) {
        autoStartTransaction();

        if (null == id)
            throw ExceptionFactory.vertexIdCanNotBeNull();
        try {
            final Long longId = Double.valueOf(id.toString()).longValue();
            com.sparsity.dex.gdb.Graph rawGraph = getRawGraph();
            final int type = rawGraph.getObjectType(longId);
            if (type != com.sparsity.dex.gdb.Type.InvalidType)
                return new DexVertex(this, longId);
            else
                return null;
        } catch (NumberFormatException e) {
            return null;
        } catch (RuntimeException re) {
            // dex throws a runtime exception => [DEX: 12] Invalid object identifier.
            return null;
        }
    }

    /*
      * (non-Javadoc)
      *
      * @see
      * com.tinkerpop.blueprints.Graph#removeVertex(com.tinkerpop.blueprints
      * .pgm.Vertex)
      */
    @Override
    public void removeVertex(final Vertex vertex) {
        autoStartTransaction();

        assert vertex instanceof DexVertex;
        getRawGraph().drop((Long) vertex.getId());
    }

    /*
      * (non-Javadoc)
      *
      * @see com.tinkerpop.blueprints.Graph#getVertices()
      */
    @Override
    public CloseableIterable<Vertex> getVertices() {
        autoStartTransaction();

        com.sparsity.dex.gdb.Graph rawGraph = getRawGraph();
        com.sparsity.dex.gdb.TypeList tlist = rawGraph.findNodeTypes();
        List<Iterable<Vertex>> vertices = new ArrayList<Iterable<Vertex>>();
        for (Integer type : tlist) {
            com.sparsity.dex.gdb.Objects objs = rawGraph.select(type);
            vertices.add(new DexIterable<Vertex>(this, objs, Vertex.class));
        }
        tlist.delete();
        tlist = null;
        return new MultiIterable<Vertex>(vertices);
    }

    /**
     * Returns an iterable to all the vertices in the graph that have a particular key/value property.
     * <p/>
     * In case key is {@link StringFactory#LABEL}, it returns an iterable of all the vertices having
     * the given value as the label (therefore, belonging to the given type).
     * <p/>
     * In case {@link #label} is null, it will return all vertices having a particular
     * key/value no matters the type.
     * In case {@link #label} is not null, it will return all vertices having a particular
     * key/value belonging to the given type.
     *
     * @see com.tinkerpop.blueprints.Graph#getVertices(String, Object)
     * @see #label
     */
    @Override
    public CloseableIterable<Vertex> getVertices(final String key, final Object value) {
        autoStartTransaction();

        com.sparsity.dex.gdb.Graph rawGraph = getRawGraph();

        if (key.compareTo(StringFactory.LABEL) == 0) { // label is "indexed"

            int type = rawGraph.findType(value.toString());
            if (type != com.sparsity.dex.gdb.Type.InvalidType) {
                com.sparsity.dex.gdb.Type tdata = rawGraph.getType(type);
                if (tdata.getObjectType() == ObjectType.Node) {
                    com.sparsity.dex.gdb.Objects objs = rawGraph.select(type);
                    return new DexIterable<Vertex>(this, objs, Vertex.class);
                }
            }
            return null;
        }

        String label = this.label.get();
        if (label == null) { // all vertex types

            com.sparsity.dex.gdb.TypeList tlist = rawGraph.findNodeTypes();
            List<Iterable<Vertex>> vertices = new ArrayList<Iterable<Vertex>>();
            for (Integer type : tlist) {
                int attr = rawGraph.findAttribute(type, key);
                if (com.sparsity.dex.gdb.Attribute.InvalidAttribute != attr) {
                    com.sparsity.dex.gdb.Attribute adata = rawGraph.getAttribute(attr);
                    if (adata.getKind() == AttributeKind.Basic) { // "table" scan
                        com.sparsity.dex.gdb.Objects objs = rawGraph.select(type);
                        vertices.add(new PropertyFilteredIterable<Vertex>(key, value, new DexIterable<Vertex>(this, objs, Vertex.class)));
                    } else { // use the index
                        vertices.add(new DexIterable<Vertex>(this, this.rawGet(adata, value), Vertex.class));
                    }
                }
            }
            tlist.delete();
            tlist = null;

            if (vertices.size() > 0) return new MultiIterable<Vertex>(vertices);
            else throw new IllegalArgumentException("The given attribute '" + key + "' does not exist");

        } else { // restricted to a type

            int type = rawGraph.findType(label);
            if (type == com.sparsity.dex.gdb.Type.InvalidType) {
                throw new IllegalArgumentException("Unnexisting vertex label: " + label);
            }
            com.sparsity.dex.gdb.Type tdata = rawGraph.getType(type);
            if (tdata.getObjectType() != com.sparsity.dex.gdb.ObjectType.Node) {
                throw new IllegalArgumentException("Given label is not a vertex label: " + label);
            }

            int attr = rawGraph.findAttribute(type, key);
            if (com.sparsity.dex.gdb.Attribute.InvalidAttribute == attr) {
                throw new IllegalArgumentException("The given attribute '" + key
                        + "' does not exist for the given node label '" + label + "'");
            }

            com.sparsity.dex.gdb.Attribute adata = rawGraph.getAttribute(attr);
            if (adata.getKind() == AttributeKind.Basic) { // "table" scan
                com.sparsity.dex.gdb.Objects objs = rawGraph.select(type);
                return new PropertyFilteredIterable<Vertex>(key, value, new DexIterable<Vertex>(this, objs, Vertex.class));
            } else { // use the index
                return new DexIterable<Vertex>(this, this.rawGet(adata, value), Vertex.class);
            }
        }
    }

    /*
      * (non-Javadoc)
      *
      * @see com.tinkerpop.blueprints.Graph#addEdge(java.lang.Object,
      * com.tinkerpop.blueprints.Vertex, com.tinkerpop.blueprints.Vertex,
      * java.lang.String)
      */
    @Override
    public Edge addEdge(final Object id, final Vertex outVertex, final Vertex inVertex, final String label) {
        autoStartTransaction();

        com.sparsity.dex.gdb.Graph rawGraph = getRawGraph();
        int type = rawGraph.findType(label);
        if (type == com.sparsity.dex.gdb.Type.InvalidType) {
            // First instance of this type, let's create it
            type = rawGraph.newEdgeType(label, true, true);
        }
        assert type != com.sparsity.dex.gdb.Type.InvalidType;
        // create object instance
        assert outVertex instanceof DexVertex && inVertex instanceof DexVertex;
        long oid = rawGraph.newEdge(type, (Long) outVertex.getId(), (Long) inVertex.getId());
        return new DexEdge(this, oid);
    }

    /*
      * (non-Javadoc)
      *
      * @see com.tinkerpop.blueprints.Graph#getEdge(java.lang.Object)
      */
    @Override
    public Edge getEdge(final Object id) {
        autoStartTransaction();

        if (null == id)
            throw ExceptionFactory.edgeIdCanNotBeNull();
        try {
            com.sparsity.dex.gdb.Graph rawGraph = getRawGraph();
            Long longId = Double.valueOf(id.toString()).longValue();
            int type = rawGraph.getObjectType(longId);
            if (type != com.sparsity.dex.gdb.Type.InvalidType)
                return new DexEdge(this, longId);
            else
                return null;
        } catch (NumberFormatException e) {
            return null;
        } catch (RuntimeException re) {
            // dex throws an runtime exception => [DEX: 12] Invalid object identifier.
            return null;
        }

    }

    /*
      * (non-Javadoc)
      *
      * @see
      * com.tinkerpop.blueprints.Graph#removeEdge(com.tinkerpop.blueprints
      * .pgm.Edge)
      */
    @Override
    public void removeEdge(final Edge edge) {
        autoStartTransaction();

        assert edge instanceof DexEdge;
        getRawGraph().drop((Long) edge.getId());
    }

    /*
      * (non-Javadoc)
      *
      * @see com.tinkerpop.blueprints.Graph#getEdges()
      */
    @Override
    public CloseableIterable<Edge> getEdges() {
        autoStartTransaction();

        com.sparsity.dex.gdb.Graph rawGraph = getRawGraph();
        com.sparsity.dex.gdb.TypeList tlist = rawGraph.findEdgeTypes();
        List<Iterable<Edge>> edges = new ArrayList<Iterable<Edge>>();
        for (Integer type : tlist) {
            com.sparsity.dex.gdb.Objects objs = rawGraph.select(type);
            edges.add(new DexIterable<Edge>(this, objs, Edge.class));
        }
        tlist.delete();
        tlist = null;
        return new MultiIterable<Edge>(edges);
    }

    /**
     * Returns an iterable to all the edges in the graph that have a particular key/value property.
     * <p/>
     * In case key is {@link StringFactory#LABEL}, it returns an iterable of all the edges having
     * the given value as the label (therefore, belonging to the given type).
     * <p/>
     * In case {@link #label} is null, it will return all edges having a particular
     * key/value no matters the type.
     * In case {@link #label} is not null, it will return all edges having a particular
     * key/value belonging to the given type.
     *
     * @see com.tinkerpop.blueprints.Graph#getEdges(String, Object)
     * @see #label
     */
    @Override
    public CloseableIterable<Edge> getEdges(final String key, final Object value) {
        autoStartTransaction();

        com.sparsity.dex.gdb.Graph rawGraph = getRawGraph();
        
        if (key.compareTo(StringFactory.LABEL) == 0) { // label is "indexed"

            int type = rawGraph.findType(value.toString());
            if (type != com.sparsity.dex.gdb.Type.InvalidType) {
                com.sparsity.dex.gdb.Type tdata = rawGraph.getType(type);
                if (tdata.getObjectType() == ObjectType.Edge) {
                    com.sparsity.dex.gdb.Objects objs = rawGraph.select(type);
                    return new DexIterable<Edge>(this, objs, Edge.class);
                }
            }
            return null;
        }

        String label = this.label.get();
        if (label == null) { // all vertex types

            com.sparsity.dex.gdb.TypeList tlist = rawGraph.findEdgeTypes();
            List<Iterable<Edge>> edges = new ArrayList<Iterable<Edge>>();
            for (Integer type : tlist) {
                int attr = rawGraph.findAttribute(type, key);
                if (com.sparsity.dex.gdb.Attribute.InvalidAttribute != attr) {
                    com.sparsity.dex.gdb.Attribute adata = rawGraph.getAttribute(attr);
                    if (adata.getKind() == AttributeKind.Basic) { // "table" scan
                        com.sparsity.dex.gdb.Objects objs = rawGraph.select(type);
                        edges.add(new PropertyFilteredIterable<Edge>(key, value, new DexIterable<Edge>(this, objs, Edge.class)));
                    } else { // use the index
                        edges.add(new DexIterable<Edge>(this, this.rawGet(adata, value), Edge.class));
                    }
                }
            }
            tlist.delete();
            tlist = null;

            if (edges.size() > 0) return new MultiIterable<Edge>(edges);
            else throw new IllegalArgumentException("The given attribute '" + key + "' does not exist");

        } else { // restricted to a type

            int type = rawGraph.findType(label);
            if (type == com.sparsity.dex.gdb.Type.InvalidType) {
                throw new IllegalArgumentException("Unnexisting edge label: " + label);
            }
            com.sparsity.dex.gdb.Type tdata = rawGraph.getType(type);
            if (tdata.getObjectType() != com.sparsity.dex.gdb.ObjectType.Edge) {
                throw new IllegalArgumentException("Given label is not a edge label: " + label);
            }

            int attr = rawGraph.findAttribute(type, key);
            if (com.sparsity.dex.gdb.Attribute.InvalidAttribute == attr) {
                throw new IllegalArgumentException("The given attribute '" + key
                        + "' does not exist for the given edge label '" + label + "'");
            }

            com.sparsity.dex.gdb.Attribute adata = rawGraph.getAttribute(attr);
            if (adata.getKind() == AttributeKind.Basic) { // "table" scan
                com.sparsity.dex.gdb.Objects objs = rawGraph.select(type);
                return new PropertyFilteredIterable<Edge>(key, value, new DexIterable<Edge>(this, objs, Edge.class));
            } else { // use the index
                return new DexIterable<Edge>(this, this.rawGet(adata, value), Edge.class);
            }
        }
    }

    /**
     * Closes all non-closed iterables.
     */
    protected synchronized void closeAllSessionCollections() {
        com.sparsity.dex.gdb.Session sess = getRawSession();
        List<DexIterable<? extends Element>> list = sessCollections.get(sess);
        if (list != null) {
            while (list.size() > 0) {
                list.get(0).close(); // closing also unregisters!
            }
        }
        sessCollections.remove(sess);
    }

    /*
      * (non-Javadoc)
      *
      * @see com.tinkerpop.blueprints.Graph#shutdown()
      */
    @Override
    public void shutdown() {
        stopTransaction(Conclusion.SUCCESS);
        
        if (sessCollections.size() > 0) {
            throw new IllegalStateException("Non closed transactions");
        }
        
        db.close();
        dex.close();
    }

    @Override
    public String toString() {
        return StringFactory.graphString(this, dbFile.getPath());
    }

    public Features getFeatures() {
        return FEATURES;
    }

    private com.sparsity.dex.gdb.Objects rawGet(final com.sparsity.dex.gdb.Attribute adata, final Object value) {
        com.sparsity.dex.gdb.Value v = new com.sparsity.dex.gdb.Value();
        switch (adata.getDataType()) {
            case Boolean:
                v.setBooleanVoid((Boolean) value);
                break;
            case Integer:
                v.setIntegerVoid((Integer) value);
                break;
            case Long:
                v.setLongVoid((Long) value);
                break;
            case String:
                v.setStringVoid((String) value);
                break;
            case Double:
                if (value instanceof Double) {
                    v.setDoubleVoid((Double) value);
                } else if (value instanceof Float) {
                    v.setDoubleVoid(((Float) value).doubleValue());
                }
                break;
            default:
                throw new UnsupportedOperationException();
        }
        return this.getRawGraph().select(adata.getId(), com.sparsity.dex.gdb.Condition.Equal, v);
    }

    @Override
    public <T extends Element> void dropKeyIndex(String key,
                                                 Class<T> elementClass) {
        throw new UnsupportedOperationException();
    }

    /**
     * Create an automatic indexing structure for indexing provided key for element class.
     * <p/>
     * Dex attributes are restricted to an specific vertex/edge type. The property
     * {@link #label} must be used to specify the vertex/edge label.
     * <p/>
     * The index could be created even before the vertex/edge label
     * had been created (that is, there are no instances for the given vertex/edge label).
     * If so, this will create the vertex/edge type automatically.
     * The same way, if necessary the attribute will be created automatically.
     * <p/>
     * FIXME: In case the attribute is created, this always creates an String
     * attribute, could this be set somehow?
     *
     * @see com.tinkerpop.blueprints.KeyIndexableGraph#createKeyIndex(String, Class)
     * @see #label
     */
    @Override
    public <T extends Element> void createKeyIndex(String key,
                                                   Class<T> elementClass) {
        autoStartTransaction();

        String label = this.label.get();
        if (label == null) {
            throw new IllegalArgumentException("Label must be given");
        }

        com.sparsity.dex.gdb.Graph rawGraph = getRawGraph();
        
        int type = rawGraph.findType(label);
        if (type == com.sparsity.dex.gdb.Type.InvalidType) {
            // create the node/edge type
            if (Vertex.class.isAssignableFrom(elementClass)) {
                type = rawGraph.newNodeType(label);
            } else if (Edge.class.isAssignableFrom(elementClass)) {
                type = rawGraph.newEdgeType(label, true, true);
            } else {
                throw ExceptionFactory.classIsNotIndexable(elementClass);
            }
        } else {
            // validate the node/edge type
            com.sparsity.dex.gdb.Type tdata = rawGraph.getType(type);
            if (tdata.getObjectType() == ObjectType.Node) {
                if (!Vertex.class.isAssignableFrom(elementClass)) {
                    throw new IllegalArgumentException("Given element class '"
                            + elementClass.getName()
                            + "' is not valid for the given node type '"
                            + label + "'");
                }
            } else if (tdata.getObjectType() == ObjectType.Edge) {
                if (!Edge.class.isAssignableFrom(elementClass)) {
                    throw new IllegalArgumentException("Given element class '"
                            + elementClass.getName()
                            + "' is not valid for the given edge type '"
                            + label + "'");
                }
            }
        }

        int attr = rawGraph.findAttribute(type, key);
        if (com.sparsity.dex.gdb.Attribute.InvalidAttribute == attr) {
            // create the attribute (indexed)
            attr = rawGraph.newAttribute(type, key,
                    com.sparsity.dex.gdb.DataType.String,
                    com.sparsity.dex.gdb.AttributeKind.Indexed);
        } else {
            // it already exists, let's indexe it if necessary
            com.sparsity.dex.gdb.Attribute adata = rawGraph.getAttribute(attr);
            if (adata.getKind() == AttributeKind.Indexed || adata.getKind() == AttributeKind.Unique) {
                throw ExceptionFactory.indexAlreadyExists(label + " " + key);
            }
            rawGraph.indexAttribute(attr,
                    com.sparsity.dex.gdb.AttributeKind.Indexed);
        }
    }

    @Override
    public <T extends Element> Set<String> getIndexedKeys(Class<T> elementClass) {
        autoStartTransaction();

        com.sparsity.dex.gdb.TypeList tlist = null;
        com.sparsity.dex.gdb.Graph rawGraph = getRawGraph();
        if (Vertex.class.isAssignableFrom(elementClass)) {
            tlist = rawGraph.findNodeTypes();
        } else if (Edge.class.isAssignableFrom(elementClass)) {
            tlist = rawGraph.findEdgeTypes();
        } else {
            throw ExceptionFactory.classIsNotIndexable(elementClass);
        }
        boolean found = false;
        Set<String> ret = new HashSet<String>();
        for (Integer type : tlist) {
            com.sparsity.dex.gdb.AttributeList alist = rawGraph.findAttributes(type);
            for (Integer attr : alist) {
                com.sparsity.dex.gdb.Attribute adata = rawGraph.getAttribute(attr);
                if (adata.getKind() == AttributeKind.Indexed || adata.getKind() == AttributeKind.Unique) {
                    ret.add(adata.getName());
                }
            }
            alist.delete();
            alist = null;
        }
        tlist.delete();
        tlist = null;
        return ret;
    }
    
    void autoStartTransaction() {
        com.sparsity.dex.gdb.Session sess = getRawSession(false);
        
        if (sess == null) {
            sess = db.newSession();
            session.set(sess);
            //System.out.println("> th=" + Thread.currentThread().getId() + " starts tx with sess=" + sess);
        } else {
            assert !sess.isClosed();
        }
    }
    
    @Override
    public void stopTransaction(Conclusion conclusion) {
        com.sparsity.dex.gdb.Session sess = getRawSession(false);
        if (sess == null) {
            // already closed session
            return;
        }

        if (Conclusion.FAILURE == conclusion) {
            throw new UnsupportedOperationException("FAILURE conclusion is not supported");
        }
        //
        // Close Session
        //
        closeAllSessionCollections();
        if (sess != null && !sess.isClosed()) {
            sess.close();
        }
        session.set(null);
        
        //System.out.println("< th=" + Thread.currentThread().getId() + " ends tx with sess=" + sess);
    }
}