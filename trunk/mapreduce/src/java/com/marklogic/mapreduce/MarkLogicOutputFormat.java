/*
 * Copyright (c) 2003-2011 MarkLogic Corporation. All rights reserved.
 */
package com.marklogic.mapreduce;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.output.FileOutputCommitter;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

import com.marklogic.xcc.AdhocQuery;
import com.marklogic.xcc.ContentSource;
import com.marklogic.xcc.ContentSourceFactory;
import com.marklogic.xcc.ResultItem;
import com.marklogic.xcc.ResultSequence;
import com.marklogic.xcc.Session;
import com.marklogic.xcc.exceptions.RequestException;
import com.marklogic.xcc.exceptions.XccConfigException;

/**
 * MarkLogic-based OutputFormat.
 * 
 * @author jchen
 */
public abstract class MarkLogicOutputFormat<KEYOUT, VALUEOUT> 
extends OutputFormat<KEYOUT, VALUEOUT> 
implements MarkLogicConstants {
	public static final Log LOG =
	    LogFactory.getLog(MarkLogicOutputFormat.class);
	
	static final String DIRECTORY_TEMPLATE = "{dir}";
	static final String DELETE_DIRECTORY_TEMPLATE = 
		"xdmp:directory-delete(\"" + DIRECTORY_TEMPLATE + "\")";
	static final String CHECK_DIRECTORY_EXIST_TEMPLATE = 
		"exists(xdmp:document-properties(\"" + DIRECTORY_TEMPLATE + 
		"\")//prop:directory)";
	
	protected static String getHost(Configuration conf, int taskId) {
		String[] hosts = conf.getStrings(OUTPUT_HOSTS);
		return hosts[taskId % hosts.length];
	}
	
	/**
     * get server URI from the configuration.
     * 
     * @param conf job configuration
	 * @param taskAttemptID 
     * @return server URI
     * @throws URISyntaxException 
     */
    protected static URI getServerUri(Configuration conf, String host)
    throws URISyntaxException {
    	
		String user = conf.get(OUTPUT_USERNAME, "");
		String password = conf.get(OUTPUT_PASSWORD, "");		
		String port = conf.get(OUTPUT_PORT, "");
		
		String serverUriStr = 
			SERVER_URI_TEMPLATE.replace(USER_TEMPLATE, user)
			.replace(PASSWORD_TEMPLATE, password)
			.replace(HOST_TEMPLATE, host)
			.replace(PORT_TEMPLATE, port);
		return new URI(serverUriStr);
    }
    
	@Override
	public void checkOutputSpecs(JobContext context) throws IOException,
			InterruptedException {
		Configuration conf = context.getConfiguration();
		Session session = null;
		ResultSequence result = null;
		try {
			String host = getHost(conf, 0);
			URI serverUri = getServerUri(conf, host);
		    // try getting a connection
			ContentSource cs = ContentSourceFactory.newContentSource(serverUri);
			session = cs.newSession();
			
			String outputDir = conf.get(OUTPUT_DIRECTORY);
			if (outputDir == null || outputDir.isEmpty()) {
				return;
			}
			if (conf.getBoolean(OUTPUT_CLEAN_DIR, false)) { 
				// delete directory if exists
				String queryText = DELETE_DIRECTORY_TEMPLATE.replace(
						DIRECTORY_TEMPLATE, outputDir);
				AdhocQuery query = session.newAdhocQuery(queryText);
				result = session.submitRequest(query);
			} else {
				// make sure the output directory doesn't exist
				String queryText = 
					CHECK_DIRECTORY_EXIST_TEMPLATE.replace(
						DIRECTORY_TEMPLATE, outputDir);
				AdhocQuery query = session.newAdhocQuery(queryText);
				result = session.submitRequest(query);
				if (result.hasNext()) {
					ResultItem item = result.next();
					if (item.getItem().asString().equals("true")) {
						throw new IOException("Directory " + outputDir + 
								" already exists");
					}
				}
			}
		} catch (URISyntaxException e) {
			LOG.error(e);
			throw new IOException(e);
		} catch (XccConfigException e) {
			LOG.error(e);
			throw new IOException(e);
		} catch (RequestException e) {
			LOG.error(e);
		} finally {
			if (result != null) {
				result.close();
			}
			if (session != null) {
				try {
	                session.close();
                } catch (RequestException e) {
                	LOG.error(e);
                }
			}
		}	
	}

	@Override
	public OutputCommitter getOutputCommitter(TaskAttemptContext context)
			throws IOException, InterruptedException {
		return new FileOutputCommitter(FileOutputFormat.getOutputPath(context),
		        context);
	}
}
