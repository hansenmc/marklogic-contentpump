/*
 * Copyright 2003-2013 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.marklogic.mapreduce;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.ArrayWritable;
import org.apache.hadoop.io.DefaultStringifier;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

import com.marklogic.mapreduce.utilities.ForestStatus;
import com.marklogic.mapreduce.utilities.InternalUtilities;
import com.marklogic.xcc.AdhocQuery;
import com.marklogic.xcc.ContentCapability;
import com.marklogic.xcc.ContentSource;
import com.marklogic.xcc.RequestOptions;
import com.marklogic.xcc.ResultItem;
import com.marklogic.xcc.ResultSequence;
import com.marklogic.xcc.Session;
import com.marklogic.xcc.exceptions.RequestException;
import com.marklogic.xcc.exceptions.XccConfigException;
import com.marklogic.xcc.types.XSBoolean;

/**
 * MarkLogicOutputFormat for Content.
 * 
 * <p>
 *  Use this class to store results as content in a MarkLogic Server database.
 *  The text, XML, or binary content is inserted into the database at the
 *  given {@link DocumentURI}.
 * </p>
 * <p>
 *  When using this {@link MarkLogicOutputFormat}, your key should be the URI of
 *  the document to insert into the database. The value should be the content to
 *  insert, in the form of {@link org.apache.hadoop.io.Text} or 
 *  {@link MarkLogicNode}.
 * </p>
 * <p>
 *  Several configuration properties exist for controlling the content insertion,
 *  including permissions, collections, quality, directory, and content type.
 * </p>
 * 
 * @see MarkLogicConstants
 * @see com.marklogic.mapreduce.examples.ContentLoader
 * @see com.marklogic.mapreduce.examples.ZipContentLoader
 * @author jchen
 *
 * @param <VALUEOUT>
 */
public class ContentOutputFormat<VALUEOUT> extends
        MarkLogicOutputFormat<DocumentURI, VALUEOUT> {
    public static final Log LOG = LogFactory.getLog(ContentOutputFormat.class);
    
    // Prepend to a forest id to form a database name parsed by XDBC.
    // Also used here alone as the forest id placeholder in non-fast-mode.
    public static final String ID_PREFIX = "#";
 
    @Override
    public void checkOutputSpecs(Configuration conf, ContentSource cs) 
    throws IOException { 
        boolean fastLoad;
        Session session = null;
        ResultSequence result = null;
        try {
            session = cs.newSession(); 
            RequestOptions options = new RequestOptions();
            options.setDefaultXQueryVersion("1.0-ml");
            session.setDefaultRequestOptions(options);
            
            // clear output dir if specified
            String outputDir = conf.get(OUTPUT_DIRECTORY);
            if (outputDir != null) {
                fastLoad = true;
                outputDir = outputDir.endsWith("/") ? 
                        outputDir : outputDir + "/";
                if (conf.getBoolean(OUTPUT_CLEAN_DIR, false)) {
                    // delete directory if exists
                    String queryText = DELETE_DIRECTORY_TEMPLATE.replace(
                            DIRECTORY_TEMPLATE, outputDir);
                    AdhocQuery query = session.newAdhocQuery(queryText);
                    result = session.submitRequest(query);
                } else { // ensure nothing exists under output dir
                    String queryText = CHECK_DIRECTORY_EXIST_TEMPLATE.replace(
                            DIRECTORY_TEMPLATE, outputDir);
                    AdhocQuery query = session.newAdhocQuery(queryText);
                    result = session.submitRequest(query);
                    if (result.hasNext()) {
                        ResultItem item = result.next();
                        if (((XSBoolean)(item.getItem())).asBoolean()) {
                            throw new IllegalStateException("Directory " + 
                                    outputDir + " already exists");
                        }
                    } else {
                        throw new IllegalStateException(
                                "Failed to query directory content.");
                    }
                }
            } else {
                fastLoad = conf.getBoolean(OUTPUT_FAST_LOAD, false);
            }
    
            // ensure manual directory creation 
            if (fastLoad) {
                LOG.info("Running in fast load mode");
                    // store forest-stats map into config system
                DefaultStringifier.store(conf, queryForestStatusMap(cs),
                    OUTPUT_FOREST_HOST);

                AdhocQuery query = session.newAdhocQuery(
                                DIRECTORY_CREATE_QUERY);
                result = session.submitRequest(query);
                if (result.hasNext()) {
                    ResultItem item = result.next();
                    String dirMode = item.asString();
                    if (!dirMode.equals(MANUAL_DIRECTORY_MODE)) {
                        throw new IllegalStateException(
                                "Manual directory creation mode is required. " +
                                "The current creation mode is " + dirMode + ".");
                    }
                } else {
                    throw new IllegalStateException(
                            "Failed to query directory creation mode.");
                }
            } else {
                ArrayWritable hostArray = queryHosts(cs);
                // store hosts into config system
                DefaultStringifier.store(conf, hostArray, OUTPUT_FOREST_HOST);
            }
    
            // validate capabilities
            String[] perms = conf.getStrings(OUTPUT_PERMISSION);
            if (perms != null && perms.length > 0) {
                if (perms.length % 2 != 0) {
                    throw new IllegalStateException(
                    "Permissions are expected to be in <role, capability> pairs.");
                }
                int i = 0;
                while (i + 1 < perms.length) {
                    String roleName = perms[i++];
                    if (roleName == null || roleName.isEmpty()) {
                        throw new IllegalStateException(
                                "Illegal role name: " + roleName);
                    }
                    String perm = perms[i].trim();
                    if (!perm.equalsIgnoreCase(ContentCapability.READ.toString()) &&
                        !perm.equalsIgnoreCase(ContentCapability.EXECUTE.toString()) &&
                        !perm.equalsIgnoreCase(ContentCapability.INSERT.toString()) &&
                        !perm.equalsIgnoreCase(ContentCapability.UPDATE.toString())) {
                        throw new IllegalStateException("Illegal capability: " + perm);
                    }
                    i++;
                }
            }
        } catch (RequestException ex) {
            throw new IOException(ex);
        } finally {
            if (session != null) {
                session.close();
            } 
            if (result != null) {
                result.close();
            }
        }
    }
    
    protected Map<String, ContentSource> getSourceMap(boolean fastLoad, TaskAttemptContext context) throws IOException{
        Configuration conf = context.getConfiguration();
        Map<String, ContentSource> sourceMap = 
            new LinkedHashMap<String, ContentSource>();
        if (fastLoad) {
            LinkedMapWritable forestStatusMap = getForestStatusMap(conf);
            // get host->contentSource mapping
            Map<Writable, ContentSource> hostSourceMap = 
                new HashMap<Writable, ContentSource>();
            for (Writable v : forestStatusMap.values()) {
                ForestStatus fs = (ForestStatus)v;
                //unupdatable forests
                if(fs.getUpdatable().get() == false) continue;
                if (hostSourceMap.get(fs.getHostName()) == null) {
                    try {
                        ContentSource cs = InternalUtilities.getOutputContentSource(
                            conf, fs.getHostName().toString());
                        hostSourceMap.put(fs.getHostName(), cs);
                    } catch (XccConfigException e) {
                        throw new IOException(e);
                    } 
                }
            }
            
            // consolidate forest->host map and host-contentSource map to 
            // forest-contentSource map
            for (Writable forestId : forestStatusMap.keySet()) {
                String forest = ((Text)forestId).toString();
                Writable hostName = ((ForestStatus)forestStatusMap.get(forestId)).getHostName();
                ContentSource cs = hostSourceMap.get(hostName);
                sourceMap.put(ID_PREFIX + forest, cs);
            }
        } else {
            ArrayWritable hosts = getHosts(conf);
            int taskId = context.getTaskAttemptID().getTaskID().getId();
            String host = InternalUtilities.getHost(taskId, hosts);
            try {
                ContentSource cs = InternalUtilities.getOutputContentSource(
                    conf, host);
                sourceMap.put(ID_PREFIX, cs);
            } catch (XccConfigException e) {
                throw new IOException(e);
            }
        }
        return sourceMap;
    }
    
    @Override
    public RecordWriter<DocumentURI, VALUEOUT> getRecordWriter(
            TaskAttemptContext context) throws IOException, InterruptedException {
        Configuration conf = context.getConfiguration();
        boolean fastLoad = conf.getBoolean(OUTPUT_FAST_LOAD, false) ||
        (conf.get(OUTPUT_DIRECTORY) != null);
        Map<String, ContentSource> sourceMap = getSourceMap(fastLoad, context);
        // construct the ContentWriter
        return new ContentWriter<VALUEOUT>(conf, sourceMap, fastLoad, am);
    }
}
