/*
 * (C) Copyright 2018-2018, by CHEN Kui and Contributors.
 *
 * JGraphT : a free Java graph-theory library
 *
 * This program and the accompanying materials are dual-licensed under
 * either
 *
 * (a) the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation, or (at your option) any
 * later version.
 *
 * or (per the licensee's choosing)
 *
 * (b) the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation.
 */
package org.jgrapht.graph.concurrent;

import java.io.*;
import java.lang.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.function.*;
import java.util.stream.*;
import org.jgrapht.*;
import org.jgrapht.graph.*;

/**
 * Create a synchronized (thread-safe) Graph backed by the specified Graph. This Graph is designed
 * to support concurrent reads which are mutually exclusive with writes. In order to guarantee serial
 * access, it is critical that <strong>all</strong> access to the backing Graph is accomplished
 * through the created Graph.
 *
 * <p>
 * Users need to manually synchronize on {@link EdgeFactory} if creating an edge needs to access
 * shared resources. Failure to follow this advice may result in non-deterministic behavior.
 * </p>
 *
 * <p>
 * For all methods returning a Set, the Graph guarantees that all operations on the returned Set do
 * not affect the backing Graph. For <code>edgeSet</code> and <code>vertexSet</code> methods, the
 * returned Set is backed by the underlying graph, but when a traversal over the set is started via
 * a method such as iterator(), a snapshot of the underlying Set is copied for iteration purposes.
 * For <code>edgesOf</code>, <code>incomingEdgesOf</code> and <code>outgoingEdgesOf</code> methods,
 * the returned Set is a unmodifiable copy of the result produced by the underlying Graph. Users can
 * control whether those copies should be cached; caching may significantly increase memory
 * requirements. If users decide to cache those copies and the backing graph's changes don't affect
 * them, those copies will be returned the next time the method is called. If the backing graph's
 * changes affect them, they will be removed from cache and re-created the next time the method is
 * called. If users decide to not cache those copies, the graph will create ephemeral copies every
 * time the method is called. For other methods returning a Set, the Set is just the backing Graph's
 * return.
 * </p>

 * <p>
 * Even though this graph implementation is thread-safe, callers should still be aware of potential
 * hazards from removal methods. If calling code obtains a reference to a vertex or edge from the
 * graph, and then calls another graph method to access information about that object, an
 * {@link IllegalArgumentException} may be thrown if another thread has concurrently removed that
 * object. Therefore, calling the remove methods concurrently with a typical algorithm is likely to
 * cause the algorithm to fail with an {@link IllegalArgumentException}. So really the main
 * concurrent read/write use case is add-only.
 * <br>
 * eg: If threadA tries to get all edges touching a certain vertex after threadB removes the vertex,
 * the algorithm will be interrupted by {@link IllegalArgumentException}.
 * </p>
 * <pre>
 *      Thread threadA = new Thread(() -&gt; {
 *          Set vertices = graph.vertexSet();
 *          for (Object v : vertices) {
 *              // {@link IllegalArgumentException} may be thrown since other threads may have removed the vertex.
 *              Set edges = graph.edgesOf(v);
 *              doOtherThings();
 *          }
 *      });
 *      Thread threadB = new Thread(() -&gt; {
 *          Set vertices = graph.vertexSet();
 *          for (Object v : vertices) {
 *              if (someConditions)
 *                  graph.removeVertex(v);
 *          }
 *      });
 * </pre>
 *
 * <p>
 * The created Graph's hashCode is equal to the backing set's hashCode. And the created Graph is equal
 * to another Graph if they are the same Graph or the backing Graph is equal to the other Graph.
 * </p>
 *
 * @param <V> the graph vertex type
 * @param <E> the graph edge type
 *
 * @author CHEN Kui
 * @since Feb 23, 2018
 */
public class AsSynchronizedGraph<V, E>
    extends GraphDelegator<V, E>
    implements Graph<V, E>, Serializable
{
    private static final long serialVersionUID = 5144561442831050752L;

    private final ReentrantReadWriteLock readWriteLock;

    // A set encapsulating backing vertexSet.
    private transient CopyOnDemandSet<V> allVerticesSet;

    // A set encapsulating backing edgeSet.
    private transient CopyOnDemandSet<E> allEdgesSet;

    private CacheStrategy<V, E> cacheStrategy;

    /**
     * Constructor for AsSynchronizedGraph with strategy of not caching the copies for
     * <code>edgesOf</code>, <code>incomingEdgesOf</code> and <code>outgoingEdgesOf</code> methods
     * and non-fair mode for thread-access.
     *
     * @param g the backing graph (the delegate)
     */
    public AsSynchronizedGraph(Graph<V, E> g)
    {
        this(g, false, false);
    }

    /**
     * Constructor for AsSynchronizedGraph with specified properties.
     *
     * @param g the backing graph (the delegate)
     * @param cacheEnable a flag describing whether a cache will be used
     * @param fair a flag describing whether fair model will be used
     */
    private AsSynchronizedGraph(Graph<V, E> g, boolean cacheEnable, boolean fair)
    {
        super(g);
        readWriteLock = new ReentrantReadWriteLock(fair);
        if (cacheEnable)
            cacheStrategy = new CacheAccess();
        else
            cacheStrategy = new NoCache();
        allEdgesSet = new CopyOnDemandSet<>(super.edgeSet(), readWriteLock);
        allVerticesSet = new CopyOnDemandSet<>(super.vertexSet(), readWriteLock);

        // Ensure that the underlying data structure is completely constructed. For some of Set are built lazily in underlying data structure.
        for (V v : allVerticesSet) {
            inDegreeOf(v);
            outDegreeOf(v);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<E> getAllEdges(V sourceVertex, V targetVertex)
    {
        readWriteLock.readLock().lock();
        try {
            return super.getAllEdges(sourceVertex, targetVertex);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public E getEdge(V sourceVertex, V targetVertex)
    {
        readWriteLock.readLock().lock();
        try {
            return super.getEdge(sourceVertex, targetVertex);
        } finally {
            readWriteLock.readLock().unlock();
        }
            
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public E addEdge(V sourceVertex, V targetVertex)
    {
        readWriteLock.writeLock().lock();
        try {
            E e = cacheStrategy.addEdge(sourceVertex, targetVertex);
            if (e != null)
                edgeSetModified();
            return e;
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean addEdge(V sourceVertex, V targetVertex, E e)
    {
        readWriteLock.writeLock().lock();
        try {
             if (cacheStrategy.addEdge(sourceVertex, targetVertex, e)) {
                 edgeSetModified();
                 return true;
             }
             return false;
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean addVertex(V v)
    {
        readWriteLock.writeLock().lock();
        try {
            if (super.addVertex(v)) {
                vertexSetModified();

                // Ensure that the underlying data structure is completely constructed. For some of Set are built lazily in underlying data structure.
                inDegreeOf(v);
                outDegreeOf(v);
                return true;
            }
            return false;
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsEdge(V sourceVertex, V targetVertex)
    {
        readWriteLock.readLock().lock();
        try {
            return super.containsEdge(sourceVertex, targetVertex);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsEdge(E e)
    {
        readWriteLock.readLock().lock();
        try {
            return super.containsEdge(e);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsVertex(V v)
    {
        readWriteLock.readLock().lock();
        try {
            return super.containsVertex(v);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int degreeOf(V vertex)
    {
        readWriteLock.readLock().lock();
        try {
            return super.degreeOf(vertex);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<E> edgeSet()
    {
        return allEdgesSet;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<E> edgesOf(V vertex)
    {
        readWriteLock.readLock().lock();
        try {
            return cacheStrategy.edgesOf(vertex);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int inDegreeOf(V vertex)
    {
        readWriteLock.readLock().lock();
        try {
            return super.inDegreeOf(vertex);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<E> incomingEdgesOf(V vertex)
    {
        readWriteLock.readLock().lock();
        try {
            return cacheStrategy.incomingEdgesOf(vertex);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int outDegreeOf(V vertex)
    {
        readWriteLock.readLock().lock();
        try {
            return super.outDegreeOf(vertex);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<E> outgoingEdgesOf(V vertex)
    {
        readWriteLock.readLock().lock();
        try {
            return cacheStrategy.outgoingEdgesOf(vertex);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeAllEdges(Collection<? extends E> edges)
    {
        readWriteLock.writeLock().lock();
        try {
            return super.removeAllEdges(edges);
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<E> removeAllEdges(V sourceVertex, V targetVertex)
    {
        readWriteLock.writeLock().lock();
        try {
            return super.removeAllEdges(sourceVertex, targetVertex);
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeAllVertices(Collection<? extends V> vertices)
    {
        readWriteLock.writeLock().lock();
        try {
            return super.removeAllVertices(vertices);
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeEdge(E e)
    {
        readWriteLock.writeLock().lock();
        try {
            if (cacheStrategy.removeEdge(e)) {
                edgeSetModified();
                return true;
            }
            return false;
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public E removeEdge(V sourceVertex, V targetVertex)
    {
        readWriteLock.writeLock().lock();
        try {
            E e = cacheStrategy.removeEdge(sourceVertex, targetVertex);
            if (e != null)
                edgeSetModified();
            return e;
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeVertex(V v)
    {
        readWriteLock.writeLock().lock();
        try {
            if (cacheStrategy.removeVertex(v)) {
                edgeSetModified();
                vertexSetModified();
                return true;
            }
            return false;
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        readWriteLock.readLock().lock();
        try {
            return super.toString();
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<V> vertexSet()
    {
        return allVerticesSet;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V getEdgeSource(E e)
    {
        readWriteLock.readLock().lock();
        try {
            return super.getEdgeSource(e);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V getEdgeTarget(E e)
    {
        readWriteLock.readLock().lock();
        try {
            return super.getEdgeTarget(e);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getEdgeWeight(E e)
    {
        readWriteLock.readLock().lock();
        try {
            return super.getEdgeWeight(e);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setEdgeWeight(E e, double weight)
    {
        readWriteLock.writeLock().lock();
        try {
            super.setEdgeWeight(e, weight);
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    /**
     * Return whether the graph uses cache for <code>edgesOf</code>, <code>incomingEdgesOf</code> and
     * <code>outgoingEdgesOf</code> methods.
     * @return <tt>true</tt> if cache is in use, <tt>false</tt> if cache is not in use.
     */
    public boolean isCacheEnabled()
    {
        readWriteLock.readLock().lock();
        try {
            return cacheStrategy.isCacheEnabled();
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * Set the cache strategy for <code>edgesOf</code>, <code>incomingEdgesOf</code> and
     * <code>outgoingEdgesOf</code> methods.
     *
     * @param cacheEnabled a flag whether to use cache for those methods, if <tt>true</tt>, cache
     *        will be used for those methods, otherwise cache will not be used.
     * @return the AsSynchronizedGraph
     */
    public AsSynchronizedGraph<V, E> setCache(boolean cacheEnabled)
    {
        readWriteLock.writeLock().lock();
        try {
            if (cacheEnabled == isCacheEnabled())
                return this;
            if (cacheEnabled)
                cacheStrategy = new CacheAccess();
            else
                cacheStrategy = new NoCache();
            return this;
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode()
    {
        readWriteLock.readLock().lock();
        try {
            return getDelegate().hashCode();
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        readWriteLock.readLock().lock();
        try {
            return getDelegate().equals(o);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * Create a unmodifiable copy of the set.
     *
     * @param set the set to be copied.
     *
     * @return a unmodifiable copy of the set
     */
    private <C> Set<C> copySet(Set<C> set)
    {
        return Collections.unmodifiableSet(new LinkedHashSet<>(set));
    }

    /**
     * Inform allVerticesSet that the backing data has been modified.
     */
    private void vertexSetModified()
    {
        allVerticesSet.modified();
    }

    /**
     * Inform allEdgesSet that the backing data has been modified.
     */
    private void edgeSetModified()
    {
        allEdgesSet.modified();
    }

    /**
     * Return whether fair mode will be used for the graph.
     * @return <tt>true</tt> if the graph uses fair mode, <tt>false</tt> if non-fair model
     */
    public boolean isFair()
    {
        return readWriteLock.isFair();
    }

    /**
     * Create a synchronized (thread-safe) and unmodifiable Set backed by the specified Set. In order
     * to guarantee serial access, it is critical that <strong>all</strong> access to the backing
     * Set is accomplished through the created Set.
     *
     * <p>
     * When a traversal over the set is started via a method such as iterator(), a snapshot of the
     * underlying set is copied for iteration purposes.
     * </p>
     *
     * <p>
     * The created Set's hashCode is equal to the backing Set's hashCode. And the created Set is equal
     * to another set if they are the same Set or the backing Set is equal to the other Set.
     * </p>
     *
     * <p>
     * The created set will be serializable if the backing set is serializable.
     * </p>
     *
     * @param <E> the class of the objects in the set
     *
     * @author CHEN Kui
     * @since Feb 23, 2018
     */
    private static class CopyOnDemandSet<E>
            implements Set<E>, Serializable
    {
        private static final long serialVersionUID = -102323563687847936L;

        // Backing set.
        private Set<E> set;

        // Backing set's unmodifiable copy. If null, needs to be recomputed on next access.
        volatile private transient Set<E> copy;

        final ReadWriteLock readWriteLock;

        private static final String UNMODIFIABLE = "this set is unmodifiable";

        /**
         * Constructor for CopyOnDemandSet.
         * @param s the backing set.
         * @param readWriteLock the ReadWriteLock on which to locked
         */
        private CopyOnDemandSet(Set<E> s, ReadWriteLock readWriteLock)
        {
            set = Objects.requireNonNull(s, "s must not be null");
            copy = null;
            this.readWriteLock = readWriteLock;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int size()
        {
            readWriteLock.readLock().lock();
            try {
                return set.size();
            } finally {
                readWriteLock.readLock().unlock();
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isEmpty()
        {
            readWriteLock.readLock().lock();
            try {
                return set.isEmpty();
            } finally {
                readWriteLock.readLock().unlock();
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean contains(Object o)
        {
            readWriteLock.readLock().lock();
            try {
                return set.contains(o);
            } finally {
                readWriteLock.readLock().unlock();
            }
        }

        /**
         * Returns an iterator over the elements in the backing set's unmodifiable copy. The elements
         * are returned in the same order of the backing set.
         *
         * @return an iterator over the elements in the backing set's unmodifiable copy.
         */
        @Override
        public Iterator<E> iterator()
        {
            return getCopy().iterator();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object[] toArray()
        {
            readWriteLock.readLock().lock();
            try {
                return set.toArray();
            } finally {
                readWriteLock.readLock().unlock();
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public <T> T[] toArray(T[] a)
        {
            readWriteLock.readLock().lock();
            try {
                return set.toArray(a);
            } finally {
                readWriteLock.readLock().unlock();
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean add(E e)
        {
            throw new UnsupportedOperationException(UNMODIFIABLE);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean remove(Object o)
        {
            throw new UnsupportedOperationException(UNMODIFIABLE);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean containsAll(Collection<?> c)
        {
            readWriteLock.readLock().lock();
            try {
                return set.containsAll(c);
            } finally {
                readWriteLock.readLock().unlock();
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean addAll(Collection<? extends E> c)
        {
            throw new UnsupportedOperationException(UNMODIFIABLE);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean retainAll(Collection<?> c)
        {
            throw new UnsupportedOperationException(UNMODIFIABLE);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean removeAll(Collection<?> c)
        {
            throw new UnsupportedOperationException(UNMODIFIABLE);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void clear()
        {
            throw new UnsupportedOperationException(UNMODIFIABLE);
        }

        /**
         * {@inheritDoc}
         */
        // Override default methods in Collection
        @Override
        public void forEach(Consumer<? super E> action)
        {
            readWriteLock.readLock().lock();
            try {
                set.forEach(action);
            } finally {
                readWriteLock.readLock().unlock();
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean removeIf(Predicate<? super E> filter)
        {
            throw new UnsupportedOperationException(UNMODIFIABLE);
        }

        /**
         * Creates a <Code>Spliterator</code> over the elements in the set's unmodifiable copy.
         *
         * @return a  <code>Spliterator</code> over the elements in the backing set's unmodifiable copy.
         */
        @Override
        public Spliterator<E> spliterator()
        {
            return getCopy().spliterator();
        }

        /**
         * Return a sequential <code>Stream</code> with the backing set's unmodifiable copy as its
         * source.
         * @return a sequential <code>Stream</code> with the backing set's unmodifiable copy as its
         * source.
         */
        @Override
        public Stream<E> stream()
        {
            return getCopy().stream();
        }

        /**
         * Return a possibly parallel <code>Stream</code> with the backing set's unmodifiable copy as
         * its source.
         * @return a possibly parallel <code>Stream</code> with the backing set's unmodifiable copy
         * as its source.
         */
        @Override
        public Stream<E> parallelStream()
        {
            return getCopy().parallelStream();
        }

        /**
         * Compares the specified object with this set for equality.
         * @param o object to be compared for equality with this set.
         * @return <code>true</code> if o and this set are the same object or o is equal to the
         * backing object, false otherwise.
         */
        @Override
        public boolean equals(Object o)
        {
            if (this == o)
                return true;
            readWriteLock.readLock().lock();
            try {
                return set.equals(o);
            } finally {
                readWriteLock.readLock().unlock();
            }
        }

        /**
         * Return the backing set's hashcode.
         * @return the backing set's hashcode.
         */
        @Override
        public int hashCode()
        {
            readWriteLock.readLock().lock();
            try {
                return set.hashCode();
            } finally {
                readWriteLock.readLock().unlock();
            }
        }

        /**
         * Return the backing set's toString result.
         * @return the backing set's toString result.
         */
        @Override
        public String toString()
        {
            readWriteLock.readLock().lock();
            try {
                return set.toString();
            } finally {
                readWriteLock.readLock().unlock();
            }
        }

        /**
         * Get the backing set's unmodifiable copy.
         * @return the backing set's unmodifiable copy.
         */
        private Set<E> getCopy()
        {
            readWriteLock.readLock().lock();
            try {
                Set tempCopy = copy;
                if (tempCopy == null) {
                    synchronized (this) {
                        tempCopy = copy;
                        if (tempCopy == null) {
                            copy = tempCopy = new LinkedHashSet(set);
                        }
                    }
                }
                return tempCopy;
            } finally {
                readWriteLock.readLock().unlock();
            }
        }

        /**
         * If the backing set is modified, call this method to let this set knows the backing set's
         * copy need to update.
         */
        private void modified()
        {
            copy = null;
        }
    }

    /**
     * An interface for cache strategy of AsSynchronizedGraph's <code>edgesOf</code>,
     * <code>incomingEdgesOf</code> and <code>outgoingEdgesOf</code> methods.
     */
    private interface CacheStrategy<V, E>
    {
        /**
         * Add an edge into AsSynchronizedGraph's backing graph.
         */
        E addEdge(V sourceVertex, V targetVertex);

        /**
         * Add an edge into AsSynchronizedGraph's backing graph.
         */
        boolean addEdge(V sourceVertex, V targetVertex, E e);

        /**
         * Get all edges touching the specified vertex in AsSynchronizedGraph's backing graph.
         */
        Set<E> edgesOf(V vertex);

        /**
         * Get a set of all edges in AsSynchronizedGraph's backing graph incoming into the specified
         * vertex.
         */
        Set<E> incomingEdgesOf(V vertex);

        /**
         * Get a set of all edges in AsSynchronizedGraph's backing graph outgoing from the specified
         * vertex.
         */
        Set<E> outgoingEdgesOf(V vertex);

        /**
         * Remove the specified edge from AsSynchronizedGraph's backing graph.
         */
        boolean removeEdge(E e);

        /**
         * Remove an edge from AsSynchronizedGraph's backing graph.
         */
        E removeEdge(V sourceVertex, V targetVertex);

        /**
         * Remove the specified vertex from AsSynchronizedGraph's backing graph.
         */
        boolean removeVertex(V v);

        /**
         * Return whether the graph uses cache for <code>edgesOf</code>, <code>incomingEdgesOf</code>
         * and <code>outgoingEdgesOf</code> methods.
         * @return <tt>true</tt> if cache is in use, <tt>false</tt> if cache is not in use.
         */
        boolean isCacheEnabled();
    }

    /**
     * Don't use cache for AsSynchronizedGraph's <code>edgesOf</code>, <code>incomingEdgesOf</code>
     * and <code>outgoingEdgesOf</code> methods.
     */
    private class NoCache
        implements CacheStrategy<V, E>, Serializable
    {
        private static final long serialVersionUID = 19246150051213471L;

        /**
         * {@inheritDoc}
         */
        @Override
        public E addEdge(V sourceVertex, V targetVertex)
        {
            return AsSynchronizedGraph.super.addEdge(sourceVertex, targetVertex);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean addEdge(V sourceVertex, V targetVertex, E e)
        {
            return AsSynchronizedGraph.super.addEdge(sourceVertex, targetVertex, e);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Set<E> edgesOf(V vertex)
        {
            return copySet(AsSynchronizedGraph.super.edgesOf(vertex));
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Set<E> incomingEdgesOf(V vertex)
        {
            return copySet(AsSynchronizedGraph.super.incomingEdgesOf(vertex));
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Set<E> outgoingEdgesOf(V vertex)
        {
            return copySet(AsSynchronizedGraph.super.outgoingEdgesOf(vertex));
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean removeEdge(E e)
        {
            return AsSynchronizedGraph.super.removeEdge(e);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public E removeEdge(V sourceVertex, V targetVertex)
        {
            return AsSynchronizedGraph.super.removeEdge(sourceVertex, targetVertex);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean removeVertex(V v)
        {
            return AsSynchronizedGraph.super.removeVertex(v);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isCacheEnabled()
        {
            return false;
        }
    }

    /**
     * Use cache for AsSynchronizedGraph's <code>edgesOf</code>, <code>incomingEdgesOf</code>
     * and <code>outgoingEdgesOf</code> methods.
     */
    private class CacheAccess
        implements CacheStrategy<V, E>, Serializable
    {
        private static final long serialVersionUID = -18262921841829294L;

        // A map caching for incomingEdges operation.
        private final transient Map<V, Set<E>> incomingEdgesMap = new ConcurrentHashMap<>();

        // A map caching for outgoingEdges operation.
        private final transient Map<V, Set<E>> outgoingEdgesMap = new ConcurrentHashMap<>();

        // A map caching for edgesOf operation.
        private final transient Map<V, Set<E>> edgesOfMap = new ConcurrentHashMap<>();

        /**
         * {@inheritDoc}
         */
        @Override
        public E addEdge(V sourceVertex, V targetVertex)
        {
            E e = AsSynchronizedGraph.super.addEdge(sourceVertex, targetVertex);
            if (e != null)
                edgeModified(sourceVertex, targetVertex);
            return e;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean addEdge(V sourceVertex, V targetVertex, E e)
        {
            if (AsSynchronizedGraph.super.addEdge(sourceVertex, targetVertex, e)) {
                edgeModified(sourceVertex, targetVertex);
                return true;
            }
            return false;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Set<E> edgesOf(V vertex)
        {
            Set<E> s = edgesOfMap.get(vertex);
            if (s != null)
                return s;
            s = copySet(AsSynchronizedGraph.super.edgesOf(vertex));
            edgesOfMap.put(vertex, s);
            return s;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Set<E> incomingEdgesOf(V vertex)
        {
            Set<E> s = incomingEdgesMap.get(vertex);
            if (s != null)
                return s;
            s = copySet(AsSynchronizedGraph.super.incomingEdgesOf(vertex));
            incomingEdgesMap.put(vertex, s);
            return s;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Set<E> outgoingEdgesOf(V vertex)
        {
            Set<E> s = outgoingEdgesMap.get(vertex);
            if (s != null)
                return s;
            s = copySet(AsSynchronizedGraph.super.outgoingEdgesOf(vertex));
            outgoingEdgesMap.put(vertex, s);
            return s;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean removeEdge(E e)
        {
            V sourceVertex = getEdgeSource(e);
            V targetVertex = getEdgeTarget(e);
            if (AsSynchronizedGraph.super.removeEdge(e)) {
                edgeModified(sourceVertex, targetVertex);
                return true;
            }
            return false;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public E removeEdge(V sourceVertex, V targetVertex)
        {
            E e = AsSynchronizedGraph.super.removeEdge(sourceVertex, targetVertex);
            if (e != null)
                edgeModified(sourceVertex, targetVertex);
            return e;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean removeVertex(V v)
        {
            if (AsSynchronizedGraph.super.removeVertex(v)) {
                edgesOfMap.clear();
                incomingEdgesMap.clear();
                outgoingEdgesMap.clear();
                return true;
            }
            return false;
        }

        /**
         * Clear the copies which the edge to be added or removed can affect.
         *
         * @param sourceVertex source vertex of the modified edge.
         * @param targetVertex target vertex of the modified edge.
         */
        private void edgeModified(V sourceVertex, V targetVertex)
        {
            outgoingEdgesMap.remove(sourceVertex);
            incomingEdgesMap.remove(targetVertex);
            edgesOfMap.remove(sourceVertex);
            edgesOfMap.remove(targetVertex);
            if (!AsSynchronizedGraph.super.getType().isDirected()) {
                outgoingEdgesMap.remove(targetVertex);
                incomingEdgesMap.remove(sourceVertex);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isCacheEnabled()
        {
            return true;
        }
    }

    /**
     * A builder for {@link AsSynchronizedGraph}.
     *
     * @author CHEN Kui
     */
    public static class Builder
    {
        private boolean cacheEnable;
        private boolean fair;

        /**
         * Construct a new Builder with cacheDisable and non-fair mode.
         */
        public Builder()
        {
            cacheEnable = false;
            fair = false;
        }

        /**
         * Construct a new Builder.
         *
         * @param graph the graph to base the builder
         */
        public Builder(AsSynchronizedGraph graph)
        {
            this.cacheEnable = graph.isCacheEnabled();
            this.fair = graph.isFair();
        }

        /**
         * Request a synchronized graph using non-fair mode without caching.
         *
         * @return the Builder
         */
        public Builder cacheDisable()
        {
            cacheEnable = false;
            return this;
        }

        /**
         * Request a synchronized graph with caching.
         *
         * @return the Builder
         */
        public Builder cacheEnable()
        {
            cacheEnable = true;
            return this;
        }

        /**
         * Return whether a cache will be used for the synchronized graph being built.
         *
         * @return <tt>true</tt> if cache will be used, <tt>false</tt> if cache will not be use
         */
        public boolean isCacheEnable() {
            return cacheEnable;
        }

        /**
         * Request a synchronized graph with fair mode.
         *
         * @return the SynchronizedGraphParams
         */
        public Builder setFair()
        {
            fair = true;
            return this;
        }

        /**
         * Request a synchronized graph with non-fair mode.
         * @return the SynchronizedGraphParams
         */
        public Builder setNonfair()
        {
            fair = false;
            return this;
        }

        /**
         * Return whether fair model will be used for the synchronized graph being built.
         *
         * @return <tt>true</tt> if constructed as fair mode, <tt>false</tt> if non-fair
         */
        public boolean isFair()
        {
            return fair;
        }

        /**
         * Build the AsSynchronizedGraph.
         *
         * @param graph the backing graph (the delegate)
         * @param <V> the graph vertex type
         * @param <E> the graph edge type
         * @return the AsSynchronizedGraph
         */
        public <V, E> AsSynchronizedGraph build(Graph<V, E> graph)
        {
            return new AsSynchronizedGraph<>(graph, cacheEnable, fair);
        }
    }
}

// End AsSynchronizedGraph.java
