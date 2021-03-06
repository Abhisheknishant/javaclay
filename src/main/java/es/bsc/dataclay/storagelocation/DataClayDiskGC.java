
package es.bsc.dataclay.storagelocation;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import es.bsc.dataclay.api.BackendID;
import es.bsc.dataclay.commonruntime.DataServiceRuntime;
import es.bsc.dataclay.dataservice.api.DataServiceAPI;
import es.bsc.dataclay.serialization.lib.DataClayDeserializationLib;
import es.bsc.dataclay.util.Configuration;
import es.bsc.dataclay.util.ReferenceCounting;
import es.bsc.dataclay.util.ids.ExecutionEnvironmentID;
import es.bsc.dataclay.util.ids.ObjectID;
import es.bsc.dataclay.util.management.metadataservice.MetaDataInfo;

/**
 * This class is responsible to collect objects in disk.
 */
public final class DataClayDiskGC {

	/** Logger. */
	private static final Logger LOGGER = LogManager.getLogger("GlobalGC");

	/** Indicates if debug is enabled. */
	private static final boolean DEBUG_ENABLED = Configuration.isDebugEnabled();

	/** BackendID representing unknown location. */
	private static final BackendID UNKNOW_LOCATION_UUID = new ExecutionEnvironmentID(new UUID(0L, 0L));

	/** Storage Location. */
	private final StorageLocationService storageLocation;

	/** Runtime used to connect to other EE, SL or LM. */
	private final DataServiceRuntime runtime;

	/** Path in which to store caches in case of restart or shutdown (in GlobalGC, where countings are stored). */
	private final String cachePath;

	/**
	 * Reference counting to add/subtract for each object. Resource note: maximum size of this map is maximum number of objects
	 * and associations in a EE.
	 */
	private Map<BackendID, Map<ObjectID, AtomicInteger>> countersPerNode = new ConcurrentHashMap<>();

	/**
	 * Objects in quarantine. Resource note: maximum size of this map is maximum number of objects in SL. Linked queue
	 * guarantees FIFO.
	 */
	private Map<ObjectID, Long> quarantine = new ConcurrentHashMap<>();

	/**
	 * Candidates list of objects to be removed. Resource note: maximum size of this map is maximum number of objects in SL.
	 * Linked queue guarantees FIFO.
	 */
	private Queue<ObjectID> candidates = new ConcurrentLinkedQueue<>();

	/**
	 * Pending reference countings to process. Resource note: this is not infinite since there is max requests
	 * (get/store/update) possible. Linked queue guarantees that updates of references are always executed after get.
	 */
	private final Queue<SerializedReferenceCounting> refCounting = new ConcurrentLinkedQueue<>();

	/**
	 * Objects that might be unaccessible. This set is needed for cleaning 'cyclic' references, objects that are retaining other
	 * objects and cannot be accessed. An object is an unaccessible candidate if has no alias, but it is actually unaccessible
	 * if no alias + no retained ref in EE (no session, no execution using it...).
	 */
	private final Set<ObjectID> unaccessibleCandidates = new HashSet<>();

	/** Pool for tasks. */
	protected final ScheduledExecutorService threadPool = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
		@Override
		public Thread newThread(final Runnable r) {
			final Thread t = Executors.defaultThreadFactory().newThread(r);
			t.setName(runtime.getDataService().dsName + "-GlobalGC");
			t.setDaemon(true);
			return t;
		}
	});

	/** Serialized reference counting to add into the pending queue. */
	private class SerializedReferenceCounting {
		/** ID of the EE of the object. */
		ExecutionEnvironmentID eeID;
		/** ID of the object. */
		ObjectID objectID;
		/** Bytes to be deserialized. We do not have it already deserialized to avoid a penalty during get/store/update. */
		byte[] bytes;
		/**
		 * Indicates if reference counting arrived during a GET operation. If False, we understand it is a wRITE (update/store)
		 * operation.
		 */
		boolean isGet;

	}

	/**
	 * Constructor.
	 * 
	 * @param thestorageLocationService
	 *            Storage location service.
	 * @param theruntime
	 *            The runtime to use for communications and others.
	 * @param theassociatedEE
	 *            Associated execution environment ID.
	 */
	public DataClayDiskGC(final StorageLocationService thestorageLocationService, final DataServiceRuntime theruntime,
			final ExecutionEnvironmentID theassociatedEE) {
		this.storageLocation = thestorageLocationService;
		this.runtime = theruntime;
		this.cachePath = Configuration.Flags.DEFAULT_GLOBALGC_CACHE_PATH.getStringValue()
				+ "globalgc" + runtime.getDataService().dsName + ".cache";
		if (DEBUG_ENABLED) {
			LOGGER.debug("Starting DiskGC");
		}

		initCachesFromPersistent();

		
		long initalDelay = 0;
		if (Configuration.Flags.GLOBALGC_COLLECTOR_INITIAL_DELAY_HOURS.getLongValue() != 0) {
			// === prepare delay === //
			final LocalDateTime now = LocalDateTime.now();
			final LocalDateTime startTime = now.plusHours(Configuration.Flags.GLOBALGC_COLLECTOR_INITIAL_DELAY_HOURS.getLongValue());
			final Duration duration = Duration.between(LocalDateTime.now(), startTime);
			if (!Configuration.mockTesting) { // GC testing does not add initial delay (too many hours!)
				initalDelay = duration.toMillis();
			}
		}

		final TimerTask collectorTask = new CollectorTask();
		final TimerTask refCountingQueueProcessor = new ReferenceCountingQueueProcessor();
		final TimerTask remoteCountingNotifier = new RemoteReferenceCountingNotifier();
		threadPool.scheduleAtFixedRate(remoteCountingNotifier, Configuration.Flags.GLOBALGC_CHECK_REMOTE_PENDING.getLongValue(),
				Configuration.Flags.GLOBALGC_CHECK_REMOTE_PENDING.getLongValue(), TimeUnit.MILLISECONDS);
		threadPool.scheduleAtFixedRate(refCountingQueueProcessor, Configuration.Flags.GLOBALGC_CHECK_REMOTE_PENDING.getLongValue(),
				Configuration.Flags.GLOBALGC_PROCESS_COUNTINGS_INTERVAL.getLongValue(), TimeUnit.MILLISECONDS);
		threadPool.scheduleAtFixedRate(collectorTask, initalDelay,
				Configuration.Flags.GLOBALGC_CHECK_TIME_INTERVAL.getLongValue(), TimeUnit.MILLISECONDS);

	}

	/**
	 * Serialize caches into files. Used for restart or shutdown.
	 */
	private void serializeCachesIntoFiles() {
		// Store cache in file
		try {
			LOGGER.debug("Storing in " + this.cachePath);
			for (final Entry<BackendID, Map<ObjectID, AtomicInteger>> curCounter : countersPerNode.entrySet()) {
				LOGGER.debug("Serializing -> Counters of node {}: {} ", curCounter.getKey(), curCounter.getValue());
			}
			LOGGER.debug("Serializing -> Num Candidates: " + candidates.toString());
			LOGGER.debug("Serializing -> Num quarantine: " + quarantine.toString());
			final FileOutputStream fos = new FileOutputStream(this.cachePath);
			final ObjectOutputStream oos = new ObjectOutputStream(fos);
			oos.writeObject(new PersistentReferenceCounters(this.countersPerNode, this.quarantine, this.candidates));
			oos.close();
			fos.close();
		} catch (final IOException ioe) {
			LOGGER.debug("serializeCachesIntoFiles got an I/O exception", ioe);
		}
	}

	/**
	 * Initialize caches from persistent files
	 * 
	 * @param cachesPath
	 *            Path of cache
	 */
	private void initCachesFromPersistent() {
		// Deserialize metadata cache if present
		try {
			LOGGER.debug("Recovering countings from " + this.cachePath);
			final FileInputStream fis = new FileInputStream(this.cachePath);
			final ObjectInputStream ois = new ObjectInputStream(fis);
			final PersistentReferenceCounters persistentCaches = (PersistentReferenceCounters) ois.readObject();
			this.countersPerNode = persistentCaches.getCountersPerNode();
			this.candidates = persistentCaches.getCandidates();
			this.quarantine = persistentCaches.getQuarantine();
			for (final Entry<BackendID, Map<ObjectID, AtomicInteger>> curCounter : countersPerNode.entrySet()) {
				LOGGER.debug("Recovering -> Counters of node {}: {} ", curCounter.getKey(), curCounter.getValue());
			}
			LOGGER.debug("Recovering Candidates: " + candidates);
			LOGGER.debug("Recovering quarantine: " + quarantine);

			ois.close();
			fis.close();
		} catch (final IOException ioe) {
			// do nothing, object not exist
		} catch (final ClassNotFoundException c) {
			LOGGER.debug("initCachesFromPersistent error", c);
			// do nothing
		}
	}

	/**
	 * Add reference counting of an object ("counter of referenced objects FROM the object serialized provided") into queue so
	 * DiskGC can process it. Parameter contains all bytes of the object. We extract the bytes representing the reference
	 * counting in order to avoid 'retaining' bytes in memory (resource leak). This function is called during a get from DB, an
	 * update or a store. We add the bytes representing the reference counting without deserializing it in order to let thread
	 * continue work and wait for GC to deserialize and process it.
	 * 
	 * @param theEEID
	 *            ID of the execution environment ID of the object.
	 * @param theobjectID
	 *            ID of the object
	 * @param serializedObject
	 *            Serialized object.
	 * @param theisGet
	 *            Indicates if reference counting arrived during a GET operation. If False, we understand it is a wRITE
	 *            (update/store) operation.
	 * @param dirty
	 *            dirty Indicates object has been modified. If false, it means that bytes only contains reference counting
	 *            information. DESIGN NOTE: in order to be able to find out which references where removed in complex objects
	 *            (arrays, collections) GlobalGc decreases all pointed references in a Get procedure and increase them again
	 *            (except removed ones) during update. While in EE, objects have memory references so they cannot be removed
	 *            neither.
	 */
	public void addToQueueReferenceCounting(final ExecutionEnvironmentID theEEID,
			final ObjectID theobjectID, final byte[] serializedObject, final boolean theisGet,
			final boolean dirty) {
		final SerializedReferenceCounting serializedReferenceCounting = new SerializedReferenceCounting();
		if (theisGet) {
			if (DEBUG_ENABLED) {
				LOGGER.debug("[GET] Adding to queue the reference counting for object " + theobjectID);
			}
			serializedReferenceCounting.bytes = DataClayDeserializationLib.extractReferenceCounting(serializedObject);
		} else if (dirty) {
			if (DEBUG_ENABLED) {
				LOGGER.debug("[UPDATE/STORE] Adding to queue the reference counting for object " + theobjectID);
			}
			serializedReferenceCounting.bytes = DataClayDeserializationLib.extractReferenceCounting(serializedObject);
		} else {
			if (DEBUG_ENABLED) {
				LOGGER.debug("[UPDATE] Added not dirty pending ref. counting for object " + theobjectID);
			}
			serializedReferenceCounting.bytes = serializedObject;
		}
		serializedReferenceCounting.isGet = theisGet;
		serializedReferenceCounting.objectID = theobjectID;
		serializedReferenceCounting.eeID = theEEID;
		this.refCounting.add(serializedReferenceCounting);
	}

	/**
	 * Add delta number of references to object.
	 * 
	 * @param objectID
	 *            ID of the object
	 * @param theownerID
	 *            ID of the node owning the object
	 * @param delta
	 * @param initializingCounter
	 *            Indicates we are adding the counting because it might be the first time we see the reference
	 */
	private void addRefs(final ObjectID objectID, final BackendID theownerID, final int delta, final boolean initializingCounter) {

		BackendID ownerID = UNKNOW_LOCATION_UUID;
		if (theownerID != null) {
			ownerID = theownerID;
		}

		Map<ObjectID, AtomicInteger> counters = countersPerNode.get(ownerID);
		if (counters == null) {
			// do not create two maps - race condition
			synchronized (countersPerNode) {
				counters = countersPerNode.get(ownerID);
				if (counters == null) {
					counters = new ConcurrentHashMap<>();
					countersPerNode.put(ownerID, counters);
				}
			}
		}
		AtomicInteger counter = counters.get(objectID);
		if (counter == null) {
			// do not create two counters - race condition
			synchronized (counters) {
				counter = counters.get(objectID);
				if (counter == null) {
					counter = new AtomicInteger(0);
					counters.put(objectID, counter);
				}
			}
		}

		final int numRefs = counter.addAndGet(delta);
		if (DEBUG_ENABLED && !initializingCounter) {
			LOGGER.debug("Num references for " + objectID + " : " + numRefs);
		}
		// should we do it only for updates? (also being done during get)
		// check if object has reference counting = 0 (might have session reference, memory reference or alias reference)
		if (this.storageLocation.getAssociateExecutionEnvironments().contains(ownerID) && numRefs == 0
				&& !this.candidates.contains(objectID)) {
			if (DEBUG_ENABLED && !initializingCounter) {
				LOGGER.debug("** Added candidate " + objectID);
			}
			this.candidates.add(objectID);
		}

		// remove from unaccessible candidates if numrefs != 0 and is present
		synchronized (unaccessibleCandidates) {
			if (numRefs != 0 && this.unaccessibleCandidates.contains(objectID)) {
				if (DEBUG_ENABLED) {
					LOGGER.debug("** Removed from unaccessible candidate " + objectID);
				}
				this.unaccessibleCandidates.remove(objectID);
			}
		}

	}

	/**
	 * Update counters of references.
	 * 
	 * @param updateCounterRefs
	 *            Update counter of references.
	 */
	public void updateRefs(final Map<ObjectID, Integer> updateCounterRefs) {
		LOGGER.debug("Notified counters to update: {}", updateCounterRefs);
		for (final Entry<ObjectID, Integer> updateCounting : updateCounterRefs.entrySet()) {
			final ObjectID oid = updateCounting.getKey();
			final Integer counting = updateCounting.getValue();
			// check if object is ACTUALLY mine, otherwise is null
			ExecutionEnvironmentID thenode = null;
			for (final ExecutionEnvironmentID nodeID : this.storageLocation.getAssociateExecutionEnvironments()) {
				if (this.storageLocation.exists(nodeID, oid)) { // too costly? no critical path.
					thenode = nodeID;
					break;
				}
			}
			LOGGER.trace("Updating counting from other node, to: " + oid + " (" + counting + ")");
			this.addRefs(oid, thenode, counting, false);
		}
	}

	/**
	 * Finish all threads.
	 */
	public void shutDown() {

		if (DEBUG_ENABLED) {
			LOGGER.debug("[==GGC Shutdown==] Shutting down GlobalGC threads");
		}

		// Shutdown and wait for threads to terminate
		this.threadPool.shutdown();
		try {
			this.threadPool.awaitTermination(Configuration.Flags.GLOBALGC_WAIT_TO_SHUTDOWN.getLongValue(), TimeUnit.MILLISECONDS);
		} catch (final InterruptedException ex) {
			LOGGER.debug("shutDown interrupted", ex);
		}

		// process pending reference counting
		processPendingReferenceCounters();

		// send updates. If some node is shutdown, ignore it and store information in cache to send later.
		notifyReferencesToNodes();

		// collect garbage
		collect(true);

		// store caches
		serializeCachesIntoFiles();
	}

	/**
	 * This function process reference counting obtained during GET of an object. For association of the object we add '-1'
	 * reference. This is used to detect in which cases a reference has been removed without an intervention in setter methods.
	 * So, when an object is read from DB all its references decreases until it is written in DB again (+1) except the ones that
	 * were removed. When the object is in memory no GC can happen so it is not a problem (and associations are all in memory)
	 * 
	 * @param serializedCounting
	 *            Serialized reference counting to process.
	 */
	private void countAtGet(final SerializedReferenceCounting serializedCounting) {
		final ObjectID referrerObjectID = serializedCounting.objectID;
		if (DEBUG_ENABLED) {
			LOGGER.debug("[==GGC counters processor==] Processing serializedCounting at Get for "
					+ referrerObjectID
					+ " ref.id = " + System.identityHashCode(serializedCounting));

		}
		final ReferenceCounting refCounting = new ReferenceCounting();
		refCounting.deserializeReferenceCounting(referrerObjectID, serializedCounting.bytes);
		for (final Entry<BackendID, Map<ObjectID, Integer>> countingsPerHint : refCounting.getReferenceCounting().entrySet()) {
			final BackendID ownerID = countingsPerHint.getKey();
			for (final Entry<ObjectID, Integer> pointedRefEntry : countingsPerHint.getValue().entrySet()) {
				final Integer counting = pointedRefEntry.getValue();
				final ObjectID pointedRef = pointedRefEntry.getKey();
				if (!pointedRef.equals(referrerObjectID)) {
					LOGGER.trace("Adding reference " + referrerObjectID + " -> " + pointedRef + "(-" + counting + ")");
					addRefs(pointedRef, ownerID, -counting, false);
				}
			}
		}
	}

	/**
	 * This function process reference counting obtained during STORE/UPDATE of an object. We update references here.
	 * 
	 * @param serializedCounting
	 *            Serialized reference counting to process.
	 */
	private void countAtUpdate(final SerializedReferenceCounting serializedCounting) {
		try {
			final ObjectID referrerObjectID = serializedCounting.objectID;
			if (DEBUG_ENABLED) {
				LOGGER.debug("[==GGC counters processor==] Processing serializedCounting at Update for "
						+ referrerObjectID
						+ " ref.id = " + System.identityHashCode(serializedCounting));

			}
			final ReferenceCounting refCounting = new ReferenceCounting();
			refCounting.deserializeReferenceCounting(referrerObjectID, serializedCounting.bytes);

			// object might be unaccessible (very volatile)
			// FIXME: do not add any object as unaccesible candidate 
			// currently we are adding them in case of cycles, and collect thread is removing them after
			// checking it. 
			if (this.getNumReferencesTo(referrerObjectID) == 0) {
				synchronized (unaccessibleCandidates) { //avoid concurrent modification in collector task
					// an object A -> B -> A arrives, A and B are marked as unaccesible but when B is processed, 
					// A is removed from unacessible candidates, but B continue as a candidate. When collector realizes
					// that B has != 0 references, it removes it from candidates list, and wait for B to be in unaccessible
					// candidates list, which will happen when A is removed (and its refs are decreased). 
					// Alias case: any object arrives A, with session and alias refs, it is marked as unaccesible 
					// till alias arrive, then addrefs will remove it from unaccessible candidates. 
					unaccessibleCandidates.add(referrerObjectID);
				}
				LOGGER.debug("[==GGC counters processor==] Added unaccesible candidate: " + referrerObjectID);
			}
			
			if (DEBUG_ENABLED) {
				LOGGER.debug("[==GGC counters processor==] Reference counting size : " + refCounting.getReferenceCounting().size());
			}
			for (final Entry<BackendID, Map<ObjectID, Integer>> countingsPerHint : refCounting.getReferenceCounting().entrySet()) {
				final BackendID ownerID = countingsPerHint.getKey();
				for (final Entry<ObjectID, Integer> pointedRefEntry : countingsPerHint.getValue().entrySet()) {
					final Integer counting = pointedRefEntry.getValue();
					final ObjectID pointedRef = pointedRefEntry.getKey();
					if (!pointedRef.equals(referrerObjectID)) {
						if (DEBUG_ENABLED) {
							LOGGER.debug("[==Count at update==] Adding {} to object {} ", counting, pointedRef);
						}
						addRefs(pointedRef, ownerID, counting, false);
					}

				}
			}
		} catch (final Exception e) {
			if (DEBUG_ENABLED) {
				LOGGER.debug(e);
			}
		}
	}

	/**
	 * Provided object is unaccesible. All its references should not count as actual references to allow GC action.
	 * 
	 * @param unaccessibleCandidate
	 *            OID of unaccessible object.
	 */
	private void markUnaccesibleObject(final ObjectID unaccessibleCandidate) {

		// WARNING: THE ELAPSED TIME TO NOTIFY AN ALIAS/EXTERNAL REFERENCE ++MUST++ BE LESS THAN THE
		// TIME TO MARK UNACCESSIBLE OBJECT --- so, when we call this function we are sure
		// that there is no pending 'addRefs' about the object with +1 from an alias/external reference.

		if (DEBUG_ENABLED) {
			LOGGER.debug("[==GGC collector==] Found unaccesible object: " + unaccessibleCandidate);
		}
		// the object is actually unaccessible, get reference counting and 'discount' all references.
		// this way, we allow GC to clean cyclic retained references.
		// get the object from any EE (any replica is ok)
		byte[] unaccessibleBytes = null;
		for (final ExecutionEnvironmentID associatedExecutionEnvironmentID : this.storageLocation.getAssociateExecutionEnvironments()) {
			unaccessibleBytes = storageLocation.getDbHandler(associatedExecutionEnvironmentID).get(unaccessibleCandidate);
			if (unaccessibleBytes != null) {
				break;
			}
		}
		final byte[] unaccesibleRefCounting = DataClayDeserializationLib.extractReferenceCounting(unaccessibleBytes);
		final ReferenceCounting refCounting = new ReferenceCounting();
		refCounting.deserializeReferenceCounting(unaccessibleCandidate, unaccesibleRefCounting);
		for (final Entry<BackendID, Map<ObjectID, Integer>> countingsPerHint : refCounting.getReferenceCounting().entrySet()) {
			final BackendID ownerID = countingsPerHint.getKey();
			for (final Entry<ObjectID, Integer> pointedRefEntry : countingsPerHint.getValue().entrySet()) {
				final Integer counting = pointedRefEntry.getValue();
				final ObjectID pointedRef = pointedRefEntry.getKey();
				if (!pointedRef.equals(unaccessibleCandidate)) {
					if (DEBUG_ENABLED) {
						LOGGER.debug("[==GGC collector==] Found unaccesible reference: " + unaccessibleCandidate + "->" + pointedRef);
					}
					addRefs(pointedRef, ownerID, -counting, false);
				}
			}
		}

	}
	
	/**
	 * Provided object is actually accessible
	 * 
	 * @param unaccessibleCandidate
	 *            OID of unaccessible object.
	 */
	private void remarkAccesibleObject(final ObjectID unaccessibleCandidate) {
		if (DEBUG_ENABLED) {
			LOGGER.debug("[==GGC collector==] Marking unaccesible object as accesible: " + unaccessibleCandidate);
		}
		// the object is actually unaccessible, get reference counting and 'discount' all references.
		// this way, we allow GC to clean cyclic retained references.
		// get the object from any EE (any replica is ok)
		byte[] unaccessibleBytes = null;
		for (final ExecutionEnvironmentID associatedExecutionEnvironmentID : this.storageLocation.getAssociateExecutionEnvironments()) {
			unaccessibleBytes = storageLocation.getDbHandler(associatedExecutionEnvironmentID).get(unaccessibleCandidate);
			if (unaccessibleBytes != null) {
				break;
			}
		}
		final byte[] unaccesibleRefCounting = DataClayDeserializationLib.extractReferenceCounting(unaccessibleBytes);
		final ReferenceCounting refCounting = new ReferenceCounting();
		refCounting.deserializeReferenceCounting(unaccessibleCandidate, unaccesibleRefCounting);
		for (final Entry<BackendID, Map<ObjectID, Integer>> countingsPerHint : refCounting.getReferenceCounting().entrySet()) {
			final BackendID ownerID = countingsPerHint.getKey();
			for (final Entry<ObjectID, Integer> pointedRefEntry : countingsPerHint.getValue().entrySet()) {
				final Integer counting = pointedRefEntry.getValue();
				final ObjectID pointedRef = pointedRefEntry.getKey();
				if (!pointedRef.equals(unaccessibleCandidate)) {
					if (DEBUG_ENABLED) {
						LOGGER.debug("[==GGC collector==] Found unaccesible reference: " + unaccessibleCandidate + "->" + pointedRef);
					}
					addRefs(pointedRef, ownerID, counting, false);
				}
			}
		}

	}

	/**
	 * Process pending reference counters
	 */
	private void processPendingReferenceCounters() {
		if (DEBUG_ENABLED) {
			LOGGER.debug("[==GGC counters processor==] Processing pending references");
		}
		while (!refCounting.isEmpty()) {
			final SerializedReferenceCounting serializedRefCounting = refCounting.poll();
			if (serializedRefCounting.isGet) {
				countAtGet(serializedRefCounting);
			} else {
				// ==== design note ==== //
				// A is persisted via make persistent, nobody except a session and EE memory is using it
				// Since no-one is pointing to A, we do not have counter for A, so once EE releases A, we won't add it
				// as candidate.
				// Therefore, for each object we find here (it passes through SL) WITHOUT counter, we add a counter = 0, and
				// add it as candidate.
				// "adding 0" if it exists is not going to affect counting and we reuse the algorithm and locking system.
				LOGGER.trace("Adding 0 just in case to: " + serializedRefCounting.objectID);
				this.addRefs(serializedRefCounting.objectID, serializedRefCounting.eeID, 0, true);

				countAtUpdate(serializedRefCounting);
			}

		}
		if (DEBUG_ENABLED) {
			LOGGER.debug("[==GGC counters processor==] Finished processing pending references");
		}
	}

	/**
	 * Notify reference counting to other nodes.
	 */
	private void notifyReferencesToNodes() {
		if (DEBUG_ENABLED) {
			LOGGER.debug("[==GGC notifier==] Notifying references to other nodes");
			LOGGER.debug("[==GGC notifier==] Get locations of objects");
		}
		// ========== process objects without location hint ==== //
		final Map<ObjectID, AtomicInteger> unknownCounters = countersPerNode.get(UNKNOW_LOCATION_UUID);
		if (unknownCounters != null) {
			final Iterator<Entry<ObjectID, AtomicInteger>> iterator = unknownCounters.entrySet().iterator(); // use iterator for
																												// removing
			while (iterator.hasNext()) {
				try {
					final Entry<ObjectID, AtomicInteger> curCounting = iterator.next();
					final ObjectID oid = curCounting.getKey();
					final Integer counting = curCounting.getValue().get();
					// TODO: should we use a cache?
					if (DEBUG_ENABLED) {
						LOGGER.debug("[==GGC notifier==] Get location of object " + oid);
					}
					final MetaDataInfo mdInfo = runtime.getLogicModuleAPI().getMetadataByOIDForDS(oid);
					final BackendID execEnvID = mdInfo.getLocations().keySet().iterator().next();

					LOGGER.trace("After getting location, references to " + oid + "(" + counting + ")");
					addRefs(oid, execEnvID, counting, false);
				} catch (final Exception ex) {
					LOGGER.debug("notifyReferencesToNodes error", ex);
					// object has no metadata, maybe due to a explicit remove in a consolidate/move
				}
				iterator.remove();
			}
		}

		if (DEBUG_ENABLED) {
			LOGGER.debug("[==GGC notifier==] Now, notifying references");
		}
		// ========== NOTIFY ==== //
		for (final Entry<BackendID, Map<ObjectID, AtomicInteger>> countingsPerNode : countersPerNode.entrySet()) {
			final BackendID nodeID = countingsPerNode.getKey();
			// check if unknown due to concurrent conditions (added oid with unknown while processing this)
			if (!this.storageLocation.getAssociateExecutionEnvironments().contains(nodeID) && !nodeID.equals(UNKNOW_LOCATION_UUID)) {
				if (DEBUG_ENABLED) {
					LOGGER.debug("[==GGC notifier==] Notifying references to " + nodeID);
				}
				try {
					final DataServiceAPI dsAPI = runtime.getRemoteExecutionEnvironmentForDS((ExecutionEnvironmentID) nodeID);
					final Map<ObjectID, Integer> referenceCounting = new HashMap<>();
					for (final Entry<ObjectID, AtomicInteger> curCounting : countingsPerNode.getValue().entrySet()) {
						final AtomicInteger atomicCounting = curCounting.getValue();
						// Race condition design: if some one is trying to add/remove references, we set 0.
						final int value = atomicCounting.get(); // TODO: should we remove them? locking!
						referenceCounting.put(curCounting.getKey(), value);
						if (DEBUG_ENABLED) {
							LOGGER.debug("[==GGC notifier==] Notifying ref counting = " + value + " for object " + curCounting.getKey());
						}
					}

					dsAPI.updateRefs(referenceCounting);
					countersPerNode.remove(nodeID);

					// only set counters to 0 if communication succeeded
					for (final Entry<ObjectID, AtomicInteger> curCounting : countingsPerNode.getValue().entrySet()) {
						final AtomicInteger atomicCounting = curCounting.getValue();
						if (DEBUG_ENABLED) {
							LOGGER.debug("[==GGC notifier==] Setting ref counting = 0 for object " + curCounting.getKey());
						}
						atomicCounting.set(0);
					}

				} catch (final Exception ex) {
					LOGGER.debug("notifyReferencesToNodes expected error", ex);
					// node is shutdown, do not remove counting.
				}
			}
		}
	}

	/**
	 * Collect all objects that are garbage.
	 * 
	 * @param isShutDown
	 *            Indicates if it is a collection requested from shutdown process.
	 */
	private void collect(final boolean isShutDown) {
		if (DEBUG_ENABLED) {
			LOGGER.debug("[==GGC collector==] Checking and collecting");
		}
		try {
			// if there is replicas, the put will replace them but counter is the same.
			final Map<ObjectID, AtomicInteger> currentSLcounters = new HashMap<>();
			for (final ExecutionEnvironmentID associatedExecutionEnvironmentID : this.storageLocation.getAssociateExecutionEnvironments()) {
				final Map<ObjectID, AtomicInteger> thecounters = countersPerNode.get(associatedExecutionEnvironmentID);
				if (thecounters != null) {
					currentSLcounters.putAll(thecounters);
				}
			}
			final long currentTimeStamp = System.currentTimeMillis();

			// ========================= CHECK REFERENCES IN EE ======================= //
			final Set<ObjectID> referencedObjectsByEE = new HashSet<>();
			if (!isShutDown) { // if shutdown, memory and session references are ignored. (TODO: alias)
				if (DEBUG_ENABLED) {
					LOGGER.debug("[==GGC collector==] Asking for references retained in EEs " + this.storageLocation.getAssociateExecutionEnvironments());
				}
				// ==== PULL from EE: update reference counting === //
				// get information from EE to check if candidates are in memory, used by some session or have alias

				for (final ExecutionEnvironmentID associatedExecutionEnvironmentID : this.storageLocation.getAssociateExecutionEnvironments()) {
					final DataServiceAPI dsAPI = runtime.getRemoteExecutionEnvironmentForDS(associatedExecutionEnvironmentID);
					referencedObjectsByEE.addAll(dsAPI.getRetainedReferences());
				}
				// NOTE: do not add it to SL counters, just check it! EE is going to send what it has, not delta (-1 to session,
				// ...)
				// because actually we do not need it to know if we can remove an object or not.
				if (DEBUG_ENABLED) {
					LOGGER.debug("[==GGC collector==] Retained refs in EE: " + referencedObjectsByEE.size());
				}

			}
			// ========================= CHECK UNACCESSIBLE CANDIDATES ======================= //

			// If an unaccessible candidate is retained in EE then it might be accessible.
			// However, we cannot remove the candidate to be unaccessible because we do not know why it is being
			// retained in EE (maybe last execution). For each unaccessible candidate that is not retained,
			// we know they are actually unaccesible.
			final Set<ObjectID> actualUnaccessibles = new HashSet<>();
			synchronized (unaccessibleCandidates) { //Avoid concurrent modification during updates
				for (final ObjectID unaccessibleCandidate : unaccessibleCandidates) {
					if (!referencedObjectsByEE.contains(unaccessibleCandidate)) {
						try {
	
							this.markUnaccesibleObject(unaccessibleCandidate);
							actualUnaccessibles.add(unaccessibleCandidate);
							
	
						} catch (final Exception e) {
							// FIXME: object might be removed explicitly (dgasull 2018)
							// (usually this happens in consolidate or moves)
							// Currently, object is considered actually unaccessible if exception is raised.
							// Fix this when resuming consolidates and moves.
							LOGGER.debug(e);
						}
					}
				}
				unaccessibleCandidates.removeAll(actualUnaccessibles);
			}

			// ========================= CHECK CANDIDATES ======================= //

			// at this point, if some candidate is in memory, has alias or session, is not in list anymore.
			if (DEBUG_ENABLED) {
				LOGGER.debug("[==GGC collector==] Number of candidates: " + candidates.size());
				LOGGER.debug("[==GGC collector==] Candidates: " + candidates);
			}

			final int candidatesSize = candidates.size(); // get size here since loop is modifying it.
			int i = 0;
			while (i < candidatesSize && i < Configuration.Flags.GLOBALGC_MAX_OBJECTS_TO_COLLECT_PERTASK.getIntValue()) {
				final ObjectID oidCandidate = candidates.poll();
				// race condition: i added an object as a candidate but later references were added.
				// so check num. references again.
				final int numRefs = this.getNumReferencesTo(oidCandidate);
				// actual number of references
				if (DEBUG_ENABLED) {
					LOGGER.debug("[==GGC collector==] For object " + oidCandidate + ", num references: " + numRefs);
				}

				if (numRefs == 0) {
					// check if no reference in EE
					boolean retainedByEE = false;
					if (!isShutDown) {
						retainedByEE = referencedObjectsByEE.contains(oidCandidate);
						if (DEBUG_ENABLED) {
							LOGGER.debug("[==GGC collector==] For object " + oidCandidate + ", retained in EE: " + retainedByEE);
						}
					}
					// candidates are added to quarantine.
					if (!quarantine.containsKey(oidCandidate) && !retainedByEE) {
						if (DEBUG_ENABLED) {
							LOGGER.debug("[==GGC collector==] Adding to quarantine: " + oidCandidate);
						}
						quarantine.put(oidCandidate, System.currentTimeMillis());
					} else {
						// add it to the tail of the queue
						candidates.offer(oidCandidate);
					}
				} else { 
					if (DEBUG_ENABLED) {
						LOGGER.debug("[==GGC collector==] References to candidate object {} is not zero, probably due to a cycle, "
								+ ". It was polled from candidates list, will be added to unaccessible candidates when references are 0", oidCandidate);
					}
					
					
				}
				i++;
			}

			// =============================== REMOVE OBJECTS IN QUARANTINE =============================== //
			if (DEBUG_ENABLED) {
				LOGGER.debug("[==GGC collector==] Number of objects in quarantine: " + quarantine.size());
			}

			final Set<ObjectID> objectsToUnregister = new HashSet<>();
			final Iterator<Entry<ObjectID, Long>> iterator = this.quarantine.entrySet().iterator();
			while (iterator.hasNext() && i < Configuration.Flags.GLOBALGC_MAX_OBJECTS_TO_COLLECT_PERTASK.getIntValue()) {
				final Entry<ObjectID, Long> curQuarantine = iterator.next();
				final ObjectID oid = curQuarantine.getKey();
				// check no new references appeared
				int numRefs = 0;
				if (currentSLcounters != null) { // is null if no other objects point to this and nothing is registered.
					// no need to check references in EE since we make sure that time in quarantine is enough to get EE ref.
					// counting.
					final AtomicInteger numRefsAssociatedAtomic = currentSLcounters.get(oid);
					if (numRefsAssociatedAtomic != null) {
						numRefs = numRefsAssociatedAtomic.get();
					}
				}
				if (DEBUG_ENABLED) {
					LOGGER.debug("[==GGC collector==] Num references for " + oid + " = " + numRefs);
				}
				if (numRefs == 0) {
					final Long timestamp = curQuarantine.getValue();
					final long diff = currentTimeStamp - timestamp;
					if (diff > Configuration.Flags.GLOBALGC_MAX_TIME_QUARANTINE.getLongValue()) {
						// remove it from all databases
						for (final ExecutionEnvironmentID associatedExecutionEnvironmentID : this.storageLocation.getAssociateExecutionEnvironments()) {
							if (DEBUG_ENABLED) {
								LOGGER.debug("[==GGC collector==] *** Going to remove {} from {}", oid, associatedExecutionEnvironmentID);
							}
							try {
								storageLocation.delete(associatedExecutionEnvironmentID, oid);

							} catch (final Exception e) {
								// FIXME: object might be removed explicitly (dgasull 2018)
								// (usually this happens in consolidate or moves)
								// Currently, if already removed, ignore it.
								// Fix this when resuming consolidates and moves.
								LOGGER.debug(e);
							}
						}
						if (DEBUG_ENABLED) {
							LOGGER.debug("[==GGC collector==] *** Object " + oid + " removed from DB");
						}
						System.err.println("[==GGC collector==] *** Object " + oid + " removed from DB");

						objectsToUnregister.add(oid);

						// remove it from reference counting map if present
						if (currentSLcounters != null) {
							currentSLcounters.remove(oid);
						}
						// remove it from quarantine map
						iterator.remove();
					} else {
						if (DEBUG_ENABLED) {
							LOGGER.debug("[==GGC collector==] Object " + oid + " must stay in quarantine. Spend time: " + diff);
						}
					}
				} else { 
					
				}
				i++;
			}

			if (objectsToUnregister.size() > 0) {
				if (DEBUG_ENABLED) {
					LOGGER.debug("[==GGC collector==] *** Going to unregister " + objectsToUnregister);
				}
				runtime.getLogicModuleAPI().unregisterObjects(objectsToUnregister);
			}
		} catch (final Exception ex) {
			LOGGER.debug("collect error", ex);
		}
		if (DEBUG_ENABLED) {
			LOGGER.debug("[==GGC collector==] Finished collection");
		}
	}

	/**
	 * Task that collects objects in disk and send update of references.
	 */
	private class CollectorTask extends TimerTask {

		@Override
		public final void run() {
			if (DEBUG_ENABLED) {
				LOGGER.debug("[==GGC Collector==] Starting");
			}
			collect(false);
		}
	}

	/**
	 * Task that process serialized reference countings.
	 */
	private class ReferenceCountingQueueProcessor extends TimerTask {

		@Override
		public final void run() {
			if (DEBUG_ENABLED) {
				LOGGER.debug("[==GGC counters processor==] Starting");
			}
			processPendingReferenceCounters();
		}
	}

	/**
	 * Task that notifies other nodes about reference countings here belonging to them.
	 */
	private class RemoteReferenceCountingNotifier extends TimerTask {

		@Override
		public final void run() {
			if (DEBUG_ENABLED) {
				LOGGER.debug("[==GGC notifier==] Starting");
			}
			notifyReferencesToNodes();
		}

	}

	/**
	 * Return number of references pointing to object.
	 * 
	 * @param objectID
	 *            ID of object
	 * @return Number of references pointing to object
	 */
	public int getNumReferencesTo(final ObjectID objectID) {
		// Check in all EEs until we find the object, (object can be en many, but they are replicas)
		Map<ObjectID, AtomicInteger> referencesInThisNode = null;
		for (final ExecutionEnvironmentID associatedExecutionEnvironmentID : this.storageLocation.getAssociateExecutionEnvironments()) {
			final Map<ObjectID, AtomicInteger> curReferencesInThisNode = countersPerNode.get(associatedExecutionEnvironmentID);
			if (curReferencesInThisNode != null) {
				if (DEBUG_ENABLED) {
					LOGGER.debug("[==Getting number of references==] Found references to {} in node {}",
							objectID, associatedExecutionEnvironmentID);
				}
				referencesInThisNode = curReferencesInThisNode;
			}
		}
		if (referencesInThisNode == null) {
			return 0;
		}
		final AtomicInteger refs = referencesInThisNode.get(objectID);
		if (refs == null) {
			return 0;
		} else {
			final int numRefs = refs.get();
			if (DEBUG_ENABLED) {
				LOGGER.debug("[==Getting number of references==] References to {} are: {}",
						objectID, numRefs);
			}
			return numRefs;
		}
	}
	
}
