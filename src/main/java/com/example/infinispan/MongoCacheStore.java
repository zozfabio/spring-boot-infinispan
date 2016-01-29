package com.example.infinispan;

import com.mongodb.*;
import org.infinispan.commons.marshall.StreamingMarshaller;
import org.infinispan.configuration.cache.CustomStoreConfiguration;
import org.infinispan.executors.ExecutorAllCompletionService;
import org.infinispan.filter.KeyFilter;
import org.infinispan.marshall.core.MarshalledEntry;
import org.infinispan.metadata.InternalMetadata;
import org.infinispan.persistence.TaskContextImpl;
import org.infinispan.persistence.spi.AdvancedLoadWriteStore;
import org.infinispan.persistence.spi.InitializationContext;
import org.infinispan.persistence.spi.PersistenceException;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Objects.isNull;
import static java.util.stream.Collectors.toSet;

/**
 * Created by zozfabio on 06/12/15.
 */
public class MongoCacheStore implements AdvancedLoadWriteStore<Object, Object> {

    private InitializationContext context;

    private CustomStoreConfiguration configuration;

    private MongoClient mongo;
    private DB db;
    private DBCollection dbCollection;

    @Override
    public void init(InitializationContext initializationContext) {
        try {
            context = initializationContext;
            configuration = context.getConfiguration();
            mongo = new MongoClient();
            db = mongo.getDB(configuration.properties().getProperty("database"));
            dbCollection = db.getCollection(configuration.properties().getProperty("collection"));
        } catch (UnknownHostException ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public void start() {
        if (configuration.purgeOnStartup()) {
            purge(null, null);
        }
    }

    private Stream<DBObject> parallelStreamAll() {
        return StreamSupport.stream(dbCollection.find().spliterator(), true);
    }

    private Set<byte[]> keySet() {
        return parallelStreamAll()
                .map(dbObject -> (byte[]) dbObject.get("_id"))
                .collect(toSet());
    }

    @Override
    public void process(final KeyFilter<? super Object> keyFilter, final CacheLoaderTask<Object, Object> task, final Executor executor, final boolean fetchValue, final boolean fetchMetadata) {
        final Set<byte[]> keys = keySet();
        final ExecutorAllCompletionService eacs = new ExecutorAllCompletionService(executor);
        final TaskContextImpl taskContext = new TaskContextImpl();
        for (byte[] key : keys) {
            final Object id = toObject(key);
            if (!keyFilter.accept(id)) {
                continue;
            }
            if (taskContext.isStopped()) {
                break;
            }
            eacs.submit(() -> {
                final MarshalledEntry<Object, Object> entry = load(id);
                if (!isNull(entry)) {
                    task.processEntry(entry, taskContext);
                }
                return null;
            });
        }
        eacs.waitUntilAllCompleted();
        if (eacs.isExceptionThrown()) {
            throw new PersistenceException("Execution exception!", eacs.getFirstException());
        }
    }

    @Override
    public int size() {
        return Long.valueOf(dbCollection.count()).intValue();
    }

    @Override
    public void clear() {
        dbCollection.drop();
    }

    private static DBObject whereKeyIs(Object key) {
        return QueryBuilder.start().put("_id").is(key).get();
    }

    @Override
    public void purge(Executor executor, PurgeListener<? super Object> purgeListener) {
        dbCollection.remove(QueryBuilder.start()
                .put("expiryTime").lessThanEquals(new Date()).get());
    }

    @Override
    public MarshalledEntry<Object, Object> load(Object key) {
        DBObject object = dbCollection.findOne(whereKeyIs(key));

        if (isNull(object)) return null;

        Object value = toObject((byte[]) object.get("value"));
        InternalMetadata metadata = (InternalMetadata) toObject((byte[]) object.get("metadata"));
        Date expiryTime = (Date) object.get("expiryTime");

        if (new Date().compareTo(expiryTime) > 0) {
            delete(key);
            return null;
        }

        return context.getMarshalledEntryFactory().newMarshalledEntry(key, value, metadata);
    }

    @Override
    public boolean contains(Object key) {
        return dbCollection.find(whereKeyIs(key)).hasNext();
    }

    @Override
    public void write(MarshalledEntry<?, ?> entry) {
        Object key = entry.getKey();
        Object value = entry.getValue();
        InternalMetadata metadata = entry.getMetadata();
        Date expiryTime = new Date(entry.getMetadata().expiryTime());

        dbCollection.save(BasicDBObjectBuilder.start()
                .add("_id", key.toString())
                .add("value", toByteArray(value))
                .add("metadata", toByteArray(metadata))
                .add("expiryTime", expiryTime).get());
    }

    @Override
    public boolean delete(Object key) {
        return dbCollection.remove(whereKeyIs(key)).getN() > 0;
    }

    @Override
    public void stop() {
        mongo.close();
    }

    private Object toObject(byte[] bytes) {
        try {
            return marshaller().objectFromByteBuffer(bytes);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }

    private byte[] toByteArray(Object obj) {
        try {
            return marshaller().objectToByteBuffer(obj);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return null;
    }

    private StreamingMarshaller marshaller() {
        return context.getMarshaller();
    }
}
