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

import org.apache.sling.api.resource.Resource;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * SymlinkResourceIterator ...
 */
public class SymlinkResourceIterator implements Iterator<Resource> {
    private final SymlinkResourceProvider symlink;
    private Iterator<Resource> decoratee;

    private Resource next;


    SymlinkResourceIterator(SymlinkResourceProvider symlink, Iterator<Resource> decoratee) {
        this.symlink = symlink;
        this.decoratee = decoratee;
        seek();
    }

    private void seek() {
        next = null;
        while (next == null && decoratee.hasNext()) {
            final Resource resource = decoratee.next();
            final String symlinkPath = SymlinkResourceProvider.reverseMapPath(symlink, resource.getPath());
            if (null != symlinkPath) {
                next = new SymlinkResource(resource, symlinkPath);
            }
        }
    }

    public boolean hasNext() {
        return null != next;
    }

    public Resource next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        final Resource current = next;
        seek();
        return current;
    }

    public void remove() {
        throw new UnsupportedOperationException();
    }
}
