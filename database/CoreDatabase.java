/*
 * Copyright (C) 2022 Vaticle
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.vaticle.typedb.core.database;

import com.vaticle.typedb.common.collection.ConcurrentSet;
import com.vaticle.typedb.common.collection.Pair;
import com.vaticle.typedb.core.TypeDB;
import com.vaticle.typedb.core.common.collection.ByteArray;
import com.vaticle.typedb.core.common.exception.TypeDBException;
import com.vaticle.typedb.core.common.iterator.FunctionalIterator;
import com.vaticle.typedb.core.common.parameters.Arguments;
import com.vaticle.typedb.core.common.parameters.Options;
import com.vaticle.typedb.core.concept.type.Type;
import com.vaticle.typedb.core.concurrent.executor.Executors;
import com.vaticle.typedb.core.encoding.Encoding;
import com.vaticle.typedb.core.encoding.iid.VertexIID;
import com.vaticle.typedb.core.encoding.key.Key;
import com.vaticle.typedb.core.encoding.key.KeyGenerator;
import com.vaticle.typedb.core.encoding.key.StatisticsKey;
import com.vaticle.typedb.core.graph.TypeGraph;
import com.vaticle.typedb.core.graph.edge.ThingEdge;
import com.vaticle.typedb.core.graph.vertex.AttributeVertex;
import com.vaticle.typedb.core.graph.vertex.ThingVertex;
import com.vaticle.typedb.core.graph.vertex.TypeVertex;
import com.vaticle.typedb.core.logic.LogicCache;
import com.vaticle.typedb.core.traversal.TraversalCache;
import com.vaticle.typeql.lang.TypeQL;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.OptimisticTransactionDB;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.StampedLock;
import java.util.stream.Stream;

import static com.vaticle.typedb.common.collection.Collections.list;
import static com.vaticle.typedb.common.collection.Collections.pair;
import static com.vaticle.typedb.common.collection.Collections.set;
import static com.vaticle.typedb.core.common.collection.ByteArray.encodeLong;
import static com.vaticle.typedb.core.common.collection.ByteArray.encodeLongs;
import static com.vaticle.typedb.core.common.exception.ErrorMessage.Database.DATABASE_CLOSED;
import static com.vaticle.typedb.core.common.exception.ErrorMessage.Database.INCOMPATIBLE_ENCODING;
import static com.vaticle.typedb.core.common.exception.ErrorMessage.Database.INVALID_DATABASE_DIRECTORIES;
import static com.vaticle.typedb.core.common.exception.ErrorMessage.Database.ROCKS_LOGGER_SHUTDOWN_TIMEOUT;
import static com.vaticle.typedb.core.common.exception.ErrorMessage.Database.STATISTICS_CORRECTOR_SHUTDOWN_TIMEOUT;
import static com.vaticle.typedb.core.common.exception.ErrorMessage.Internal.DIRTY_INITIALISATION;
import static com.vaticle.typedb.core.common.exception.ErrorMessage.Internal.ILLEGAL_STATE;
import static com.vaticle.typedb.core.common.exception.ErrorMessage.Internal.RESOURCE_CLOSED;
import static com.vaticle.typedb.core.common.exception.ErrorMessage.Session.SCHEMA_ACQUIRE_LOCK_TIMEOUT;
import static com.vaticle.typedb.core.common.exception.ErrorMessage.Transaction.TRANSACTION_ISOLATION_DELETE_MODIFY_VIOLATION;
import static com.vaticle.typedb.core.common.exception.ErrorMessage.Transaction.TRANSACTION_ISOLATION_EXCLUSIVE_CREATE_VIOLATION;
import static com.vaticle.typedb.core.common.exception.ErrorMessage.Transaction.TRANSACTION_ISOLATION_MODIFY_DELETE_VIOLATION;
import static com.vaticle.typedb.core.common.iterator.Iterators.iterate;
import static com.vaticle.typedb.core.common.iterator.Iterators.link;
import static com.vaticle.typedb.core.common.parameters.Arguments.Session.Type.DATA;
import static com.vaticle.typedb.core.common.parameters.Arguments.Session.Type.SCHEMA;
import static com.vaticle.typedb.core.common.parameters.Arguments.Transaction.Type.READ;
import static com.vaticle.typedb.core.common.parameters.Arguments.Transaction.Type.WRITE;
import static com.vaticle.typedb.core.concurrent.executor.Executors.serial;
import static com.vaticle.typedb.core.encoding.Encoding.ENCODING_VERSION;
import static com.vaticle.typedb.core.encoding.Encoding.ROCKS_DATA;
import static com.vaticle.typedb.core.encoding.Encoding.ROCKS_SCHEMA;
import static com.vaticle.typedb.core.encoding.Encoding.System.ENCODING_VERSION_KEY;
import static java.util.Comparator.reverseOrder;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class CoreDatabase implements TypeDB.Database {

    private static final Logger LOG = LoggerFactory.getLogger(CoreDatabase.class);
    private static final int ROCKS_LOG_PERIOD = 300;

    private final CoreDatabaseManager databaseMgr;
    private final Factory.Session sessionFactory;
    protected final String name;
    protected final AtomicBoolean isOpen;
    private final AtomicLong nextTransactionID;
    private final AtomicInteger schemaLockWriteRequests;
    private final StampedLock schemaLock;
    protected final ConcurrentMap<UUID, Pair<CoreSession, Long>> sessions;
    protected final RocksConfiguration rocksConfiguration;
    protected final KeyGenerator.Schema.Persisted schemaKeyGenerator;
    protected final KeyGenerator.Data.Persisted dataKeyGenerator;
    private final IsolationManager isolationMgr;
    private final StatisticsCorrector statisticsCorrector;
    protected OptimisticTransactionDB rocksSchema;
    protected OptimisticTransactionDB rocksData;
    protected CorePartitionManager.Schema rocksSchemaPartitionMgr;
    protected CorePartitionManager.Data rocksDataPartitionMgr;
    protected CoreSession.Data statisticsBackgroundCounterSession;
    protected ScheduledExecutorService scheduledPropertiesLogger;
    private Cache cache;

    protected CoreDatabase(CoreDatabaseManager databaseMgr, String name, Factory.Session sessionFactory) {
        this.databaseMgr = databaseMgr;
        this.name = name;
        this.sessionFactory = sessionFactory;
        schemaKeyGenerator = new KeyGenerator.Schema.Persisted();
        dataKeyGenerator = new KeyGenerator.Data.Persisted();
        isolationMgr = new IsolationManager();
        statisticsCorrector = createStatisticsCorrector();
        sessions = new ConcurrentHashMap<>();
        rocksConfiguration = new RocksConfiguration(options().storageDataCacheSize(),
                options().storageIndexCacheSize(), LOG.isDebugEnabled() || LOG.isTraceEnabled(), ROCKS_LOG_PERIOD);
        schemaLock = new StampedLock();
        schemaLockWriteRequests = new AtomicInteger(0);
        nextTransactionID = new AtomicLong(0);
        isOpen = new AtomicBoolean(false);
    }

    protected StatisticsCorrector createStatisticsCorrector() {
        return new StatisticsCorrector(this);
    }

    static CoreDatabase createAndOpen(CoreDatabaseManager databaseMgr, String name, Factory.Session sessionFactory) {
        try {
            Files.createDirectory(databaseMgr.directory().resolve(name));
        } catch (IOException e) {
            throw TypeDBException.of(e);
        }

        CoreDatabase database = new CoreDatabase(databaseMgr, name, sessionFactory);
        database.initialise();
        return database;
    }

    static CoreDatabase loadAndOpen(CoreDatabaseManager databaseMgr, String name, Factory.Session sessionFactory) {
        CoreDatabase database = new CoreDatabase(databaseMgr, name, sessionFactory);
        database.load();
        return database;
    }

    protected void initialise() {
        openSchema();
        initialiseEncodingVersion();
        openData();
        isOpen.set(true);
        try (CoreSession.Schema session = createAndOpenSession(SCHEMA, new Options.Session()).asSchema()) {
            try (CoreTransaction.Schema txn = session.initialisationTransaction()) {
                if (txn.graph().isInitialised()) throw TypeDBException.of(DIRTY_INITIALISATION);
                txn.graph().initialise();
                txn.commit();
            }
        }
        statisticsCorrector.markActivating();
        statisticsCorrector.doActivate();
    }

    protected void openSchema() {
        try {
            List<ColumnFamilyDescriptor> schemaDescriptors = CorePartitionManager.Schema.descriptors(rocksConfiguration.schema());
            List<ColumnFamilyHandle> schemaHandles = new ArrayList<>();
            rocksSchema = OptimisticTransactionDB.open(
                    rocksConfiguration.schema().dbOptions(),
                    directory().resolve(Encoding.ROCKS_SCHEMA).toString(),
                    schemaDescriptors,
                    schemaHandles
            );
            rocksSchemaPartitionMgr = createPartitionMgrSchema(schemaDescriptors, schemaHandles);
        } catch (RocksDBException e) {
            throw TypeDBException.of(e);
        }
    }

    protected CorePartitionManager.Schema createPartitionMgrSchema(List<ColumnFamilyDescriptor> schemaDescriptors,
                                                                   List<ColumnFamilyHandle> schemaHandles) {
        return new CorePartitionManager.Schema(schemaDescriptors, schemaHandles);
    }

    protected void openData() {
        try {
            List<ColumnFamilyDescriptor> dataDescriptors = CorePartitionManager.Data.descriptors(rocksConfiguration.data());
            List<ColumnFamilyHandle> dataHandles = new ArrayList<>();
            rocksData = OptimisticTransactionDB.open(
                    rocksConfiguration.data().dbOptions(),
                    directory().resolve(Encoding.ROCKS_DATA).toString(),
                    dataDescriptors.subList(0, 1),
                    dataHandles
            );
            assert dataHandles.size() == 1;
            dataHandles.addAll(rocksData.createColumnFamilies(dataDescriptors.subList(1, dataDescriptors.size())));
            rocksDataPartitionMgr = createPartitionMgrData(dataDescriptors, dataHandles);
        } catch (RocksDBException e) {
            throw TypeDBException.of(e);
        }
        mayInitRocksDataLogger();
    }

    protected CorePartitionManager.Data createPartitionMgrData(List<ColumnFamilyDescriptor> dataDescriptors,
                                                               List<ColumnFamilyHandle> dataHandles) {
        return new CorePartitionManager.Data(dataDescriptors, dataHandles);
    }

    protected void load() {
        validateDirectories();
        loadSchema();
        validateEncodingVersion();
        loadData();
        isOpen.set(true);
        try (CoreSession.Schema session = createAndOpenSession(SCHEMA, new Options.Session()).asSchema()) {
            try (CoreTransaction.Schema txn = session.initialisationTransaction()) {
                schemaKeyGenerator.sync(txn.schemaStorage());
                dataKeyGenerator.sync(txn.schemaStorage(), txn.dataStorage());
            }
        }
        statisticsCorrector.markReactivating();
        statisticsCorrector.doReactivate();
    }

    protected void validateDirectories() {
        boolean dataExists = directory().resolve(Encoding.ROCKS_DATA).toFile().exists();
        boolean schemaExists = directory().resolve(Encoding.ROCKS_SCHEMA).toFile().exists();
        if (!schemaExists || !dataExists) {
            throw TypeDBException.of(INVALID_DATABASE_DIRECTORIES, name(), directory(), list(ROCKS_SCHEMA, ROCKS_DATA));
        }
    }

    protected void loadSchema() {
        openSchema();
    }

    protected void loadData() {
        try {
            List<ColumnFamilyDescriptor> dataDescriptors = CorePartitionManager.Data.descriptors(rocksConfiguration.data());
            List<ColumnFamilyHandle> dataHandles = new ArrayList<>();
            rocksData = OptimisticTransactionDB.open(
                    rocksConfiguration.data().dbOptions(),
                    directory().resolve(Encoding.ROCKS_DATA).toString(),
                    dataDescriptors,
                    dataHandles
            );
            assert dataDescriptors.size() == dataHandles.size();
            rocksDataPartitionMgr = createPartitionMgrData(dataDescriptors, dataHandles);
        } catch (RocksDBException e) {
            throw TypeDBException.of(e);
        }
        mayInitRocksDataLogger();
    }

    private void mayInitRocksDataLogger() {
        if (rocksConfiguration.isLoggingEnabled()) {
            scheduledPropertiesLogger = java.util.concurrent.Executors.newScheduledThreadPool(1);
            scheduledPropertiesLogger.scheduleAtFixedRate(
                    new RocksProperties.Logger(rocksData, rocksDataPartitionMgr.handles, name),
                    0, ROCKS_LOG_PERIOD, SECONDS
            );
        } else {
            scheduledPropertiesLogger = null;
        }
    }

    protected void initialiseEncodingVersion() {
        try {
            rocksSchema.put(
                    rocksSchemaPartitionMgr.get(Key.Partition.DEFAULT),
                    ENCODING_VERSION_KEY.bytes().getBytes(),
                    ByteArray.encodeInt(ENCODING_VERSION).getBytes()
            );
        } catch (RocksDBException e) {
            throw TypeDBException.of(e);
        }
    }

    protected void validateEncodingVersion() {
        try {
            byte[] encodingBytes = rocksSchema.get(
                    rocksSchemaPartitionMgr.get(Key.Partition.DEFAULT),
                    ENCODING_VERSION_KEY.bytes().getBytes()
            );
            int encoding = encodingBytes == null || encodingBytes.length == 0 ? 0 : ByteArray.of(encodingBytes).decodeInt();
            if (encoding != ENCODING_VERSION) {
                throw TypeDBException.of(INCOMPATIBLE_ENCODING, name(), directory().toAbsolutePath(), encoding, ENCODING_VERSION);
            }
        } catch (RocksDBException e) {
            throw TypeDBException.of(e);
        }
    }

    public CoreSession createAndOpenSession(Arguments.Session.Type type, Options.Session options) {
        if (!isOpen.get()) throw TypeDBException.of(DATABASE_CLOSED, name);

        long lock = 0;
        CoreSession session;

        if (type.isSchema()) {
            try {
                schemaLockWriteRequests.incrementAndGet();
                lock = schemaLock().tryWriteLock(options.schemaLockTimeoutMillis(), MILLISECONDS);
                if (lock == 0) throw TypeDBException.of(SCHEMA_ACQUIRE_LOCK_TIMEOUT);
            } catch (InterruptedException e) {
                throw TypeDBException.of(e);
            } finally {
                schemaLockWriteRequests.decrementAndGet();
            }
            session = sessionFactory.sessionSchema(this, options);
        } else if (type.isData()) {
            session = sessionFactory.sessionData(this, options);
        } else {
            throw TypeDBException.of(ILLEGAL_STATE);
        }

        sessions.put(session.uuid(), new Pair<>(session, lock));
        return session;
    }

    synchronized Cache cacheBorrow() {
        if (!isOpen.get()) throw TypeDBException.of(DATABASE_CLOSED, name);

        if (cache == null) cache = new Cache(this);
        cache.borrow();
        return cache;
    }

    synchronized void cacheUnborrow(Cache cache) {
        cache.unborrow();
    }

    public synchronized void cacheInvalidate() {
        if (!isOpen.get()) throw TypeDBException.of(DATABASE_CLOSED, name);

        if (cache != null) {
            cache.invalidate();
            cache = null;
        }
    }

    protected synchronized void cacheClose() {
        if (cache != null) cache.close();
    }

    long nextTransactionID() {
        return nextTransactionID.getAndIncrement();
    }

    protected Path directory() {
        return databaseMgr.directory().resolve(name);
    }

    public Options.Database options() {
        return databaseMgr.options();
    }

    KeyGenerator.Schema schemaKeyGenerator() {
        return schemaKeyGenerator;
    }

    KeyGenerator.Data dataKeyGenerator() {
        return dataKeyGenerator;
    }

    public IsolationManager isolationMgr() {
        return isolationMgr;
    }

    protected StatisticsCorrector statisticsCorrector() {
        return statisticsCorrector;
    }

    /**
     * Get the lock that guarantees that the schema is not modified at the same
     * time as data being written to the database. When a schema session is
     * opened (to modify the schema), all write transaction need to wait until
     * the schema session is completed. If there is a write transaction opened,
     * a schema session needs to wait until those transactions are completed.
     *
     * @return a {@code StampedLock} to protect data writes from concurrent schema modification
     */
    protected StampedLock schemaLock() {
        return schemaLock;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isEmpty() {
        try (TypeDB.Session session = databaseMgr.session(name, SCHEMA); TypeDB.Transaction tx = session.transaction(READ)) {
            return tx.concepts().getRootThingType().getSubtypes().allMatch(Type::isRoot);
        }
    }

    @Override
    public boolean contains(UUID sessionID) {
        return sessions.containsKey(sessionID);
    }

    @Override
    public TypeDB.Session session(UUID sessionID) {
        if (sessions.containsKey(sessionID)) return sessions.get(sessionID).first();
        else return null;
    }

    @Override
    public Stream<TypeDB.Session> sessions() {
        return sessions.values().stream().map(Pair::first);
    }

    @Override
    public String schema() {
        try (TypeDB.Session session = databaseMgr.session(name, DATA); TypeDB.Transaction tx = session.transaction(READ)) {
            return TypeQL.parseQuery("define\n\n" + tx.concepts().typesSyntax() + tx.logic().rulesSyntax()).toString(true);
        }
    }

    @Override
    public String typeSchema() {
        try (TypeDB.Session session = databaseMgr.session(name, DATA); TypeDB.Transaction tx = session.transaction(READ)) {
            return TypeQL.parseQuery("define\n\n" + tx.concepts().typesSyntax()).toString(true);
        }
    }

    @Override
    public String ruleSchema() {
        try (TypeDB.Session session = databaseMgr.session(name, DATA); TypeDB.Transaction tx = session.transaction(READ)) {
            return TypeQL.parseQuery("define\n\n" + tx.logic().rulesSyntax()).toString(true);
        }
    }

    void closed(CoreSession session) {
        if (session != statisticsBackgroundCounterSession) {
            long lock = sessions.remove(session.uuid()).second();
            if (session.type().isSchema()) schemaLock().unlockWrite(lock);
        }
    }

    public void close() {
        if (isOpen.compareAndSet(true, false)) {
            if (scheduledPropertiesLogger != null) shutdownRocksPropertiesLogger();
            closeResources();
        }
    }

    private void shutdownRocksPropertiesLogger() {
        assert scheduledPropertiesLogger != null;
        try {
            scheduledPropertiesLogger.shutdown();
            boolean terminated = scheduledPropertiesLogger.awaitTermination(Executors.SHUTDOWN_TIMEOUT_MS, MILLISECONDS);
            if (!terminated) throw TypeDBException.of(ROCKS_LOGGER_SHUTDOWN_TIMEOUT);
        } catch (InterruptedException e) {
            throw TypeDBException.of(e);
        }
    }

    protected void closeResources() {
        statisticsCorrector.close();
        sessions.values().forEach(p -> p.first().close());
        cacheClose();
        rocksDataPartitionMgr.close();
        rocksData.close();
        rocksSchemaPartitionMgr.close();
        rocksSchema.close();
    }

    @Override
    public void delete() {
        close();
        databaseMgr.remove(this);
        try {
            Files.walk(directory()).sorted(reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException e) {
            throw TypeDBException.of(e);
        }
    }

    public static class IsolationManager {

        private final ConcurrentSet<CoreTransaction.Data> uncommitted;
        private final ConcurrentSet<CoreTransaction.Data> committing;
        private final ConcurrentSet<CoreTransaction.Data> committed;
        private final AtomicBoolean cleanupRunning;

        IsolationManager() {
            uncommitted = new ConcurrentSet<>();
            committing = new ConcurrentSet<>();
            committed = new ConcurrentSet<>();
            cleanupRunning = new AtomicBoolean(false);
        }

        void opened(CoreTransaction.Data transaction) {
            uncommitted.add(transaction);
        }

        public Set<CoreTransaction.Data> validateOverlappingAndStartCommit(CoreTransaction.Data txn) {
            Set<CoreTransaction.Data> transactions;
            synchronized (this) {
                transactions = commitMayConflict(txn);
                transactions.forEach(other -> validateIsolation(txn, other));
                committing.add(txn);
                uncommitted.remove(txn);
            }
            return transactions;
        }

        private Set<CoreTransaction.Data> commitMayConflict(CoreTransaction.Data txn) {
            if (!txn.dataStorage.hasTrackedWrite()) return set();
            Set<CoreTransaction.Data> mayConflict = new HashSet<>(committing);
            for (CoreTransaction.Data committedTxn : committed) {
                if (committedTxn.snapshotEnd().get() > txn.snapshotStart()) mayConflict.add(committedTxn);
            }
            return mayConflict;
        }

        private void validateIsolation(CoreTransaction.Data txn, CoreTransaction.Data mayConflict) {
            if (txn.dataStorage.modifyDeleteConflict(mayConflict.dataStorage)) {
                throw TypeDBException.of(TRANSACTION_ISOLATION_MODIFY_DELETE_VIOLATION);
            } else if (txn.dataStorage.deleteModifyConflict(mayConflict.dataStorage)) {
                throw TypeDBException.of(TRANSACTION_ISOLATION_DELETE_MODIFY_VIOLATION);
            } else if (txn.dataStorage.exclusiveCreateConflict(mayConflict.dataStorage)) {
                throw TypeDBException.of(TRANSACTION_ISOLATION_EXCLUSIVE_CREATE_VIOLATION);
            }
        }

        public void committed(CoreTransaction.Data txn) {
            assert committing.contains(txn) && txn.snapshotEnd().isPresent();
            committed.add(txn);
            committing.remove(txn);
        }

        void closed(CoreTransaction.Data txn) {
            // txn closed with commit or without failed commit
            uncommitted.remove(txn);
            committing.remove(txn);
            cleanupCommitted();
        }

        private void cleanupCommitted() {
            if (cleanupRunning.compareAndSet(false, true)) {
                long lastCommittedSnapshot = newestCommittedSnapshot();
                Long cleanupUntil = oldestUncommittedSnapshot().orElse(lastCommittedSnapshot + 1);
                committed.forEach(txn -> {
                    if (txn.snapshotEnd().get() < cleanupUntil) {
                        txn.delete();
                        committed.remove(txn);
                    }
                });
                cleanupRunning.set(false);
            }
        }

        private Optional<Long> oldestUncommittedSnapshot() {
            return link(iterate(uncommitted), iterate(committing)).map(CoreTransaction.Data::snapshotStart)
                    .stream().min(Comparator.naturalOrder());
        }

        private long newestCommittedSnapshot() {
            return iterate(committed).map(txn -> txn.snapshotEnd().get()).stream().max(Comparator.naturalOrder()).orElse(0L);
        }

        FunctionalIterator<CoreTransaction.Data> getNotCommitted() {
            return link(iterate(uncommitted), iterate(committing));
        }

        long committedEventCount() {
            return committed.size();
        }
    }

    public static class StatisticsCorrector {

        private final CoreDatabase database;
        protected final AtomicReference<State> state;
        protected final ConcurrentSet<CompletableFuture<Void>> corrections;
        private final ConcurrentSet<Long> deletedTxnIDs;
        protected CoreSession.Data session;

        protected enum State {INACTIVE, ACTIVATING, REACTIVATING, WAITING, CORRECTION_QUEUED, CLOSED}

        protected StatisticsCorrector(CoreDatabase database) {
            this.database = database;
            corrections = new ConcurrentSet<>();
            deletedTxnIDs = new ConcurrentSet<>();
            state = new AtomicReference<>(State.INACTIVE);
        }

        public void markActivating() {
            assert state.get() == State.INACTIVE;
            state.set(State.ACTIVATING);
        }

        public void doActivate() {
            assert state.get() == State.ACTIVATING;
            session = database.createAndOpenSession(DATA, new Options.Session()).asData();
            state.set(State.WAITING);
        }

        public void markReactivating() {
            assert state.get() == State.INACTIVE;
            state.set(State.REACTIVATING);
        }

        protected void doReactivate() {
            assert state.get() == State.REACTIVATING;
            session = database.createAndOpenSession(DATA, new Options.Session()).asData();
            state.set(State.WAITING);
            LOG.trace("Cleaning up statistics metadata.");
            correctMiscounts();
            deleteCorrectionMetadata();
            LOG.trace("Statistics are ready and up to date.");
            if (LOG.isTraceEnabled()) logSummary();
        }

        private void deleteCorrectionMetadata() {
            try (CoreTransaction.Data txn = session.transaction(WRITE)) {
                txn.dataStorage.iterate(StatisticsKey.txnCommittedPrefix()).forEachRemaining(kv ->
                        txn.dataStorage.deleteUntracked(kv.key())
                );
                txn.commit();
            }
        }

        private void logSummary() {
            try (CoreTransaction.Data txn = session.transaction(READ)) {
                LOG.trace("Total 'thing' count: " +
                        txn.graphMgr.data().stats().thingVertexTransitiveCount(txn.graphMgr.schema().rootThingType())
                );
                long hasCount = 0;
                NavigableSet<TypeVertex> allTypes = txn.graphMgr.schema().getSubtypes(txn.graphMgr.schema().rootThingType());
                Set<TypeVertex> attributes = txn.graphMgr.schema().getSubtypes(txn.graphMgr.schema().rootAttributeType());
                for (TypeVertex attr : attributes) {
                    hasCount += txn.graphMgr.data().stats().hasEdgeSum(allTypes, attr);
                }
                LOG.trace("Total 'role' count: " +
                        txn.graphMgr.data().stats().thingVertexTransitiveCount(txn.graphMgr.schema().rootRoleType())
                );
                LOG.trace("Total 'has' count: " + hasCount);
            }
        }

        public void committed(CoreTransaction.Data transaction) {
            handleDeferredSetUp();
            if (mayMiscount(transaction) && state.compareAndSet(State.WAITING, State.CORRECTION_QUEUED)) {
                submitCorrection();
            }
        }

        private void handleDeferredSetUp() {
            if (state.get() == State.ACTIVATING) {
                synchronized (this) {
                    if (state.get() == State.ACTIVATING) doActivate();
                }
            } else if (state.get() == State.REACTIVATING) {
                synchronized (this) {
                    if (state.get() == State.REACTIVATING) doReactivate();
                }
            }
        }

        CompletableFuture<Void> submitCorrection() {
            CompletableFuture<Void> correction = CompletableFuture.runAsync(() -> {
                if (state.compareAndSet(State.CORRECTION_QUEUED, State.WAITING)) correctMiscounts();
            }, serial());
            corrections.add(correction);
            correction.exceptionally(exception -> {
                LOG.debug("StatisticsCorrection task failed with exception: " + exception.toString());
                return null;
            }).thenRun(() -> corrections.remove(correction));

            return correction;
        }

        private boolean mayMiscount(CoreTransaction.Data transaction) {
            return !transaction.graphMgr.data().attributesCreated().isEmpty() ||
                    !transaction.graphMgr.data().attributesDeleted().isEmpty() ||
                    !transaction.graphMgr.data().hasEdgeCreated().isEmpty() ||
                    !transaction.graphMgr.data().hasEdgeDeleted().isEmpty();
        }

        void deleted(CoreTransaction.Data transaction) {
            deletedTxnIDs.add(transaction.id());
        }

        /**
         * Scan through all attributes that may need to be corrected (eg. have been over/under counted),
         * and correct them if we have enough information to do so.
         */
        protected void correctMiscounts() {
            if (state.get().equals(State.CLOSED)) return;
            try (CoreTransaction.Data txn = session.transaction(WRITE)) {
                if (mayCorrectMiscounts(txn)) database.cache.incrementStatisticsVersion();
            }
        }

        protected boolean mayCorrectMiscounts(CoreTransaction.Data txn) {
            Set<Long> deletableTxnIDs = new HashSet<>(deletedTxnIDs);
            boolean[] modified = new boolean[]{false};
            boolean[] miscountCorrected = new boolean[]{false};
            Set<Long> openTxnIDs = database.isolationMgr.getNotCommitted().map(CoreTransaction::id).toSet();
            txn.dataStorage.iterate(StatisticsKey.Miscountable.prefix()).forEachRemaining(kv -> {
                StatisticsKey.Miscountable item = kv.key();
                List<Long> txnIDsCausingMiscount = kv.value().decodeLongs();

                if (anyCommitted(txnIDsCausingMiscount, txn.dataStorage)) {
                    correctMiscount(item, txn);
                    miscountCorrected[0] = true;
                    txn.dataStorage.deleteUntracked(item);
                    modified[0] = true;
                } else if (noneOpen(txnIDsCausingMiscount, openTxnIDs)) {
                    txn.dataStorage.deleteUntracked(item);
                    modified[0] = true;
                } else {
                    // transaction IDs causing miscount are not deletable
                    deletableTxnIDs.removeAll(txnIDsCausingMiscount);
                }
            });
            if (!deletableTxnIDs.isEmpty()) {
                for (Long txnID : deletableTxnIDs) {
                    txn.dataStorage.deleteUntracked(StatisticsKey.txnCommitted(txnID));
                }
                deletedTxnIDs.removeAll(deletableTxnIDs);
                modified[0] = true;
            }
            if (modified[0]) txn.commit();
            return miscountCorrected[0];
        }

        private void correctMiscount(StatisticsKey.Miscountable miscount, CoreTransaction.Data txn) {
            if (miscount.isAttrOvertcount()) {
                VertexIID.Type type = miscount.getMiscountableAttribute().type();
                txn.dataStorage.mergeUntracked(StatisticsKey.vertexCount(type), encodeLong(-1));
            } else if (miscount.isAttrUndercount()) {
                VertexIID.Type type = miscount.getMiscountableAttribute().type();
                txn.dataStorage.mergeUntracked(StatisticsKey.vertexCount(type), encodeLong(1));
            } else if (miscount.isHasEdgeOvercount()) {
                Pair<VertexIID.Thing, VertexIID.Attribute<?>> has = miscount.getMiscountableHasEdge();
                txn.dataStorage.mergeUntracked(
                        StatisticsKey.hasEdgeCount(has.first().type(), has.second().type()),
                        encodeLong(-1)
                );
            } else if (miscount.isHasEdgeUndercount()) {
                Pair<VertexIID.Thing, VertexIID.Attribute<?>> has = miscount.getMiscountableHasEdge();
                txn.dataStorage.mergeUntracked(
                        StatisticsKey.hasEdgeCount(has.first().type(), has.second().type()),
                        encodeLong(1)
                );
            }
        }

        private boolean anyCommitted(List<Long> txnIDsToCheck, RocksStorage.Data storage) {
            for (Long txnID : txnIDsToCheck) {
                if (storage.get(StatisticsKey.txnCommitted(txnID)) != null) return true;
            }
            return false;
        }

        private boolean noneOpen(List<Long> txnIDs, Set<Long> openTxnIDs) {
            return iterate(txnIDs).noneMatch(openTxnIDs::contains);
        }

        public void recordCorrectionMetadata(CoreTransaction.Data txn, Set<CoreTransaction.Data> overlappingTxn) {
            recordMiscountableCauses(txn, overlappingTxn);
            txn.dataStorage.putUntracked(StatisticsKey.txnCommitted(txn.id()));
        }

        private void recordMiscountableCauses(CoreTransaction.Data txn, Set<CoreTransaction.Data> overlappingTxn) {
            Map<AttributeVertex<?>, List<Long>> attrOvercount = new HashMap<>();
            Map<AttributeVertex<?>, List<Long>> attrUndercount = new HashMap<>();
            Map<Pair<ThingVertex, AttributeVertex<?>>, List<Long>> hasEdgeOvercount = new HashMap<>();
            Map<Pair<ThingVertex, AttributeVertex<?>>, List<Long>> hasEdgeUndercount = new HashMap<>();
            for (CoreTransaction.Data overlapping : overlappingTxn) {
                attrMiscountableCauses(attrOvercount, overlapping.id(), txn.graphMgr.data().attributesCreated(),
                        overlapping.graphMgr.data().attributesCreated());
                attrMiscountableCauses(attrUndercount, overlapping.id(), txn.graphMgr.data().attributesDeleted(),
                        overlapping.graphMgr.data().attributesDeleted());
                hasEdgeMiscountableCauses(hasEdgeOvercount, overlapping.id(), txn.graphMgr.data().hasEdgeCreated(),
                        overlapping.graphMgr.data().hasEdgeCreated());
                hasEdgeMiscountableCauses(hasEdgeUndercount, overlapping.id(), txn.graphMgr.data().hasEdgeDeleted(),
                        overlapping.graphMgr.data().hasEdgeDeleted());
            }

            attrOvercount.forEach((attr, txns) -> txn.dataStorage.putUntracked(
                    StatisticsKey.Miscountable.attrOvercount(txn.id(), attr.iid()), encodeLongs(txns)
            ));
            attrUndercount.forEach((attr, txs) -> txn.dataStorage.putUntracked(
                    StatisticsKey.Miscountable.attrUndercount(txn.id(), attr.iid()), encodeLongs(txs)
            ));
            hasEdgeOvercount.forEach((has, txs) -> txn.dataStorage.putUntracked(
                    StatisticsKey.Miscountable.hasEdgeOvercount(txn.id(), has.first().iid(), has.second().iid()), encodeLongs(txs)
            ));
            hasEdgeUndercount.forEach((has, txs) -> txn.dataStorage.putUntracked(
                    StatisticsKey.Miscountable.hasEdgeUndercount(txn.id(), has.first().iid(), has.second().iid()), encodeLongs(txs)
            ));
        }

        private void attrMiscountableCauses(Map<AttributeVertex<?>, List<Long>> miscountableCauses, long cause,
                                            Set<? extends AttributeVertex<?>> attrs1,
                                            Set<? extends AttributeVertex<?>> attrs2) {
            // note: fail-fast if checks are much faster than using empty iterators (due to concurrent data structures)
            if (!attrs1.isEmpty() && !attrs2.isEmpty()) {
                iterate(attrs1).filter(attrs2::contains).forEachRemaining(attribute ->
                        miscountableCauses.computeIfAbsent(attribute, (key) -> new ArrayList<>()).add(cause)
                );
            }
        }

        private void hasEdgeMiscountableCauses(Map<Pair<ThingVertex, AttributeVertex<?>>, List<Long>> miscountableCauses,
                                               long cause, Set<ThingEdge> hasEdge1, Set<ThingEdge> hasEdge2) {
            // note: fail-fast if checks are much faster than using empty iterators (due to concurrent data structures)
            if (!hasEdge1.isEmpty() && !hasEdge2.isEmpty()) {
                iterate(hasEdge1).filter(hasEdge2::contains).forEachRemaining(edge ->
                        miscountableCauses.computeIfAbsent(
                                pair(edge.from(), edge.to().asAttribute()),
                                (key) -> new ArrayList<>()
                        ).add(cause)
                );
            }
        }

        protected void close() {
            try {
                state.set(State.CLOSED);
                for (CompletableFuture<Void> correction : corrections) {
                    correction.get(Executors.SHUTDOWN_TIMEOUT_MS, MILLISECONDS);
                }
                corrections.clear();
            } catch (InterruptedException | TimeoutException e) {
                LOG.warn(STATISTICS_CORRECTOR_SHUTDOWN_TIMEOUT.message());
                throw TypeDBException.of(e);
            } catch (ExecutionException e) {
                if (!((e.getCause() instanceof TypeDBException) &&
                        ((TypeDBException) e.getCause()).code().map(code ->
                                code.equals(RESOURCE_CLOSED.code()) || code.equals(DATABASE_CLOSED.code())
                        ).orElse(false))) {
                    throw TypeDBException.of(e);
                }
            } finally {
                session.close();
            }
        }
    }

    static class Cache {

        private final TraversalCache traversalCache;
        private final LogicCache logicCache;
        private final TypeGraph typeGraph;
        private final RocksStorage schemaStorage;
        private final AtomicLong statisticsVersion;
        private long borrowerCount;
        private boolean invalidated;

        private Cache(CoreDatabase database) {
            schemaStorage = new RocksStorage.Cache(database.rocksSchema, database.rocksSchemaPartitionMgr);
            typeGraph = new TypeGraph(schemaStorage, true);
            traversalCache = new TraversalCache();
            logicCache = new LogicCache();
            borrowerCount = 0L;
            invalidated = false;
            statisticsVersion = new AtomicLong(0);
        }

        public TraversalCache traversal() {
            return traversalCache;
        }

        public LogicCache logic() {
            return logicCache;
        }

        public TypeGraph typeGraph() {
            return typeGraph;
        }

        private void borrow() {
            borrowerCount++;
        }

        private void unborrow() {
            borrowerCount--;
            mayClose();
        }

        private void invalidate() {
            invalidated = true;
            mayClose();
        }

        private void mayClose() {
            if (borrowerCount == 0 && invalidated) {
                schemaStorage.close();
            }
        }

        AtomicLong statisticsVersion() {
            return statisticsVersion;
        }

        void incrementStatisticsVersion() {
            statisticsVersion.incrementAndGet();
        }

        private void close() {
            schemaStorage.close();
        }
    }

    private static class SchemaExporter {

    }
}
