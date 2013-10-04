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
package org.apache.sling.symlinks.impl;

import java.util.Collections;
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
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SymlinkManager ...
 */
@Component(label = "Apache Sling Symlink Manager", immediate = true, policy = ConfigurationPolicy.IGNORE)
public class SymlinkManager implements EventListener {

    /**
     * default log
     */
    private static final Logger log = LoggerFactory.getLogger(SymlinkManager.class);

    /**
     * Mixin for symlinks.
     */
    private static final String MIXIN_SYMLINK = "sling:Symlink";

    /**
     * XPath query for all nodes marked as symlink.
     */
    private static final String SYMLINK_QUERY = "//element(*, " + MIXIN_SYMLINK + ")";

    /**
     * Property pointing to an absolute or relative repository path, which
     * this symlink points to.
     */
    private static final String PROP_SYMLINK_TARGET = "sling:symlinkTarget";

    /**
     * Property indicating if the node itself is used as root for the symlink (default),
     * of it it's parent should be used. The latter is useful in a Page/PageContent scenario
     * where the mixin cannot be added on the parent node itself.
     */
    private static final String PROP_SYMLINK_REGISTER_PARENT = "sling:symlinkRegisterParent";

    /**
     * Property indicating whether this symlink allows the symlinked content
     * to be overlayed by real nodes created below the symlink node.
     * Default value is false.
     */
    private static final String PROP_SYMLINK_OVERLAYABLE = "sling:symlinkOverlayable";

    /**
     * Map for holding the symlink mappings, with the symlink path as key and
     * the SymlinkResourceProvider
     */
    private ConcurrentMap<String, SymlinkResourceProvider> symlinkProviders =
            new ConcurrentHashMap<String, SymlinkResourceProvider>();

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private SymlinkConfig symlinkConfig;

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


    private Iterator<Resource> findSymlinks(ResourceResolver resolver) {
        return resolver.findResources(SYMLINK_QUERY, Query.XPATH);
    }

    private void registerAllSymlinks() {
        log.debug("Start registering all symlinks...");
        final long start = System.currentTimeMillis();
        long countSuccess = 0;
        long countFailed = 0;

        final Iterator<Resource> symlinks = findSymlinks(resolver);
        while (symlinks.hasNext()) {
            final Resource symlink = symlinks.next();
            try {
                boolean success = registerSymlink(symlink);
                if (success) {
                    countSuccess++;
                } else {
                    countFailed++;
                }
            } catch (RepositoryException e) {
                log.error("Unexpected repository exception while registering symlink: ", e);
            }
        }

        final long time = System.currentTimeMillis() - start;
        log.info("Registered {} SymlinkResourceProvider(s) in {} ms, skipping {} invalid one(s).", new Object[] {
                countSuccess, time, countFailed });
    }

    /**
     * @param symlink
     * @return true if registration was done, false if skipped (already registered)
     * @throws RepositoryException
     */
    private boolean registerSymlink(Resource symlink) throws RepositoryException {
        ValueMap props = ResourceUtil.getValueMap(symlink);
        String symlinkPath = symlink.getPath();
        final String targetPath = props.get(PROP_SYMLINK_TARGET, String.class);
        final boolean registerParent = props.get(PROP_SYMLINK_REGISTER_PARENT, false);
        final boolean overlayable = props.get(PROP_SYMLINK_OVERLAYABLE, false);

        // check if symlink definition is valid
        boolean valid = true;
        if (StringUtils.isBlank(targetPath)) {
            valid = false;
        }
        else {
            // check whether the parent of the symlink node should be registered as symlink source
            if (registerParent) {
                symlinkPath = ResourceUtil.getParent(symlinkPath);
            }
            // target path is not valid if it equals to a parent or child of the symlink path, or to the symlink path itself
            if (StringUtils.equals(targetPath, symlinkPath)
                    || StringUtils.startsWith(targetPath, symlinkPath + "/")
                    || StringUtils.startsWith(symlinkPath, targetPath + "/")) {
                valid = false;
            }
        }

        // register valid symlink
        if (valid) {
            final SymlinkResourceProvider srp =
                    new SymlinkResourceProvider(symlinkPath, targetPath, overlayable);
            final SymlinkResourceProvider oldSrp = symlinkProviders.put(symlinkPath, srp);

            // unregister in case there was a provider registered before
            if (!srp.equals(oldSrp)) {
                log.debug("(Re-)registering resource provider {}.", symlinkPath);
                if (null != oldSrp) {
                    oldSrp.unregisterService();
                }
                srp.registerService(bundleContext);
                return true;
            } else {
                log.debug("Skipped re-registering resource provider {} because there were no relevant changes.", symlinkPath);
            }
        }

        // otherwise remove previous symlink resource provider if new symlink definition is not valid
        else {
            final SymlinkResourceProvider oldSrp = symlinkProviders.remove(symlinkPath);
            if (null != oldSrp) {
                log.debug("Unregistering resource provider {}.", symlinkPath);
                oldSrp.unregisterService();
            }
            log.warn("Symlink '{}' pointing to '{}' is invalid.", symlinkPath, targetPath);
        }

        return false;
    }

    private void registerSymlink(String path) {
        try {
            final Resource symlink = resolver.getResource(path);
            if (symlink != null) {
                registerSymlink(symlink);
            }
        } catch (RepositoryException e) {
            log.error("Unexpected repository exception: ", e);
        }
    }

    private void unregisterSymlink(String path) {
        final SymlinkResourceProvider srp = symlinkProviders.remove(path);
        if (null != srp) {
            srp.unregisterService();
        }
    }

    // ---------- SCR Integration

    @Activate
    protected synchronized void activate(final ComponentContext ctx) throws LoginException, RepositoryException {
        if (isEnabled()) {
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
                        registerAllSymlinks();
                    }
                });
            }
        }
    }

    @Deactivate
    protected synchronized void deactivate(final ComponentContext ctx) throws RepositoryException {
        try {
            // make sure initialization has finished
            if (null != initialization && !initialization.isDone()) {
                initialization.cancel(/* myInterruptIfRunning */ true);
            }

            for (final SymlinkResourceProvider srp : symlinkProviders.values()) {
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
            symlinkProviders.clear();
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
                    } else if (event.getType() == Event.NODE_REMOVED && symlinkProviders.containsKey(path)) {
                        nodeRemoved = true;
                        actions.put(path, false);
                    } else if (StringUtils.equals(name, PROP_SYMLINK_TARGET)
                            || StringUtils.equals(name, PROP_SYMLINK_REGISTER_PARENT)
                            || StringUtils.equals(name, PROP_SYMLINK_OVERLAYABLE)) {
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
                    registerAllSymlinks();
                }
            } catch (RepositoryException e) {
                log.error("Unexpected repository exception during event processing.");
            }
        }
    }

    /**
     * @return true if symlink handling is enabled in configuration
     */
    public boolean isEnabled() {
        if (symlinkConfig != null) {
            return symlinkConfig.isEnabled();
        } else {
            log.info("No Symlink configuration found");
            return false;
        }
    }

    /**
     * @return Immutable map wit hall symlink providers currently registered
     */
    public Map<String, SymlinkResourceProvider> getRegisteredSymlinkProviders() {
        return Collections.unmodifiableMap(symlinkProviders);
    }
    
}
