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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.collections.IteratorUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.SlingConstants;
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
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the resource registrations for the {@link SuperimposingResourceProvider}.
 * Provides read-only access to all registered providers.
 */
@Component(label = "Apache Sling Superimposing Resource Manager",
    description = "Manages the resource registrations for the Superimposing Resource Provider.",
    immediate = true, metatype = true)
@Service(SuperimposingManager.class)
@Property(name=EventConstants.EVENT_TOPIC, value={SlingConstants.TOPIC_RESOURCE_ADDED,SlingConstants.TOPIC_RESOURCE_CHANGED,SlingConstants.TOPIC_RESOURCE_REMOVED})
public class SuperimposingManagerImpl implements SuperimposingManager, EventHandler {

    @Property(label = "Enabled", description = "Enable/Disable the superimposing functionality.", boolValue = SuperimposingManagerImpl.ENABLED_DEFAULT)
    private static final String ENABLED_PROPERTY = "enabled";
    private static final boolean ENABLED_DEFAULT = false;
    private boolean enabled;
    
    @Property(label = "Find all Queries", description = "List of query expressions to find all existing superimposing registrations on service startup. "
            + "Query syntax is depending on underlying resource provdider implementation. Prepend the query with query syntax name separated by \"|\".",
            value={SuperimposingManagerImpl.FINDALLQUERIES_DEFAULT}, cardinality=Integer.MAX_VALUE)
    private static final String FINDALLQUERIES_PROPERTY = "findAllQueries";
    private static final String FINDALLQUERIES_DEFAULT = "xpath|//element(*, " + MIXIN_SUPERIMPOSE + ")";
    private String[] findAllQueries;
    
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


    /**
     * Find all existing superimposing registrations using all query defined in service configuration.
     * @param resolver Resource resolver
     * @return All superimposing registrations
     */
    @SuppressWarnings("unchecked")
    private List<Resource> findeSuperimposings(ResourceResolver resolver) {
        List<Resource> allResources = new ArrayList<Resource>();
        for (String queryString : this.findAllQueries) {
            if (!StringUtils.contains(queryString, "|")) {
                throw new IllegalArgumentException("Query string does not contain query syntax seperated by '|': " + queryString);
            }
            String queryLanguage = StringUtils.substringBefore(queryString, "|");
            String query = StringUtils.substringAfter(queryString, "|");
            allResources.addAll(IteratorUtils.toList(resolver.findResources(query, queryLanguage)));
        }
        return allResources;
    }

    private void registerAllSuperimposings() {
        log.debug("Start registering all superimposing trees...");
        final long start = System.currentTimeMillis();
        long countSuccess = 0;
        long countFailed = 0;

        final List<Resource> existingSuperimposings = findeSuperimposings(resolver);
        for (Resource superimposingResource : existingSuperimposings) {
            boolean success = registerProvider(superimposingResource);
            if (success) {
                countSuccess++;
            } else {
                countFailed++;
            }
        }

        final long time = System.currentTimeMillis() - start;
        log.info("Registered {} SuperimposingResourceProvider(s) in {} ms, skipping {} invalid one(s).",
                new Object[] { countSuccess, time, countFailed });
    }

    /**
     * @param superimposingResource
     * @return true if registration was done, false if skipped (already registered)
     * @throws RepositoryException
     */
    private boolean registerProvider(Resource superimposingResource) {
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
            // check whether the parent of the node should be registered as superimposing provider
            if (registerParent) {
                superimposePath = ResourceUtil.getParent(superimposePath);
            }
            // target path is not valid if it equals to a parent or child of the superimposing path, or to the superimposing path itself
            if (StringUtils.equals(targetPath, superimposePath)
                    || StringUtils.startsWith(targetPath, superimposePath + "/")
                    || StringUtils.startsWith(superimposePath, targetPath + "/")) {
                valid = false;
            }
        }

        // register valid superimposing
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

        // otherwise remove previous superimposing resource provider if new superimposing definition is not valid
        else {
            final SuperimposingResourceProvider oldSrp = superimposingProviders.remove(superimposePath);
            if (null != oldSrp) {
                log.debug("Unregistering resource provider {}.", superimposePath);
                oldSrp.unregisterService();
            }
            log.warn("Superimposing definition '{}' pointing to '{}' is invalid.", superimposePath, targetPath);
        }

        return false;
    }

    private void registerProvider(String path) {
        final Resource provider = resolver.getResource(path);
        if (provider != null) {
            registerProvider(provider);
        }
    }

    private void unregisterProvider(String path) {
        final SuperimposingResourceProvider srp = superimposingProviders.remove(path);
        if (null != srp) {
            srp.unregisterService();
        }
    }

    // ---------- SCR Integration

    @Activate
    protected synchronized void activate(final ComponentContext ctx) throws LoginException {
        
        // check enabled state
        @SuppressWarnings("unchecked")
        final Dictionary<String, Object> props = ctx.getProperties();
        this.enabled = PropertiesUtil.toBoolean(props.get(ENABLED_PROPERTY), ENABLED_DEFAULT);
        log.debug("Config: " + "Enabled={} ", enabled);
        if (!isEnabled()) {
            return;
        }
        
        // get "find all" queries
        this.findAllQueries = PropertiesUtil.toStringArray(FINDALLQUERIES_PROPERTY, new String[] { FINDALLQUERIES_DEFAULT });
        
        if (null == resolver) {
            bundleContext = ctx.getBundleContext();
            resolver = resolverFactory.getAdministrativeResourceResolver(null);

            initialization = Executors.newSingleThreadExecutor().submit(new Runnable() {
                public void run() {
                    registerAllSuperimposings();
                }
            });
        }
    }

    @Deactivate
    protected synchronized void deactivate(final ComponentContext ctx) {
        try {
            // make sure initialization has finished
            if (null != initialization && !initialization.isDone()) {
                initialization.cancel(/* myInterruptIfRunning */ true);
            }

            for (final SuperimposingResourceProvider srp : superimposingProviders.values()) {
                srp.unregisterService();
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

    /**
     * Handle resource events to add or remove superimposing registrations
     */
    public void handleEvent(Event event) {
        if (!isEnabled()) {
            return;
        }
        String path = (String)event.getProperty(SlingConstants.PROPERTY_PATH);
        String topic = event.getTopic();
        if (StringUtils.equals(SlingConstants.TOPIC_RESOURCE_ADDED, topic)
                || StringUtils.equals(SlingConstants.TOPIC_RESOURCE_CHANGED, topic)) {
            registerProvider(path);
        }
        else if (StringUtils.equals(SlingConstants.TOPIC_RESOURCE_REMOVED, topic)) {
            unregisterProvider(path);
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
    public Map<String, SuperimposingResourceProvider> getRegisteredProviders() {
        return Collections.unmodifiableMap(superimposingProviders);
    }
    
}
