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
package org.apache.sling.superimposing.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import static org.apache.sling.superimposing.SuperimposingResourceProvider.PROP_SUPERIMPOSE_OVERLAYABLE;
import static org.apache.sling.superimposing.SuperimposingResourceProvider.PROP_SUPERIMPOSE_REGISTER_PARENT;
import static org.apache.sling.superimposing.SuperimposingResourceProvider.PROP_SUPERIMPOSE_SOURCE_PATH;

import java.util.Arrays;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.superimposing.SuperimposingResourceProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;

@SuppressWarnings("javadoc")
@RunWith(MockitoJUnitRunner.class)
public class SuperimposingManagerImplTest {
    
    @Mock
    private Dictionary<String, Object> componentContextProperties;
    @Mock
    private ComponentContext componentContext;
    @Mock
    private BundleContext bundleContext;    
    @Mock
    private ResourceResolverFactory resourceResolverFactory;
    @Mock
    private ResourceResolver resourceResolver;
    @Mock(answer=Answers.RETURNS_DEEP_STUBS)
    private Session session;
    
    private SuperimposingManagerImpl underTest;

    private static final String ORIGINAL_PATH = "/root/path1";
    private static final String SUPERIMPOSED_PATH = "/root/path2";
    
    @SuppressWarnings("unchecked")
    @Before
    public void setUp() throws LoginException {
        when(componentContext.getBundleContext()).thenReturn(bundleContext);
        when(componentContext.getProperties()).thenReturn(componentContextProperties);
        when(resourceResolverFactory.getAdministrativeResourceResolver(any(Map.class))).thenReturn(resourceResolver);
        when(resourceResolver.adaptTo(Session.class)).thenReturn(session);
    }
    
    private void initialize(boolean enabled) throws InterruptedException, LoginException, RepositoryException {
        when(componentContextProperties.get(SuperimposingManagerImpl.ENABLED_PROPERTY)).thenReturn(enabled);

        underTest = new SuperimposingManagerImpl().withResourceResolverFactory(resourceResolverFactory);
        underTest.activate(componentContext);
        
        if (enabled) {
            verify(session.getWorkspace().getObservationManager()).addEventListener(eq(underTest), anyInt(), anyString(), anyBoolean(), any(String[].class), any(String[].class), anyBoolean());
            
            while (!underTest.initialization.isDone()) {
                Thread.sleep(10);
            }
        }
    }

    @After
    public void tearDown() {
        underTest.deactivate(componentContext);
    }

    private Resource prepareSuperimposingResource(String superimposedPath, String sourcePath, boolean registerParent, boolean overlayable) {
        Resource resource = mock(Resource.class);
        when(resource.getPath()).thenReturn(superimposedPath);
        ValueMap props = new ValueMapDecorator(new HashMap<String, Object>());
        props.put(PROP_SUPERIMPOSE_SOURCE_PATH, sourcePath);
        props.put(PROP_SUPERIMPOSE_REGISTER_PARENT, registerParent);
        props.put(PROP_SUPERIMPOSE_OVERLAYABLE, overlayable);
        when(resource.adaptTo(ValueMap.class)).thenReturn(props);
        return resource;
    }

    @Test
    public void testFindAllSuperimposings() throws InterruptedException, LoginException, RepositoryException {
        when(componentContextProperties.get(SuperimposingManagerImpl.FINDALLQUERIES_PROPERTY)).thenReturn("syntax|query");
        when(resourceResolver.findResources("query", "syntax")).then(new Answer<Iterator<Resource>>() {
            public Iterator<Resource> answer(InvocationOnMock invocation) {
                return Arrays.asList(new Resource[] {
                        prepareSuperimposingResource(SUPERIMPOSED_PATH, ORIGINAL_PATH, false, false)
                }).iterator();
            }
        });
        initialize(true);
        
        Map<String, SuperimposingResourceProvider> providers = underTest.getRegisteredProviders();
        assertEquals(1, providers.size());
        SuperimposingResourceProvider provider = providers.values().iterator().next();
        assertEquals(SUPERIMPOSED_PATH, provider.getRootPath());
        assertEquals(ORIGINAL_PATH, provider.getSourcePath());
        assertFalse(provider.isOverlayable());
    }
    
}
