/*
Kontraktor-Http Copyright (c) Ruediger Moeller, All rights reserved.

This library is free software; you can redistribute it and/or
modify it under the terms of the GNU Lesser General Public
License as published by the Free Software Foundation; either
version 3.0 of the License, or (at your option) any later version.

This library is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
Lesser General Public License for more details.

See https://www.gnu.org/licenses/lgpl.txt
*/

package org.nustaq.kontraktor.webapp.javascript;

import io.undertow.io.IoCallback;
import io.undertow.io.Sender;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.resource.FileResource;
import io.undertow.server.handlers.resource.FileResourceManager;
import io.undertow.server.handlers.resource.Resource;
import io.undertow.util.*;
import org.jsoup.nodes.Element;
import org.nustaq.kontraktor.webapp.javascript.jsmin.JSMin;
import org.nustaq.kontraktor.util.Log;
import org.nustaq.kontraktor.webapp.transpiler.TranspilerHook;
import org.nustaq.serialization.util.FSTUtil;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * adapts kontraktors js + html snippets dependency management to undertow
 *
 */
public class DynamicResourceManager extends FileResourceManager implements FileResolver, HtmlImportShim.ResourceLocator {

    boolean devMode = false;
    DependencyResolver dependencyResolver;
    HtmlImportShim importShim; // null means no imports supported
    String prefix = "";
    Date lastStartup; // last startUp, will be returned as LastModifiedDate for cached resources..

    /**
     * in case not run with cacheAggregates, store lookup results in memory,
     * so file crawling is done once after server restart.
     */
    ConcurrentHashMap<String,Resource> lookupCache = new ConcurrentHashMap<>();
    boolean minify;
    private Map<String, TranspilerHook> transpilerMap;
    private Map<String,byte[]> debugInstalls = new ConcurrentHashMap<>();

    public DynamicResourceManager(boolean devMode, String prefix, boolean minify, String resPathBase, String ... resourcePath) {
        this(new File("."),devMode,prefix,minify,resPathBase,resourcePath);
    }

    public DynamicResourceManager(File base, boolean devMode, String prefix, boolean minify, String resPathBase, String ... resourcePath) {
        super(base, 100);
        this.devMode = devMode;
        this.minify = minify;
        this.lastStartup = new Date();
        setPrefix(prefix);
        dependencyResolver = new DependencyResolver(resPathBase,resourcePath,this);
        if ( devMode )
            Log.Warn(this, "Dependency resolving is running in *DEVELOPMENT MODE*. Turn off development mode to cache aggregated resources");
        else
            Log.Info(this, "Dependency resolving is running in *PRODUCTION MODE*. Turn on development mode for script-refresh-on-reload and per file javascript debugging");
    }

    public void setImportShim( HtmlImportShim shim ) {
        shim.setLocator(dependencyResolver);
        this.importShim = shim;
    }

    public void setPrefix(String prefix) {
        while (prefix.startsWith("/")) {
            prefix = prefix.substring(1);
        }
        this.prefix = prefix;
    }

    public boolean isDevMode() {
        return devMode;
    }

    public Resource getCacheEntry(String normalizedPath) {
        return lookupCache.get(normalizedPath);
    }

    @Override
    public Resource getResource(String initialPath) {
        String normalizedPath;
        byte[] debugLib = debugInstalls.get(initialPath);
        if (debugLib!= null) {
            return new MyResource(
                initialPath, initialPath.substring(1), debugLib, "" +
                "text/javascript", null );
        }
        if (initialPath.startsWith("/")) {
            normalizedPath = initialPath.substring(1);
        } else {
            normalizedPath = initialPath;
        }
        if ( ! isDevMode() ) { // lookup cache if not devmode
            if ( normalizedPath.startsWith("f5_") ) {
                lookupCache.clear();
                return super.getResource(initialPath);
            }
            Resource res = lookupCache.get(normalizedPath);
            if (res != null) {
                return res;
            }
        }
        // import shim is applied to *index.html file only
        if ( initialPath.endsWith("index.html") && importShim != null ) {
            try {
                Element element = importShim.shimImports(normalizedPath);
                if ( element == null ) {
                    return super.getResource(initialPath);
                }
                byte bytes[] = element.toString().getBytes("UTF-8");
                return mightCache(normalizedPath, new MyResource(initialPath, normalizedPath, bytes, "text/html",  !isDevMode()? lastStartup : null ));
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            File file = dependencyResolver.locateResource(normalizedPath);
            if ( file != null ) {
                // FIMXE: could be done via TranspilerHook now ..
                final String fname = file.getName();
                if ( fname.endsWith(".js") && minify ) {
                    try {
                        byte[] bytes = Files.readAllBytes(file.toPath());
                        bytes = JSMin.minify(bytes);
                        return mightCache(normalizedPath, new MyResource(initialPath, normalizedPath, bytes, "text/javascript", !isDevMode()? lastStartup : null ));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if ( transpilerMap != null && transpilerMap.size() > 0 ) {
                    try {
                        int idx = fname.lastIndexOf('.');
                        if ( idx > 0 ) {
                            String ext = fname.substring(idx+1).toLowerCase();
                            TranspilerHook transpilerHook = transpilerMap.get(ext);
                            if ( transpilerHook != null ) {
                                byte[] transpiled = transpilerHook.transpile(file, this, new HashSet<>());
                                if (minify) {
                                    transpiled = JSMin.minify(transpiled);
                                }
                                if ( transpiled != null ) {
                                    return mightCache(normalizedPath, new MyResource(initialPath, normalizedPath, transpiled, "text/javascript", !isDevMode()? lastStartup : null ));
                                }
                            }
                        }
                    } catch (Exception ex) {
                        Log.Error(this, ex);
                    }
                }
                try {
                    return mightCache(normalizedPath, getFileResource(file, initialPath));
                } catch (IOException e) {
                    FSTUtil.rethrow(e);
                }
            }
        }
        return super.getResource(initialPath);
    }

    protected FileResource getFileResource(final File file, final String path) throws IOException {
        if (_isFileSameCase(file)) {
            return new FileResource(file, this, path) {
                @Override
                public Date getLastModified() {
                    if ( isDevMode() ) {
                        return new Date();
                    } else {
                        if ( lastStartup == null )
                            lastStartup = new Date();
                        return lastStartup;
                    }
                }
            };
        } else {
            return null;
        }
    }

    private boolean _isFileSameCase(final File file) throws IOException {
        String canonicalName = file.getCanonicalFile().getName();
        if (canonicalName.equals(file.getName())) {
            return true;
        } else {
            return !canonicalName.equalsIgnoreCase(file.getName());
        }
    }

    // FIXME: Http level caching is independent of this result, needs redesign
    private Resource mightCache(String key, Resource fileResource) {
        if ( ! isDevMode() ) {
            lookupCache.put(key,fileResource);
        }
        return fileResource;
    }

    public void setTranspilerMap(Map<String, TranspilerHook> transpilerMap) {
        this.transpilerMap = transpilerMap;
    }

    public Map<String, TranspilerHook> getTranspilerMap() {
        return transpilerMap;
    }

    @Override
    public byte[] resolve(File baseDir, String name, Set<String> alreadyProcessed) {
        try {
            File file = new File(baseDir,name); // check relative to current dir
            if ( ! file.exists() )
                file = null;
            if ( file == null )
                file = dependencyResolver.locateResource(name);
            byte bytes[] = null;
            // fixme: doubles logic of getResource
            if ( file != null ) {
                String normalizedPath = file.getCanonicalPath();
                if ( alreadyProcessed.contains(normalizedPath) ) {
                    return new byte[0];
                }
                alreadyProcessed.add(normalizedPath);
                final String fname = file.getName();
                if ( transpilerMap != null && transpilerMap.size() > 0 ) {
                    try {
                        int idx = fname.lastIndexOf('.');
                        if ( idx > 0 ) {
                            String ext = fname.substring(idx+1).toLowerCase();
                            TranspilerHook transpilerHook = transpilerMap.get(ext);
                            if ( transpilerHook != null ) {
                                bytes = transpilerHook.transpile(file, this, alreadyProcessed);
                            }
                        }
                    } catch (Exception ex) {
                        Log.Error(this, ex);
                    }
                }
                if ( bytes == null )
                    bytes = Files.readAllBytes(file.toPath());
                if ( fname.endsWith(".js") && minify ) {
                    bytes = JSMin.minify(bytes);
                }
            }
            return bytes;
        } catch (Exception e) {
            FSTUtil.rethrow(e);
        }
        return null;
    }

    /**
     * a transpiler generates files which need to be mapped temporary
     *
     * @param path
     * @param resolved
     */
    @Override
    public void install(String path, byte[] resolved) {
        debugInstalls.put(path,resolved);
    }

    @Override
    public File locateResource(String urlPath) {
        return dependencyResolver.locateResource(urlPath);
    }

    /**
     * invoke transpilers during prodmode inlining
     * @param impFi
     * @return
     */
    @Override
    public byte[] retrieveBytes(File impFi) {
        String fname = impFi.getName();
        int idx = fname.indexOf('.');
        if ( idx >= 0 ) {
            String ext = fname.substring(idx+1).toLowerCase();
            TranspilerHook transpilerHook = transpilerMap.get(ext);
            if ( transpilerHook != null ) {
                return transpilerHook.transpile(impFi, this, new HashSet());
            }
        }
        return null;
    }

    public long getLastModified() {
        if ( lastStartup != null )
            return lastStartup.getTime();
        return 0;
    }

    public Date getLastModifiedDate() {
        return lastStartup;
    }

    public static class MyResource implements Resource {
        protected String p0;
        protected String finalP;
        protected byte[] bytes;
        protected String resType;
        protected Date lastModified;
        public MyResource(String p0, String finalP, byte[] bytes, String resType , Date lastStartup ) {
            this.p0 = p0;
            this.finalP = finalP;
            this.bytes = bytes;
            this.resType = resType;
            this.lastModified = lastStartup;
        }

        @Override
        public String getPath() {
            return p0;
        }

        @Override
        public Date getLastModified() {
            return lastModified == null ? new Date(): lastModified;
        }

        @Override
        public String getLastModifiedString() {
            return DateUtils.toDateString(getLastModified());
        }

        @Override
        public ETag getETag() {
            return null;
        }

        @Override
        public String getName() {
            return finalP;
        }

        @Override
        public boolean isDirectory() {
            return false;
        }

        @Override
        public List<Resource> list() {
            return null;
        }

        @Override
        public String getContentType(MimeMappings mimeMappings) {
            return resType;
        }

        @Override
        public void serve(Sender sender, HttpServerExchange exchange, IoCallback completionCallback) {
            exchange.getResponseSender().send(ByteBuffer.wrap(bytes));
//            exchange.startBlocking(); // rarely called (once per login) also served from mem in production mode
//            try {
//                exchange.getOutputStream().write(bytes);
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//            completionCallback.onComplete(exchange, sender);
        }

        @Override
        public Long getContentLength() {
            return Long.valueOf(bytes.length);
        }

        @Override
        public String getCacheKey() {
            return null;
        }

        @Override
        public File getFile() {
            return null;
        }

        @Override
        public File getResourceManagerRoot() {
            return null;
        }

        @Override
        public URL getUrl() {
            return null;
        }

        public byte[] getBytes() {
            return bytes;
        }
    }

}