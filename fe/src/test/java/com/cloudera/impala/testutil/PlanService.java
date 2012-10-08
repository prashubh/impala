// Copyright (c) 2011 Cloudera, Inc. All rights reserved.

package com.cloudera.impala.testutil;

import java.util.Enumeration;
import java.util.Properties;
import java.util.Set;

import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.log4j.Logger;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TTransportException;

import com.cloudera.impala.common.ImpalaException;
import com.cloudera.impala.service.Frontend;
import com.cloudera.impala.thrift.ImpalaPlanService;
import com.cloudera.impala.thrift.TCatalogUpdate;
import com.cloudera.impala.thrift.TClientRequest;
import com.cloudera.impala.thrift.TExecRequest;
import com.cloudera.impala.thrift.TImpalaPlanServiceException;
import com.google.common.collect.Sets;

/**
 * Service to construct a TPlanExecRequest for a given query string.
 * We're implementing that as a stand-alone service, rather than having
 * the backend call the corresponding Coordinator function, because
 * the process would crash somewhere during metastore setup when running
 * under gdb.
 */
public class PlanService {
  // Note: query-executor.cc has a hardcoded port of 20000, be cautious if overriding this
  // value
  public static final int DEFAULT_PORT = 20000;
  final PlanServiceHandler handler;
  final TServer server;

  private static class PlanServiceHandler implements ImpalaPlanService.Iface {
    private static final Logger LOG = Logger.getLogger(PlanService.class);
    private final Frontend frontend;

    public PlanServiceHandler(boolean lazy) {
      frontend = new Frontend(lazy);
    }

    public TExecRequest CreateExecRequest(TClientRequest tRequest)
        throws TImpalaPlanServiceException {
      LOG.info(
          "Executing '" + tRequest.stmt + "' for " +
          Integer.toString(tRequest.queryOptions.num_nodes) + " nodes");
      StringBuilder explainStringBuilder = new StringBuilder();
      TExecRequest result;
      try {
        result = frontend.createExecRequest(tRequest, explainStringBuilder);
      } catch (ImpalaException e) {
        LOG.warn("Error creating query request result", e);
        throw new TImpalaPlanServiceException(e.getMessage());
      }

      // Print explain string.
      LOG.info(explainStringBuilder.toString());

      LOG.info("returned TExecRequest: " + result.toString());
      return result;
    }

    public void ShutdownServer() {
      frontend.close();
      System.exit(0);
    }

    /**
     * Loads an updated catalog from the metastore. Is thread-safe.
     */
    @Override
    public void RefreshMetadata() {
      frontend.resetCatalog();
    }

    @Override
    public String GetExplainString(TClientRequest tRequest)
        throws TImpalaPlanServiceException {
      try {
        return frontend.getExplainString(tRequest);
      } catch (ImpalaException e) {
        LOG.warn("Error getting explain string", e);
        throw new TImpalaPlanServiceException(e.getMessage());
      }
    }

    /**
     * Updates the metastore with new partitions
     */
    @Override
    public void UpdateMetastore(TCatalogUpdate update)
        throws TImpalaPlanServiceException {
      try {
        frontend.updateMetastore(update);
      } catch (ImpalaException e) {
        throw new TImpalaPlanServiceException(e.getMessage());
      }
    }
  }

  public PlanService(int port, boolean lazy) throws TTransportException {
    handler = new PlanServiceHandler(lazy);
    ImpalaPlanService.Processor proc = new ImpalaPlanService.Processor(handler);
    TServerTransport transport = new TServerSocket(port);
    server =
      new TThreadPoolServer(new TThreadPoolServer.Args(transport).processor(proc));
  }

  /**
   * If lazy is true, load the catalog lazily rather than eagerly at start-up
   */
  public PlanService(boolean lazy) throws TTransportException, MetaException {
    this(DEFAULT_PORT, lazy);
  }

  /**
   * Waits for some length of time for the plan server to come up, and returns true if it
   * does, and false if not.
   */
  public boolean waitForServer(long timeoutMs) {
    long start = System.currentTimeMillis();
    while (!server.isServing()) {
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {

      }
      long now = System.currentTimeMillis();
      if (now - start > timeoutMs) {
        return false;
      }
    }
    return true;
  }

  public void serve() throws MetaException, TTransportException {
    server.serve();
  }

  public void shutdown() {
    server.stop();
  }

  private static final String NON_LAZY_ARG = "-nonlazy";

  public static void main(String[] args) throws TTransportException, MetaException {
    Set<String> argSet = Sets.newHashSet(args);
    PlanService service = new PlanService(!argSet.contains(NON_LAZY_ARG));
    try {
      service.serve();
    } catch (Exception e) {
      System.err.println(e.getMessage());
      System.exit(2);
    }

    Properties prop = System.getProperties();
    Enumeration<Object> keys = prop.keys();
    while (keys.hasMoreElements()) {
      String key = (String)keys.nextElement();
      String value = (String)prop.get(key);
      System.out.println(key + ": " + value);
    }
  }

}
