/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.superimposing;

import java.util.Map;

import org.apache.sling.superimposing.impl.SuperimposingResourceProvider;

/**
 * Manages the resource registrations for the Superimposing Resource Provider.
 */
public interface SuperimposingManager {

    /**
     * Mixin for symlinks.
     */
    String MIXIN_SUPERIMPOSE = "sling:Superimpose";

    /**
     * Property pointing to an absolute or relative repository path, which
     * this symlink points to.
     */
    String PROP_SUPERIMPOSE_TARGET = "sling:superimposeTarget";

    /**
     * Property indicating if the node itself is used as root for the symlink (default),
     * of it it's parent should be used. The latter is useful in a Page/PageContent scenario
     * where the mixin cannot be added on the parent node itself.
     */
    String PROP_SUPERIMPOSE_REGISTER_PARENT = "sling:superimposeRegisterParent";

    /**
     * Property indicating whether this symlink allows the symlinked content
     * to be overlayed by real nodes created below the symlink node.
     * Default value is false.
     */
    String PROP_SUPERIMPOSE_OVERLAYABLE = "sling:superimposeOverlayable";

    /**
     * @return true if superimposing mode is enabled
     */
    boolean isEnabled();
        
    /**
     * @return Immutable map with all superimposing resource providers currently registered
     */
    Map<String, SuperimposingResourceProvider> getRegisteredSymlinkProviders();

}
