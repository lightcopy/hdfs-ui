package com.github.lightcopy.fs;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSInotifyEventInputStream;
import org.apache.hadoop.hdfs.client.HdfsAdmin;
import org.apache.hadoop.ipc.RemoteException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;

import com.github.lightcopy.conf.AppConf;

public class HdfsManager {
  private static final Logger LOG = LoggerFactory.getLogger(HdfsManager.class);

  public static final String MONGO_DATABASE = "dbfs";
  public static final String MONGO_COLLECTION_FILE_SYSTEM = "filesystem";
  public static final String MONGO_COLLECTION_EVENT_POOL = "eventpool";

  private HdfsAdmin admin;
  private FileSystem fs;
  private MongoClient mongo;
  private MongoFileSystem mongoFS;
  private MongoEventPool mongoEventPool;
  private Path root;
  private DFSInotifyEventInputStream eventStream;
  private EventProcess eventProcess;
  private Thread eventProcessThread;

  public HdfsManager(AppConf conf) {
    this(conf.hdfsURI(), new Path("/"), conf.mongoConnectionString());
  }

  public HdfsManager(URI hdfsURI, Path root, String mongoConnection) {
    try {
      LOG.info("Initialize hdfs manager with uri {}", hdfsURI);
      Configuration hadoopConfiguration = new Configuration(false);
      this.admin = new HdfsAdmin(hdfsURI, hadoopConfiguration);
      this.eventStream = this.admin.getInotifyEventStream();
      LOG.info("Initialize file system for uri {}", hdfsURI);
      this.fs = FileSystem.get(hdfsURI, hadoopConfiguration);
      LOG.info("Initialize mongo client for connection {}", mongoConnection);
      this.mongo = new MongoClient(new MongoClientURI(mongoConnection));
      LOG.info("Set root path as {}", root);
      this.root = root;
      // Mongo will create database if one does not exist already, the same applies to collections,
      // they are only created when inserting document. Calling cleanup state after initializing
      // properties is okay - database and collections will be recreated
      this.mongoFS = new MongoFileSystem(
        this.mongo.getDatabase(MONGO_DATABASE).getCollection(MONGO_COLLECTION_FILE_SYSTEM));
      this.mongoEventPool = new MongoEventPool(
        this.mongo.getDatabase(MONGO_DATABASE).getCollection(MONGO_COLLECTION_EVENT_POOL));
    } catch (IOException ioe) {
      String msg = "Failed to initialize hdfs manager";
      LOG.error(msg, ioe);
      throw new RuntimeException(msg, ioe);
    }
  }

  private void cleanupState() {
    // delete database if exists
    LOG.info("Delete Mongo database {}", MONGO_DATABASE);
    this.mongo.getDatabase(MONGO_DATABASE).drop();
  }

  private void startEventProcessing() {
    this.eventProcess = new EventProcess(this);
    this.eventProcessThread = new Thread(this.eventProcess);
    LOG.info("Start event processing ({})", this.eventProcessThread);
    this.eventProcessThread.start();
  }

  private void stopEventProcessing() {
    LOG.info("Stop event processing ({})", this.eventProcessThread);
    if (this.eventProcessThread != null) {
      try {
        this.eventProcess.terminate();
        this.eventProcessThread.join();
        // reset properties to null
        this.eventProcessThread = null;
        this.eventProcess = null;
      } catch (InterruptedException err) {
        throw new RuntimeException("Intrerrupted thread " + this.eventProcessThread, err);
      }
    }
  }

  /**
   * Traverse root directory and propagate visitor for indexing.
   */
  private void indexFileSystem() throws IOException {
    FileSystem fs = getFileSystem();
    Path root = getRoot();
    FileStatus rootStatus = fs.getFileStatus(root);
    if (!rootStatus.isDirectory()) {
      throw new IllegalArgumentException("Expected root path as directory, got " + rootStatus);
    }
    TreeVisitor visitor = prepareTreeVisitor();
    walkTree(fs, rootStatus, visitor);
  }

  /**
   * Walk file system tree starting with root directory. Root must be a valid directory, otherwise
   * traversal is ignored, each file or symlink (non-directory) node is processed as child of
   * current tree traversal.
   */
  private void walkTree(FileSystem fs, FileStatus root, TreeVisitor visitor)
      throws FileNotFoundException, IOException {
    if (root.isDirectory()) {
      visitor.visitBefore(root);
      FileStatus[] children = fs.listStatus(root.getPath());
      if (children != null && children.length > 0) {
        for (FileStatus child : children) {
          if (child.isDirectory()) {
            TreeVisitor levelVisitor = prepareTreeVisitor();
            walkTree(fs, child, levelVisitor);
            visitor.visitChild(levelVisitor);
          } else {
            visitor.visitChild(child);
          }
        }
      }
      visitor.visitAfter();
    }
  }

  protected DFSInotifyEventInputStream getEventStream() {
    return this.eventStream;
  }

  /**
   * Prepare tree visitor for a directory. All initialization code should go into this method,
   * including allocating buffers for child leaves, etc. This method is invoked before walking
   * part of the tree.
   */
  public TreeVisitor prepareTreeVisitor() {
    return new NodeTreeVisitor(mongoFileSystem());
  }

  /**
   * Get file system used by this file system manager.
   * Should return the same instance when called multiple times.
   */
  public FileSystem getFileSystem() {
    return this.fs;
  }

  /**
   * Return root path for traversing managed by this file system manager. Must be visible for
   * current traversal and must be a directory. Method should be stable, and return the same path
   * when called multiple times.
   */
  public Path getRoot() {
    return this.root;
  }

  /** Get mongo file system to manage metadata */
  protected MongoFileSystem mongoFileSystem() {
    return this.mongoFS;
  }

  /** Get mongo event pool to store hdfs events */
  protected MongoEventPool mongoEventPool() {
    return this.mongoEventPool;
  }

  /**
   * Initialize manager, this should include buffering streams, creating connections, and file
   * system. Method is called only once.
   */
  public void start() {
    long startTime = System.nanoTime();
    LOG.info("Start hdfs manager");
    try {
      // cleanup state
      LOG.info("Clean up current state");
      cleanupState();
      // traverse and reindex file system
      LOG.info("Index file system");
      indexFileSystem();
      // start thread to process events
      LOG.info("Start processing thread");
      startEventProcessing();
    } catch (IOException ioe) {
      String msg = "Failed to start hdfs manager";
      LOG.error(msg, ioe);
      throw new RuntimeException(msg, ioe);
    }
    long endTime = System.nanoTime();
    LOG.info("Started in {} ms", (endTime - startTime) / 1e6);
  }

  /**
   * Method to return status as true/false, on whether or not all systems for hdfs manager are
   * running. This can also print necessary status details on overall performance. Return `true`,
   * if event process thread is running correctly and other threads are okay.
   */
  public boolean status() {
    boolean isAlive = !this.eventProcess.isStopped();
    try {
      isAlive = isAlive && this.mongo.getServerAddressList() != null;
    } catch (Exception err) {
      isAlive = false;
    }
    return isAlive;
  }

  /**
   * Close associated resources, e.g. connection, event stream, etc.
   * Method is called only once.
   */
  public void stop() {
    long startTime = System.nanoTime();
    LOG.info("Stop hdfs manager");
    stopEventProcessing();
    // it does not seem like you can close event stream
    this.eventStream = null;
    // reset hdfs admin
    this.admin = null;
    // close mongodb
    this.mongo.close();
    this.mongo = null;
    long endTime = System.nanoTime();
    LOG.info("Stopped in {} ms", (endTime - startTime) / 1e6);
  }
}
