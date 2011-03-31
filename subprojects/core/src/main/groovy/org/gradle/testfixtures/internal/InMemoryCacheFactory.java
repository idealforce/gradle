/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.testfixtures.internal;

import org.gradle.CacheUsage;
import org.gradle.api.internal.changedetection.InMemoryIndexedCache;
import org.gradle.cache.*;

import java.io.File;
import java.util.Map;

public class InMemoryCacheFactory implements CacheFactory {
    public void close(PersistentCache cache) {
    }

    public PersistentCache open(final File cacheDir, CacheUsage usage, Map<String, ?> properties) {
        cacheDir.mkdirs();
        return new PersistentCache() {
            public File getBaseDir() {
                return cacheDir;
            }

            public boolean isValid() {
                return false;
            }

            public void markValid() {
            }

            public <K, V> PersistentIndexedCache<K, V> openIndexedCache(Serializer<V> serializer) {
                return new InMemoryIndexedCache<K, V>();
            }

            public <K, V> PersistentIndexedCache<K, V> openIndexedCache() {
                return new InMemoryIndexedCache<K, V>();
            }

            public <T> PersistentStateCache<T> openStateCache() {
                return new SimpleStateCache<T>(this, new DefaultSerializer<T>());
            }
        };
    }
}