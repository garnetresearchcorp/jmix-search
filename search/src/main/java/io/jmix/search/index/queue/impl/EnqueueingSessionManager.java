/*
 * Copyright 2021 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.jmix.search.index.queue.impl;

import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.search.index.impl.IndexingLocker;
import io.jmix.search.index.mapping.IndexConfigurationManager;
import io.jmix.search.index.queue.entity.EnqueueingSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component("search_EnqueueingSessionManager")
public class EnqueueingSessionManager {

    private static final Logger log = LoggerFactory.getLogger(EnqueueingSessionManager.class);

    @Autowired
    protected DataManager dataManager;
    @Autowired
    protected Metadata metadata;
    @Autowired
    protected MetadataTools metadataTools;
    @Autowired
    protected IndexConfigurationManager indexConfigurationManager;
    @Autowired
    protected IndexingLocker locker;

    /**
     * Initializes session for provided entity.
     * New session will be created if it doesn't exist.
     * Existing session with {@link EnqueueingSessionAction#STOP} action will be reset to initial state.
     *
     * @param entityName entity name
     * @return true if operation was successfully performed, false otherwise
     */
    public boolean initSession(String entityName) {
        return initSession(entityName, false);
    }

    /**
     * Initializes session for provided entity.
     * New session will be created if it doesn't exist.
     * Existing session will be reset to initial state if it has {@link EnqueueingSessionAction#STOP} action
     * or 'restart' flag is set to true.
     *
     * @param entityName entity name
     * @param restart    restarts existing session if true - sets ordering value to null
     * @return true if operation was successfully performed, false otherwise
     */
    public boolean initSession(String entityName, boolean restart) {
        return executeManagementAction(entityName, 10000, () -> {
            EnqueueingSession existingSession = getSession(entityName);
            EnqueueingSession effectiveSession;
            if (existingSession != null) {
                if (restart || EnqueueingSessionAction.STOP.equals(existingSession.getAction())) {
                    effectiveSession = existingSession;
                } else {
                    return true;
                }
            } else {
                effectiveSession = metadata.create(EnqueueingSession.class);
            }

            MetaClass entityClass = metadata.getClass(entityName);
            MetaProperty orderingProperty = resolveOrderingProperty(entityClass);

            effectiveSession.setEntityName(entityName);
            effectiveSession.setAction(EnqueueingSessionAction.EXECUTE);
            effectiveSession.setOrderingProperty(orderingProperty.getName());
            effectiveSession.setLastProcessedValue(null);

            dataManager.save(effectiveSession);
            return true;
        });
    }

    /**
     * Prevents session from being executed.
     *
     * @param entityName entity name
     * @return true if operation was successfully performed, false otherwise
     */
    public boolean suspendSession(String entityName) {
        return executeManagementAction(entityName, 10000, () -> {
            EnqueueingSession session = getSession(entityName);
            if (session != null) {
                if (EnqueueingSessionAction.SKIP.equals(session.getAction())) {
                    return true;
                } else if (EnqueueingSessionAction.EXECUTE.equals(session.getAction())) {
                    session.setAction(EnqueueingSessionAction.SKIP);
                    dataManager.save(session);
                    return true;
                } else {
                    return false;
                }
            }
            return false;
        });
    }

    /**
     * Resumes previously suspended session
     *
     * @param entityName entity name
     * @return true if operation was successfully performed, false otherwise
     */
    public boolean resumeSession(String entityName) {
        return executeManagementAction(entityName, 10000, () -> {
            EnqueueingSession session = getSession(entityName);
            if (session != null) {
                if (EnqueueingSessionAction.EXECUTE.equals(session.getAction())) {
                    return true;
                } else if (EnqueueingSessionAction.SKIP.equals(session.getAction())) {
                    session.setAction(EnqueueingSessionAction.EXECUTE);
                    dataManager.save(session);
                    return true;
                } else {
                    return false;
                }
            }
            return false;
        });
    }

    /**
     * Marks session as terminated. It can't be executed, suspended or resumed. It can only be restarted or removed.
     *
     * @param entityName entity name
     * @return true if operation was successfully performed, false otherwise
     */
    public boolean stopSession(String entityName) {
        return executeManagementAction(entityName, 10000, () -> {
            EnqueueingSession session = getSession(entityName);
            if (session != null) {
                if (!EnqueueingSessionAction.STOP.equals(session.getAction())) {
                    session.setAction(EnqueueingSessionAction.STOP);
                    dataManager.save(session);
                }
                return true;
            }
            return false;
        });
    }

    /**
     * Removes provided session.
     *
     * @param session session
     * @return true if operation was successfully performed, false otherwise
     */
    public boolean removeSession(EnqueueingSession session) {
        String entityName = session.getEntityName();
        return executeManagementAction(entityName, 10000, () -> {
            dataManager.remove(session);
            return true;
        });
    }

    /**
     * Gets session for provided entity.
     *
     * @param entityName entity name
     * @return existing session or null if it doesn't exist
     */
    @Nullable
    public EnqueueingSession getSession(String entityName) {
        if (indexConfigurationManager.isDirectlyIndexed(entityName)) {
            Optional<EnqueueingSession> enqueueingSessionEntityOpt = loadEnqueueingSessionEntityByEntityName(entityName);
            return enqueueingSessionEntityOpt.orElse(null);
        } else {
            throw new IllegalArgumentException(
                    String.format("Unable to get enqueuing session for non-indexed entity '%s'", entityName)
            );
        }
    }

    /**
     * Gets next existing session. Ignores session with action {@link EnqueueingSessionAction#SKIP}
     *
     * @return some existing session or null if there are no sessions at all
     */
    @Nullable
    public EnqueueingSession getNextSession() {
        Optional<EnqueueingSession> session = dataManager.load(EnqueueingSession.class)
                .query("WHERE e.action <> :action ORDER BY e.createdDate ASC")
                .parameter("action", EnqueueingSessionAction.SKIP)
                .optional();
        return session.orElse(null);
    }

    /**
     * Updates provided session with provided ordering value.
     *
     * @param session           session
     * @param lastOrderingValue value
     */
    public void updateOrderingValue(EnqueueingSession session, @Nullable Object lastOrderingValue) {
        String entityName = session.getEntityName();
        executeManagementAction(entityName, 10000, () -> {
            EnqueueingSession internalSession = getSession(entityName);
            if (internalSession == null) {
                return false;
            }

            String rawOrderingValue;
            if (lastOrderingValue == null) {
                rawOrderingValue = null;
            } else {
                rawOrderingValue = convertOrderingValueToString(lastOrderingValue);
            }

            internalSession.setLastProcessedValue(rawOrderingValue);
            dataManager.save(internalSession);
            return true;
        });
    }

    protected Optional<EnqueueingSession> loadEnqueueingSessionEntityByEntityName(String entityName) {
        return dataManager.load(EnqueueingSession.class)
                .query("where e.entityName = ?1", entityName).optional();
    }

    protected MetaProperty resolveOrderingProperty(MetaClass entityClass) {
        if (metadataTools.hasCompositePrimaryKey(entityClass) && metadataTools.hasUuid(entityClass)) {
            String uuidPropertyName = metadataTools.getUuidPropertyName(entityClass.getJavaClass());
            if (uuidPropertyName == null) {
                throw new IllegalArgumentException("Expected UUID property is null");
            }
            return entityClass.getProperty(uuidPropertyName);
        }

        MetaProperty primaryKeyProperty = metadataTools.getPrimaryKeyProperty(entityClass);
        if (primaryKeyProperty == null) {
            throw new IllegalArgumentException(
                    String.format("Entity '%s' doesn't have primary key property", entityClass.getName())
            );
        }
        return primaryKeyProperty;
    }

    protected boolean executeManagementAction(String entityName, int lockTimeoutMs, SessionManagementAction action) {
        if (indexConfigurationManager.isDirectlyIndexed(entityName)) {
            log.debug("Try to lock enqueueing session for entity '{}'", entityName);
            if (!locker.tryLockEnqueueingSession(entityName, lockTimeoutMs, TimeUnit.MILLISECONDS)) {
                log.info("Unable to lock enqueuing session for entity '{}': session is locked", entityName);
                return false;
            }

            try {
                return action.execute();
            } finally {
                locker.unlockEnqueueingSession(entityName);
                log.debug("Unlock enqueueing session for entity '{}'", entityName);
            }
        } else {
            throw new IllegalArgumentException(
                    String.format("Unable to perform management action on enqueuing session: entity '%s' is not indexed", entityName)
            );
        }
    }

    protected String convertOrderingValueToString(Object orderingValue) {
        return orderingValue.toString();
    }

    protected interface SessionManagementAction {

        boolean execute();
    }
}
