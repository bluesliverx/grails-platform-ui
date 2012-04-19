/* Copyright 2011-2012 the original author or authors:
 *
 *    Marc Palmer (marc@grailsrocks.com)
 *    Stéphane Maldini (stephane.maldini@gmail.com)
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
package org.grails.plugin.platform.views

import org.springframework.context.ApplicationContextAware
import org.codehaus.groovy.grails.commons.GrailsApplication
import org.springframework.context.ApplicationContext
import org.codehaus.groovy.grails.web.pages.GroovyPagesUriService
import org.codehaus.groovy.grails.web.servlet.GrailsApplicationAttributes
import org.slf4j.LoggerFactory
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

import org.springframework.core.io.Resource
import org.codehaus.groovy.grails.plugins.GrailsPlugin

import grails.util.GrailsUtil

/**
 * Interface for grailsViewFinder bean used to see if GSP views and layouts exist.
 *
 * Implementations may vary based on Grails version
 */
class Grails13ViewFinder implements ViewFinder, ApplicationContextAware {

    final log = LoggerFactory.getLogger(Grails13ViewFinder)

    ApplicationContext applicationContext
    def grailsApplication
    def resourceLoader
    def pluginManager
    Map precompiledGspMap
    
    GroovyPagesUriService groovyPagesUriService
    
    private static final String GROOVY_PAGE_RESOURCE_LOADER = "groovyPageResourceLoader"
    private static final String RESOURCES_PREFIX = GrailsUtil.grailsVersion.startsWith('1.') ? '/WEB-INF' : ''
    private static final String VIEW_PATH_PREFIX = '/grails-app/views'
    private static final String APP_VIEW_PATH_PREFIX = VIEW_PATH_PREFIX
    private static final String PLUGINS_PATH = "/plugins"
    private static final String LAYOUTS_PATH = "/layouts"
    
    /**
     * @param name The path of the view relative to grails-views/ and excluding the .gsp part, but containing relevant underscores
     */
    boolean templateExists(String path) {
        GrailsApplication application = applicationContext.getBean(GrailsApplication.APPLICATION_ID, GrailsApplication.class)

        def loader = establishResourceLoader()
        String gspView = groovyPagesUriService.getTemplateURI('', path)
        def fullpath = RESOURCES_PREFIX+'/grails-app/views'+gspView
        if (log.debugEnabled) {
            log.debug "Checking for template at ${fullpath}"
        }
        def res = loader.getResource(fullpath)
        if (log.debugEnabled) {
            log.debug "Checking for template at ${fullpath} found: [${res}] (${res.exists()})"
        }
        return res?.exists()
    }
    
    /** 
     * See if a GSP template exists in a plugin
     * If plugin is null, reverts to application views
     */
    boolean templateExists(String path, GrailsPlugin plugin) {
        if (!plugin) {
            return templateExists(path)
        }
        GrailsApplication application = applicationContext.getBean(GrailsApplication.APPLICATION_ID, GrailsApplication.class)

        String gspView = groovyPagesUriService.getTemplateURI('', path)
        def fullpath = '/plugins/'+plugin.fileSystemName+'/grails-app/views'+gspView
        if (log.debugEnabled) {
            log.debug "Checking for template at ${fullpath}"
        }
        def exists = viewExists(fullpath)
        if (log.debugEnabled) {
            log.debug "Checking for template at ${fullpath} found: ${exists}"
        }
        return exists
    }
    
    /**
     * @param path The path of the layout view relative to grails-views/layouts/ and excluding the .gsp part
     */
    boolean layoutExists(String path) {
        GrailsApplication application = applicationContext.getBean(GrailsApplication.APPLICATION_ID, GrailsApplication.class);

        def fullpath = APP_VIEW_PATH_PREFIX + LAYOUTS_PATH + '/'+path + '.gsp'
        if (log.debugEnabled) {
            log.debug "Checking for layout at ${fullpath}"
        }
        def exists = viewExists(fullpath)
        if (log.debugEnabled) {
            log.debug "Checking for layout at ${fullpath} found: ${exists}"
        }
        if (!exists) {
            for (plugin in pluginManager.allPlugins) {
                fullpath = getPathToPluginViews(plugin) + LAYOUTS_PATH + '/'+path + '.gsp'
                if (log.debugEnabled) {
                    log.debug "Checking for layout in plugin [${plugin.name}] at ${fullpath}"
                }
                exists = viewExists(fullpath)
                if (log.debugEnabled) {
                    log.debug "Checking for layout in plugin [${plugin.name}] at ${fullpath} found: $exists"
                }
                if (exists) {
                    break;
                }
            }
        }
        return exists
    }
    
    List<String> extractLastFolderNamesFromPaths(paths, filePartToRemove) {
        int fileLen = filePartToRemove.length()+1 // Add 1 for the preceding /
        paths.collect { p ->
            String pMinusFile = p[0..(p.length()-fileLen)-1]
            int lastSlash = pMinusFile.lastIndexOf('/')
            pMinusFile[lastSlash+1..-1]
        }
    }

    List<String> listPluginViewFoldersAt(GrailsPlugin plugin, String path, String markerView) {
        def searchPath = '/plugins/'+plugin.fileSystemName+VIEW_PATH_PREFIX+path
        
        def resources = listViewsInFolder(searchPath, '*/'+markerView, 'URI') // look for a specific file
        if (log.debugEnabled) {
            log.debug "listPluginViewFoldersAt for [$searchPath]  found: ${resources}"
        }
        resources = extractLastFolderNamesFromPaths(resources, markerView)
        return resources
    }

    List<String> listAppViewFoldersAt(String path, String markerView) {
        def appPath = APP_VIEW_PATH_PREFIX+path
        def resources = listViewsInFolder(appPath, '*/'+markerView, 'URI')

        if (log.debugEnabled) {
            log.debug "listAppViewFoldersAt for [$appPath]  found: ${resources}"
        }
        resources = extractLastFolderNamesFromPaths(resources, markerView)
        return resources
    }

    List<String> listPluginViewsAt(GrailsPlugin plugin, String path) {
        List<String> resourceStrings = []
        def pluginPath = '/plugins/'+plugin.fileSystemName+VIEW_PATH_PREFIX+path
        def resources = listViewsInFolder(pluginPath, '*.gsp')
        if (log.debugEnabled) {
            log.debug "listPluginViewsAt for [$path] (plugin:${plugin?.name}) found: ${resources}"
        }
        return resources
    }

    List<String> listAppViewsAt(String path) {
        List<String> resourceStrings = []
        def resources = listViewsInFolder(VIEW_PATH_PREFIX+path, '*.gsp')
        if (resources) {
            for (r in resources) {
                if (log.debugEnabled) {
                    log.debug "listAppViewsAt for [$path] found: ${r.toString()}"
                }
                resourceStrings << r
            }
        }
        return resourceStrings
    }
    

    String getPathToAppViews() {
        APP_VIEW_PATH_PREFIX
    }
    
    String getPathToPluginViews(GrailsPlugin plugin) {
        '/plugins/'+plugin.fileSystemName+VIEW_PATH_PREFIX
    }

    String getFullPathToAppViews() {
        establishResourceLoader().getResource(pathToAppViews).toString()
    }
    
    String getFullPathToPluginViews(GrailsPlugin plugin) {
        establishResourceLoader().getResource(pathToPluginViews(plugin)).toString()
    }

    protected listPrecompiledViews(String path) {
        def result = []
        if (precompiledAvailable) {
            if (log.debugEnabled) {
                log.debug "listPrecompiledViews - we have precompiled views"
            }
            def r = precompiledGspMap.keySet().findAll { p -> 
                if (log.debugEnabled) {
                    log.debug "listPrecompiledViews - Seeing if precompiled GSP ${p} matches the path $path"
                }
                // NOTE: does not support wildcards
                def match = p == path
                if (log.debugEnabled) {
                    log.debug "listPrecompiledViews -  Found precompiled GSP for path [${path}] at $p"
                }
                return match
            }
            if (r) {
                result.addAll(r)
            }
        }
        return result
    }
    
    protected boolean viewExists(String path) {
        List precompiledViews = listPrecompiledViews(path)
        if (precompiledViews) {
            return true
        }

        def loader = new PathMatchingResourcePatternResolver(establishResourceLoader())
        
        def searchPath = RESOURCES_PREFIX+path

        if (log.debugEnabled) {
            log.debug "findView - Looking for non-compiled GSP at: $searchPath"
        }
        def resource = loader.getResource(searchPath)
        if (log.debugEnabled) {
            log.debug "findView - Found GSP at ${searchPath}: ${resource}"
        }
        return resource?.exists()
    }

    protected List<String> listViewsInFolder(String path, String pattern = null, String resourcePropertyToGet = 'filename') {
        if (log.debugEnabled) {
            log.debug "listViewsInFolder - Looking for GSPs at: $path with pattern [${pattern}]"
        }
        def result = listPrecompiledViews(path)
        if (result) {
            return result
        }

        def loader = new PathMatchingResourcePatternResolver(establishResourceLoader())

        def basePath = RESOURCES_PREFIX+path+'/'
        def searchPattern = basePath
        if (pattern) {
            searchPattern += pattern
        }
        if (log.debugEnabled) {
            log.debug "listViewsInFolder - Looking for non-compiled GSP at: $searchPattern"
        }
        try {
            def resources = loader.getResources(searchPattern)
            if (log.debugEnabled) {
                log.debug "listViewsInFolder - Found GSPs with [$searchPattern]: ${resources}"
            }
            return resources.collect { r -> r[resourcePropertyToGet].toString() }
        } catch (FileNotFoundException fnfe) {
            if (log.debugEnabled) {
                log.debug "listViewsInFolder - Resource loader couldn't find folder in [${path}], skipping"
            }
            return Collections.EMPTY_LIST
        }
    }

    private boolean isPrecompiledAvailable() {
      return precompiledGspMap != null && precompiledGspMap.size() > 0 && grailsApplication.warDeployed;
    }

/*    
    protected GroovyPageScriptSource findResourceScriptSource(final String uri) {
        List<String> searchPaths = null;

        if (warDeployed) {
            if (uri.startsWith(PLUGINS_PATH)) {
                PluginViewPathInfo pathInfo = getPluginViewPathInfo(uri);

                searchPaths = CollectionUtils.newList( 
                    GrailsResourceUtils.appendPiecesForUri(GrailsResourceUtils.WEB_INF, PLUGINS_PATH, pathInfo.pluginName,GrailsResourceUtils.VIEWS_DIR_PATH, pathInfo.path),
                    GrailsResourceUtils.appendPiecesForUri(GrailsResourceUtils.WEB_INF, uri),
                    uri);
            }
            else {
                searchPaths = CollectionUtils.newList(
                    GrailsResourceUtils.appendPiecesForUri(PATH_TO_WEB_INF_VIEWS, uri),
                    uri);
            }
        }
        else {
            searchPaths = CollectionUtils.newList(
                GrailsResourceUtils.appendPiecesForUri('/' + GrailsResourceUtils.VIEWS_DIR_PATH, uri),
                uri);
        }

        return findResourceScriptPathForSearchPaths(uri, searchPaths);
    }
*/
    private establishResourceLoader() {
        def ctx = applicationContext

        if (grailsApplication && !grailsApplication.warDeployed && ctx.containsBean(GROOVY_PAGE_RESOURCE_LOADER)) {
            return ctx.getBean(GROOVY_PAGE_RESOURCE_LOADER)
        } else {
            return ctx
        }
    }
}