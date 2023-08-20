package org.micromanager.acqj.internal;

import org.micromanager.acqj.api.AcqNotificationListener;
import org.micromanager.acqj.main.AcqNotification;

import java.util.LinkedList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Class that handles asynchronous notifications from the acquisition engine
 * Runs a dedicated thread that stores notifications and dispatchems them to listeners
 */
public class NotificationHandler {

   private ExecutorService executor_ = Executors.newSingleThreadExecutor(r ->
           new Thread(r, "Acquisition Notification Thread"));
   private ConcurrentLinkedDeque<AcqNotification> notificationQueue_ = new ConcurrentLinkedDeque<>();

   private BlockingQueue<AcqNotificationListener> listeners_ = new LinkedBlockingQueue<>();

   public NotificationHandler() {
      executor_.submit(() -> {
         while (true) {
            AcqNotification n = notificationQueue_.pollFirst();
            if (n != null) {
               for (AcqNotificationListener l : listeners_) {
                  l.postNotification(n);
               }
            }
            if (executor_.isShutdown()) {
               break;
            }
         }
         // Listeners are responsible for shutting themselves down when they receive the
         // acquisition finished notification
      });
   }

   private void shutdown() {
      while (!notificationQueue_.isEmpty()) {
         try {
            Thread.sleep(10);
         } catch (InterruptedException e) {
            e.printStackTrace();
         }
      }
      executor_.shutdown();
   }
   public void postNotification(AcqNotification notification) {
      notificationQueue_.add(notification);
      if (notificationQueue_.size() > 500) {
         System.err.println("Warning: Acquisition notification queue size: " + notificationQueue_.size());
      }
      if (notification.isAcquisitionFinishedNotification()) {
         shutdown();
      }
   }

   public void addListener(AcqNotificationListener listener) {
      listeners_.add(listener);
   }
}
