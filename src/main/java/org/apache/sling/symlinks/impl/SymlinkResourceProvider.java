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

import org.apache.commons.lang.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceProvider;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.http.HttpServletRequest;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Iterator;

/**
 * SymlinkResourceProvider ...
 */
public class SymlinkResourceProvider implements ResourceProvider {
    /**
     * default log
     */
    private static final Logger log = LoggerFactory.getLogger(SymlinkResourceProvider.class);

    private final SymlinkManager symlinkManager;
    private final String rootPath;
    private final String rootPrefix;
    private final String targetPath;
    private final String targetPrefix;
    private final boolean overlayable;
    private ServiceRegistration registration;

    SymlinkResourceProvider(SymlinkManager symlinkManager, String rootPath, String targetPath, boolean overlayable) {
        this.symlinkManager = symlinkManager;
        this.rootPath = rootPath;
        this.rootPrefix = rootPath.concat("/");
        this.targetPath = targetPath;
        this.targetPrefix = targetPath.concat("/");
        this.overlayable = overlayable;
    }

    /**
     * {@inheritDoc}
     */
    public Resource getResource(ResourceResolver resolver, HttpServletRequest httpServletRequest, String path) {
        return getResource(resolver, path);
    }

    /**
     * {@inheritDoc}
     */
    public Resource getResource(ResourceResolver resolver, String path) {
        final String mappedPath = mapPath(this, resolver, path);
        if (null != mappedPath) {
            // the existing resource where the symlink's content is retrieved from
            final Resource mappedResource = resolver.getResource(mappedPath);
            if (null != mappedResource) {
                return new SymlinkResource(mappedResource, path);
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public Iterator<Resource> listChildren(Resource resource) {
        // TODO: this should probably handle other Resource implementations as well?
        if (resource instanceof SymlinkResource) {
            final SymlinkResource res = (SymlinkResource) resource;
            final ResourceResolver resolver = res.getResource().getResourceResolver();
            final Iterator<Resource> children = resolver.listChildren(res.getResource());
            return new SymlinkResourceIterator(this, children);
        }
        return null;
    }

    /**
     * Maps a path below the symlink to the target resource's path.
     *
     * @param symlink
     * @param resolver
     * @param path
     * @return
     */
    static String mapPath(SymlinkResourceProvider symlink, ResourceResolver resolver, String path) {
        final Session session = resolver.adaptTo(Session.class);
        try {
            // TODO: how to handle the node defining the symlink:
            //       * should it always be overlayed? (current implementation)
            //       * should it never be overlayed?
            //       * should it depend on the overlayable property? (may need adjustments in listChildren())
            //       * should it be seperately configurable?
            // always apply mapping for the symlink itself
            //     or
            // always apply mapping if the symlink is not overlayable
            //     or
            // if no item exists at "path" (no overlay exists)
            if (path.equals(symlink.rootPath)
                    || !symlink.overlayable
                    || (null != session && !session.itemExists(path))) {
                final String mappedPath;
                if (path.equals(symlink.rootPath)) {
                    mappedPath = symlink.targetPath;
                } else if (path.startsWith(symlink.rootPrefix)) {
                    mappedPath = StringUtils.replaceOnce(path, symlink.rootPrefix, symlink.targetPrefix);
                } else {
                    mappedPath = null;
                }
                return mappedPath;
            }
        } catch (RepositoryException e) {
            log.error("Error accessing the repository. ", e);
        }
        return null;
    }

    /**
     * Maps a path below the target resource to the symlinked resource's path.
     *
     * @param symlink
     * @param path
     * @return
     */
    static String reverseMapPath(SymlinkResourceProvider symlink, String path) {
        final String mappedPath;
        if (path.startsWith(symlink.targetPrefix)) {
            mappedPath = StringUtils.replaceOnce(path, symlink.targetPrefix, symlink.rootPrefix);
        } else if (path.equals(symlink.targetPath)) {
            mappedPath = symlink.rootPath;
        } else {
            mappedPath = null;
        }
        return mappedPath;
    }

    //---------- Service Registration

    void registerService(BundleContext context) {
        final Dictionary<String, Object> props = new Hashtable<String, Object>();
        props.put(Constants.SERVICE_DESCRIPTION, "Provider of symlink resources");
        props.put(Constants.SERVICE_VENDOR, "The Apache Software Foundation");
        props.put(ROOTS, new String[]{rootPath});

        registration = context.registerService(SERVICE_NAME, this, props);

        log.info("Registered {}", this);
    }

    void unregisterService() {
        if (registration != null) {
            registration.unregister();
            registration = null;
            log.info("Unregistered {}", this);
        }
    }

    public boolean equals(Object o) {
        if (o instanceof SymlinkResourceProvider) {
            final SymlinkResourceProvider srp = (SymlinkResourceProvider) o;
            return this.targetPath.equals(srp.targetPath) && this.overlayable == srp.overlayable;

        }
        return false;
    }

    public String toString() {
        final StringBuilder sb = new StringBuilder(getClass().getSimpleName());
        sb.append(" [path=").append(rootPath).append(", ");
        sb.append("target=").append(targetPath).append(", ");
        sb.append("overlayable=").append(overlayable).append("]");
        return sb.toString();
    }
}
