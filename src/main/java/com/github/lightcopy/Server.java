package com.github.lightcopy;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Properties;

import javax.ws.rs.core.UriBuilder;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.lightcopy.conf.AppConf;

/**
 * Abstract class that is shared between production implementation and test server for frontend.
 */
public abstract class Server {
  protected static final Logger LOG = LoggerFactory.getLogger(Server.class);

  protected final String scheme;
  protected final String host;
  protected final int port;
  protected AppConf conf;
  private final HttpServer server;
  private ArrayList<Runnable> events;

  /**
   * Shutdown hook to gracefully stop web server.
   */
  static class ServerShutdown implements Runnable {
    private final Server server;

    ServerShutdown(Server server) {
      this.server = server;
    }

    @Override
    public void run() {
      this.server.shutdown();
    }

    @Override
    public String toString() {
      return "ServerShutdown" + this.server;
    }
  }

  /**
   * Initialize server from properties.
   * Use zero-argument constructor in subclasses, server will be initialized with system
   * properties by default.
   */
  private Server(Properties props) {
    this.conf = new AppConf(props);
    this.scheme = this.conf.scheme();
    this.host = this.conf.httpHost();
    this.port = this.conf.httpPort();
    // initialize events list and internal server
    this.events = new ArrayList<Runnable>();
    this.server = createHttpServer(this.conf);
  }

  public Server() {
    this(System.getProperties());
  }

  /** Create endpoint uri from initialized properties */
  protected URI createEndpoint() {
    return UriBuilder.fromPath("")
      .scheme(this.scheme)
      .host(this.host)
      .port(this.port)
      .build();
  }

  /** Create http server from initialized properties */
  protected HttpServer createHttpServer(AppConf conf) {
    URI endpoint = createEndpoint();
    ApplicationContext context = new ApplicationContext(conf);
    return GrizzlyHttpServerFactory.createHttpServer(endpoint, context);
  }

  /**
   * Shutdown server. This should not be used directly, instead call launch() method. It will
   * add shutdown hook to gracefully stop server.
   */
  protected void shutdown() {
    LOG.info("Stop server {}", this);
    this.server.shutdown();
  }

  /** Get current host */
  public String getHost() {
    return this.host;
  }

  /** Get current port */
  public int getPort() {
    return this.port;
  }

  /**
   * Register shutdown hook to call when server is about to be stopped. These events are always
   * called before server shutdown.
   */
  public void registerShutdownHook(Runnable event) {
    this.events.add(event);
  }

  /**
   * Start web server using provided options. As part of initialization registers all shutdown
   * hooks, including one for the server.
   */
  public void launch() throws IOException, InterruptedException {
    // register shutdown hook for server after all events
    registerShutdownHook(new ServerShutdown(this));
    for (Runnable event : this.events) {
      LOG.info("Register shutdown event {}", event);
      Runtime.getRuntime().addShutdownHook(new Thread(event));
    }
    LOG.info("Start server {}", this);
    this.server.start();
    afterLaunch();
  }

  /**
   * Launch functionality provided by subclasses, if required.
   * This gets invoked after server is up and running.
   */
  public void afterLaunch() {
    /* no-op */
  }

  @Override
  public String toString() {
    return "[" + getClass().getSimpleName() + " @ " + createEndpoint() + "]";
  }
}
