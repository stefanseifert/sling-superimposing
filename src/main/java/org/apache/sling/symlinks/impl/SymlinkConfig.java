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
package org.apache.sling.symlinks.impl;

import java.util.Dictionary;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.osgi.framework.Constants;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of an OSGI Config for the Apache Symlink bundle. The Symlink functionality can either be enabled or disabled
 */
@Component(metatype = true, immediate = true, label = SymlinkConfig.SERVICE_NAME, description = SymlinkConfig.SERVICE_DESCRIPTION)
@Service(SymlinkConfig.class)
@Property(name = Constants.SERVICE_DESCRIPTION, value = SymlinkConfig.SERVICE_NAME)
public class SymlinkConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(SymlinkConfig.class);
    private final String enabledPropery = "enabled";

    protected static final String SERVICE_NAME = "Apache Sling Symlink Configuration";
    protected static final String SERVICE_DESCRIPTION = "Configuration of the  Symlink behaviour.";

    @Property(label = "Enabled", name = "enabled", description = "Enable/Disable the symlink functionality.", boolValue = false)
    private boolean enabled;

    /**
     * Check if symlinks are enabled
     * @return flag indicating if symlinks are enabled
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    // --- SCR Integration ---
    protected void activate(ComponentContext osgiContext) {
        @SuppressWarnings("unchecked")
        final Dictionary<String, Object> props = osgiContext.getProperties();
        enabled = PropertiesUtil.toBoolean(props.get(enabledPropery), false);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Apache Symlink Config: " + "Enabled={} ", new Object[] { enabled });
        }
    }

}
