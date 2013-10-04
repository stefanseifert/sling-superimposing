/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.superimposing.impl;

import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import javax.jcr.observation.EventListener;
import javax.jcr.query.Query;

import org.apache.commons.lang.StringUtils;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.apache.sling.superimposing.SuperimposingManager;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the resource registrations for the Superimposing Resource Provider.
 */
@Component(label = "Apache Sling Superimposing Resource Manager",
    description = "Manages the resource registrations for the Superimposing Resource Provider.",
    immediate = true, metatype = true)
@Service(SuperimposingManager.class)
public class SuperimposingManagerImpl implements SuperimposingManager, EventListener {

    /**
     * XPath query for all nodes marked as superimposed trees.
     */
    private static final String SUPERIMPOSE_QUERY = "//element(*, " + MIXIN_SUPERIMPOSE + ")";

    @Property(label = "Enabled", name = "enabled", description = "Enable/Disable the superimposing functionality.", boolValue = false)
    private boolean enabled;
    
    /**
     * Map for holding the superimposing mappings, with the superimpose path as key and the providers as values
     */
    private ConcurrentMap<String, SuperimposingResourceProvider> superimposingProviders = new ConcurrentHashMap<String, SuperimposingResourceProvider>();

    @Reference
    private ResourceResolverFactory resolverFactory;

    /**
     * Administrative resource resolver (read only usage)
     */
    private ResourceResolver resolver;

    /**
     * A reference to the initialization task. Needed to check if
     * initialization has completed.
     */
    private Future<?> initialization;

    /**
     * This bundle's context.
     */
    private BundleContext bundleContext;

    /**
     * The default logger
     */
    private static final Logger log = LoggerFactory.getLogger(SuperimposingManagerImpl.class);


    private Iterator<Resource> findeSuperimposings(ResourceResolver resolver) {
        return resolver.findResources(SUPERIMPOSE_QUERY, Query.XPATH);
    }

    private void registerAllSuperimposings() {
        log.debug("Start registering all superimposing trees...");
        final long start = System.currentTimeMillis();
        long countSuccess = 0;
        long countFailed = 0;

        final Iterator<Resource> superimposings = findeSuperimposings(resolver);
        while (superimposings.hasNext()) {
            final Resource superimposingResource = superimposings.next();
            boolean success = registerSuperimposing(superimposingResource);
            if (success) {
                countSuccess++;
            } else {
                countFailed++;
            }
        }

        final long time = System.currentTimeMillis() - start;
        log.info("Registered {} SuperimposingResourceProvider(s) in {} ms, skipping {} invalid one(s).", new Object[] {
                countSuccess, time, countFailed });
    }

    /**
     * @param superimposingResource
     * @return true if registration was done, false if skipped (already registered)
     * @throws RepositoryException
     */
    private boolean registerSuperimposing(Resource superimposingResource) {
        ValueMap props = ResourceUtil.getValueMap(superimposingResource);
        String superimposePath = superimposingResource.getPath();
        final String targetPath = props.get(PROP_SUPERIMPOSE_TARGET, String.class);
        final boolean registerParent = props.get(PROP_SUPERIMPOSE_REGISTER_PARENT, false);
        final boolean overlayable = props.get(PROP_SUPERIMPOSE_OVERLAYABLE, false);

        // check if superimposing definition is valid
        boolean valid = true;
        if (StringUtils.isBlank(targetPath)) {
            valid = false;
        }
        else {
            // check whether the parent of the symlink node should be registered as symlink source
            if (registerParent) {
                superimposePath = ResourceUtil.getParent(superimposePath);
            }
            // target path is not valid if it equals to a parent or child of the symlink path, or to the symlink path itself
            if (StringUtils.equals(targetPath, superimposePath)
                    || StringUtils.startsWith(targetPath, superimposePath + "/")
                    || StringUtils.startsWith(superimposePath, targetPath + "/")) {
                valid = false;
            }
        }

        // register valid symlink
        if (valid) {
            final SuperimposingResourceProvider srp = new SuperimposingResourceProvider(superimposePath, targetPath, overlayable);
            final SuperimposingResourceProvider oldSrp = superimposingProviders.put(superimposePath, srp);

            // unregister in case there was a provider registered before
            if (!srp.equals(oldSrp)) {
                log.debug("(Re-)registering resource provider {}.", superimposePath);
                if (null != oldSrp) {
                    oldSrp.unregisterService();
                }
                srp.registerService(bundleContext);
                return true;
            } else {
                log.debug("Skipped re-registering resource provider {} because there were no relevant changes.", superimposePath);
            }
        }

        // otherwise remove previous symlink resource provider if new symlink definition is not valid
        else {
            final SuperimposingResourceProvider oldSrp = superimposingProviders.remove(superimposePath);
            if (null != oldSrp) {
                log.debug("Unregistering resource provider {}.", superimposePath);
                oldSrp.unregisterService();
            }
            log.warn("Symlink '{}' pointing to '{}' is invalid.", superimposePath, targetPath);
        }

        return false;
    }

    private void registerSymlink(String path) {
        final Resource symlink = resolver.getResource(path);
        if (symlink != null) {
            registerSuperimposing(symlink);
        }
    }

    private void unregisterSymlink(String path) {
        final SuperimposingResourceProvider srp = superimposingProviders.remove(path);
        if (null != srp) {
            srp.unregisterService();
        }
    }

    // ---------- SCR Integration

    @Activate
    protected synchronized void activate(final ComponentContext ctx) throws LoginException, RepositoryException {
        
        // check enabled state
        @SuppressWarnings("unchecked")
        final Dictionary<String, Object> props = ctx.getProperties();
        this.enabled = PropertiesUtil.toBoolean(props.get("enabled"), false);
        log.debug("Config: " + "Enabled={} ", enabled);
        if (!isEnabled()) {
            return;
        }

        
        if (null == resolver) {
            bundleContext = ctx.getBundleContext();
            resolver = resolverFactory.getAdministrativeResourceResolver(null);

            // Watch for events on the root - that might be one of our root
            // folders
            final Session session = resolver.adaptTo(Session.class);
            session.getWorkspace()
                    .getObservationManager()
                    .addEventListener(
                            this,
                            Event.NODE_ADDED | Event.NODE_REMOVED | Event.PROPERTY_ADDED | Event.PROPERTY_CHANGED
                                    | Event.PROPERTY_REMOVED, "/", true, // isDeep
                            null, // uuids
                            null, // node types
                            true); // noLocal

            initialization = Executors.newSingleThreadExecutor().submit(new Runnable() {
                public void run() {
                    registerAllSuperimposings();
                }
            });
        }
    }

    @Deactivate
    protected synchronized void deactivate(final ComponentContext ctx) throws RepositoryException {
        try {
            // make sure initialization has finished
            if (null != initialization && !initialization.isDone()) {
                initialization.cancel(/* myInterruptIfRunning */ true);
            }

            for (final SuperimposingResourceProvider srp : superimposingProviders.values()) {
                srp.unregisterService();
            }

            if (null != resolver) {
                final Session session = resolver.adaptTo(Session.class);
                session.getWorkspace().getObservationManager().removeEventListener(this);
            }
        } finally {
            if (null != resolver) {
                resolver.close();
                resolver = null;
            }
            initialization = null;
            superimposingProviders.clear();
        }
    }

    // ---------- EventListener

    public void onEvent(EventIterator eventIterator) {
        if (isEnabled()) {
            try {
                // collect all actions to be performed for this event
                final Map<String, Boolean> actions = new HashMap<String, Boolean>();
                boolean nodeAdded = false;
                boolean nodeRemoved = false;
                while (eventIterator.hasNext()) {
                    final Event event = eventIterator.nextEvent();
                    final String path = event.getPath();
                    final String name = ResourceUtil.getName(path);
                    if (event.getType() == Event.NODE_ADDED) {
                        nodeAdded = true;
                    } else if (event.getType() == Event.NODE_REMOVED && superimposingProviders.containsKey(path)) {
                        nodeRemoved = true;
                        actions.put(path, false);
                    } else if (StringUtils.equals(name, PROP_SUPERIMPOSE_TARGET)
                            || StringUtils.equals(name, PROP_SUPERIMPOSE_REGISTER_PARENT)
                            || StringUtils.equals(name, PROP_SUPERIMPOSE_OVERLAYABLE)) {
                        final String nodePath = ResourceUtil.getParent(path);
                        actions.put(nodePath, true);
                    }

                }

                // execute all collected actions (having this outside the above
                // loop prevents repeated registrations within one transaction
                // but allows for several symlinks to be added within a single
                // transaction)
                for (Map.Entry<String, Boolean> action : actions.entrySet()) {
                    if (action.getValue()) {
                        registerSymlink(action.getKey());
                    } else {
                        unregisterSymlink(action.getKey());
                    }
                }

                if (nodeAdded && nodeRemoved) {
                    // maybe a symlink was moved, re-register all symlinks
                    // (existing
                    // ones will be skipped)
                    registerAllSuperimposings();
                }
            } catch (RepositoryException e) {
                log.error("Unexpected repository exception during event processing.");
            }
        }
    }

    /**
     * @return true if superimposing mode is enabled
     */
    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * @return Immutable map with all superimposing resource providers currently registered
     */
    public Map<String, SuperimposingResourceProvider> getRegisteredSymlinkProviders() {
        return Collections.unmodifiableMap(superimposingProviders);
    }
    
}
