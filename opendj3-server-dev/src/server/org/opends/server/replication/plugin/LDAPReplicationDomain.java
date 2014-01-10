/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at legal-notices/CDDLv1_0.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2006-2010 Sun Microsystems, Inc.
 *      Portions Copyright 2011-2014 ForgeRock AS
 */
package org.opends.server.replication.plugin;

import java.io.*;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.DataFormatException;

import org.opends.messages.Category;
import org.opends.messages.Message;
import org.opends.messages.MessageBuilder;
import org.opends.messages.Severity;
import org.opends.server.admin.server.ConfigurationChangeListener;
import org.opends.server.admin.std.meta.ReplicationDomainCfgDefn.*;
import org.opends.server.admin.std.server.ExternalChangelogDomainCfg;
import org.opends.server.admin.std.server.ReplicationDomainCfg;
import org.opends.server.api.AlertGenerator;
import org.opends.server.api.Backend;
import org.opends.server.api.DirectoryThread;
import org.opends.server.api.SynchronizationProvider;
import org.opends.server.backends.task.Task;
import org.opends.server.config.ConfigException;
import org.opends.server.core.*;
import org.opends.server.loggers.debug.DebugTracer;
import org.opends.server.protocols.asn1.ASN1Exception;
import org.opends.server.protocols.internal.InternalClientConnection;
import org.opends.server.protocols.internal.InternalSearchListener;
import org.opends.server.protocols.internal.InternalSearchOperation;
import org.opends.server.protocols.ldap.LDAPAttribute;
import org.opends.server.protocols.ldap.LDAPControl;
import org.opends.server.protocols.ldap.LDAPFilter;
import org.opends.server.protocols.ldap.LDAPModification;
import org.opends.server.replication.common.*;
import org.opends.server.replication.protocol.*;
import org.opends.server.replication.service.ReplicationBroker;
import org.opends.server.replication.service.ReplicationDomain;
import org.opends.server.tasks.PurgeConflictsHistoricalTask;
import org.opends.server.tasks.TaskUtils;
import org.opends.server.types.*;
import org.forgerock.opendj.ldap.ByteString;
import org.opends.server.types.operation.*;
import org.opends.server.util.LDIFReader;
import org.opends.server.util.TimeThread;
import org.opends.server.workflowelement.externalchangelog.ECLWorkflowElement;
import org.opends.server.workflowelement.localbackend.*;

import static org.opends.messages.ReplicationMessages.*;
import static org.opends.messages.ToolMessages.*;
import static org.opends.server.loggers.ErrorLogger.*;
import static org.opends.server.loggers.debug.DebugLogger.*;
import static org.opends.server.replication.plugin.EntryHistorical.*;
import static org.opends.server.replication.protocol.OperationContext.*;
import static org.opends.server.replication.service.ReplicationMonitor.*;
import static org.opends.server.types.ResultCode.*;
import static org.opends.server.util.ServerConstants.*;
import static org.opends.server.util.StaticUtils.*;

/**
 *  This class implements the bulk part of the Directory Server side
 *  of the replication code.
 *  It contains the root method for publishing a change,
 *  processing a change received from the replicationServer service,
 *  handle conflict resolution,
 *  handle protocol messages from the replicationServer.
 */
public final class LDAPReplicationDomain extends ReplicationDomain
       implements ConfigurationChangeListener<ReplicationDomainCfg>,
                  AlertGenerator
{

  /**
   * Set of attributes that will return all the user attributes and the
   * replication related operational attributes when used in a search operation.
   */
  private static final Set<String> USER_AND_REPL_OPERATIONAL_ATTRS =
      new HashSet<String>(Arrays.asList(
          HISTORICAL_ATTRIBUTE_NAME, ENTRYUUID_ATTRIBUTE_NAME, "*"));

  /**
   * This class is used in the session establishment phase
   * when no Replication Server with all the local changes has been found
   * and we therefore need to recover them.
   * A search is then performed on the database using this
   * internalSearchListener.
   */
  private class ScanSearchListener implements InternalSearchListener
  {
    private final CSN startCSN;
    private final CSN endCSN;

    public ScanSearchListener(CSN startCSN, CSN endCSN)
    {
      this.startCSN = startCSN;
      this.endCSN = endCSN;
    }

    @Override
    public void handleInternalSearchEntry(
        InternalSearchOperation searchOperation, SearchResultEntry searchEntry)
        throws DirectoryException
    {
      // Build the list of Operations that happened on this entry after startCSN
      // and before endCSN and add them to the replayOperations list
      Iterable<FakeOperation> updates =
        EntryHistorical.generateFakeOperations(searchEntry);

      for (FakeOperation op : updates)
      {
        CSN csn = op.getCSN();
        if (csn.isNewerThan(startCSN) && csn.isOlderThan(endCSN))
        {
          synchronized (replayOperations)
          {
            replayOperations.put(csn, op);
          }
        }
      }
    }

    @Override
    public void handleInternalSearchReference(
        InternalSearchOperation searchOperation,
        SearchResultReference searchReference) throws DirectoryException
    {
       // Nothing to do.
    }
  }

  /**
   * The fully-qualified name of this class.
   */
  private static final String CLASS_NAME = LDAPReplicationDomain.class
      .getName();

  /**
   * The attribute used to mark conflicting entries.
   * The value of this attribute should be the dn that this entry was
   * supposed to have when it was marked as conflicting.
   */
  public static final String DS_SYNC_CONFLICT = "ds-sync-conflict";

 /**
   * The tracer object for the debug logger.
   */
  private static final DebugTracer TRACER = getTracer();

  /**
   * The update to replay message queue where the listener thread is going to
   * push incoming update messages.
   */
  private final BlockingQueue<UpdateToReplay> updateToReplayQueue;
  private final AtomicInteger numResolvedNamingConflicts = new AtomicInteger();
  private final AtomicInteger numResolvedModifyConflicts = new AtomicInteger();
  private final AtomicInteger numUnresolvedNamingConflicts =
    new AtomicInteger();
  private final PersistentServerState state;
  private int numReplayedPostOpCalled = 0;

  private volatile boolean generationIdSavedStatus = false;

  private final CSNGenerator generator;

  /**
   * This object is used to store the list of update currently being
   * done on the local database.
   * Is is useful to make sure that the local operations are sent in a
   * correct order to the replication server and that the ServerState
   * is not updated too early.
   */
  private final PendingChanges pendingChanges;
  private final AtomicReference<RSUpdater> rsUpdater =
      new AtomicReference<RSUpdater>(null);

  /**
   * It contain the updates that were done on other servers, transmitted
   * by the replication server and that are currently replayed.
   * It is useful to make sure that dependencies between operations
   * are correctly fulfilled and to to make sure that the ServerState is
   * not updated too early.
   */
  private final RemotePendingChanges remotePendingChanges;

  private final InternalClientConnection conn =
      InternalClientConnection.getRootConnection();

  private boolean solveConflictFlag = true;

  private volatile boolean shutdown = false;
  private volatile boolean disabled = false;
  private volatile boolean stateSavingDisabled = false;

  /**
   * This list is used to temporary store operations that needs to be replayed
   * at session establishment time.
   */
  private final SortedMap<CSN, FakeOperation> replayOperations =
    new TreeMap<CSN, FakeOperation>();

  private ExternalChangelogDomain eclDomain;

  /**
   * A boolean indicating if the thread used to save the persistentServerState
   * is terminated.
   */
  private volatile boolean done = true;

  private final ServerStateFlush flushThread;

  /**
   * The attribute name used to store the generation id in the backend.
   */
  private static final String REPLICATION_GENERATION_ID =
    "ds-sync-generation-id";
  /**
   * The attribute name used to store the fractional include configuration in
   * the backend.
   */
  public static final String REPLICATION_FRACTIONAL_INCLUDE =
    "ds-sync-fractional-include";
  /**
   * The attribute name used to store the fractional exclude configuration in
   * the backend.
   */
  public static final String REPLICATION_FRACTIONAL_EXCLUDE =
    "ds-sync-fractional-exclude";

  /**
   * Fractional replication variables.
   */

  /** Holds the fractional configuration for this domain, if any. */
  private FractionalConfig fractionalConfig;

  /**
   * The list of attributes that cannot be used in fractional replication
   * configuration.
   */
  private static final String[] FRACTIONAL_PROHIBITED_ATTRIBUTES = new String[]
  {
    "objectClass",
    "2.5.4.0" // objectClass OID
  };

  /**
   * When true, this flag is used to force the domain status to be put in bad
   * data set just after the connection to the replication server.
   * This must be used when fractional replication is enabled with a
   * configuration different from the previous one (or at the very first
   * fractional usage time) : after connection, a ChangeStatusMsg is sent
   * requesting the bad data set status. Then none of the update messages
   * received from the replication server are taken into account until the
   * backend is synchronized with brand new data set compliant with the new
   * fractional configuration (i.e with compliant fractional configuration in
   * domain root entry).
   */
  private boolean forceBadDataSet = false;

  /**
   * The message id to be used when an import is stopped with error by
   * the fractional replication ldif import plugin.
   */
  private int importErrorMessageId = -1;
  /**
   * Message type for ERR_FULL_UPDATE_IMPORT_FRACTIONAL_BAD_REMOTE.
   */
  public static final int IMPORT_ERROR_MESSAGE_BAD_REMOTE = 1;
  /**
   * Message type for ERR_FULL_UPDATE_IMPORT_FRACTIONAL_REMOTE_IS_FRACTIONAL.
   */
  public static final int IMPORT_ERROR_MESSAGE_REMOTE_IS_FRACTIONAL = 2;

  /*
   * Definitions for the return codes of the
   * fractionalFilterOperation(PreOperationModifyOperation
   *  modifyOperation, boolean performFiltering) method
   */
  /**
   * The operation contains attributes subject to fractional filtering according
   * to the fractional configuration.
   */
  private static final int FRACTIONAL_HAS_FRACTIONAL_FILTERED_ATTRIBUTES = 1;
  /**
   * The operation contains no attributes subject to fractional filtering
   * according to the fractional configuration.
   */
  private static final int FRACTIONAL_HAS_NO_FRACTIONAL_FILTERED_ATTRIBUTES = 2;
  /** The operation should become a no-op. */
  private static final int FRACTIONAL_BECOME_NO_OP = 3;

  /**
   * The last CSN purged in this domain. Allows to have a continuous purging
   * process from one purge processing (task run) to the next one. Values 0 when
   * the server starts.
   */
  private CSN lastCSNPurgedFromHist = new CSN(0,0,0);

  /**
   * The thread that periodically saves the ServerState of this
   * LDAPReplicationDomain in the database.
   */
  private class ServerStateFlush extends DirectoryThread
  {
    protected ServerStateFlush()
    {
      super("Replica DS(" + getServerId()
          + ") state checkpointer for domain \"" + getBaseDNString() + "\"");
    }

    /** {@inheritDoc} */
    @Override
    public void run()
    {
      done = false;

      while (!isShutdownInitiated())
      {
        try
        {
          synchronized (this)
          {
            this.wait(1000);
            if (!disabled && !stateSavingDisabled)
            {
              // save the ServerState
              state.save();
            }
          }
        }
        catch (InterruptedException e)
        {
          // Thread interrupted: check for shutdown.
          Thread.currentThread().interrupt();
        }
      }
      state.save();

      done = true;
    }
  }

  /**
   * The thread that is responsible to update the RS to which this domain is
   * connected in case it is late and there is no RS which is up to date.
   */
  private class RSUpdater extends DirectoryThread
  {
    private final CSN startCSN;
    /**
     * Used to communicate that the current thread computation needs to
     * shutdown.
     */
    private AtomicBoolean shutdown = new AtomicBoolean(false);

    protected RSUpdater(CSN replServerMaxCSN)
    {
      super("Replica DS(" + getServerId()
          + ") missing change publisher for domain \"" + getBaseDNString()
          + "\"");
      this.startCSN = replServerMaxCSN;
    }

    /** {@inheritDoc} */
    @Override
    public void run()
    {
      // Replication server is missing some of our changes: let's
      // send them to him.
      logError(DEBUG_GOING_TO_SEARCH_FOR_CHANGES.get());

      /*
       * Get all the changes that have not been seen by this
       * replication server and publish them.
       */
      try
      {
        if (buildAndPublishMissingChanges(startCSN, broker, shutdown))
        {
          logError(DEBUG_CHANGES_SENT.get());
          synchronized(replayOperations)
          {
            replayOperations.clear();
          }
        }
        else
        {
          /*
           * An error happened trying to search for the updates
           * This server will start accepting again new updates but
           * some inconsistencies will stay between servers.
           * Log an error for the repair tool
           * that will need to re-synchronize the servers.
           */
          logError(ERR_CANNOT_RECOVER_CHANGES.get(getBaseDNString()));
        }
      } catch (Exception e)
      {
        /*
         * An error happened trying to search for the updates
         * This server will start accepting again new updates but
         * some inconsistencies will stay between servers.
         * Log an error for the repair tool
         * that will need to re-synchronize the servers.
         */
        logError(ERR_CANNOT_RECOVER_CHANGES.get(getBaseDNString()));
      }
      finally
      {
        broker.setRecoveryRequired(false);
        // RSUpdater thread has finished its work, let's remove it from memory
        // so another RSUpdater thread can be started if needed.
        rsUpdater.compareAndSet(this, null);
      }
    }

    /** {@inheritDoc} */
    @Override
    public void initiateShutdown()
    {
      this.shutdown.set(true);
      super.initiateShutdown();
    }
  }


  /**
   * Creates a new ReplicationDomain using configuration from configEntry.
   *
   * @param configuration    The configuration of this ReplicationDomain.
   * @param updateToReplayQueue The queue for update messages to replay.
   * @throws ConfigException In case of invalid configuration.
   */
  public LDAPReplicationDomain(ReplicationDomainCfg configuration,
      BlockingQueue<UpdateToReplay> updateToReplayQueue) throws ConfigException
  {
    super(configuration, -1);

    this.updateToReplayQueue = updateToReplayQueue;

    // Get assured configuration
    readAssuredConfig(configuration, false);

    // Get fractional configuration
    fractionalConfig = new FractionalConfig(getBaseDN());
    readFractionalConfig(configuration, false);
    storeECLConfiguration(configuration);
    solveConflictFlag = isSolveConflict(configuration);

    Backend backend = getBackend();
    if (backend == null)
    {
      throw new ConfigException(ERR_SEARCHING_DOMAIN_BACKEND.get(
                                  getBaseDNString()));
    }

    try
    {
      generationId = loadGenerationId();
    }
    catch (DirectoryException e)
    {
      logError(ERR_LOADING_GENERATION_ID.get(
          getBaseDNString(), stackTraceToSingleLineString(e)));
    }

    /*
     * Create a new Persistent Server State that will be used to store
     * the last CSN seen from all LDAP servers in the topology.
     */
    state = new PersistentServerState(getBaseDN(), getServerId(),
        getServerState());
    flushThread = new ServerStateFlush();

    /*
     * CSNGenerator is used to create new unique CSNs for each operation done on
     * this replication domain.
     *
     * The generator time is adjusted to the time of the last CSN received from
     * remote other servers.
     */
    generator = getGenerator();

    pendingChanges = new PendingChanges(generator, this);
    remotePendingChanges = new RemotePendingChanges(getServerState());

    // listen for changes on the configuration
    configuration.addChangeListener(this);

    // register as an AlertGenerator
    DirectoryServer.registerAlertGenerator(this);

    startPublishService(configuration);
  }

  /**
   * Modify conflicts are solved for all suffixes but the schema suffix because
   * we don't want to store extra information in the schema ldif files. This has
   * no negative impact because the changes on schema should not produce
   * conflicts.
   */
  private boolean isSolveConflict(ReplicationDomainCfg cfg)
  {
    return !getBaseDN().equals(DirectoryServer.getSchemaDN())
        && cfg.isSolveConflicts();
  }

  /**
   * Sets the error message id to be used when online import is stopped with
   * error by the fractional replication ldif import plugin.
   * @param importErrorMessageId The message to use.
   */
  void setImportErrorMessageId(int importErrorMessageId)
  {
    this.importErrorMessageId = importErrorMessageId;
  }

  /**
   * This flag is used by the fractional replication ldif import plugin to stop
   * the (online) import process if a fractional configuration inconsistency is
   * detected by it.
   *
   * @return true if the online import currently in progress should continue,
   *         false otherwise.
   */
  private boolean isFollowImport()
  {
    return importErrorMessageId == -1;
  }

  /**
   * Gets and stores the fractional replication configuration parameters.
   * @param configuration The configuration object
   * @param allowReconnection Tells if one must reconnect if significant changes
   *        occurred
   */
  private void readFractionalConfig(ReplicationDomainCfg configuration,
    boolean allowReconnection)
  {
    // Read the configuration entry
    FractionalConfig newFractionalConfig;
    try
    {
      newFractionalConfig = FractionalConfig.toFractionalConfig(configuration);
    }
    catch(ConfigException e)
    {
      // Should not happen as normally already called without problem in
      // isConfigurationChangeAcceptable or isConfigurationAcceptable
      // if we come up to this method
      logError(NOTE_ERR_FRACTIONAL.get(getBaseDNString(),
          stackTraceToSingleLineString(e)));
      return;
    }

    /**
     * Is there any change in fractional configuration ?
     */

    // Compute current configuration
    boolean needReconnection;
     try
    {
      needReconnection = !FractionalConfig.
        isFractionalConfigEquivalent(fractionalConfig, newFractionalConfig);
    }
    catch  (ConfigException e)
    {
      // Should not happen
      logError(NOTE_ERR_FRACTIONAL.get(getBaseDNString(),
          stackTraceToSingleLineString(e)));
      return;
    }

    // Disable service if configuration changed
    final boolean needRestart = needReconnection && allowReconnection;
    if (needRestart)
    {
      disableService();
    }
    // Set new configuration
    int newFractionalMode = newFractionalConfig.fractionalConfigToInt();
    fractionalConfig.setFractional(newFractionalMode !=
      FractionalConfig.NOT_FRACTIONAL);
    if (fractionalConfig.isFractional())
    {
      // Set new fractional configuration values
      fractionalConfig.setFractionalExclusive(
          newFractionalMode == FractionalConfig.EXCLUSIVE_FRACTIONAL);
      fractionalConfig.setFractionalSpecificClassesAttributes(
        newFractionalConfig.getFractionalSpecificClassesAttributes());
      fractionalConfig.setFractionalAllClassesAttributes(
        newFractionalConfig.fractionalAllClassesAttributes);
    } else
    {
      // Reset default values
      fractionalConfig.setFractionalExclusive(true);
      fractionalConfig.setFractionalSpecificClassesAttributes(
        new HashMap<String, Set<String>>());
      fractionalConfig.setFractionalAllClassesAttributes(new HashSet<String>());
    }

    // Reconnect if required
    if (needRestart)
      enableService();
  }

  /**
   * Return true if the fractional configuration stored in the domain root
   * entry of the backend is equivalent to the fractional configuration stored
   * in the local variables.
   */
  private boolean isBackendFractionalConfigConsistent()
  {
    // Read config stored in domain root entry
    if (debugEnabled())
      TRACER.debugInfo(
          "Attempt to read the potential fractional config in domain root "
              + "entry " + getBaseDNString());

    LDAPFilter filter;
    try
    {
      filter = LDAPFilter.decode("(objectclass=*)");
    } catch (LDAPException e)
    {
      // Can not happen
      return false;
    }

    // Search the domain root entry that is used to save the generation id
    ByteString asn1BaseDn = ByteString.valueOf(getBaseDNString());
    Set<String> attributes = new LinkedHashSet<String>(3);
    attributes.add(REPLICATION_GENERATION_ID);
    attributes.add(REPLICATION_FRACTIONAL_EXCLUDE);
    attributes.add(REPLICATION_FRACTIONAL_INCLUDE);
    InternalSearchOperation search = conn.processSearch(asn1BaseDn,
      SearchScope.BASE_OBJECT,
      DereferencePolicy.DEREF_ALWAYS, 0, 0, false,
      filter, attributes);
    if (search.getResultCode() != ResultCode.SUCCESS
        && search.getResultCode() != ResultCode.NO_SUCH_OBJECT)
    {
      logError(ERR_SEARCHING_GENERATION_ID.get(
        search.getResultCode().getResultCodeName() + " " +
        search.getErrorMessage(),
        getBaseDNString()));
      return false;
    }

    SearchResultEntry resultEntry = findReplicationSearchResultEntry(search);
    if (resultEntry == null)
    {
      /*
       * The backend is probably empty: if there is some fractional
       * configuration in memory, we do not let the domain being connected,
       * otherwise, it's ok
       */
      return !fractionalConfig.isFractional();
    }

    // Now extract fractional configuration if any
    Iterator<String> exclIt =
        getAttributeValueIterator(resultEntry, REPLICATION_FRACTIONAL_EXCLUDE);
    Iterator<String> inclIt =
        getAttributeValueIterator(resultEntry, REPLICATION_FRACTIONAL_INCLUDE);

    // Compare backend and local fractional configuration
    return isFractionalConfigConsistent(fractionalConfig, exclIt, inclIt);
  }

  private SearchResultEntry findReplicationSearchResultEntry(
      InternalSearchOperation searchOperation)
  {
    if (searchOperation.getResultCode() != ResultCode.SUCCESS)
    {
      return null;
    }

    List<SearchResultEntry> result = searchOperation.getSearchEntries();
    SearchResultEntry resultEntry = result.get(0);
    if (resultEntry != null)
    {
      AttributeType synchronizationGenIDType =
          DirectoryServer.getAttributeType(REPLICATION_GENERATION_ID);
      List<Attribute> attrs =
          resultEntry.getAttribute(synchronizationGenIDType);
      if (attrs != null)
      {
        Attribute attr = attrs.get(0);
        if (attr.size() == 1)
        {
          return resultEntry;
        }
        if (attr.size() > 1)
        {
          logError(ERR_LOADING_GENERATION_ID.get(getBaseDNString(),
              "#Values=" + attr.size() + " Must be exactly 1 in entry "
              + resultEntry.toLDIFString()));
        }
      }
    }
    return null;
  }

  private Iterator<String> getAttributeValueIterator(
      SearchResultEntry resultEntry, String attrName)
  {
    AttributeType attrType = DirectoryServer.getAttributeType(attrName);
    List<Attribute> exclAttrs = resultEntry.getAttribute(attrType);
    if (exclAttrs != null)
    {
      Attribute exclAttr = exclAttrs.get(0);
      if (exclAttr != null)
      {
        return new AttributeValueStringIterator(exclAttr.iterator());
      }
    }
    return null;
  }

  /**
   * Return true if the fractional configuration passed as fractional
   * configuration attribute values is equivalent to the fractional
   * configuration stored in the local variables.
   * @param fractionalConfig The local fractional configuration
   * @param exclIt Fractional exclude mode configuration attribute values to
   * analyze.
   * @param inclIt Fractional include mode configuration attribute values to
   * analyze.
   * @return True if the fractional configuration passed as fractional
   * configuration attribute values is equivalent to the fractional
   * configuration stored in the local variables.
   */
  static boolean isFractionalConfigConsistent(
      FractionalConfig fractionalConfig, Iterator<String> exclIt,
      Iterator<String> inclIt)
  {
    /*
     * Parse fractional configuration stored in passed fractional configuration
     * attributes values
     */

    Map<String, Set<String>> storedFractionalSpecificClassesAttributes =
        new HashMap<String, Set<String>>();
    Set<String> storedFractionalAllClassesAttributes = new HashSet<String>();

    int storedFractionalMode;
    try
    {
      storedFractionalMode = FractionalConfig.parseFractionalConfig(exclIt,
        inclIt, storedFractionalSpecificClassesAttributes,
        storedFractionalAllClassesAttributes);
    } catch (ConfigException e)
    {
      // Should not happen as configuration in domain root entry is flushed
      // from valid configuration in local variables
      logError(NOTE_ERR_FRACTIONAL.get(
          fractionalConfig.getBaseDn().toString(),
          stackTraceToSingleLineString(e)));
      return false;
    }

    FractionalConfig storedFractionalConfig = new FractionalConfig(
      fractionalConfig.getBaseDn());
    storedFractionalConfig.setFractional(storedFractionalMode !=
      FractionalConfig.NOT_FRACTIONAL);
    // Set stored fractional configuration values
    if (storedFractionalConfig.isFractional())
    {
      storedFractionalConfig.setFractionalExclusive(
          storedFractionalMode == FractionalConfig.EXCLUSIVE_FRACTIONAL);
    }
    storedFractionalConfig.setFractionalSpecificClassesAttributes(
      storedFractionalSpecificClassesAttributes);
    storedFractionalConfig.setFractionalAllClassesAttributes(
      storedFractionalAllClassesAttributes);

    /*
     * Compare configuration stored in passed fractional configuration
     * attributes with local variable one
     */
    try
    {
      return FractionalConfig.
        isFractionalConfigEquivalent(fractionalConfig, storedFractionalConfig);
    } catch (ConfigException e)
    {
      // Should not happen as configuration in domain root entry is flushed
      // from valid configuration in local variables so both should have already
      // been checked
      logError(NOTE_ERR_FRACTIONAL.get(
          fractionalConfig.getBaseDn().toString(),
          stackTraceToSingleLineString(e)));
      return false;
    }
  }

  /**
   * Utility class to have get a string iterator from an AtributeValue iterator.
   * Assuming the attribute values are strings.
   */
  public static class AttributeValueStringIterator implements Iterator<String>
  {
    private Iterator<AttributeValue> attrValIt;

    /**
     * Creates a new AttributeValueStringIterator object.
     * @param attrValIt The underlying attribute iterator to use, assuming
     * internal values are strings.
     */
    public AttributeValueStringIterator(Iterator<AttributeValue> attrValIt)
    {
      this.attrValIt = attrValIt;
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasNext()
    {
      return attrValIt.hasNext();
    }

    /** {@inheritDoc} */
    @Override
    public String next()
    {
      return attrValIt.next().getValue().toString();
    }

    /** {@inheritDoc} */
    // Should not be needed anyway
    @Override
    public void remove()
    {
      attrValIt.remove();
    }
  }

  /**
   * Compare 2 attribute collections and returns true if they are equivalent.
   *
   * @param attributes1
   *          First attribute collection to compare.
   * @param attributes2
   *          Second attribute collection to compare.
   * @return True if both attribute collection are equivalent.
   * @throws ConfigException
   *           If some attributes could not be retrieved from the schema.
   */
  private static boolean areAttributesEquivalent(
      Collection<String> attributes1, Collection<String> attributes2)
      throws ConfigException
  {
    // Compare all classes attributes
    if (attributes1.size() != attributes2.size())
      return false;

    // Check consistency of all classes attributes
    Schema schema = DirectoryServer.getSchema();
    /*
     * For each attribute in attributes1, check there is the matching
     * one in attributes2.
     */
    for (String attrName1 : attributes1)
    {
      // Get attribute from attributes1
      AttributeType attributeType1 = schema.getAttributeType(attrName1);
      if (attributeType1 == null)
      {
        throw new ConfigException(
          NOTE_ERR_FRACTIONAL_CONFIG_UNKNOWN_ATTRIBUTE_TYPE.get(attrName1));
      }
      // Look for matching one in attributes2
      boolean foundAttribute = false;
      for (String attrName2 : attributes2)
      {
        AttributeType attributeType2 = schema.getAttributeType(attrName2);
        if (attributeType2 == null)
        {
          throw new ConfigException(
            NOTE_ERR_FRACTIONAL_CONFIG_UNKNOWN_ATTRIBUTE_TYPE.get(attrName2));
        }
        if (attributeType1.equals(attributeType2))
        {
          foundAttribute = true;
          break;
        }
      }
      // Found matching attribute ?
      if (!foundAttribute)
        return false;
    }

    return true;
  }

  /**
   * Check that the passed fractional configuration is acceptable
   * regarding configuration syntax, schema constraints...
   * Throws an exception if the configuration is not acceptable.
   * @param configuration The configuration to analyze.
   * @throws org.opends.server.config.ConfigException if the configuration is
   * not acceptable.
   */
  private static void isFractionalConfigAcceptable(
    ReplicationDomainCfg configuration) throws ConfigException
  {
    /*
     * Parse fractional configuration
     */

    // Read the configuration entry
    FractionalConfig newFractionalConfig = FractionalConfig.toFractionalConfig(
        configuration);

    if (!newFractionalConfig.isFractional())
    {
        // Nothing to check
        return;
    }

    // Prepare variables to be filled with config
    Map<String, Set<String>> newFractionalSpecificClassesAttributes =
      newFractionalConfig.getFractionalSpecificClassesAttributes();
    Set<String> newFractionalAllClassesAttributes =
      newFractionalConfig.getFractionalAllClassesAttributes();

    /*
     * Check attributes consistency : we only allow to filter MAY (optional)
     * attributes of a class : to be compliant with the schema, no MUST
     * (mandatory) attribute can be filtered by fractional replication.
     */

    // Check consistency of specific classes attributes
    Schema schema = DirectoryServer.getSchema();
    int fractionalMode = newFractionalConfig.fractionalConfigToInt();
    for (String className : newFractionalSpecificClassesAttributes.keySet())
    {
      // Does the class exist ?
      ObjectClass fractionalClass = schema.getObjectClass(
        className.toLowerCase());
      if (fractionalClass == null)
      {
        throw new ConfigException(
          NOTE_ERR_FRACTIONAL_CONFIG_UNKNOWN_OBJECT_CLASS.get(className));
      }

      boolean isExtensibleObjectClass =
          "extensibleObject".equalsIgnoreCase(className);

      Set<String> attributes =
        newFractionalSpecificClassesAttributes.get(className);

      for (String attrName : attributes)
      {
        // Not a prohibited attribute ?
        if (isFractionalProhibitedAttr(attrName))
        {
          throw new ConfigException(
            NOTE_ERR_FRACTIONAL_CONFIG_PROHIBITED_ATTRIBUTE.get(attrName));
        }

        // Does the attribute exist ?
        AttributeType attributeType = schema.getAttributeType(attrName);
        if (attributeType != null)
        {
          // No more checking for the extensibleObject class
          if (!isExtensibleObjectClass
              && fractionalMode == FractionalConfig.EXCLUSIVE_FRACTIONAL
              // Exclusive mode : the attribute must be optional
              && !fractionalClass.isOptional(attributeType))
          {
            throw new ConfigException(
                NOTE_ERR_FRACTIONAL_CONFIG_NOT_OPTIONAL_ATTRIBUTE.get(attrName,
                    className));
          }
        }
        else
        {
          throw new ConfigException(
            NOTE_ERR_FRACTIONAL_CONFIG_UNKNOWN_ATTRIBUTE_TYPE.get(attrName));
        }
      }
    }


    // Check consistency of all classes attributes
    for (String attrName : newFractionalAllClassesAttributes)
    {
      // Not a prohibited attribute ?
      if (isFractionalProhibitedAttr(attrName))
      {
        throw new ConfigException(
          NOTE_ERR_FRACTIONAL_CONFIG_PROHIBITED_ATTRIBUTE.get(attrName));
      }

      // Does the attribute exist ?
      if (schema.getAttributeType(attrName) == null)
      {
        throw new ConfigException(
          NOTE_ERR_FRACTIONAL_CONFIG_UNKNOWN_ATTRIBUTE_TYPE.get(attrName));
      }
    }
  }

  /**
   * Test if the passed attribute is not allowed to be used in configuration of
   * fractional replication.
   * @param attr Attribute to test.
   * @return true if the attribute is prohibited.
   */
  private static boolean isFractionalProhibitedAttr(String attr)
  {
    for (String forbiddenAttr : FRACTIONAL_PROHIBITED_ATTRIBUTES)
    {
      if (forbiddenAttr.equalsIgnoreCase(attr))
      {
        return true;
      }
    }
    return false;
  }

  /**
   * If fractional replication is enabled, this analyzes the operation and
   * suppresses the forbidden attributes in it so that they are not added in
   * the local backend.
   *
   * @param addOperation The operation to modify based on fractional
   * replication configuration
   * @param performFiltering Tells if the effective attribute filtering should
   * be performed or if the call is just to analyze if there are some
   * attributes filtered by fractional configuration
   * @return true if the operation contains some attributes subject to filtering
   * by the fractional configuration
   */
  private boolean fractionalFilterOperation(
    PreOperationAddOperation addOperation, boolean performFiltering)
  {
    return fractionalRemoveAttributesFromEntry(fractionalConfig,
      addOperation.getEntryDN().rdn(), addOperation.getObjectClasses(),
      addOperation.getUserAttributes(), performFiltering);
  }

  /**
   * If fractional replication is enabled, this analyzes the operation and
   * suppresses the forbidden attributes in it so that they are not added in
   * the local backend.
   *
   * @param modifyDNOperation The operation to modify based on fractional
   * replication configuration
   * @param performFiltering Tells if the effective modifications should
   * be performed or if the call is just to analyze if there are some
   * inconsistency with fractional configuration
   * @return true if the operation is inconsistent with fractional
   * configuration
   */
  private boolean fractionalFilterOperation(
    PreOperationModifyDNOperation modifyDNOperation, boolean performFiltering)
  {
    // Quick exit if not called for analyze and
    if (performFiltering && modifyDNOperation.deleteOldRDN())
    {
      // The core will remove any occurrence of attribute that was part of the
      // old RDN, nothing more to do.
      return true; // Will not be used as analyze was not requested
    }

    // Create a list of filtered attributes for this entry
    Entry concernedEntry = modifyDNOperation.getOriginalEntry();
    Set<String> fractionalConcernedAttributes =
      createFractionalConcernedAttrList(fractionalConfig,
      concernedEntry.getObjectClasses().keySet());

    boolean fractionalExclusive = fractionalConfig.isFractionalExclusive();
    if (fractionalExclusive && fractionalConcernedAttributes.isEmpty())
      // No attributes to filter
      return false;

    /*
     * Analyze the old and new rdn to see if they are some attributes to be
     * removed: if the oldRDN contains some forbidden attributes (for instance
     * it is possible if the entry was created with an add operation and the
     * RDN used contains a forbidden attribute: in this case the attribute value
     * has been kept to be consistent with the dn of the entry.) that are no
     * more part of the new RDN, we must remove any attribute of this type by
     * putting a modification to delete the attribute.
     */

    boolean inconsistentOperation = false;
    RDN rdn = modifyDNOperation.getEntryDN().rdn();
    RDN newRdn = modifyDNOperation.getNewRDN();

    // Go through each attribute of the old RDN
    for (int i=0 ; i<rdn.getNumValues() ; i++)
    {
      AttributeType attributeType = rdn.getAttributeType(i);
      boolean found = false;
      // Is it present in the fractional attributes established list ?
      for (String attrTypeStr : fractionalConcernedAttributes)
      {
        AttributeType attributeTypeFromList =
        DirectoryServer.getAttributeType(attrTypeStr);
        if (attributeTypeFromList.equals(attributeType))
        {
          found = true;
          break;
        }
      }
      boolean attributeToBeFiltered = (fractionalExclusive && found)
          || (!fractionalExclusive && !found);
      if (attributeToBeFiltered
          && !newRdn.hasAttributeType(attributeType)
          && !modifyDNOperation.deleteOldRDN())
      {
        /*
         * A forbidden attribute is in the old RDN and no more in the new RDN,
         * and it has not been requested to remove attributes from old RDN:
         * let's remove the attribute from the entry to stay consistent with
         * fractional configuration
         */
        Modification modification = new Modification(ModificationType.DELETE,
          Attributes.empty(attributeType));
        modifyDNOperation.addModification(modification);
        inconsistentOperation = true;
      }
    }

    return inconsistentOperation;
  }

  /**
   * Remove attributes from an entry, according to the passed fractional
   * configuration. The entry is represented by the 2 passed parameters.
   * The attributes to be removed are removed using the remove method on the
   * passed iterator for the attributes in the entry.
   * @param fractionalConfig The fractional configuration to use
   * @param entryRdn The rdn of the entry to add
   * @param classes The object classes representing the entry to modify
   * @param attributesMap The map of attributes/values to be potentially removed
   * from the entry.
   * @param performFiltering Tells if the effective attribute filtering should
   * be performed or if the call is just an analyze to see if there are some
   * attributes filtered by fractional configuration
   * @return true if the operation contains some attributes subject to filtering
   * by the fractional configuration
   */
   static boolean fractionalRemoveAttributesFromEntry(
    FractionalConfig fractionalConfig, RDN entryRdn,
    Map<ObjectClass,String> classes, Map<AttributeType,
    List<Attribute>> attributesMap, boolean performFiltering)
  {
    boolean hasSomeAttributesToFilter = false;
    /*
     * Prepare a list of attributes to be included/excluded according to the
     * fractional replication configuration
     */

    Set<String> fractionalConcernedAttributes =
      createFractionalConcernedAttrList(fractionalConfig, classes.keySet());
    boolean fractionalExclusive = fractionalConfig.isFractionalExclusive();
    if (fractionalExclusive && fractionalConcernedAttributes.isEmpty())
      return false; // No attributes to filter

    // Prepare list of object classes of the added entry
    Set<ObjectClass> entryClasses = classes.keySet();

    /*
     * Go through the user attributes and remove those that match filtered one
     * - exclude mode : remove only attributes that are in
     * fractionalConcernedAttributes
     * - include mode : remove any attribute that is not in
     * fractionalConcernedAttributes
     */
    Iterator<AttributeType> attributeTypes = attributesMap.keySet().iterator();
    List<List<Attribute>> newRdnAttrLists = new ArrayList<List<Attribute>>();
    List<AttributeType> rdnAttrTypes = new ArrayList<AttributeType>();
    while (attributeTypes.hasNext())
    {
      AttributeType attributeType = attributeTypes.next();

      // Only optional attributes may be removed
      if (isMandatoryAttribute(entryClasses, attributeType)
      // Do not remove an attribute if it is a prohibited one
          || isFractionalProhibited(attributeType)
          || !canRemoveAttribute(attributeType, fractionalExclusive,
              fractionalConcernedAttributes))
      {
        continue;
      }

      if (!performFiltering)
      {
        // The call was just to check : at least one attribute to filter
        // found, return immediately the answer;
        return true;
      }

      // Do not remove an attribute/value that is part of the RDN of the
      // entry as it is forbidden
      if (entryRdn.hasAttributeType(attributeType))
      {
        /*
        We must remove all values of the attributes map for this
        attribute type but the one that has the value which is in the RDN
        of the entry. In fact the (underlying )attribute list does not
        support remove so we have to create a new list, keeping only the
        attribute value which is the same as in the RDN
        */
        AttributeValue rdnAttributeValue =
          entryRdn.getAttributeValue(attributeType);
        List<Attribute> attrList = attributesMap.get(attributeType);
        AttributeValue sameAttrValue = null;
        // Locate the attribute value identical to the one in the RDN
        for (Attribute attr : attrList)
        {
          if (attr.contains(rdnAttributeValue))
          {
            for (AttributeValue attrValue : attr) {
              if (rdnAttributeValue.equals(attrValue)) {
                // Keep the value we want
                sameAttrValue = attrValue;
              } else {
                hasSomeAttributesToFilter = true;
              }
            }
          }
          else
          {
            hasSomeAttributesToFilter = true;
          }
        }
        //    Recreate the attribute list with only the RDN attribute value
        if (sameAttrValue != null)
          // Paranoia check: should never be the case as we should always
          // find the attribute/value pair matching the pair in the RDN
        {
          // Construct and store new attribute list
          AttributeBuilder attrBuilder = new AttributeBuilder(attributeType);
          attrBuilder.add(sameAttrValue);
          List<Attribute> newRdnAttrList = new ArrayList<Attribute>();
          newRdnAttrList.add(attrBuilder.toAttribute());
          newRdnAttrLists.add(newRdnAttrList);
          /*
          Store matching attribute type
          The mapping will be done using object from rdnAttrTypes as key
          and object from newRdnAttrLists (at same index) as value in
          the user attribute map to be modified
          */
          rdnAttrTypes.add(attributeType);
        }
      }
      else
      {
        // Found an attribute to remove, remove it from the list.
        attributeTypes.remove();
        hasSomeAttributesToFilter = true;
      }
    }
    // Now overwrite the attribute values for the attribute types present in the
    // RDN, if there are some filtered attributes in the RDN
    for (int index = 0 ; index < rdnAttrTypes.size() ; index++)
    {
      attributesMap.put(rdnAttrTypes.get(index), newRdnAttrLists.get(index));
    }
    return hasSomeAttributesToFilter;
  }

   private static boolean isMandatoryAttribute(Set<ObjectClass> entryClasses,
       AttributeType attributeType)
   {
     for (ObjectClass objectClass : entryClasses)
     {
       if (objectClass.isRequired(attributeType))
       {
         return true;
       }
     }
     return false;
   }

   private static boolean isFractionalProhibited(AttributeType attrType)
   {
     String attributeName = attrType.getPrimaryName();
     return (attributeName != null && isFractionalProhibitedAttr(attributeName))
         || isFractionalProhibitedAttr(attrType.getOID());
   }

  private static boolean canRemoveAttribute(AttributeType attributeType,
      boolean fractionalExclusive, Set<String> fractionalConcernedAttributes)
  {
    String attributeName = attributeType.getPrimaryName();
    String attributeOid = attributeType.getOID();

    // Is the current attribute part of the established list ?
    boolean foundAttribute =
        contains(fractionalConcernedAttributes, attributeName, attributeOid);
    // Now remove the attribute or modification if:
    // - exclusive mode and attribute is in configuration
    // - inclusive mode and attribute is not in configuration
    return (foundAttribute && fractionalExclusive)
        || (!foundAttribute && !fractionalExclusive);
  }

  private static boolean contains(Set<String> fractionalConcernedAttributes,
      String attributeName, String attributeOid)
  {
    final boolean foundAttribute =
        attributeName != null
            && fractionalConcernedAttributes.contains(attributeName
                .toLowerCase());
    return foundAttribute
        || fractionalConcernedAttributes.contains(attributeOid);
  }

  /**
   * Prepares a list of attributes of interest for the fractional feature.
   * @param fractionalConfig The fractional configuration to use
   * @param entryObjectClasses The object classes of an entry on which an
   * operation is going to be performed.
   * @return The list of attributes of the entry to be excluded/included
   * when the operation will be performed.
   */
  private static Set<String> createFractionalConcernedAttrList(
    FractionalConfig fractionalConfig, Set<ObjectClass> entryObjectClasses)
  {
    /*
     * Is the concerned entry of a type concerned by fractional replication
     * configuration ? If yes, add the matching attribute names to a set of
     * attributes to take into account for filtering
     * (inclusive or exclusive mode).
     * Using a Set to avoid duplicate attributes (from 2 inheriting classes for
     * instance)
     */
    Set<String> fractionalConcernedAttributes = new HashSet<String>();

    // Get object classes the entry matches
    Set<String> fractionalAllClassesAttributes =
      fractionalConfig.getFractionalAllClassesAttributes();
    Map<String, Set<String>> fractionalSpecificClassesAttributes =
      fractionalConfig.getFractionalSpecificClassesAttributes();

    Set<String> fractionalClasses =
        fractionalSpecificClassesAttributes.keySet();
    for (ObjectClass entryObjectClass : entryObjectClasses)
    {
      for(String fractionalClass : fractionalClasses)
      {
        if (entryObjectClass.hasNameOrOID(fractionalClass.toLowerCase()))
        {
          fractionalConcernedAttributes.addAll(
              fractionalSpecificClassesAttributes.get(fractionalClass));
        }
      }
    }

    // Add to the set any attribute which is class independent
    fractionalConcernedAttributes.addAll(fractionalAllClassesAttributes);

    return fractionalConcernedAttributes;
  }

  /**
   * If fractional replication is enabled, this analyzes the operation and
   * suppresses the forbidden attributes in it so that they are not added/
   * deleted/modified in the local backend.
   *
   * @param modifyOperation The operation to modify based on fractional
   * replication configuration
   * @param performFiltering Tells if the effective attribute filtering should
   * be performed or if the call is just to analyze if there are some
   * attributes filtered by fractional configuration
   * @return FRACTIONAL_HAS_FRACTIONAL_FILTERED_ATTRIBUTES,
   * FRACTIONAL_HAS_NO_FRACTIONAL_FILTERED_ATTRIBUTES or FRACTIONAL_BECOME_NO_OP
   */
  private int fractionalFilterOperation(PreOperationModifyOperation
    modifyOperation, boolean performFiltering)
  {
    /*
     * Prepare a list of attributes to be included/excluded according to the
     * fractional replication configuration
     */

    Entry modifiedEntry = modifyOperation.getCurrentEntry();
    Set<String> fractionalConcernedAttributes =
      createFractionalConcernedAttrList(fractionalConfig,
      modifiedEntry.getObjectClasses().keySet());
    boolean fractionalExclusive = fractionalConfig.isFractionalExclusive();
    if (fractionalExclusive && fractionalConcernedAttributes.isEmpty())
      // No attributes to filter
      return FRACTIONAL_HAS_NO_FRACTIONAL_FILTERED_ATTRIBUTES;

    // Prepare list of object classes of the modified entry
    DN entryToModifyDn = modifyOperation.getEntryDN();
    Entry entryToModify;
    try
    {
      entryToModify = DirectoryServer.getEntry(entryToModifyDn);
    }
    catch(DirectoryException e)
    {
      logError(NOTE_ERR_FRACTIONAL.get(getBaseDNString(),
          stackTraceToSingleLineString(e)));
      return FRACTIONAL_HAS_NO_FRACTIONAL_FILTERED_ATTRIBUTES;
    }
    Set<ObjectClass> entryClasses = entryToModify.getObjectClasses().keySet();

    /*
     * Now go through the attribute modifications and filter the mods according
     * to the fractional configuration (using the just established concerned
     * attributes list):
     * - delete attributes: remove them if regarding a filtered attribute
     * - add attributes: remove them if regarding a filtered attribute
     * - modify attributes: remove them if regarding a filtered attribute
     */

    int result = FRACTIONAL_HAS_NO_FRACTIONAL_FILTERED_ATTRIBUTES;
    List<Modification> mods = modifyOperation.getModifications();
    Iterator<Modification> modsIt = mods.iterator();
    while (modsIt.hasNext())
    {
      Modification mod = modsIt.next();
      Attribute attr = mod.getAttribute();
      AttributeType attrType = attr.getAttributeType();
      // Fractional replication ignores operational attributes
      if (attrType.isOperational()
          || isMandatoryAttribute(entryClasses, attrType)
          || isFractionalProhibited(attrType)
          || !canRemoveAttribute(attrType, fractionalExclusive,
              fractionalConcernedAttributes))
      {
        continue;
      }

      if (!performFiltering)
      {
        // The call was just to check : at least one attribute to filter
        // found, return immediately the answer;
        return FRACTIONAL_HAS_FRACTIONAL_FILTERED_ATTRIBUTES;
      }

      // Found a modification to remove, remove it from the list.
      modsIt.remove();
      result = FRACTIONAL_HAS_FRACTIONAL_FILTERED_ATTRIBUTES;
      if (mods.isEmpty())
      {
        // This operation must become a no-op as no more modification in it
        return FRACTIONAL_BECOME_NO_OP;
      }
    }

    return result;
  }

  /**
   * This is overwritten to allow stopping the (online) import process by the
   * fractional ldif import plugin when it detects that the (imported) remote
   * data set is not consistent with the local fractional configuration.
   * {@inheritDoc}
   */
  @Override
  protected byte[] receiveEntryBytes()
  {
    if (isFollowImport())
    {
      // Ok, next entry is allowed to be received
      return super.receiveEntryBytes();
    }

    // Fractional ldif import plugin detected inconsistency between local and
    // remote server fractional configuration and is stopping the import
    // process:
    // This is an error termination during the import
    // The error is stored and the import is ended by returning null
    final IEContext ieCtx = getImportExportContext();
    Message msg = null;
    switch (importErrorMessageId)
    {
    case IMPORT_ERROR_MESSAGE_BAD_REMOTE:
      msg = NOTE_ERR_FULL_UPDATE_IMPORT_FRACTIONAL_BAD_REMOTE.get(
          getBaseDNString(), Integer.toString(ieCtx.getImportSource()));
      break;
    case IMPORT_ERROR_MESSAGE_REMOTE_IS_FRACTIONAL:
      msg = NOTE_ERR_FULL_UPDATE_IMPORT_FRACTIONAL_REMOTE_IS_FRACTIONAL.get(
          getBaseDNString(), Integer.toString(ieCtx.getImportSource()));
      break;
    }
    ieCtx.setException(new DirectoryException(UNWILLING_TO_PERFORM, msg));
    return null;
  }

  /**
   * This is overwritten to allow stopping the (online) export process if the
   * local domain is fractional and the destination is all other servers:
   * This make no sense to have only fractional servers in a replicated
   * topology. This prevents from administrator manipulation error that would
   * lead to whole topology data corruption.
   * {@inheritDoc}
   */
  @Override
  protected void initializeRemote(int target, int requestorID,
    Task initTask, int initWindow) throws DirectoryException
  {
    if (target == RoutableMsg.ALL_SERVERS && fractionalConfig.isFractional())
    {
      Message msg = NOTE_ERR_FRACTIONAL_FORBIDDEN_FULL_UPDATE_FRACTIONAL.get(
            getBaseDNString(), Integer.toString(getServerId()));
      throw new DirectoryException(ResultCode.UNWILLING_TO_PERFORM, msg);
    }

    // FIXME should the next call use the initWindow parameter rather than the
    // instance variable?
    super.initializeRemote(target, requestorID, initTask, getInitWindow());
  }

  /**
   * Implement the  handleConflictResolution phase of the deleteOperation.
   *
   * @param deleteOperation The deleteOperation.
   * @return A SynchronizationProviderResult indicating if the operation
   *         can continue.
   */
  SynchronizationProviderResult handleConflictResolution(
         PreOperationDeleteOperation deleteOperation)
  {
    if (!deleteOperation.isSynchronizationOperation() && !brokerIsConnected())
    {
      Message msg = ERR_REPLICATION_COULD_NOT_CONNECT.get(getBaseDNString());
      return new SynchronizationProviderResult.StopProcessing(
          ResultCode.UNWILLING_TO_PERFORM, msg);
    }

    DeleteContext ctx =
      (DeleteContext) deleteOperation.getAttachment(SYNCHROCONTEXT);
    Entry deletedEntry = deleteOperation.getEntryToDelete();

    if (ctx != null)
    {
      /*
       * This is a replication operation
       * Check that the modified entry has the same entryuuid
       * as it was in the original message.
       */
      String operationEntryUUID = ctx.getEntryUUID();
      String modifiedEntryUUID = EntryHistorical.getEntryUUID(deletedEntry);
      if (!operationEntryUUID.equals(modifiedEntryUUID))
      {
        /*
         * The changes entry is not the same entry as the one on
         * the original change was performed.
         * Probably the original entry was renamed and replaced with
         * another entry.
         * We must not let the change proceed, return a negative
         * result and set the result code to NO_SUCH_OBJECT.
         * When the operation will return, the thread that started the
         * operation will try to find the correct entry and restart a new
         * operation.
         */
        return new SynchronizationProviderResult.StopProcessing(
            ResultCode.NO_SUCH_OBJECT, null);
      }
    }
    else
    {
      // There is no replication context attached to the operation
      // so this is not a replication operation.
      CSN csn = generateCSN(deleteOperation);
      String modifiedEntryUUID = EntryHistorical.getEntryUUID(deletedEntry);
      ctx = new DeleteContext(csn, modifiedEntryUUID);
      deleteOperation.setAttachment(SYNCHROCONTEXT, ctx);

      synchronized (replayOperations)
      {
        int size = replayOperations.size();
        if (size >= 10000)
        {
          replayOperations.remove(replayOperations.firstKey());
        }
        FakeOperation op = new FakeDelOperation(
            deleteOperation.getEntryDN(), csn, modifiedEntryUUID);
        replayOperations.put(csn, op);
      }
    }

    return new SynchronizationProviderResult.ContinueProcessing();
  }

  /**
   * Implement the  handleConflictResolution phase of the addOperation.
   *
   * @param addOperation The AddOperation.
   * @return A SynchronizationProviderResult indicating if the operation
   *         can continue.
   */
  SynchronizationProviderResult handleConflictResolution(
      PreOperationAddOperation addOperation)
  {
    if (!addOperation.isSynchronizationOperation() && !brokerIsConnected())
    {
      Message msg = ERR_REPLICATION_COULD_NOT_CONNECT.get(getBaseDNString());
      return new SynchronizationProviderResult.StopProcessing(
          ResultCode.UNWILLING_TO_PERFORM, msg);
    }

    if (fractionalConfig.isFractional())
    {
      if (addOperation.isSynchronizationOperation())
      {
        /*
         * Filter attributes here for fractional replication. If fractional
         * replication is enabled, we analyze the operation to suppress the
         * forbidden attributes in it so that they are not added in the local
         * backend. This must be called before any other plugin is called, to
         * keep coherency across plugin calls.
         */
        fractionalFilterOperation(addOperation, true);
      }
      else
      {
        /*
         * Direct access from an LDAP client : if some attributes are to be
         * removed according to the fractional configuration, simply forbid
         * the operation
         */
        if (fractionalFilterOperation(addOperation, false))
        {
          Message msg = NOTE_ERR_FRACTIONAL_FORBIDDEN_OPERATION.get(
            getBaseDNString(), addOperation.toString());
          return new SynchronizationProviderResult.StopProcessing(
            ResultCode.UNWILLING_TO_PERFORM, msg);
        }
      }
    }

    if (addOperation.isSynchronizationOperation())
    {
      AddContext ctx = (AddContext) addOperation.getAttachment(SYNCHROCONTEXT);
      /*
       * If an entry with the same entry uniqueID already exist then
       * this operation has already been replayed in the past.
       */
      String uuid = ctx.getEntryUUID();
      if (findEntryDN(uuid) != null)
      {
        return new SynchronizationProviderResult.StopProcessing(
            ResultCode.NO_OPERATION, null);
      }

      /* The parent entry may have been renamed here since the change was done
       * on the first server, and another entry have taken the former dn
       * of the parent entry
       */

      String parentEntryUUID = ctx.getParentEntryUUID();
      // root entry have no parent, there is no need to check for it.
      if (parentEntryUUID != null)
      {
        // There is a potential of perfs improvement here
        // if we could avoid the following parent entry retrieval
        DN parentDnFromCtx = findEntryDN(ctx.getParentEntryUUID());
        if (parentDnFromCtx == null)
        {
          // The parent does not exist with the specified unique id
          // stop the operation with NO_SUCH_OBJECT and let the
          // conflict resolution or the dependency resolution solve this.
          return new SynchronizationProviderResult.StopProcessing(
              ResultCode.NO_SUCH_OBJECT, null);
        }

        DN entryDN = addOperation.getEntryDN();
        DN parentDnFromEntryDn = entryDN.getParentDNInSuffix();
        if (parentDnFromEntryDn != null
            && !parentDnFromCtx.equals(parentDnFromEntryDn))
        {
          // parentEntry has been renamed
          // replication name conflict resolution is expected to fix that
          // later in the flow
          return new SynchronizationProviderResult.StopProcessing(
              ResultCode.NO_SUCH_OBJECT, null);
        }
      }
    }
    return new SynchronizationProviderResult.ContinueProcessing();
  }

  /**
   * Check that the broker associated to this ReplicationDomain has found
   * a Replication Server and that this LDAP server is therefore able to
   * process operations.
   * If not, set the ResultCode, the response message,
   * interrupt the operation, and return false
   *
   * @return  true when it OK to process the Operation, false otherwise.
   *          When false is returned the resultCode and the response message
   *          is also set in the Operation.
   */
  private boolean brokerIsConnected()
  {
    final IsolationPolicy isolationPolicy = config.getIsolationPolicy();
    if (isolationPolicy.equals(IsolationPolicy.ACCEPT_ALL_UPDATES))
    {
      // this policy imply that we always accept updates.
      return true;
    }
    if (isolationPolicy.equals(IsolationPolicy.REJECT_ALL_UPDATES))
    {
      // this isolation policy specifies that the updates are denied
      // when the broker had problems during the connection phase
      // Updates are still accepted if the broker is currently connecting..
      return !hasConnectionError();
    }
    // we should never get there as the only possible policies are
    // ACCEPT_ALL_UPDATES and REJECT_ALL_UPDATES
    return true;
  }


  /**
   * Implement the  handleConflictResolution phase of the ModifyDNOperation.
   *
   * @param modifyDNOperation The ModifyDNOperation.
   * @return A SynchronizationProviderResult indicating if the operation
   *         can continue.
   */
  SynchronizationProviderResult handleConflictResolution(
      PreOperationModifyDNOperation modifyDNOperation)
  {
    if (!modifyDNOperation.isSynchronizationOperation() && !brokerIsConnected())
    {
      Message msg = ERR_REPLICATION_COULD_NOT_CONNECT.get(getBaseDNString());
      return new SynchronizationProviderResult.StopProcessing(
          ResultCode.UNWILLING_TO_PERFORM, msg);
    }

    if (fractionalConfig.isFractional())
    {
      if (modifyDNOperation.isSynchronizationOperation())
      {
        /*
         * Filter operation here for fractional replication. If fractional
         * replication is enabled, we analyze the operation and modify it if
         * necessary to stay consistent with what is defined in fractional
         * configuration.
         */
        fractionalFilterOperation(modifyDNOperation, true);
      }
      else
      {
        /*
         * Direct access from an LDAP client : something is inconsistent with
         * the fractional configuration, forbid the operation.
         */
        if (fractionalFilterOperation(modifyDNOperation, false))
        {
          Message msg = NOTE_ERR_FRACTIONAL_FORBIDDEN_OPERATION.get(
            getBaseDNString(), modifyDNOperation.toString());
          return new SynchronizationProviderResult.StopProcessing(
            ResultCode.UNWILLING_TO_PERFORM, msg);
        }
      }
    }

    ModifyDnContext ctx =
      (ModifyDnContext) modifyDNOperation.getAttachment(SYNCHROCONTEXT);
    if (ctx != null)
    {
      /*
       * This is a replication operation
       * Check that the modified entry has the same entryuuid
       * as was in the original message.
       */
      String modifiedEntryUUID =
        EntryHistorical.getEntryUUID(modifyDNOperation.getOriginalEntry());
      if (!modifiedEntryUUID.equals(ctx.getEntryUUID()))
      {
        /*
         * The modified entry is not the same entry as the one on
         * the original change was performed.
         * Probably the original entry was renamed and replaced with
         * another entry.
         * We must not let the change proceed, return a negative
         * result and set the result code to NO_SUCH_OBJECT.
         * When the operation will return, the thread that started the operation
         * will try to find the correct entry and restart a new operation.
         */
        return new SynchronizationProviderResult.StopProcessing(
            ResultCode.NO_SUCH_OBJECT, null);
      }

      if (modifyDNOperation.getNewSuperior() != null)
      {
        /*
         * Also check that the current id of the
         * parent is the same as when the operation was performed.
         */
        String newParentId = findEntryUUID(modifyDNOperation.getNewSuperior());
        if (newParentId != null && ctx.getNewSuperiorEntryUUID() != null
            && !newParentId.equals(ctx.getNewSuperiorEntryUUID()))
        {
        return new SynchronizationProviderResult.StopProcessing(
            ResultCode.NO_SUCH_OBJECT, null);
        }
      }

      /*
       * If the object has been renamed more recently than this
       * operation, cancel the operation.
       */
      EntryHistorical hist = EntryHistorical.newInstanceFromEntry(
          modifyDNOperation.getOriginalEntry());
      if (hist.addedOrRenamedAfter(ctx.getCSN()))
      {
        return new SynchronizationProviderResult.StopProcessing(
            ResultCode.NO_OPERATION, null);
      }
    }
    else
    {
      // There is no replication context attached to the operation
      // so this is not a replication operation.
      CSN csn = generateCSN(modifyDNOperation);
      String newParentId = null;
      if (modifyDNOperation.getNewSuperior() != null)
      {
        newParentId = findEntryUUID(modifyDNOperation.getNewSuperior());
      }

      Entry modifiedEntry = modifyDNOperation.getOriginalEntry();
      String modifiedEntryUUID = EntryHistorical.getEntryUUID(modifiedEntry);
      ctx = new ModifyDnContext(csn, modifiedEntryUUID, newParentId);
      modifyDNOperation.setAttachment(SYNCHROCONTEXT, ctx);
    }
    return new SynchronizationProviderResult.ContinueProcessing();
  }

  /**
   * Handle the conflict resolution.
   * Called by the core server after locking the entry and before
   * starting the actual modification.
   * @param modifyOperation the operation
   * @return code indicating is operation must proceed
   */
  SynchronizationProviderResult handleConflictResolution(
         PreOperationModifyOperation modifyOperation)
  {
    if (!modifyOperation.isSynchronizationOperation() && !brokerIsConnected())
    {
      Message msg = ERR_REPLICATION_COULD_NOT_CONNECT.get(getBaseDNString());
      return new SynchronizationProviderResult.StopProcessing(
          ResultCode.UNWILLING_TO_PERFORM, msg);
    }

    if (fractionalConfig.isFractional())
    {
      if  (modifyOperation.isSynchronizationOperation())
      {
        /*
         * Filter attributes here for fractional replication. If fractional
         * replication is enabled, we analyze the operation and modify it so
         * that no forbidden attribute is added/modified/deleted in the local
         * backend. This must be called before any other plugin is called, to
         * keep coherency across plugin calls.
         */
        if (fractionalFilterOperation(modifyOperation, true) ==
          FRACTIONAL_BECOME_NO_OP)
        {
          // Every modifications filtered in this operation: the operation
          // becomes a no-op
          return new SynchronizationProviderResult.StopProcessing(
            ResultCode.NO_OPERATION, null);
        }
      }
      else
      {
        /*
         * Direct access from an LDAP client : if some attributes are to be
         * removed according to the fractional configuration, simply forbid
         * the operation
         */
        switch(fractionalFilterOperation(modifyOperation, false))
        {
          case FRACTIONAL_HAS_NO_FRACTIONAL_FILTERED_ATTRIBUTES:
            // Ok, let the operation happen
            break;
          case FRACTIONAL_HAS_FRACTIONAL_FILTERED_ATTRIBUTES:
            // Some attributes not compliant with fractional configuration :
            // forbid the operation
            Message msg = NOTE_ERR_FRACTIONAL_FORBIDDEN_OPERATION.get(
              getBaseDNString(), modifyOperation.toString());
            return new SynchronizationProviderResult.StopProcessing(
              ResultCode.UNWILLING_TO_PERFORM, msg);
        }
      }
    }

    ModifyContext ctx =
      (ModifyContext) modifyOperation.getAttachment(SYNCHROCONTEXT);

    Entry modifiedEntry = modifyOperation.getModifiedEntry();
    if (ctx == null)
    {
      // No replication ctx attached => not a replicated operation
      // - create a ctx with : CSN, entryUUID
      // - attach the context to the op

      CSN csn = generateCSN(modifyOperation);
      String modifiedEntryUUID = EntryHistorical.getEntryUUID(modifiedEntry);
      ctx = new ModifyContext(csn, modifiedEntryUUID);

      modifyOperation.setAttachment(SYNCHROCONTEXT, ctx);
    }
    else
    {
      // Replication ctx attached => this is a replicated operation being
      // replayed here, it is necessary to
      // - check if the entry has been renamed
      // - check for conflicts
      String modifiedEntryUUID = ctx.getEntryUUID();
      String currentEntryUUID = EntryHistorical.getEntryUUID(modifiedEntry);
      if (currentEntryUUID != null
          && !currentEntryUUID.equals(modifiedEntryUUID))
      {
        /*
         * The current modified entry is not the same entry as the one on
         * the original modification was performed.
         * Probably the original entry was renamed and replaced with
         * another entry.
         * We must not let the modification proceed, return a negative
         * result and set the result code to NO_SUCH_OBJECT.
         * When the operation will return, the thread that started the
         * operation will try to find the correct entry and restart a new
         * operation.
         */
         return new SynchronizationProviderResult.StopProcessing(
              ResultCode.NO_SUCH_OBJECT, null);
      }

      // Solve the conflicts between modify operations
      EntryHistorical historicalInformation =
        EntryHistorical.newInstanceFromEntry(modifiedEntry);
      modifyOperation.setAttachment(EntryHistorical.HISTORICAL,
                                    historicalInformation);

      if (historicalInformation.replayOperation(modifyOperation, modifiedEntry))
      {
        numResolvedModifyConflicts.incrementAndGet();
      }
    }
    return new SynchronizationProviderResult.ContinueProcessing();
  }

  /**
   * The preOperation phase for the add Operation.
   * Its job is to generate the replication context associated to the
   * operation. It is necessary to do it in this phase because contrary to
   * the other operations, the entry UUID is not set when the handleConflict
   * phase is called.
   *
   * @param addOperation The Add Operation.
   */
  public void doPreOperation(PreOperationAddOperation addOperation)
  {
    AddContext ctx = new AddContext(generateCSN(addOperation),
        EntryHistorical.getEntryUUID(addOperation),
        findEntryUUID(addOperation.getEntryDN().getParentDNInSuffix()));

    addOperation.setAttachment(SYNCHROCONTEXT, ctx);
  }

  /**
   * Check if an operation must be synchronized.
   * Also update the list of pending changes and the server RUV
   * @param op the operation
   */
  public void synchronize(PostOperationOperation op)
  {
    ResultCode result = op.getResultCode();
    // Note that a failed non-replication operation might not have a change
    // number.
    CSN curCSN = OperationContext.getCSN(op);
    if (curCSN != null && config.isLogChangenumber())
    {
      op.addAdditionalLogItem(AdditionalLogItem.unquotedKeyValue(getClass(),
          "replicationCSN", curCSN));
    }

    if (result == ResultCode.SUCCESS)
    {
      if (op.isSynchronizationOperation())
      { // Replaying a sync operation
        numReplayedPostOpCalled++;
        try
        {
          remotePendingChanges.commit(curCSN);
        }
        catch  (NoSuchElementException e)
        {
          logError(ERR_OPERATION_NOT_FOUND_IN_PENDING.get(
              op.toString(), curCSN.toString()));
          return;
        }
      }
      else
      {
        // Generate a replication message for a successful non-replication
        // operation.
        LDAPUpdateMsg msg = LDAPUpdateMsg.generateMsg(op);

        if (msg == null)
        {
          /*
          * This is an operation type that we do not know about
          * It should never happen.
          */
          pendingChanges.remove(curCSN);
          logError(ERR_UNKNOWN_TYPE.get(op.getOperationType().toString()));
          return;
        }

        addEntryAttributesForCL(msg,op);

        // If assured replication is configured, this will prepare blocking
        // mechanism. If assured replication is disabled, this returns
        // immediately
        prepareWaitForAckIfAssuredEnabled(msg);
        try
        {
          msg.encode();
          pendingChanges.commitAndPushCommittedChanges(curCSN, msg);
        } catch (UnsupportedEncodingException e)
        {
          // will be caught at publish time.
        }
        catch (NoSuchElementException e)
        {
          logError(ERR_OPERATION_NOT_FOUND_IN_PENDING.get(
              op.toString(), curCSN.toString()));
          return;
        }
        // If assured replication is enabled, this will wait for the matching
        // ack or time out. If assured replication is disabled, this returns
        // immediately
        try
        {
          waitForAckIfAssuredEnabled(msg);
        } catch (TimeoutException ex)
        {
          // This exception may only be raised if assured replication is enabled
          logError(NOTE_DS_ACK_TIMEOUT.get(getBaseDNString(),
              Long.toString(getAssuredTimeout()), msg.toString()));
        }
      }

      // If the operation is a DELETE on the base entry of the suffix
      // that is replicated, the generation is now lost because the
      // DB is empty. We need to save it again the next time we add an entry.
      if (op.getOperationType().equals(OperationType.DELETE)
          && ((PostOperationDeleteOperation) op)
                .getEntryDN().equals(getBaseDN()))
      {
        generationIdSavedStatus = false;
      }

      if (!generationIdSavedStatus)
      {
        saveGenerationId(generationId);
      }
    }
    else if (!op.isSynchronizationOperation() && curCSN != null)
    {
      // Remove an unsuccessful non-replication operation from the pending
      // changes list.
      pendingChanges.remove(curCSN);
      pendingChanges.pushCommittedChanges();
    }

    checkForClearedConflict(op);
  }

  /**
   * Check if the operation that just happened has cleared a conflict :
   * Clearing a conflict happens if the operation has free a DN that
   * for which an other entry was in conflict.
   * Steps:
   * - get the DN freed by a DELETE or MODRDN op
   * - search for entries put in the conflict space (dn=entryUUID'+'....)
   *   because the expected DN was not available (ds-sync-conflict=expected DN)
   * - retain the entry with the oldest conflict
   * - rename this entry with the freedDN as it was expected originally
   */
   private void checkForClearedConflict(PostOperationOperation op)
   {
     OperationType type = op.getOperationType();
     if (op.getResultCode() != ResultCode.SUCCESS)
     {
       // those operations cannot have cleared a conflict
       return;
     }

     DN freedDN;
     if (type == OperationType.DELETE)
     {
       freedDN = ((PostOperationDeleteOperation) op).getEntryDN();
     }
     else if (type == OperationType.MODIFY_DN)
     {
       freedDN = ((PostOperationModifyDNOperation) op).getEntryDN();
     }
     else
     {
       return;
     }

    LDAPFilter filter = LDAPFilter.createEqualityFilter(DS_SYNC_CONFLICT,
        ByteString.valueOf(freedDN.toString()));

     InternalSearchOperation searchOp =  conn.processSearch(
       ByteString.valueOf(getBaseDNString()),
       SearchScope.WHOLE_SUBTREE,
       DereferencePolicy.NEVER_DEREF_ALIASES,
       0, 0, false, filter,
       USER_AND_REPL_OPERATIONAL_ATTRS, null);

     Entry entryToRename = null;
     CSN entryToRenameCSN = null;
     for (SearchResultEntry entry : searchOp.getSearchEntries())
     {
       EntryHistorical history = EntryHistorical.newInstanceFromEntry(entry);
       if (entryToRename == null)
       {
         entryToRename = entry;
         entryToRenameCSN = history.getDNDate();
       }
       else if (!history.addedOrRenamedAfter(entryToRenameCSN))
       {
         // this conflict is older than the previous, keep it.
         entryToRename = entry;
         entryToRenameCSN = history.getDNDate();
       }
     }

     if (entryToRename != null)
     {
       DN entryDN = entryToRename.getName();
       ModifyDNOperation newOp = renameEntry(
           entryDN, freedDN.rdn(), freedDN.parent(), false);

       ResultCode res = newOp.getResultCode();
       if (res != ResultCode.SUCCESS)
       {
        logError(ERR_COULD_NOT_SOLVE_CONFLICT.get(
            entryDN.toString(), res.toString()));
       }
     }
   }

  /**
   * Rename an Entry Using a synchronization, non-replicated operation.
   * This method should be used instead of the InternalConnection methods
   * when the operation that need to be run must be local only and therefore
   * not replicated to the RS.
   *
   * @param targetDN     The DN of the entry to rename.
   * @param newRDN       The new RDN to be used.
   * @param parentDN     The parentDN to be used.
   * @param markConflict A boolean indicating is this entry should be marked
   *                     as a conflicting entry. In such case the
   *                     DS_SYNC_CONFLICT attribute will be added to the entry
   *                     with the value of its original DN.
   *                     If false, the DS_SYNC_CONFLICT attribute will be
   *                     cleared.
   *
   * @return The operation that was run to rename the entry.
   */
  private ModifyDNOperation renameEntry(DN targetDN, RDN newRDN, DN parentDN,
      boolean markConflict)
  {
    ModifyDNOperation newOp = new ModifyDNOperationBasis(
        conn, InternalClientConnection.nextOperationID(),
        InternalClientConnection.nextMessageID(), new ArrayList<Control>(0),
        targetDN, newRDN, false, parentDN);

    AttributeType attrType =
        DirectoryServer.getAttributeType(DS_SYNC_CONFLICT, true);
    if (markConflict)
    {
      Attribute attr =
          Attributes.create(attrType, targetDN.toNormalizedString());
      newOp.addModification(new Modification(ModificationType.REPLACE, attr));
    }
    else
    {
      Attribute attr = Attributes.empty(attrType);
      newOp.addModification(new Modification(ModificationType.DELETE, attr));
    }

    runAsSynchronizedOperation(newOp);
    return newOp;
  }

  private void runAsSynchronizedOperation(Operation op)
  {
    op.setInternalOperation(true);
    op.setSynchronizationOperation(true);
    op.setDontSynchronize(true);
    op.run();
  }

  /**
   * Get the number of updates in the pending list.
   *
   * @return The number of updates in the pending list
   */
  private int getPendingUpdatesCount()
  {
    if (pendingChanges != null)
      return pendingChanges.size();
    return 0;
  }

  /**
   * get the number of updates replayed successfully by the replication.
   *
   * @return The number of updates replayed successfully
   */
  private int getNumReplayedPostOpCalled()
  {
    return numReplayedPostOpCalled;
  }


  /**
   * Delete this ReplicationDomain.
   */
  public void delete()
  {
    shutdown();
    removeECLDomainCfg();
  }

  /**
   * Shutdown this ReplicationDomain.
   */
  public void shutdown()
  {
    if (!shutdown)
    {
      shutdown = true;
      final RSUpdater rsUpdater = this.rsUpdater.get();
      if (rsUpdater != null)
      {
        rsUpdater.initiateShutdown();
      }

      // stop the thread in charge of flushing the ServerState.
      if (flushThread != null)
      {
        flushThread.initiateShutdown();
        synchronized (flushThread)
        {
          flushThread.notify();
        }
      }

      DirectoryServer.deregisterAlertGenerator(this);

      // stop the ReplicationDomain
      disableService();
    }

    // wait for completion of the persistentServerState thread.
    try
    {
      while (!done)
      {
        Thread.sleep(50);
      }
    } catch (InterruptedException e)
    {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Create and replay a synchronized Operation from an UpdateMsg.
   *
   * @param msg
   *          The UpdateMsg to be replayed.
   * @param shutdown
   *          whether the server initiated shutdown
   */
  public void replay(LDAPUpdateMsg msg, AtomicBoolean shutdown)
  {
    // Try replay the operation, then flush (replaying) any pending operation
    // whose dependency has been replayed until no more left.
    do
    {
      Operation op = null; // the last operation on which replay was attempted
      boolean dependency = false;
      String replayErrorMsg = null;
      CSN csn = null;
      try
      {
        // The next operation for which to attempt replay.
        // This local variable allow to keep error messages in the "op" local
        // variable until the next loop iteration starts.
        // "op" is already initialized to the next Operation because of the
        // error handling paths.
        Operation nextOp = op = msg.createOperation(conn);
        dependency = remotePendingChanges.checkDependencies(op, msg);

        boolean replayDone = false;
        int retryCount = 10;
        while (!dependency && !replayDone && (retryCount-- > 0))
        {
          if (shutdown.get())
          {
            // shutdown initiated, let's leave
            return;
          }
          // Try replay the operation
          op = nextOp;
          op.setInternalOperation(true);
          op.setSynchronizationOperation(true);

          // Always add the ManageDSAIT control so that updates to referrals
          // are processed locally.
          op.addRequestControl(new LDAPControl(OID_MANAGE_DSAIT_CONTROL));

          csn = OperationContext.getCSN(op);
          op.run();

          ResultCode result = op.getResultCode();

          if (result != ResultCode.SUCCESS)
          {
            if (result == ResultCode.NO_OPERATION)
            {
              // Pre-operation conflict resolution detected that the operation
              // was a no-op. For example, an add which has already been
              // replayed, or a modify DN operation on an entry which has been
              // renamed by a more recent modify DN.
              replayDone = true;
            }
            else if (result == ResultCode.BUSY)
            {
              /*
               * We probably could not get a lock (OPENDJ-885). Give the server
               * another chance to process this operation immediately.
               */
              Thread.yield();
              continue;
            }
            else if (result == ResultCode.UNAVAILABLE)
            {
              /*
               * It can happen when a rebuild is performed or the backend is
               * offline (OPENDJ-49). Give the server another chance to process
               * this operation after some time.
               */
              Thread.sleep(50);
              continue;
            }
            else if (op instanceof ModifyOperation)
            {
              ModifyOperation castOp = (ModifyOperation) op;
              dependency = remotePendingChanges.checkDependencies(castOp);
              ModifyMsg modifyMsg = (ModifyMsg) msg;
              replayDone = solveNamingConflict(castOp, modifyMsg);
            }
            else if (op instanceof DeleteOperation)
            {
              DeleteOperation castOp = (DeleteOperation) op;
              dependency = remotePendingChanges.checkDependencies(castOp);
              replayDone = solveNamingConflict(castOp, msg);
            }
            else if (op instanceof AddOperation)
            {
              AddOperation castOp = (AddOperation) op;
              AddMsg addMsg = (AddMsg) msg;
              dependency = remotePendingChanges.checkDependencies(castOp);
              replayDone = solveNamingConflict(castOp, addMsg);
            }
            else if (op instanceof ModifyDNOperation)
            {
              ModifyDNOperation castOp = (ModifyDNOperation) op;
              replayDone = solveNamingConflict(castOp, msg);
            }
            else
            {
              replayDone = true; // unknown type of operation ?!
            }

            if (replayDone)
            {
              // the update became a dummy update and the result
              // of the conflict resolution phase is to do nothing.
              // however we still need to push this change to the serverState
              updateError(csn);
            }
            else
            {
              /*
               * Create a new operation reflecting the new state of the
               * UpdateMsg after conflict resolution modified it.
               *  Note: When msg is a DeleteMsg, the DeleteOperation is properly
               *  created with subtreeDelete request control when needed.
               */
              nextOp = msg.createOperation(conn);
            }
          }
          else
          {
            replayDone = true;
          }
        }

        if (!replayDone && !dependency)
        {
          // Continue with the next change but the servers could now become
          // inconsistent.
          // Let the repair tool know about this.
          Message message = ERR_LOOP_REPLAYING_OPERATION.get(op.toString(),
            op.getErrorMessage().toString());
          logError(message);
          numUnresolvedNamingConflicts.incrementAndGet();
          replayErrorMsg = message.toString();
          updateError(csn);
        }
      } catch (ASN1Exception e)
      {
        replayErrorMsg = logDecodingOperationError(msg, e);
      } catch (LDAPException e)
      {
        replayErrorMsg = logDecodingOperationError(msg, e);
      } catch (DataFormatException e)
      {
        replayErrorMsg = logDecodingOperationError(msg, e);
      } catch (Exception e)
      {
        if (csn != null)
        {
          /*
           * An Exception happened during the replay process.
           * Continue with the next change but the servers will now start
           * to be inconsistent.
           * Let the repair tool know about this.
           */
          Message message = ERR_EXCEPTION_REPLAYING_OPERATION.get(
            stackTraceToSingleLineString(e), op.toString());
          logError(message);
          replayErrorMsg = message.toString();
          updateError(csn);
        } else
        {
          replayErrorMsg = logDecodingOperationError(msg, e);
        }
      } finally
      {
        if (!dependency)
        {
          processUpdateDone(msg, replayErrorMsg);
        }
      }

      // Now replay any pending update that had a dependency and whose
      // dependency has been replayed, do that until no more updates of that
      // type left...
      msg = remotePendingChanges.getNextUpdate();
    } while (msg != null);
  }

  private String logDecodingOperationError(LDAPUpdateMsg msg, Exception e)
  {
    Message message = ERR_EXCEPTION_DECODING_OPERATION.get(
        String.valueOf(msg) + " " + stackTraceToSingleLineString(e));
    logError(message);
    return message.toString();
  }

  /**
   * This method is called when an error happens while replaying
   * an operation.
   * It is necessary because the postOperation does not always get
   * called when error or Exceptions happen during the operation replay.
   *
   * @param csn the CSN of the operation with error.
   */
  private void updateError(CSN csn)
  {
    try
    {
      remotePendingChanges.commit(csn);
    }
    catch (NoSuchElementException e)
    {
      // A failure occurred after the change had been removed from the pending
      // changes table.
      if (debugEnabled())
      {
        TRACER.debugInfo(
            "LDAPReplicationDomain.updateError: Unable to find remote "
                + "pending change for CSN %s", csn);
      }
    }
  }

  /**
   * Generate a new CSN and insert it in the pending list.
   *
   * @param operation
   *          The operation for which the CSN must be generated.
   * @return The new CSN.
   */
  private CSN generateCSN(PluginOperation operation)
  {
    return pendingChanges.putLocalOperation(operation);
  }


  /**
   * Find the Unique Id of the entry with the provided DN by doing a
   * search of the entry and extracting its entryUUID from its attributes.
   *
   * @param dn The dn of the entry for which the unique Id is searched.
   *
   * @return The unique Id of the entry with the provided DN.
   */
  static String findEntryUUID(DN dn)
  {
    if (dn == null)
      return null;
    try
    {
      InternalClientConnection conn =
                InternalClientConnection.getRootConnection();
      Set<String> attrs = new LinkedHashSet<String>(1);
      attrs.add(ENTRYUUID_ATTRIBUTE_NAME);
      InternalSearchOperation search = conn.processSearch(dn,
            SearchScope.BASE_OBJECT, DereferencePolicy.NEVER_DEREF_ALIASES,
            0, 0, false,
            SearchFilter.createFilterFromString("(objectclass=*)"),
            attrs);

      if (search.getResultCode() == ResultCode.SUCCESS)
      {
        List<SearchResultEntry> result = search.getSearchEntries();
        if (!result.isEmpty())
        {
          SearchResultEntry resultEntry = result.get(0);
          if (resultEntry != null)
          {
            return EntryHistorical.getEntryUUID(resultEntry);
          }
        }
      }
    } catch (DirectoryException e)
    {
      // never happens because the filter is always valid.
    }
    return null;
  }

  /**
   * find the current DN of an entry from its entry UUID.
   *
   * @param uuid the Entry Unique ID.
   * @return The current DN of the entry or null if there is no entry with
   *         the specified UUID.
   */
  private DN findEntryDN(String uuid)
  {
    try
    {
      InternalSearchOperation search = conn.processSearch(getBaseDN(),
            SearchScope.WHOLE_SUBTREE,
            SearchFilter.createFilterFromString("entryuuid="+uuid));
      if (search.getResultCode() == ResultCode.SUCCESS)
      {
        List<SearchResultEntry> result = search.getSearchEntries();
        if (!result.isEmpty())
        {
          SearchResultEntry resultEntry = result.get(0);
          if (resultEntry != null)
          {
            return resultEntry.getName();
          }
        }
      }
    } catch (DirectoryException e)
    {
      // never happens because the filter is always valid.
    }
    return null;
  }

  /**
   * Solve a conflict detected when replaying a modify operation.
   *
   * @param op The operation that triggered the conflict detection.
   * @param msg The operation that triggered the conflict detection.
   * @return true if the process is completed, false if it must continue..
   */
  private boolean solveNamingConflict(ModifyOperation op,
      ModifyMsg msg)
  {
    ResultCode result = op.getResultCode();
    ModifyContext ctx = (ModifyContext) op.getAttachment(SYNCHROCONTEXT);
    String entryUUID = ctx.getEntryUUID();

    if (result == ResultCode.NO_SUCH_OBJECT)
    {
      /*
       * The operation is a modification but
       * the entry has been renamed on a different master in the same time.
       * search if the entry has been renamed, and return the new dn
       * of the entry.
       */
      DN newDN = findEntryDN(entryUUID);
      if (newDN != null)
      {
        // There is an entry with the same unique id as this modify operation
        // replay the modify using the current dn of this entry.
        msg.setDN(newDN);
        numResolvedNamingConflicts.incrementAndGet();
        return false;
      }
      else
      {
        // This entry does not exist anymore.
        // It has probably been deleted, stop the processing of this operation
        numResolvedNamingConflicts.incrementAndGet();
        return true;
      }
    }
    else if (result == ResultCode.NOT_ALLOWED_ON_RDN)
    {
      DN currentDN = findEntryDN(entryUUID);
      RDN currentRDN;
      if (currentDN != null)
      {
        currentRDN = currentDN.rdn();
      }
      else
      {
        // The entry does not exist anymore.
        numResolvedNamingConflicts.incrementAndGet();
        return true;
      }

      // The modify operation is trying to delete the value that is
      // currently used in the RDN. We need to alter the modify so that it does
      // not remove the current RDN value(s).

      List<Modification> mods = op.getModifications();
      for (Modification mod : mods)
      {
        AttributeType modAttrType = mod.getAttribute().getAttributeType();
        if ((mod.getModificationType() == ModificationType.DELETE
              || mod.getModificationType() == ModificationType.REPLACE)
            && currentRDN.hasAttributeType(modAttrType))
        {
          // the attribute can't be deleted because it is used in the RDN,
          // turn this operation is a replace with the current RDN value(s);
          mod.setModificationType(ModificationType.REPLACE);
          Attribute newAttribute = mod.getAttribute();
          AttributeBuilder attrBuilder = new AttributeBuilder(newAttribute);
          attrBuilder.add(currentRDN.getAttributeValue(modAttrType));
          mod.setAttribute(attrBuilder.toAttribute());
        }
      }
      msg.setMods(mods);
      numResolvedNamingConflicts.incrementAndGet();
      return false;
    }
    else
    {
      // The other type of errors can not be caused by naming conflicts.
      // Log a message for the repair tool.
      logError(ERR_ERROR_REPLAYING_OPERATION.get(
          op.toString(), ctx.getCSN().toString(),
          result.toString(), op.getErrorMessage().toString()));
      return true;
    }
  }

 /**
  * Solve a conflict detected when replaying a delete operation.
  *
  * @param op The operation that triggered the conflict detection.
  * @param msg The operation that triggered the conflict detection.
  * @return true if the process is completed, false if it must continue..
  */
 private boolean solveNamingConflict(DeleteOperation op,
     LDAPUpdateMsg msg)
 {
   ResultCode result = op.getResultCode();
   DeleteContext ctx = (DeleteContext) op.getAttachment(SYNCHROCONTEXT);
   String entryUUID = ctx.getEntryUUID();

   if (result == ResultCode.NO_SUCH_OBJECT)
   {
     /*
      * Find if the entry is still in the database.
      */
     DN currentDN = findEntryDN(entryUUID);
     if (currentDN == null)
     {
       /*
        * The entry has already been deleted, either because this delete
        * has already been replayed or because another concurrent delete
        * has already done the job.
        * In any case, there is is nothing more to do.
        */
       numResolvedNamingConflicts.incrementAndGet();
       return true;
     }
     else
     {
       // This entry has been renamed, replay the delete using its new DN.
       msg.setDN(currentDN);
       numResolvedNamingConflicts.incrementAndGet();
       return false;
     }
   }
   else if (result == ResultCode.NOT_ALLOWED_ON_NONLEAF)
   {
     /*
      * This may happen when we replay a DELETE done on a master
      * but children of this entry have been added on another master.
      *
      * Rename all the children by adding entryuuid in dn and delete this entry.
      *
      * The action taken here must be consistent with the actions
      * done in the solveNamingConflict(AddOperation) method
      * when we are adding an entry whose parent entry has already been deleted.
      */
     if (findAndRenameChild(op.getEntryDN(), op))
     {
       numUnresolvedNamingConflicts.incrementAndGet();
     }

     return false;
   }
   else
   {
     // The other type of errors can not be caused by naming conflicts.
     // Log a message for the repair tool.
     logError(ERR_ERROR_REPLAYING_OPERATION.get(
         op.toString(), ctx.getCSN().toString(),
         result.toString(), op.getErrorMessage().toString()));
     return true;
   }
 }

  /**
 * Solve a conflict detected when replaying a Modify DN operation.
 *
 * @param op The operation that triggered the conflict detection.
 * @param msg The operation that triggered the conflict detection.
 * @return true if the process is completed, false if it must continue.
 * @throws Exception When the operation is not valid.
 */
private boolean solveNamingConflict(ModifyDNOperation op,
    LDAPUpdateMsg msg) throws Exception
{
  ResultCode result = op.getResultCode();
  ModifyDnContext ctx = (ModifyDnContext) op.getAttachment(SYNCHROCONTEXT);
  String entryUUID = ctx.getEntryUUID();
  String newSuperiorID = ctx.getNewSuperiorEntryUUID();

  /*
   * four possible cases :
   * - the modified entry has been renamed
   * - the new parent has been renamed
   * - the operation is replayed for the second time.
   * - the entry has been deleted
   * action :
   *  - change the target dn and the new parent dn and
   *        restart the operation,
   *  - don't do anything if the operation is replayed.
   */

  // get the current DN of this entry in the database.
  DN currentDN = findEntryDN(entryUUID);

  // Construct the new DN to use for the entry.
  DN entryDN = op.getEntryDN();
  DN newSuperior;
  RDN newRDN = op.getNewRDN();

  if (newSuperiorID != null)
  {
    newSuperior = findEntryDN(newSuperiorID);
  }
  else
  {
    newSuperior = entryDN.parent();
  }

  //If we could not find the new parent entry, we missed this entry
  // earlier or it has disappeared from the database
  // Log this information for the repair tool and mark the entry
  // as conflicting.
  // stop the processing.
  if (newSuperior == null)
  {
    markConflictEntry(op, currentDN, currentDN.parent().child(newRDN));
    numUnresolvedNamingConflicts.incrementAndGet();
    return true;
  }

  DN newDN = newSuperior.child(newRDN);

  if (currentDN == null)
  {
    // The entry targeted by the Modify DN is not in the database
    // anymore.
    // This is a conflict between a delete and this modify DN.
    // The entry has been deleted, we can safely assume
    // that the operation is completed.
    numResolvedNamingConflicts.incrementAndGet();
    return true;
  }

  // if the newDN and the current DN match then the operation
  // is a no-op (this was probably a second replay)
  // don't do anything.
  if (newDN.equals(currentDN))
  {
    numResolvedNamingConflicts.incrementAndGet();
    return true;
  }

  if (result == ResultCode.NO_SUCH_OBJECT
      || result == ResultCode.UNWILLING_TO_PERFORM
      || result == ResultCode.OBJECTCLASS_VIOLATION)
  {
    /*
     * The entry or it's new parent has not been found
     * reconstruct the operation with the DN we just built
     */
    ModifyDNMsg modifyDnMsg = (ModifyDNMsg) msg;
    modifyDnMsg.setDN(currentDN);
    modifyDnMsg.setNewSuperior(newSuperior.toString());
    numResolvedNamingConflicts.incrementAndGet();
    return false;
  }
  else if (result == ResultCode.ENTRY_ALREADY_EXISTS)
  {
    /*
     * This may happen when two modifyDn operation
     * are done on different servers but with the same target DN
     * add the conflict object class to the entry
     * and rename it using its entryuuid.
     */
    ModifyDNMsg modifyDnMsg = (ModifyDNMsg) msg;
    markConflictEntry(op, op.getEntryDN(), newDN);
    modifyDnMsg.setNewRDN(generateConflictRDN(entryUUID,
                          modifyDnMsg.getNewRDN()));
    modifyDnMsg.setNewSuperior(newSuperior.toString());
    numUnresolvedNamingConflicts.incrementAndGet();
    return false;
  }
  else
  {
    // The other type of errors can not be caused by naming conflicts.
    // Log a message for the repair tool.
    logError(ERR_ERROR_REPLAYING_OPERATION.get(
        op.toString(), ctx.getCSN().toString(),
        result.toString(), op.getErrorMessage().toString()));
    return true;
  }
}


  /**
   * Solve a conflict detected when replaying a ADD operation.
   *
   * @param op The operation that triggered the conflict detection.
   * @param msg The message that triggered the conflict detection.
   * @return true if the process is completed, false if it must continue.
   * @throws Exception When the operation is not valid.
   */
  private boolean solveNamingConflict(AddOperation op,
      AddMsg msg) throws Exception
  {
    ResultCode result = op.getResultCode();
    AddContext ctx = (AddContext) op.getAttachment(SYNCHROCONTEXT);
    String entryUUID = ctx.getEntryUUID();
    String parentUniqueId = ctx.getParentEntryUUID();

    if (result == ResultCode.NO_SUCH_OBJECT)
    {
      /*
       * This can happen if the parent has been renamed or deleted
       * find the parent dn and calculate a new dn for the entry
       */
      if (parentUniqueId == null)
      {
        /*
         * This entry is the base dn of the backend.
         * It is quite surprising that the operation result be NO_SUCH_OBJECT.
         * There is nothing more we can do except log a
         * message for the repair tool to look at this problem.
         * TODO : Log the message
         */
        return true;
      }
      DN parentDn = findEntryDN(parentUniqueId);
      if (parentDn == null)
      {
        /*
         * The parent has been deleted
         * rename the entry as a conflicting entry.
         * The action taken here must be consistent with the actions
         * done when in the solveNamingConflict(DeleteOperation) method
         * when we are deleting an entry that have some child entries.
         */
        addConflict(msg);

        String conflictRDN =
            generateConflictRDN(entryUUID, op.getEntryDN().rdn().toString());
        msg.setDN(DN.valueOf(conflictRDN + "," + getBaseDNString()));
        // reset the parent entryUUID so that the check done is the
        // handleConflict phase does not fail.
        msg.setParentEntryUUID(null);
        numUnresolvedNamingConflicts.incrementAndGet();
      }
      else
      {
        msg.setDN(DN.valueOf(msg.getDN().rdn() + "," + parentDn));
        numResolvedNamingConflicts.incrementAndGet();
      }
      return false;
    }
    else if (result == ResultCode.ENTRY_ALREADY_EXISTS)
    {
      /*
       * This can happen if
       *  - two adds are done on different servers but with the
       *    same target DN.
       *  - the same ADD is being replayed for the second time on this server.
       * if the entryUUID already exist, assume this is a replay and
       *        don't do anything
       * if the entry unique id do not exist, generate conflict.
       */
      if (findEntryDN(entryUUID) != null)
      {
        // entry already exist : this is a replay
        return true;
      }
      else
      {
        addConflict(msg);
        String conflictRDN =
            generateConflictRDN(entryUUID, msg.getDN().toNormalizedString());
        msg.setDN(DN.valueOf(conflictRDN));
        numUnresolvedNamingConflicts.incrementAndGet();
        return false;
      }
    }
    else
    {
      // The other type of errors can not be caused by naming conflicts.
      // log a message for the repair tool.
      logError(ERR_ERROR_REPLAYING_OPERATION.get(
          op.toString(), ctx.getCSN().toString(),
          result.toString(), op.getErrorMessage().toString()));
      return true;
    }
  }

  /**
   * Find all the entries below the provided DN and rename them
   * so that they stay below the baseDN of this replicationDomain and
   * use the conflicting name and attribute.
   *
   * @param entryDN    The DN of the entry whose child must be renamed.
   * @param conflictOp The Operation that generated the conflict.
   */
  private boolean findAndRenameChild(DN entryDN, Operation conflictOp)
  {
    /*
     * TODO JNR Ludo thinks that: "Ideally, the operation should verify that the
     * entryUUID has not changed or try to use the entryUUID rather than the
     * DN.". entryUUID can be obtained from the caller of the current method.
     */
    boolean conflict = false;

    // Find an rename child entries.
    try
    {
      Set<String> attrs = new LinkedHashSet<String>(1);
      attrs.add(EntryHistorical.ENTRYUUID_ATTRIBUTE_NAME);
      attrs.add(EntryHistorical.HISTORICAL_ATTRIBUTE_NAME);

      InternalSearchOperation op =
          conn.processSearch(entryDN, SearchScope.SINGLE_LEVEL,
              DereferencePolicy.NEVER_DEREF_ALIASES, 0, 0, false,
              SearchFilter.createFilterFromString("(objectClass=*)"),
              attrs);

      if (op.getResultCode() == ResultCode.SUCCESS)
      {
        List<SearchResultEntry> entries = op.getSearchEntries();
        if (entries != null)
        {
          for (SearchResultEntry entry : entries)
          {
            /*
             * Check the ADD and ModRDN date of the child entry (All of them,
             * not only the one that are newer than the DEL op)
             * and keep the entry as a conflicting entry,
             */
            conflict = true;
            renameConflictEntry(conflictOp, entry.getName(),
                EntryHistorical.getEntryUUID(entry));
          }
        }
      }
      else
      {
        // log error and information for the REPAIR tool.
        MessageBuilder mb = new MessageBuilder();
        mb.append(ERR_CANNOT_RENAME_CONFLICT_ENTRY.get());
        mb.append(String.valueOf(entryDN));
        mb.append(" ");
        mb.append(String.valueOf(conflictOp));
        mb.append(" ");
        mb.append(String.valueOf(op.getResultCode()));
        logError(mb.toMessage());
      }
    } catch (DirectoryException e)
    {
      // log error and information for the REPAIR tool.
      MessageBuilder mb = new MessageBuilder();
      mb.append(ERR_EXCEPTION_RENAME_CONFLICT_ENTRY.get());
      mb.append(String.valueOf(entryDN));
      mb.append(" ");
      mb.append(String.valueOf(conflictOp));
      mb.append(" ");
      mb.append(stackTraceToSingleLineString(e));
      logError(mb.toMessage());
    }

    return conflict;
  }


  /**
   * Rename an entry that was conflicting so that it stays below the
   * baseDN of the replicationDomain.
   *
   * @param conflictOp The Operation that caused the conflict.
   * @param dn         The DN of the entry to be renamed.
   * @param entryUUID        The uniqueID of the entry to be renamed.
   */
  private void renameConflictEntry(Operation conflictOp, DN dn,
      String entryUUID)
  {
    Message alertMessage = NOTE_UNRESOLVED_CONFLICT.get(dn.toString());
    DirectoryServer.sendAlertNotification(this,
        ALERT_TYPE_REPLICATION_UNRESOLVED_CONFLICT, alertMessage);

    RDN newRDN = generateDeleteConflictDn(entryUUID, dn);
    ModifyDNOperation newOp = renameEntry(dn, newRDN, getBaseDN(), true);

    if (newOp.getResultCode() != ResultCode.SUCCESS)
    {
      // log information for the repair tool.
      MessageBuilder mb = new MessageBuilder();
      mb.append(ERR_CANNOT_RENAME_CONFLICT_ENTRY.get());
      mb.append(String.valueOf(dn));
      mb.append(" ");
      mb.append(String.valueOf(conflictOp));
      mb.append(" ");
      mb.append(String.valueOf(newOp.getResultCode()));
      logError(mb.toMessage());
    }
  }


  /**
   * Generate a modification to add the conflict attribute to an entry
   * whose Dn is now conflicting with another entry.
   *
   * @param op        The operation causing the conflict.
   * @param currentDN The current DN of the operation to mark as conflicting.
   * @param conflictDN     The newDn on which the conflict happened.
   */
  private void markConflictEntry(Operation op, DN currentDN, DN conflictDN)
  {
    // create new internal modify operation and run it.
    AttributeType attrType = DirectoryServer.getAttributeType(DS_SYNC_CONFLICT,
        true);
    Attribute attr = Attributes.create(attrType, AttributeValues.create(
        attrType, conflictDN.toNormalizedString()));
    List<Modification> mods = new ArrayList<Modification>();
    mods.add(new Modification(ModificationType.REPLACE, attr));

    ModifyOperation newOp = new ModifyOperationBasis(
          conn, InternalClientConnection.nextOperationID(),
          InternalClientConnection.nextMessageID(), new ArrayList<Control>(0),
          currentDN, mods);
    runAsSynchronizedOperation(newOp);

    if (newOp.getResultCode() != ResultCode.SUCCESS)
    {
      // Log information for the repair tool.
      MessageBuilder mb = new MessageBuilder();
      mb.append(ERR_CANNOT_ADD_CONFLICT_ATTRIBUTE.get());
      mb.append(String.valueOf(op));
      mb.append(" ");
      mb.append(String.valueOf(newOp.getResultCode()));
      logError(mb.toMessage());
    }

    // Generate an alert to let the administration know that some
    // conflict could not be solved.
    Message alertMessage = NOTE_UNRESOLVED_CONFLICT.get(conflictDN.toString());
    DirectoryServer.sendAlertNotification(this,
        ALERT_TYPE_REPLICATION_UNRESOLVED_CONFLICT, alertMessage);
  }

  /**
   * Add the conflict attribute to an entry that could
   * not be added because it is conflicting with another entry.
   *
   * @param msg            The conflicting Add Operation.
   *
   * @throws ASN1Exception When an encoding error happened manipulating the
   *                       msg.
   */
  private void addConflict(AddMsg msg) throws ASN1Exception
  {
    String normalizedDN = msg.getDN().toNormalizedString();

    // Generate an alert to let the administrator know that some
    // conflict could not be solved.
    Message alertMessage = NOTE_UNRESOLVED_CONFLICT.get(normalizedDN);
    DirectoryServer.sendAlertNotification(this,
        ALERT_TYPE_REPLICATION_UNRESOLVED_CONFLICT, alertMessage);

    // Add the conflict attribute
    msg.addAttribute(DS_SYNC_CONFLICT, normalizedDN);
  }

  /**
   * Generate the Dn to use for a conflicting entry.
   *
   * @param entryUUID The unique identifier of the entry involved in the
   * conflict.
   * @param rdn Original rdn.
   * @return The generated RDN for a conflicting entry.
   */
  private String generateConflictRDN(String entryUUID, String rdn)
  {
    return "entryuuid=" + entryUUID + "+" + rdn;
  }

  /**
   * Generate the RDN to use for a conflicting entry whose father was deleted.
   *
   * @param entryUUID The unique identifier of the entry involved in the
   *                 conflict.
   * @param dn       The original DN of the entry.
   *
   * @return The generated RDN for a conflicting entry.
   */
  private RDN generateDeleteConflictDn(String entryUUID, DN dn)
  {
    String newRDN =  "entryuuid=" + entryUUID + "+" + dn.rdn();
    try
    {
      return RDN.decode(newRDN);
    } catch (DirectoryException e)
    {
      // cannot happen
      return null;
    }
  }

  /**
   * Get the number of modify conflicts successfully resolved.
   * @return The number of modify conflicts successfully resolved.
   */
  private int getNumResolvedModifyConflicts()
  {
    return numResolvedModifyConflicts.get();
  }

  /**
   * Get the number of naming conflicts successfully resolved.
   * @return The number of naming conflicts successfully resolved.
   */
  private int getNumResolvedNamingConflicts()
  {
    return numResolvedNamingConflicts.get();
  }

  /**
   * Get the number of unresolved conflicts.
   * @return The number of unresolved conflicts.
   */
  private int getNumUnresolvedNamingConflicts()
  {
    return numUnresolvedNamingConflicts.get();
  }

  /**
   * Check if the domain solve conflicts.
   *
   * @return a boolean indicating if the domain should solve conflicts.
   */
  public boolean solveConflict()
  {
    return solveConflictFlag;
  }

  /**
   * Disable the replication on this domain.
   * The session to the replication server will be stopped.
   * The domain will not be destroyed but call to the pre-operation
   * methods will result in failure.
   * The listener thread will be destroyed.
   * The monitor informations will still be accessible.
   */
  public void disable()
  {
    state.save();
    state.clearInMemory();
    disabled = true;
    disableService(); // This will cut the session and wake up the listener
  }

  /**
   * Do what necessary when the data have changed : load state, load
   * generation Id.
   * If there is no such information check if there is a
   * ReplicaUpdateVector entry and translate it into a state
   * and generationId.
   * @exception DirectoryException Thrown when an error occurs.
   */
  protected void loadDataState()
  throws DirectoryException
  {
    state.clearInMemory();
    state.loadState();

    generator.adjust(state.getMaxCSN(getServerId()));
    // Retrieves the generation ID associated with the data imported

    generationId = loadGenerationId();
  }

  /**
   * Enable back the domain after a previous disable.
   * The domain will connect back to a replication Server and
   * will recreate threads to listen for messages from the Synchronization
   * server.
   * The generationId will be retrieved or computed if necessary.
   * The ServerState will also be read again from the local database.
   */
  public void enable()
  {
    try
    {
      loadDataState();
    }
    catch (Exception e)
    {
      /* TODO should mark that replicationServer service is
       * not available, log an error and retry upon timeout
       * should we stop the modifications ?
       */
      logError(ERR_LOADING_GENERATION_ID.get(
          getBaseDNString(), stackTraceToSingleLineString(e)));
      return;
    }

    enableService();

    disabled = false;
  }

  /**
   * Compute the data generationId associated with the current data present
   * in the backend for this domain.
   * @return The computed generationId.
   * @throws DirectoryException When an error occurs.
   */
  private long computeGenerationId() throws DirectoryException
  {
    long genId = exportBackend(null, true);

    if (debugEnabled())
      TRACER.debugInfo("Computed generationId: generationId=" + genId);

    return genId;
  }

  /**
   * Run a modify operation to update the entry whose DN is given as
   * a parameter with the generationID information.
   *
   * @param entryDN       The DN of the entry to be updated.
   * @param generationId  The value of the generationID to be saved.
   *
   * @return A ResultCode indicating if the operation was successful.
   */
  private ResultCode runSaveGenerationId(DN entryDN, long generationId)
  {
    // The generationId is stored in the root entry of the domain.
    ByteString asn1BaseDn = ByteString.valueOf(entryDN.toString());

    ArrayList<ByteString> values = new ArrayList<ByteString>();
    values.add(ByteString.valueOf(Long.toString(generationId)));

    LDAPAttribute attr =
      new LDAPAttribute(REPLICATION_GENERATION_ID, values);
    List<RawModification> mods = new ArrayList<RawModification>(1);
    mods.add(new LDAPModification(ModificationType.REPLACE, attr));

    ModifyOperation op = new ModifyOperationBasis(
          conn, InternalClientConnection.nextOperationID(),
          InternalClientConnection.nextMessageID(),
          new ArrayList<Control>(0), asn1BaseDn,
          mods);
    runAsSynchronizedOperation(op);

    return op.getResultCode();
  }

  /**
   * Stores the value of the generationId.
   * @param generationId The value of the generationId.
   * @return a ResultCode indicating if the method was successful.
   */
  public ResultCode saveGenerationId(long generationId)
  {
    ResultCode result = runSaveGenerationId(getBaseDN(), generationId);

    if (result != ResultCode.SUCCESS)
    {
      generationIdSavedStatus = false;
      if (result == ResultCode.NO_SUCH_OBJECT)
      {
        // If the base entry does not exist, save the generation
        // ID in the config entry
        result = runSaveGenerationId(config.dn(), generationId);
      }

      if (result != ResultCode.SUCCESS)
      {
        logError(ERR_UPDATING_GENERATION_ID.get(
            result.getResultCodeName(), getBaseDNString()));
      }
    }
    else
    {
      generationIdSavedStatus = true;
    }
    return result;
  }


  /**
   * Load the GenerationId from the root entry of the domain
   * from the REPLICATION_GENERATION_ID attribute in database
   * to memory, or compute it if not found.
   *
   * @return generationId The retrieved value of generationId
   * @throws DirectoryException When an error occurs.
   */
  private long loadGenerationId() throws DirectoryException
  {
    if (debugEnabled())
      TRACER.debugInfo("Attempt to read generation ID from DB "
          + getBaseDNString());

    /*
     * Search the database entry that is used to periodically
     * save the generation id
     */
    final Set<String> attributes = new LinkedHashSet<String>(1);
    attributes.add(REPLICATION_GENERATION_ID);
    final String filter = "(objectclass=*)";
    InternalSearchOperation search = conn.processSearch(getBaseDNString(),
        SearchScope.BASE_OBJECT,
        DereferencePolicy.DEREF_ALWAYS, 0, 0, false,
        filter,attributes);
    if (search.getResultCode() == ResultCode.NO_SUCH_OBJECT)
    {
      // if the base entry does not exist look for the generationID
      // in the config entry.
      search = conn.processSearch(config.dn().toString(),
          SearchScope.BASE_OBJECT,
          DereferencePolicy.DEREF_ALWAYS, 0, 0, false,
          filter,attributes);
    }

    boolean found = false;
    long aGenerationId = -1;
    if (search.getResultCode() != ResultCode.SUCCESS)
    {
      if (search.getResultCode() != ResultCode.NO_SUCH_OBJECT)
      {
        logError(ERR_SEARCHING_GENERATION_ID.get(
            search.getResultCode().getResultCodeName() + " " +
            search.getErrorMessage(),
            getBaseDNString()));
      }
    }
    else
    {
      List<SearchResultEntry> result = search.getSearchEntries();
      SearchResultEntry resultEntry = result.get(0);
      if (resultEntry != null)
      {
        AttributeType synchronizationGenIDType =
          DirectoryServer.getAttributeType(REPLICATION_GENERATION_ID);
        List<Attribute> attrs =
          resultEntry.getAttribute(synchronizationGenIDType);
        if (attrs != null)
        {
          Attribute attr = attrs.get(0);
          if (attr.size()>1)
          {
            logError(ERR_LOADING_GENERATION_ID.get(
                getBaseDNString(), "#Values=" + attr.size() +
                " Must be exactly 1 in entry " + resultEntry.toLDIFString()));
          }
          else if (attr.size() == 1)
          {
            found = true;
            try
            {
              aGenerationId = Long.decode(attr.iterator().next().toString());
            }
            catch(Exception e)
            {
              logError(ERR_LOADING_GENERATION_ID.get(
                  getBaseDNString(), stackTraceToSingleLineString(e)));
            }
          }
        }
      }
    }

    if (!found)
    {
      aGenerationId = computeGenerationId();
      saveGenerationId(aGenerationId);

      if (debugEnabled())
        TRACER.debugInfo("Generation ID created for domain baseDN="
            + getBaseDNString() + " generationId=" + aGenerationId);
    }
    else
    {
      generationIdSavedStatus = true;
      if (debugEnabled())
        TRACER.debugInfo("Generation ID successfully read from domain baseDN="
            + getBaseDNString() + " generationId=" + aGenerationId);
    }
    return aGenerationId;
  }

  /**
   * Do whatever is needed when a backup is started.
   * We need to make sure that the serverState is correctly save.
   */
  void backupStart()
  {
    state.save();
  }

  /**
   * Do whatever is needed when a backup is finished.
   */
  void backupEnd()
  {
    // Nothing is needed at the moment
  }

  /*
   * Total Update >>
   */

  /**
   * This method trigger an export of the replicated data.
   *
   * @param output               The OutputStream where the export should
   *                             be produced.
   * @throws DirectoryException  When needed.
   */
  @Override
  protected void exportBackend(OutputStream output) throws DirectoryException
  {
    exportBackend(output, false);
  }

  /**
   * Export the entries from the backend and/or compute the generation ID.
   * The ieContext must have been set before calling.
   *
   * @param output              The OutputStream where the export should
   *                            be produced.
   * @param checksumOutput      A boolean indicating if this export is
   *                            invoked to perform a checksum only
   *
   * @return The computed       GenerationID.
   *
   * @throws DirectoryException when an error occurred
   */
  private long exportBackend(OutputStream output, boolean checksumOutput)
      throws DirectoryException
  {
    Backend backend = getBackend();

    //  Acquire a shared lock for the backend.
    try
    {
      String lockFile = LockFileManager.getBackendLockFileName(backend);
      StringBuilder failureReason = new StringBuilder();
      if (! LockFileManager.acquireSharedLock(lockFile, failureReason))
      {
        Message message = ERR_LDIFEXPORT_CANNOT_LOCK_BACKEND.get(
            backend.getBackendID(), String.valueOf(failureReason));
        logError(message);
        throw new DirectoryException(ResultCode.OTHER, message);
      }
    }
    catch (Exception e)
    {
      Message message = ERR_LDIFEXPORT_CANNOT_LOCK_BACKEND.get(
          backend.getBackendID(), stackTraceToSingleLineString(e));
      logError(message);
      throw new DirectoryException(ResultCode.OTHER, message);
    }

    long numberOfEntries = backend.numSubordinates(getBaseDN(), true) + 1;
    long entryCount = Math.min(numberOfEntries, 1000);
    OutputStream os;
    ReplLDIFOutputStream ros = null;
    if (checksumOutput)
    {
      ros = new ReplLDIFOutputStream(entryCount);
      os = ros;
      try
      {
        os.write(Long.toString(numberOfEntries).getBytes());
      }
      catch(Exception e)
      {
        // Should never happen
      }
    }
    else
    {
      os = output;
    }

    // baseDN branch is the only one included in the export
    List<DN> includeBranches = new ArrayList<DN>(1);
    includeBranches.add(getBaseDN());
    LDIFExportConfig exportConfig = new LDIFExportConfig(os);
    exportConfig.setIncludeBranches(includeBranches);

    // For the checksum computing mode, only consider the 'stable' attributes
    if (checksumOutput)
    {
      String includeAttributeStrings[] =
        {"objectclass", "sn", "cn", "entryuuid"};
      Set<AttributeType> includeAttributes = new HashSet<AttributeType>();
      for (String attrName : includeAttributeStrings)
      {
        AttributeType attrType  = DirectoryServer.getAttributeType(attrName);
        if (attrType == null)
        {
          attrType = DirectoryServer.getDefaultAttributeType(attrName);
        }
        includeAttributes.add(attrType);
      }
      exportConfig.setIncludeAttributes(includeAttributes);
    }

    //  Launch the export.
    long genID = 0;
    try
    {
      backend.exportLDIF(exportConfig);
    }
    catch (DirectoryException de)
    {
      if (ros == null ||
          ros.getNumExportedEntries() < entryCount)
      {
        Message message =
            ERR_LDIFEXPORT_ERROR_DURING_EXPORT.get(de.getMessageObject());
        logError(message);
        throw new DirectoryException(ResultCode.OTHER, message);
      }
    }
    catch (Exception e)
    {
      Message message = ERR_LDIFEXPORT_ERROR_DURING_EXPORT.get(
          stackTraceToSingleLineString(e));
      logError(message);
      throw new DirectoryException(ResultCode.OTHER, message);
    }
    finally
    {
      // Clean up after the export by closing the export config.
      // Will also flush the export and export the remaining entries.
      exportConfig.close();

      if (checksumOutput)
      {
        genID = ros.getChecksumValue();
      }

      //  Release the shared lock on the backend.
      try
      {
        String lockFile = LockFileManager.getBackendLockFileName(backend);
        StringBuilder failureReason = new StringBuilder();
        if (! LockFileManager.releaseLock(lockFile, failureReason))
        {
          Message message = WARN_LDIFEXPORT_CANNOT_UNLOCK_BACKEND.get(
              backend.getBackendID(), String.valueOf(failureReason));
          logError(message);
          throw new DirectoryException(ResultCode.OTHER, message);
        }
      }
      catch (Exception e)
      {
        Message message = WARN_LDIFEXPORT_CANNOT_UNLOCK_BACKEND.get(
            backend.getBackendID(), stackTraceToSingleLineString(e));
        logError(message);
        throw new DirectoryException(ResultCode.OTHER, message);
      }
    }
    return genID;
  }

  /**
   * Process backend before import.
   *
   * @param backend
   *          The backend.
   * @throws DirectoryException
   *           If the backend could not be disabled or locked exclusively.
   */
  private void preBackendImport(Backend backend) throws DirectoryException
  {
    // Stop saving state
    stateSavingDisabled = true;

    // FIXME setBackendEnabled should be part of TaskUtils ?
    TaskUtils.disableBackend(backend.getBackendID());

    // Acquire an exclusive lock for the backend.
    String lockFile = LockFileManager.getBackendLockFileName(backend);
    StringBuilder failureReason = new StringBuilder();
    if (! LockFileManager.acquireExclusiveLock(lockFile, failureReason))
    {
      Message message = ERR_INIT_CANNOT_LOCK_BACKEND.get(
                          backend.getBackendID(),
                          String.valueOf(failureReason));
      logError(message);
      throw new DirectoryException(ResultCode.OTHER, message);
    }
  }

  /**
   * This method triggers an import of the replicated data.
   *
   * @param input                The InputStream from which the data are read.
   * @throws DirectoryException  When needed.
   */
  @Override
  protected void importBackend(InputStream input) throws DirectoryException
  {
    LDIFImportConfig importConfig = null;

    Backend backend = getBackend();

    IEContext ieCtx = getImportExportContext();
    try
    {
      if (!backend.supportsLDIFImport())
      {
        ieCtx.setExceptionIfNoneSet(new DirectoryException(OTHER,
            ERR_INIT_IMPORT_NOT_SUPPORTED.get(backend.getBackendID())));
      }
      else
      {
        importConfig = new LDIFImportConfig(input);
        List<DN> includeBranches = new ArrayList<DN>();
        includeBranches.add(getBaseDN());
        importConfig.setIncludeBranches(includeBranches);
        importConfig.setAppendToExistingData(false);
        importConfig.setSkipDNValidation(true);
        // We should not validate schema for replication
        importConfig.setValidateSchema(false);
        // Allow fractional replication ldif import plugin to be called
        importConfig.setInvokeImportPlugins(true);
        // Reset the follow import flag and message before starting the import
        importErrorMessageId = -1;

        // TODO How to deal with rejected entries during the import
        importConfig.writeRejectedEntries(
            getFileForPath("logs" + File.separator +
            "replInitRejectedEntries").getAbsolutePath(),
            ExistingFileBehavior.OVERWRITE);

        // Process import
        preBackendImport(backend);
        backend.importLDIF(importConfig);

        stateSavingDisabled = false;
      }
    }
    catch(Exception e)
    {
      ieCtx.setExceptionIfNoneSet(new DirectoryException(ResultCode.OTHER,
          ERR_INIT_IMPORT_FAILURE.get(stackTraceToSingleLineString(e))));
    }
    finally
    {
      try
      {
        // Cleanup
        if (importConfig != null)
        {
          importConfig.close();
          closeBackendImport(backend); // Re-enable backend
          backend = getBackend();
        }

        loadDataState();

        if (ieCtx.getException() != null)
        {
          // When an error occurred during an import, most of times
          // the generationId coming in the root entry of the imported data,
          // is not valid anymore (partial data in the backend).
          generationId = computeGenerationId();
          saveGenerationId(generationId);
        }
      }
      catch (DirectoryException fe)
      {
        // If we already catch an Exception it's quite possible
        // that the loadDataState() and setGenerationId() fail
        // so we don't bother about the new Exception.
        // However if there was no Exception before we want
        // to return this Exception to the task creator.
        ieCtx.setExceptionIfNoneSet(new DirectoryException(
            ResultCode.OTHER,
            ERR_INIT_IMPORT_FAILURE.get(stackTraceToSingleLineString(fe))));
      }
    }

    if (ieCtx.getException() != null)
      throw ieCtx.getException();
  }

  /**
   * Make post import operations.
   * @param backend The backend implied in the import.
   * @exception DirectoryException Thrown when an error occurs.
   */
  protected void closeBackendImport(Backend backend) throws DirectoryException
  {
    String lockFile = LockFileManager.getBackendLockFileName(backend);
    StringBuilder failureReason = new StringBuilder();

    // Release lock
    if (!LockFileManager.releaseLock(lockFile, failureReason))
    {
      Message message = WARN_LDIFIMPORT_CANNOT_UNLOCK_BACKEND.get(
          backend.getBackendID(), String.valueOf(failureReason));
      logError(message);
      throw new DirectoryException(ResultCode.OTHER, message);
    }

    TaskUtils.enableBackend(backend.getBackendID());
  }

  /**
   * Retrieves a replication domain based on the baseDN.
   *
   * @param baseDN The baseDN of the domain to retrieve
   * @return The domain retrieved
   * @throws DirectoryException When an error occurred or no domain
   * match the provided baseDN.
   */
  public static LDAPReplicationDomain retrievesReplicationDomain(DN baseDN)
      throws DirectoryException
  {
    LDAPReplicationDomain replicationDomain = null;

    // Retrieves the domain
    for (SynchronizationProvider<?> provider :
      DirectoryServer.getSynchronizationProviders())
    {
      if (!(provider instanceof MultimasterReplication))
      {
        Message message = ERR_INVALID_PROVIDER.get();
        throw new DirectoryException(ResultCode.OTHER, message);
      }

      // From the domainDN retrieves the replication domain
      LDAPReplicationDomain domain =
        MultimasterReplication.findDomain(baseDN, null);
      if (domain == null)
      {
        break;
      }
      if (replicationDomain != null)
      {
        // Should never happen
        Message message = ERR_MULTIPLE_MATCHING_DOMAIN.get();
        throw new DirectoryException(ResultCode.OTHER, message);
      }
      replicationDomain = domain;
    }

    if (replicationDomain == null)
    {
      throw new DirectoryException(ResultCode.OTHER,
          ERR_NO_MATCHING_DOMAIN.get(String.valueOf(baseDN)));
    }
    return replicationDomain;
  }

  /**
   * Returns the backend associated to this domain.
   * @return The associated backend.
   */
  private Backend getBackend()
  {
    return DirectoryServer.getBackend(getBaseDN());
  }

  /*
   * <<Total Update
   */


  /**
   * Push the modifications contained in the given parameter as a modification
   * that would happen on a local server. The modifications are not applied to
   * the local database, historical information is not updated but a CSN is
   * generated and the ServerState associated to this domain is updated.
   *
   * @param modifications
   *          The modification to push
   */
  public void synchronizeModifications(List<Modification> modifications)
  {
    ModifyOperation op = new ModifyOperationBasis(
                          InternalClientConnection.getRootConnection(),
                          InternalClientConnection.nextOperationID(),
                          InternalClientConnection.nextMessageID(),
                          null, DirectoryServer.getSchemaDN(),
                          modifications);
    LocalBackendModifyOperation localOp = new LocalBackendModifyOperation(op);

    CSN csn = generateCSN(localOp);
    OperationContext ctx = new ModifyContext(csn, "schema");
    localOp.setAttachment(SYNCHROCONTEXT, ctx);
    localOp.setResultCode(ResultCode.SUCCESS);
    synchronize(localOp);
  }

  /**
   * Check if the provided configuration is acceptable for add.
   *
   * @param configuration The configuration to check.
   * @param unacceptableReasons When the configuration is not acceptable, this
   *                            table is use to return the reasons why this
   *                            configuration is not acceptable.
   *
   * @return true if the configuration is acceptable, false other wise.
   */
  public static boolean isConfigurationAcceptable(
      ReplicationDomainCfg configuration, List<Message> unacceptableReasons)
  {
    // Check that there is not already a domain with the same DN
    final DN dn = configuration.getBaseDN();
    LDAPReplicationDomain domain = MultimasterReplication.findDomain(dn, null);
    if (domain != null && domain.getBaseDN().equals(dn))
    {
      unacceptableReasons.add(ERR_SYNC_INVALID_DN.get());
      return false;
    }

    // Check that the base DN is configured as a base-dn of the directory server
    if (DirectoryServer.getBackend(dn) == null)
    {
      unacceptableReasons.add(ERR_UNKNOWN_DN.get(dn.toString()));
      return false;
    }

    // Check fractional configuration
    try
    {
      isFractionalConfigAcceptable(configuration);
    } catch (ConfigException e)
    {
      unacceptableReasons.add(e.getMessageObject());
      return false;
    }

    return true;
  }

  /** {@inheritDoc} */
  @Override
  public ConfigChangeResult applyConfigurationChange(
         ReplicationDomainCfg configuration)
  {
    this.config = configuration;
    changeConfig(configuration);

    // Read assured + fractional configuration and each time reconnect if needed
    readAssuredConfig(configuration, true);
    readFractionalConfig(configuration, true);

    solveConflictFlag = isSolveConflict(configuration);

    try
    {
      storeECLConfiguration(configuration);
    }
    catch(Exception e)
    {
      return new ConfigChangeResult(ResultCode.OTHER, false);
    }

    return new ConfigChangeResult(ResultCode.SUCCESS, false);
  }

  /** {@inheritDoc} */
  @Override
  public boolean isConfigurationChangeAcceptable(
         ReplicationDomainCfg configuration, List<Message> unacceptableReasons)
  {
    // Check that a import/export is not in progress
    if (ieRunning())
    {
      unacceptableReasons.add(
          NOTE_ERR_CANNOT_CHANGE_CONFIG_DURING_TOTAL_UPDATE.get());
      return false;
    }

    // Check fractional configuration
    try
    {
      isFractionalConfigAcceptable(configuration);
      return true;
    }
    catch (ConfigException e)
    {
      unacceptableReasons.add(e.getMessageObject());
      return false;
    }
  }

  /** {@inheritDoc} */
  @Override
  public Map<String, String> getAlerts()
  {
    Map<String, String> alerts = new LinkedHashMap<String, String>();

    alerts.put(ALERT_TYPE_REPLICATION_UNRESOLVED_CONFLICT,
               ALERT_DESCRIPTION_REPLICATION_UNRESOLVED_CONFLICT);
    return alerts;
  }

  /** {@inheritDoc} */
  @Override
  public String getClassName()
  {
    return CLASS_NAME;

  }

  /** {@inheritDoc} */
  @Override
  public DN getComponentEntryDN()
  {
    return config.dn();
  }

  /**
   * Starts the Replication Domain.
   */
  public void start()
  {
    // Create the ServerStateFlush thread
    flushThread.start();

    startListenService();
  }


  /**
   * Remove from this domain configuration, the configuration of the
   * external change log.
   */
  private void removeECLDomainCfg()
  {
    try
    {
      DN eclConfigEntryDN = DN.valueOf("cn=external changeLog," + config.dn());
      if (DirectoryServer.getConfigHandler().entryExists(eclConfigEntryDN))
      {
        DirectoryServer.getConfigHandler().deleteEntry(eclConfigEntryDN, null);
      }
    }
    catch(Exception e)
    {
      TRACER.debugCaught(DebugLogLevel.ERROR, e);
      logError(ERR_CHECK_CREATE_REPL_BACKEND_FAILED.get(
          stackTraceToSingleLineString(e)));
    }
  }

  /**
   * Store the provided ECL configuration for the domain.
   * @param  domCfg       The provided configuration.
   * @throws ConfigException When an error occurred.
   */
  private void storeECLConfiguration(ReplicationDomainCfg domCfg)
      throws ConfigException
  {
    ExternalChangelogDomainCfg eclDomCfg = null;
    // create the ecl config if it does not exist
    // There may not be any config entry related to this domain in some
    // unit test cases
    try
    {
      DN configDn = config.dn();
      if (DirectoryServer.getConfigHandler().entryExists(configDn))
      {
        try
        { eclDomCfg = domCfg.getExternalChangelogDomain();
        } catch(Exception e) { /* do nothing */ }
        // domain with no config entry only when running unit tests
        if (eclDomCfg == null)
        {
          // no ECL config provided hence create a default one
          // create the default one
          DN eclConfigEntryDN = DN.valueOf("cn=external changelog," + configDn);
          if (!DirectoryServer.getConfigHandler().entryExists(eclConfigEntryDN))
          {
            // no entry exist yet for the ECL config for this domain
            // create it
            String ldif = makeLdif(
                "dn: cn=external changelog," + configDn,
                "objectClass: top",
                "objectClass: ds-cfg-external-changelog-domain",
                "cn: external changelog",
                "ds-cfg-enabled: " + !getBackend().isPrivateBackend());
            LDIFImportConfig ldifImportConfig = new LDIFImportConfig(
                new StringReader(ldif));
            // No need to validate schema in replication
            ldifImportConfig.setValidateSchema(false);
            LDIFReader reader = new LDIFReader(ldifImportConfig);
            Entry eclEntry = reader.readEntry();
            DirectoryServer.getConfigHandler().addEntry(eclEntry, null);
            ldifImportConfig.close();
          }
        }
      }
      eclDomCfg = domCfg.getExternalChangelogDomain();
      if (eclDomain != null)
      {
        eclDomain.applyConfigurationChange(eclDomCfg);
      }
      else
      {
        // Create the ECL domain object
        eclDomain = new ExternalChangelogDomain(this, eclDomCfg);
      }
    }
    catch (Exception e)
    {
      throw new ConfigException(NOTE_ERR_UNABLE_TO_ENABLE_ECL.get(
          "Replication Domain on " + getBaseDNString(),
          stackTraceToSingleLineString(e)), e);
    }
  }

  private static String makeLdif(String... lines)
  {
    final StringBuilder buffer = new StringBuilder();
    for (String line : lines) {
      buffer.append(line).append(EOL);
    }
    // Append an extra line so we can append LDIF Strings.
    buffer.append(EOL);
    return buffer.toString();
  }

  /** {@inheritDoc} */
  @Override
  public void sessionInitiated(ServerStatus initStatus, ServerState rsState)
  {
    // Check domain fractional configuration consistency with local
    // configuration variables
    forceBadDataSet = !isBackendFractionalConfigConsistent();

    super.sessionInitiated(initStatus, rsState);

    // Now that we are connected , we can enable ECL if :
    // 1/ RS must in the same JVM and created an ECL_WORKFLOW_ELEMENT
    // and 2/ this domain must NOT be private
    if (!getBackend().isPrivateBackend())
    {
      try
      {
        ECLWorkflowElement wfe = (ECLWorkflowElement)
        DirectoryServer.getWorkflowElement(
            ECLWorkflowElement.ECL_WORKFLOW_ELEMENT);
        if (wfe!=null)
          wfe.getReplicationServer().enableECL();
      }
      catch (DirectoryException de)
      {
        logError(NOTE_ERR_UNABLE_TO_ENABLE_ECL.get(
            "Replication Domain on " + getBaseDNString(),
            stackTraceToSingleLineString(de)));
        // and go on
      }
    }

    // Now for bad data set status if needed
    if (forceBadDataSet)
    {
      // Go into bad data set status
      setNewStatus(StatusMachineEvent.TO_BAD_GEN_ID_STATUS_EVENT);
      broker.signalStatusChange(status);
      logError(NOTE_FRACTIONAL_BAD_DATA_SET_NEED_RESYNC.get(getBaseDNString()));
      return; // Do not send changes to the replication server
    }

    try
    {
      /*
       * We must not publish changes to a replicationServer that has
       * not seen all our previous changes because this could cause
       * some other ldap servers to miss those changes.
       * Check that the ReplicationServer has seen all our previous
       * changes.
       */
      CSN replServerMaxCSN = rsState.getCSN(getServerId());

      // we don't want to update from here (a DS) an empty RS because
      // normally the RS should have been updated by other RSes except for
      // very last changes lost if the local connection was broken
      // ... hence the RS we are connected to should not be empty
      // ... or if it is empty, it is due to a voluntary reset
      // and we don't want to update it with our changes that could be huge.
      if (replServerMaxCSN != null && replServerMaxCSN.getSeqnum() != 0)
      {
        CSN ourMaxCSN = state.getMaxCSN(getServerId());
        if (ourMaxCSN != null
            && !ourMaxCSN.isOlderThanOrEqualTo(replServerMaxCSN))
        {
          pendingChanges.setRecovering(true);
          broker.setRecoveryRequired(true);
          final RSUpdater rsUpdater = new RSUpdater(replServerMaxCSN);
          if (this.rsUpdater.compareAndSet(null, rsUpdater))
          {
            rsUpdater.start();
          }
        }
      }
    } catch (Exception e)
    {
      logError(ERR_PUBLISHING_FAKE_OPS.get(getBaseDNString(),
          stackTraceToSingleLineString(e)));
    }
  }

  /**
   * Build the list of changes that have been processed by this server after the
   * CSN given as a parameter and publish them using the given session.
   *
   * @param startCSN
   *          The CSN where we need to start the search
   * @param session
   *          The session to use to publish the changes
   * @param shutdown
   *          whether the current run must be stopped
   * @return A boolean indicating he success of the operation.
   * @throws Exception
   *           if an Exception happens during the search.
   */
  public boolean buildAndPublishMissingChanges(CSN startCSN,
      ReplicationBroker session, AtomicBoolean shutdown) throws Exception
  {
    // Trim the changes in replayOperations that are older than the startCSN.
    synchronized (replayOperations)
    {
      Iterator<CSN> it = replayOperations.keySet().iterator();
      while (it.hasNext())
      {
        if (shutdown.get())
        {
          return false;
        }
        if (it.next().isNewerThan(startCSN))
        {
          break;
        }
        it.remove();
      }
    }

    CSN lastRetrievedChange;
    InternalSearchOperation op;
    CSN currentStartCSN = startCSN;
    do
    {
      if (shutdown.get())
      {
        return false;
      }

      lastRetrievedChange = null;
      // We can't do the search in one go because we need to store the results
      // so that we are sure we send the operations in order and because the
      // list might be large.
      // So we search by interval of 10 seconds and store the results in the
      // replayOperations list so that they are sorted before sending them.
      long missingChangesDelta = currentStartCSN.getTime() + 10000;
      CSN endCSN = new CSN(missingChangesDelta, 0xffffffff, getServerId());

      ScanSearchListener listener =
        new ScanSearchListener(currentStartCSN, endCSN);
      op = searchForChangedEntries(getBaseDN(), currentStartCSN, endCSN,
              listener);

      // Publish and remove all the changes from the replayOperations list
      // that are older than the endCSN.
      final List<FakeOperation> opsToSend = new LinkedList<FakeOperation>();
      synchronized (replayOperations)
      {
        Iterator<FakeOperation> itOp = replayOperations.values().iterator();
        while (itOp.hasNext())
        {
          if (shutdown.get())
          {
            return false;
          }
          FakeOperation fakeOp = itOp.next();
          if (fakeOp.getCSN().isNewerThan(endCSN) // sanity check
              || !state.cover(fakeOp.getCSN())
              // do not look for replay operations in the future
              || currentStartCSN.isNewerThan(now()))
          {
            break;
          }

          lastRetrievedChange = fakeOp.getCSN();
          opsToSend.add(fakeOp);
          itOp.remove();
        }
      }

      for (FakeOperation opToSend : opsToSend)
      {
        if (shutdown.get())
        {
          return false;
        }
        session.publishRecovery(opToSend.generateMessage());
      }

      if (lastRetrievedChange != null)
      {
        currentStartCSN = lastRetrievedChange;
      }
      else
      {
        currentStartCSN = endCSN;
      }
    } while (pendingChanges.recoveryUntil(lastRetrievedChange)
          && op.getResultCode().equals(ResultCode.SUCCESS));

    return op.getResultCode().equals(ResultCode.SUCCESS);
  }

  private static CSN now()
  {
    // ensure now() will always come last with isNewerThan() test,
    // even though the timestamp, or the timestamp and seqnum would be the same
    return new CSN(TimeThread.getTime(), Integer.MAX_VALUE, Integer.MAX_VALUE);
  }

  /**
   * Search for the changes that happened since fromCSN based on the historical
   * attribute. The only changes that will be send will be the one generated on
   * the serverId provided in fromCSN.
   *
   * @param baseDN
   *          the base DN
   * @param fromCSN
   *          The CSN from which we want the changes
   * @param lastCSN
   *          The max CSN that the search should return
   * @param resultListener
   *          The listener that will process the entries returned
   * @return the internal search operation
   * @throws Exception
   *           when raised.
   */
  private static InternalSearchOperation searchForChangedEntries(DN baseDN,
      CSN fromCSN, CSN lastCSN, InternalSearchListener resultListener)
      throws Exception
  {
    InternalClientConnection conn =
      InternalClientConnection.getRootConnection();
    Integer serverId = fromCSN.getServerId();

    String maxValueForId;
    if (lastCSN == null)
    {
      maxValueForId = "ffffffffffffffff" + String.format("%04x", serverId)
                      + "ffffffff";
    }
    else
    {
      maxValueForId = lastCSN.toString();
    }

    LDAPFilter filter = LDAPFilter.decode(
        "(&(" + HISTORICAL_ATTRIBUTE_NAME + ">=dummy:" + fromCSN + ")" +
          "(" + HISTORICAL_ATTRIBUTE_NAME + "<=dummy:" + maxValueForId + "))");

    return conn.processSearch(
      ByteString.valueOf(baseDN.toString()),
      SearchScope.WHOLE_SUBTREE,
      DereferencePolicy.NEVER_DEREF_ALIASES,
      0, 0, false, filter,
      USER_AND_REPL_OPERATIONAL_ATTRS,
      resultListener);
  }

  /**
   * Search for the changes that happened since fromCSN based on the historical
   * attribute. The only changes that will be send will be the one generated on
   * the serverId provided in fromCSN.
   *
   * @param baseDN
   *          the base DN
   * @param fromCSN
   *          The CSN from which we want the changes
   * @param resultListener
   *          that will process the entries returned.
   * @return the internal search operation
   * @throws Exception
   *           when raised.
   */
  public static InternalSearchOperation searchForChangedEntries(DN baseDN,
      CSN fromCSN, InternalSearchListener resultListener) throws Exception
  {
    return searchForChangedEntries(baseDN, fromCSN, null, resultListener);
  }


  /**
   * This method should return the total number of objects in the
   * replicated domain.
   * This count will be used for reporting.
   *
   * @throws DirectoryException when needed.
   *
   * @return The number of objects in the replication domain.
   */
  @Override
  public long countEntries() throws DirectoryException
  {
    Backend backend = getBackend();
    if (!backend.supportsLDIFExport())
    {
      Message msg = ERR_INIT_EXPORT_NOT_SUPPORTED.get(backend.getBackendID());
      logError(msg);
      throw new DirectoryException(ResultCode.OTHER, msg);
    }

    return backend.numSubordinates(getBaseDN(), true) + 1;
  }

  /** {@inheritDoc} */
  @Override
  public boolean processUpdate(UpdateMsg updateMsg)
  {
    // Ignore message if fractional configuration is inconsistent and
    // we have been passed into bad data set status
    if (forceBadDataSet)
    {
      return false;
    }

    if (updateMsg instanceof LDAPUpdateMsg)
    {
      LDAPUpdateMsg msg = (LDAPUpdateMsg) updateMsg;

      // Put the UpdateMsg in the RemotePendingChanges list.
      if (!remotePendingChanges.putRemoteUpdate(msg))
      {
        /*
         * Already received this change so ignore it. This may happen if there
         * are uncommitted changes in the queue and session failover occurs
         * causing a recovery of all changes since the current committed server
         * state. See OPENDJ-1115.
         */
        if (debugEnabled())
        {
          TRACER.debugInfo(
                  "LDAPReplicationDomain.processUpdate: ignoring "
                  + "duplicate change %s", msg.getCSN());
        }
        return true;
      }

      // Put update message into the replay queue
      // (block until some place in the queue is available)
      final UpdateToReplay updateToReplay = new UpdateToReplay(msg, this);
      while (!isListenerShuttingDown())
      {
        // loop until we can offer to the queue or shutdown was initiated
        try
        {
          if (updateToReplayQueue.offer(updateToReplay, 1, TimeUnit.SECONDS))
          {
            // successful offer to the queue, let's exit the loop
            break;
          }
        }
        catch (InterruptedException e)
        {
          // Thread interrupted: check for shutdown.
          Thread.currentThread().interrupt();
        }
      }

      return false;
    }

    // unknown message type, this should not happen, just ignore it.
    return true;
  }

  /**
   * Monitoring information for the LDAPReplicationDomain.
   *
   * @return Monitoring attributes specific to the LDAPReplicationDomain.
   */
  @Override
  public Collection<Attribute> getAdditionalMonitoring()
  {
    List<Attribute> attributes = new ArrayList<Attribute>();

    // get number of changes in the pending list
    addMonitorData(attributes, "pending-updates", getPendingUpdatesCount());

    addMonitorData(attributes, "replayed-updates-ok",
        getNumReplayedPostOpCalled());
    addMonitorData(attributes, "resolved-modify-conflicts",
        getNumResolvedModifyConflicts());
    addMonitorData(attributes, "resolved-naming-conflicts",
        getNumResolvedNamingConflicts());
    addMonitorData(attributes, "unresolved-naming-conflicts",
        getNumUnresolvedNamingConflicts());
    addMonitorData(attributes, "remote-pending-changes-size",
        remotePendingChanges.getQueueSize());

    return attributes;
  }

  /**
   * Verifies that the given string represents a valid source
   * from which this server can be initialized.
   * @param sourceString The string representing the source
   * @return The source as a integer value
   * @throws DirectoryException if the string is not valid
   */
  public int decodeSource(String sourceString) throws DirectoryException
  {
    int source = 0;
    try
    {
      source = Integer.decode(sourceString);
      if (source >= -1 && source != getServerId())
      {
        // TODO Verifies serverID is in the domain
        // We should check here that this is a server implied
        // in the current domain.
        return source;
      }
    }
    catch (Exception e)
    {
      Message message = ERR_INVALID_IMPORT_SOURCE.get(
          getBaseDNString(), Integer.toString(getServerId()),
          sourceString, stackTraceToSingleLineString(e));
      throw new DirectoryException(ResultCode.OTHER, message, e);
    }

    Message message = ERR_INVALID_IMPORT_SOURCE.get(getBaseDNString(),
        Integer.toString(getServerId()), Integer.toString(source), "");
    throw new DirectoryException(ResultCode.OTHER, message);
  }

  /**
   * Called by synchronize post op plugin in order to add the entry historical
   * attributes to the UpdateMsg.
   * @param msg an replication update message
   * @param op  the operation in progress
   */
  private void addEntryAttributesForCL(UpdateMsg msg,
      PostOperationOperation op)
  {
    if (op instanceof PostOperationDeleteOperation)
    {
      PostOperationDeleteOperation delOp = (PostOperationDeleteOperation) op;
      final Set<String> names = getEclIncludesForDeletes();
      Entry entry = delOp.getEntryToDelete();
      final DeleteMsg deleteMsg = (DeleteMsg) msg;
      deleteMsg.setEclIncludes(getIncludedAttributes(entry, names));

      // For delete only, add the Authorized DN since it's required in the
      // ECL entry but is not part of rest of the message.
      DN deleterDN = delOp.getAuthorizationDN();
      if (deleterDN != null)
      {
        deleteMsg.setInitiatorsName(deleterDN.toString());
      }
    }
    else if (op instanceof PostOperationModifyOperation)
    {
      PostOperationModifyOperation modOp = (PostOperationModifyOperation) op;
      Set<String> names = getEclIncludes();
      Entry entry = modOp.getCurrentEntry();
      ((ModifyMsg) msg).setEclIncludes(getIncludedAttributes(entry, names));
    }
    else if (op instanceof PostOperationModifyDNOperation)
    {
      PostOperationModifyDNOperation modDNOp =
        (PostOperationModifyDNOperation) op;
      Set<String> names = getEclIncludes();
      Entry entry = modDNOp.getOriginalEntry();
      ((ModifyDNMsg) msg).setEclIncludes(getIncludedAttributes(entry, names));
    }
    else if (op instanceof PostOperationAddOperation)
    {
      PostOperationAddOperation addOp = (PostOperationAddOperation) op;
      Set<String> names = getEclIncludes();
      Entry entry = addOp.getEntryToAdd();
      ((AddMsg) msg).setEclIncludes(getIncludedAttributes(entry, names));
    }
  }

  private Collection<Attribute> getIncludedAttributes(Entry entry,
      Set<String> names)
  {
    if (names.isEmpty())
    {
      // Fast-path.
      return Collections.emptySet();
    }
    else if (names.size() == 1 && names.contains("*"))
    {
      // Potential fast-path for delete operations.
      List<Attribute> attributes = new LinkedList<Attribute>();
      for (List<Attribute> attributeList : entry.getUserAttributes().values())
      {
        attributes.addAll(attributeList);
      }
      Attribute objectClassAttribute = entry.getObjectClassAttribute();
      if (objectClassAttribute != null)
      {
        attributes.add(objectClassAttribute);
      }
      return attributes;
    }
    else
    {
      // Expand @objectclass references in attribute list if needed.
      // We do this now in order to take into account dynamic schema changes.
      Set<String> expandedNames = getExpandedNames(names);

      Entry filteredEntry =
          entry.filterEntry(expandedNames, false, false, false);
      return filteredEntry.getAttributes();
    }
  }

  private Set<String> getExpandedNames(Set<String> names)
  {
    // Only rebuild the attribute set if necessary.
    if (!needsExpanding(names))
    {
      return names;
    }

    final Set<String> expandedNames = new HashSet<String>(names.size());
    for (String name : names)
    {
      if (name.startsWith("@"))
      {
        String ocName = name.substring(1);
        ObjectClass objectClass =
            DirectoryServer.getObjectClass(toLowerCase(ocName));
        if (objectClass != null)
        {
          for (AttributeType at : objectClass.getRequiredAttributeChain())
          {
            expandedNames.add(at.getNameOrOID());
          }
          for (AttributeType at : objectClass.getOptionalAttributeChain())
          {
            expandedNames.add(at.getNameOrOID());
          }
        }
      }
      else
      {
        expandedNames.add(name);
      }
    }
    return expandedNames;
  }

  private boolean needsExpanding(Set<String> names)
  {
    for (String name : names)
    {
      if (name.startsWith("@"))
      {
        return true;
      }
    }
    return false;
  }

  /**
   * Gets the fractional configuration of this domain.
   * @return The fractional configuration of this domain.
   */
  FractionalConfig getFractionalConfig()
  {
    return fractionalConfig;
  }

  /**
   * This bean is a utility class used for holding the parsing
   * result of a fractional configuration. It also contains some facility
   * methods like fractional configuration comparison...
   */
  static class FractionalConfig
  {
    /**
     * Tells if fractional replication is enabled or not (some fractional
     * constraints have been put in place). If this is true then
     * fractionalExclusive explains the configuration mode and either
     * fractionalSpecificClassesAttributes or fractionalAllClassesAttributes or
     * both should be filled with something.
     */
    private boolean fractional = false;

    /**
     * - If true, tells that the configured fractional replication is exclusive:
     * Every attributes contained in fractionalSpecificClassesAttributes and
     * fractionalAllClassesAttributes should be ignored when replaying operation
     * in local backend.
     * - If false, tells that the configured fractional replication is
     * inclusive:
     * Only attributes contained in fractionalSpecificClassesAttributes and
     * fractionalAllClassesAttributes should be taken into account in local
     * backend.
     */
    private boolean fractionalExclusive = true;

    /**
     * Used in fractional replication: holds attributes of a specific object
     * class.
     * - key = object class (name or OID of the class)
     * - value = the attributes of that class that should be taken into account
     * (inclusive or exclusive fractional replication) (name or OID of the
     * attribute)
     * When an operation coming from the network is to be locally replayed, if
     * the concerned entry has an objectClass attribute equals to 'key':
     * - inclusive mode: only the attributes in 'value' will be added/deleted/
     * modified
     * - exclusive mode: the attributes in 'value' will not be added/deleted/
     * modified
     */
    private Map<String, Set<String>> fractionalSpecificClassesAttributes =
        new HashMap<String, Set<String>>();

    /**
     * Used in fractional replication: holds attributes of any object class.
     * When an operation coming from the network is to be locally replayed:
     * - inclusive mode: only attributes of the matching entry not present in
     * fractionalAllClassesAttributes will be added/deleted/modified
     * - exclusive mode: attributes of the matching entry present in
     * fractionalAllClassesAttributes will not be added/deleted/modified
     * The attributes may be in human readable form of OID form.
     */
    private Set<String> fractionalAllClassesAttributes = new HashSet<String>();

    /**
     * Base DN the fractional configuration is for.
     */
    private DN baseDN;

    /**
     * Constructs a new fractional configuration object.
     * @param baseDN The base DN the object is for.
     */
    private FractionalConfig(DN baseDN)
    {
      this.baseDN = baseDN;
    }

    /**
     * Getter for fractional.
     * @return True if the configuration has fractional enabled
     */
    boolean isFractional()
    {
      return fractional;
    }

    /**
     * Set the fractional parameter.
     * @param fractional The fractional parameter
     */
    private void setFractional(boolean fractional)
    {
      this.fractional = fractional;
    }

    /**
     * Getter for fractionalExclusive.
     * @return True if the configuration has fractional exclusive enabled
     */
    boolean isFractionalExclusive()
    {
      return fractionalExclusive;
    }

    /**
     * Set the fractionalExclusive parameter.
     * @param fractionalExclusive The fractionalExclusive parameter
     */
    private void setFractionalExclusive(boolean fractionalExclusive)
    {
      this.fractionalExclusive = fractionalExclusive;
    }

    /**
     * Getter for fractionalSpecificClassesAttributes attribute.
     * @return The fractionalSpecificClassesAttributes attribute.
     */
    Map<String, Set<String>> getFractionalSpecificClassesAttributes()
    {
      return fractionalSpecificClassesAttributes;
    }

    /**
     * Set the fractionalSpecificClassesAttributes parameter.
     * @param fractionalSpecificClassesAttributes The
     * fractionalSpecificClassesAttributes parameter to set.
     */
    private void setFractionalSpecificClassesAttributes(
        Map<String, Set<String>> fractionalSpecificClassesAttributes)
    {
      this.fractionalSpecificClassesAttributes =
        fractionalSpecificClassesAttributes;
    }

    /**
     * Getter for fractionalSpecificClassesAttributes attribute.
     * @return The fractionalSpecificClassesAttributes attribute.
     */
    Set<String> getFractionalAllClassesAttributes()
    {
      return fractionalAllClassesAttributes;
    }

    /**
     * Set the fractionalAllClassesAttributes parameter.
     * @param fractionalAllClassesAttributes The
     * fractionalSpecificClassesAttributes parameter to set.
     */
    private void setFractionalAllClassesAttributes(
        Set<String> fractionalAllClassesAttributes)
    {
      this.fractionalAllClassesAttributes = fractionalAllClassesAttributes;
    }

    /**
     * Getter for the base baseDN.
     * @return The baseDN attribute.
     */
    DN getBaseDn()
    {
      return baseDN;
    }

    /**
     * Extract the fractional configuration from the passed domain configuration
     * entry.
     * @param configuration The configuration object
     * @return The fractional replication configuration.
     * @throws ConfigException If an error occurred.
     */
    static FractionalConfig toFractionalConfig(
      ReplicationDomainCfg configuration) throws ConfigException
    {
      // Prepare fractional configuration variables to parse
      Iterator<String> exclIt = configuration.getFractionalExclude().iterator();
      Iterator<String> inclIt = configuration.getFractionalInclude().iterator();

      // Get potentially new fractional configuration
      Map<String, Set<String>> newFractionalSpecificClassesAttributes =
          new HashMap<String, Set<String>>();
      Set<String> newFractionalAllClassesAttributes = new HashSet<String>();

      int newFractionalMode = parseFractionalConfig(exclIt, inclIt,
        newFractionalSpecificClassesAttributes,
        newFractionalAllClassesAttributes);

      // Create matching parsed config object
      FractionalConfig result = new FractionalConfig(configuration.getBaseDN());
      switch (newFractionalMode)
      {
        case NOT_FRACTIONAL:
          result.setFractional(false);
          result.setFractionalExclusive(true);
          break;
        case EXCLUSIVE_FRACTIONAL:
        case INCLUSIVE_FRACTIONAL:
          result.setFractional(true);
          result.setFractionalExclusive(
              newFractionalMode == EXCLUSIVE_FRACTIONAL);
          break;
      }
      result.setFractionalSpecificClassesAttributes(
        newFractionalSpecificClassesAttributes);
      result.setFractionalAllClassesAttributes(
        newFractionalAllClassesAttributes);
      return result;
    }

    /**
     * Parses a fractional replication configuration, filling the empty passed
     * variables and returning the used fractional mode. The 2 passed variables
     * to fill should be initialized (not null) and empty.
     * @param exclIt The list of fractional exclude configuration values (may be
     *               null)
     * @param inclIt The list of fractional include configuration values (may be
     *               null)
     * @param fractionalSpecificClassesAttributes An empty map to be filled with
     *        what is read from the fractional configuration properties.
     * @param fractionalAllClassesAttributes An empty list to be filled with
     *        what is read from the fractional configuration properties.
     * @return the fractional mode deduced from the passed configuration:
     *         not fractional, exclusive fractional or inclusive fractional
     *         modes
     */
    private static int parseFractionalConfig (
      Iterator<String> exclIt, Iterator<String> inclIt,
      Map<String, Set<String>> fractionalSpecificClassesAttributes,
      Set<String> fractionalAllClassesAttributes) throws ConfigException
    {
      // Determine if fractional-exclude or fractional-include property is used:
      // only one of them is allowed
      int fractionalMode;
      Iterator<String> iterator;
      if (exclIt != null && exclIt.hasNext())
      {
        if (inclIt != null && inclIt.hasNext())
        {
          throw new ConfigException(
            NOTE_ERR_FRACTIONAL_CONFIG_BOTH_MODES.get());
        }

        fractionalMode = EXCLUSIVE_FRACTIONAL;
        iterator = exclIt;
      }
      else
      {
        if (inclIt != null && inclIt.hasNext())
        {
          fractionalMode = INCLUSIVE_FRACTIONAL;
          iterator = inclIt;
        }
        else
        {
          return NOT_FRACTIONAL;
        }
      }

      while (iterator.hasNext())
      {
        // Parse a value with the form class:attr1,attr2...
        // or *:attr1,attr2...
        String fractCfgStr = iterator.next();
        StringTokenizer st = new StringTokenizer(fractCfgStr, ":");
        int nTokens = st.countTokens();
        if (nTokens < 2)
        {
          throw new ConfigException(NOTE_ERR_FRACTIONAL_CONFIG_WRONG_FORMAT.
            get(fractCfgStr));
        }
        // Get the class name
        String classNameLower = st.nextToken().toLowerCase();
        boolean allClasses = "*".equals(classNameLower);
        // Get the attributes
        String attributes = st.nextToken();
        st = new StringTokenizer(attributes, ",");
        while (st.hasMoreTokens())
        {
          String attrNameLower = st.nextToken().toLowerCase();
          // Store attribute in the appropriate variable
          if (allClasses)
          {
            fractionalAllClassesAttributes.add(attrNameLower);
          }
          else
          {
            Set<String> attrList =
                fractionalSpecificClassesAttributes.get(classNameLower);
            if (attrList == null)
            {
              attrList = new LinkedHashSet<String>();
              fractionalSpecificClassesAttributes.put(classNameLower, attrList);
            }
            attrList.add(attrNameLower);
          }
        }
      }
      return fractionalMode;
    }

    /** Return type of the parseFractionalConfig method. */
    private static final int NOT_FRACTIONAL = 0;
    private static final int EXCLUSIVE_FRACTIONAL = 1;
    private static final int INCLUSIVE_FRACTIONAL = 2;

    /**
     * Get an integer representation of the domain fractional configuration.
     * @return An integer representation of the domain fractional configuration.
     */
    int fractionalConfigToInt()
    {
      if (!fractional)
        return NOT_FRACTIONAL;
      if (fractionalExclusive)
        return EXCLUSIVE_FRACTIONAL;
      return INCLUSIVE_FRACTIONAL;
    }

    /**
     * Compare 2 fractional replication configurations and returns true if they
     * are equivalent.
     * @param cfg1 First fractional configuration
     * @param cfg2 Second fractional configuration
     * @return True if both configurations are equivalent.
     * @throws ConfigException If some classes or attributes could not be
     * retrieved from the schema.
     */
    static boolean isFractionalConfigEquivalent(FractionalConfig cfg1,
        FractionalConfig cfg2) throws ConfigException
    {
      // Compare base DNs just to be consistent
      if (!cfg1.getBaseDn().equals(cfg2.getBaseDn()))
        return false;

      // Compare modes
      if (cfg1.isFractional() != cfg2.isFractional()
          || cfg1.isFractionalExclusive() != cfg2.isFractionalExclusive())
        return false;

      // Compare all classes attributes
      Set<String> allClassesAttrs1 = cfg1.getFractionalAllClassesAttributes();
      Set<String> allClassesAttrs2 = cfg2.getFractionalAllClassesAttributes();
      if (!areAttributesEquivalent(allClassesAttrs1, allClassesAttrs2))
        return false;

      // Compare specific classes attributes
      Map<String, Set<String>> specificClassesAttrs1 =
          cfg1.getFractionalSpecificClassesAttributes();
      Map<String, Set<String>> specificClassesAttrs2 =
          cfg2.getFractionalSpecificClassesAttributes();
      if (specificClassesAttrs1.size() != specificClassesAttrs2.size())
        return false;

      /*
       * Check consistency of specific classes attributes
       *
       * For each class in specificClassesAttributes1, check that the attribute
       * list is equivalent to specificClassesAttributes2 attribute list
       */
      Schema schema = DirectoryServer.getSchema();
      for (String className1 : specificClassesAttrs1.keySet())
      {
        // Get class from specificClassesAttributes1
        ObjectClass objectClass1 = schema.getObjectClass(className1);
        if (objectClass1 == null)
        {
          throw new ConfigException(
            NOTE_ERR_FRACTIONAL_CONFIG_UNKNOWN_OBJECT_CLASS.get(className1));
        }

        // Look for matching one in specificClassesAttributes2
        boolean foundClass = false;
        for (String className2 : specificClassesAttrs2.keySet())
        {
          ObjectClass objectClass2 = schema.getObjectClass(className2);
          if (objectClass2 == null)
          {
            throw new ConfigException(
              NOTE_ERR_FRACTIONAL_CONFIG_UNKNOWN_OBJECT_CLASS.get(className2));
          }
          if (objectClass1.equals(objectClass2))
          {
            foundClass = true;
            // Now compare the 2 attribute lists
            Set<String> attributes1 = specificClassesAttrs1.get(className1);
            Set<String> attributes2 = specificClassesAttrs2.get(className2);
            if (!areAttributesEquivalent(attributes1, attributes2))
              return false;
            break;
          }
        }
        // Found matching class ?
        if (!foundClass)
          return false;
      }

      return true;
    }
  }

  /**
   * Specifies whether this domain is enabled/disabled regarding the ECL.
   * @return enabled/disabled for the ECL.
   */
  boolean isECLEnabled()
  {
    return this.eclDomain.isEnabled();
  }

  /**
   * Return the minimum time (in ms) that the domain keeps the historical
   * information necessary to solve conflicts.
   *
   * @return the purge delay.
   */
  long getHistoricalPurgeDelay()
  {
    return config.getConflictsHistoricalPurgeDelay() * 60 * 1000;
  }

  /**
   * Check if the operation that just happened has cleared a conflict : Clearing
   * a conflict happens if the operation has freed a DN for which another entry
   * was in conflict.
   * <p>
   * Steps:
   * <ul>
   * <li>get the DN freed by a DELETE or MODRDN op</li>
   * <li>search for entries put in the conflict space (dn=entryUUID'+'....)
   * because the expected DN was not available (ds-sync-conflict=expected DN)
   * </li>
   * <li>retain the entry with the oldest conflict</li>
   * <li>rename this entry with the freedDN as it was expected originally</li>
   * </ul>
   *
   * @param task
   *          the task raising this purge.
   * @param endDate
   *          the date to stop this task whether the job is done or not.
   * @throws DirectoryException
   *           when an exception happens.
   */
  public void purgeConflictsHistorical(PurgeConflictsHistoricalTask task,
      long endDate) throws DirectoryException
  {
     TRACER.debugInfo("[PURGE] purgeConflictsHistorical "
         + "on domain: " + getBaseDNString()
         + "endDate:" + new Date(endDate)
         + "lastCSNPurgedFromHist: "
         + lastCSNPurgedFromHist.toStringUI());

     LDAPFilter filter = null;
     try
     {
       filter = LDAPFilter.decode(
         "(" + EntryHistorical.HISTORICAL_ATTRIBUTE_NAME + ">=dummy:"
         + lastCSNPurgedFromHist + ")");

     } catch (LDAPException e)
     {
       // Not possible. We know the filter just above is correct.
     }

     InternalSearchOperation searchOp = conn.processSearch(
         ByteString.valueOf(getBaseDNString()),
         SearchScope.WHOLE_SUBTREE,
         DereferencePolicy.NEVER_DEREF_ALIASES,
         0, 0, false, filter,
         USER_AND_REPL_OPERATIONAL_ATTRS, null);

     int count = 0;

     if (task != null)
       task.setProgressStats(lastCSNPurgedFromHist, count);

     for (SearchResultEntry entry : searchOp.getSearchEntries())
     {
       long maxTimeToRun = endDate - TimeThread.getTime();
       if (maxTimeToRun < 0)
       {
        throw new DirectoryException(ResultCode.ADMIN_LIMIT_EXCEEDED,
            Message.raw(Category.SYNC, Severity.NOTICE, " end date reached"));
       }

       EntryHistorical entryHist = EntryHistorical.newInstanceFromEntry(entry);
       lastCSNPurgedFromHist = entryHist.getOldestCSN();
       entryHist.setPurgeDelay(getHistoricalPurgeDelay());
       Attribute attr = entryHist.encodeAndPurge();
       count += entryHist.getLastPurgedValuesCount();
       List<Modification> mods = new LinkedList<Modification>();
       mods.add(new Modification(ModificationType.REPLACE, attr));

       ModifyOperation newOp = new ModifyOperationBasis(
             conn, InternalClientConnection.nextOperationID(),
             InternalClientConnection.nextMessageID(),
             new ArrayList<Control>(0),
             entry.getName(),
             mods);
      runAsSynchronizedOperation(newOp);

       if (newOp.getResultCode() != ResultCode.SUCCESS)
       {
         // Log information for the repair tool.
         MessageBuilder mb = new MessageBuilder();
         mb.append(ERR_CANNOT_ADD_CONFLICT_ATTRIBUTE.get());
         mb.append(String.valueOf(newOp));
         mb.append(" ");
         mb.append(String.valueOf(newOp.getResultCode()));
         logError(mb.toMessage());
       }
       else if (task != null)
       {
         task.setProgressStats(lastCSNPurgedFromHist, count);
       }
     }
  }

}
