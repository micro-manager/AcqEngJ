package org.micromanager.acqj.internal;

import java.util.concurrent.LinkedBlockingDeque;
import org.micromanager.acqj.api.AcqNotificationListener;
import org.micromanager.acqj.main.AcqNotification;

import java.util.concurrent.BlockingQueue;
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
   private LinkedBlockingDeque<AcqNotification> notificationQueue_ = new LinkedBlockingDeque<AcqNotification>();

   private BlockingQueue<AcqNotificationListener> listeners_ = new LinkedBlockingQueue<>();

   public NotificationHandler() {
      executor_.submit(() -> {
         boolean eventsFinished = false;
         boolean dataSinkFinished = false;
         while (true) {
            try {
               AcqNotification n = notificationQueue_.takeFirst();
               for (AcqNotificationListener l : listeners_) {
                  l.postNotification(n);
               }

               if (n.isAcquisitionEventsFinishedNotification()) {
                  eventsFinished = true;
               }
                if (n.isDataSinkFinishedNotification()) {
                    dataSinkFinished = true;
                }

               if (eventsFinished && dataSinkFinished) {
                  executor_.shutdown();
                  break;
               }
            } catch (InterruptedException e) {
               //This should not happen
               e.printStackTrace();
            }
         }
         // Listeners are responsible for shutting themselves down when they receive the
         // acquisition finished notification
      });
   }

   public void postNotification(AcqNotification notification) {
      notificationQueue_.add(notification);
      if (notificationQueue_.size() > 500) {
         System.err.println("Warning: Acquisition notification queue size: " + notificationQueue_.size());
      }
   }

   public void addListener(AcqNotificationListener listener) {
      listeners_.add(listener);
   }
}
