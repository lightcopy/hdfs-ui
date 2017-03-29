package com.github.lightcopy.fs;

import java.util.Random;

import org.apache.hadoop.hdfs.inotify.Event;
import org.apache.hadoop.hdfs.inotify.EventBatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Event processing thread to capture HDFS events.
 */
public class EventProcess implements Runnable {
  private static final Logger LOG = LoggerFactory.getLogger(EventProcess.class);
  // polling interval in milliseconds = 0.25 sec + random interval
  public static final int POLLING_INTERVAL_MS = 250;

  private final HdfsManager manager;
  private volatile boolean stopped;
  private final Random rand;

  public EventProcess(HdfsManager manager) {
    this.manager = manager;
    this.stopped = false;
    this.rand = new Random();
  }

  @Override
  public void run() {
    EventBatch batch = null;
    while (!this.stopped) {
      try {
        while ((batch = this.manager.getEventStream().poll()) != null) {
          long transaction = batch.getTxid();
          LOG.debug("Processing batch transaction {}", transaction);
          for (Event event : batch.getEvents()) {
            processEvent(event, transaction);
          }
        }
        long interval = POLLING_INTERVAL_MS + rand.nextInt(POLLING_INTERVAL_MS);
        LOG.trace("Waiting to poll, interval={}", interval);
        Thread.sleep(interval);
      } catch (Exception err) {
        LOG.error("Thread intrerrupted", err);
        this.stopped = true;
      }
    }
  }

  /** Whether or not event process is stopped */
  public boolean isStopped() {
    return this.stopped;
  }

  /** Mark event process thread as terminated */
  public void terminate() {
    this.stopped = true;
  }

  /**
   * Invoke one of the methods to process specific event, works as dispatcher.
   * Throws exception if event is unsupported or null.
   */
  protected void processEvent(Event event, long transactionId) {
    if (event == null) {
      throw new NullPointerException("Event null for transaction " + transactionId);
    }

    if (event instanceof Event.AppendEvent) {
      doAppend((Event.AppendEvent) event, transactionId);
    } else if (event instanceof Event.CloseEvent) {
      doClose((Event.CloseEvent) event, transactionId);
    } else if (event instanceof Event.CreateEvent) {
      doCreate((Event.CreateEvent) event, transactionId);
    } else if (event instanceof Event.MetadataUpdateEvent) {
      doMetadataUpdate((Event.MetadataUpdateEvent) event, transactionId);
    } else if (event instanceof Event.RenameEvent) {
      doRename((Event.RenameEvent) event, transactionId);
    } else if (event instanceof Event.UnlinkEvent) {
      doUnlink((Event.UnlinkEvent) event, transactionId);
    } else {
      throw new UnsupportedOperationException("Unrecognized event " + event);
    }
  }

  protected void doAppend(Event.AppendEvent event, long transactionId) {
    LOG.info("APPEND(path={})", event.getPath());
  }

  protected void doClose(Event.CloseEvent event, long transactionId) {
    LOG.info("CLOSE(filesize={}, path={}, ts={})",
      event.getFileSize(), event.getPath(), event.getTimestamp());
  }

  protected void doCreate(Event.CreateEvent event, long transactionId) {
    LOG.info("CREATE(group={}, owner={}, path={})",
      event.getGroupName(), event.getOwnerName(), event.getPath());
  }

  protected void doMetadataUpdate(Event.MetadataUpdateEvent event, long transactionId) {
    LOG.info("METADATA(acls={}, group={}, owner={}, path={}, atime={}, perms={})",
      event.getAcls(), event.getGroupName(), event.getOwnerName(), event.getPath(),
      event.getAtime(), event.getPerms());
  }

  protected void doRename(Event.RenameEvent event, long transactionId) {
    LOG.info("RENAME(ts={}, src={}, dst={})",
      event.getTimestamp(), event.getSrcPath(), event.getDstPath());
  }

  /**
   * Process event that deletes object from file system. In this particular case we do not need to
   * fetch file status from hdfs, just reconstruct path and delete recursively all child paths.
   */
  protected void doUnlink(Event.UnlinkEvent event, long transactionId) {
    LOG.info("UNLINK(ts={}, path={})", event.getTimestamp(), event.getPath());
  }
}
