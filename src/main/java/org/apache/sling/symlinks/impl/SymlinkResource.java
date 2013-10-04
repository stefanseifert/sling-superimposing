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

import org.apache.sling.api.resource.AbstractResource;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceMetadata;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * SymlinkResource is provided by SymlinkResourceProvider instances.
 * It delegates to an existing JCR resource but overrides the getPath()
 * method to point to the symlinked path.
 */
public class SymlinkResource extends AbstractResource implements Resource {
    private final Resource resource;
    private final String path;

    public SymlinkResource(Resource mappedResource, String path) {
        this.resource = mappedResource;
        this.path = path;
    }

    public String getPath() {
        return this.path;
    }

    public String getResourceType() {
        return resource.getResourceType();
    }

    public String getResourceSuperType() {
        return resource.getResourceSuperType();
    }

    public ResourceMetadata getResourceMetadata() {
        return resource.getResourceMetadata();
    }

    public ResourceResolver getResourceResolver() {
        return resource.getResourceResolver();
    }

    @Override
    public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
        AdapterType adapted = super.adaptTo(type);
        if (null == adapted) {
            // TODO: does it really make sense to support adapting to Node.class?
            adapted = resource.adaptTo(type);
        }
        return adapted;
    }

    Resource getResource() {
        return this.resource;
    }

    @Override
    public String toString() {
        return new StringBuilder(getClass().getSimpleName())
                .append("[type=").append(getResourceType())
                .append(", path=").append(getPath())
                .append(", resource=[").append(getResource()).append("]]").toString();
    }
}
