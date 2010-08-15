(ns work.core
  (:import (java.util.concurrent Executors ExecutorService TimeUnit))
  (:import clojure.lang.RT))

(defn from-var [#^Var fn-var]
  (let [m (meta fn-var)]
    [(str (:ns m)) (str (:name m))]))

(defn to-var [#^String ns-name #^String fn-name]
  (let [root (.replace 
	      (.replace ns-name "-", "_")
	      "." "/")]
    (do 
    (try (RT/load root)
	 (catch Exception _ _))
    (.deref (RT/var ns-name, fn-name)))))

(defn available-processors []
(.availableProcessors (Runtime/getRuntime)))

(defn retry [retries f & args]
  "Retries applying f to args based on the number of retries.
  catches generic Exception, so if you want to do things with other exceptions, you must do so in the client code inside f."
  (try (apply f args)
    (catch java.lang.Exception _
      (if (> retries 0)
        (apply retry (- retries 1) f args)
        {:fail 1}))))

;;TODO: try within a retry loop?
(defn try-job [f]
  #(try (f)
     (catch Exception e
       (.printStackTrace e))))

(defn schedule-work
  ([pool f rate]
     (.schedule-workAtFixedRate pool (try-job f) 0 rate TimeUnit/SECONDS))
  ([jobs]
     (let [pool (Executors/newSingleThreadScheduledExecutor)] 
       (doall (for [[f rate] jobs]
		(schedule-work pool f rate))))))

(defn- work*
[fns threads]
  (let [pool (Executors/newFixedThreadPool threads)]
    (.invokeAll pool fns)))

(defn work
"takes a seq of fns executes them in parallel on n threads, blocking until all work is done."
[fns threads]
  (map #(.get %) (work* fns threads)))

(defn map-work [f xs threads]
  (work (doall (map (fn [x] #(f x)) xs)) threads))

(defn local-queue
  ([]
     (java.util.LinkedList.))
  ([xs]
     (java.util.LinkedList. xs)))

(defn offer [q v] (.offer q v))
(defn peek [q] (.peek q))
(defn poll [q] (.poll q))

;;TODO: fn & data on the queue, take them off, intern the fn, and apply.
;;TODO; unable to shutdown pool. seems recursive fns are not responding to interrupt. http://download.oracle.com/javase/tutorial/essential/concurrency/interrupt.html
(defn queue-work
"schedule-work one worker function f per thread.

f can be a pipeline of functions - for example, the work itself, writing the result of the work to s3 and notifying the system the work is complete by placing it on the done queue.

Each worker fn polls the work queue via get-work fn, applies a fn to each dequeued item, and recursively checks for more work.  If it doesn't find new work, it waits until checking for more work."
[f get-work threads]
  (let [pool (Executors/newFixedThreadPool threads)
	fns (repeatedly threads
			(fn [] (do
				 (if-let [x (get-work)]
				   (f x)
				   (Thread/sleep 5000))
				 (recur))))
	futures (doall (map #(.submit pool %) fns))]
    ;;TODO: use another thread to check futures and make sure workers don't fail, don't hang, and call for work within their time limit?
    pool))

(defn shutdown
  "Initiates an orderly shutdown in which previously submitted tasks are executed, but no new tasks will be accepted. Invocation has no additional effect if already shut down."
  [executor]
    (do (.shutdown executor) executor))

(defn shutdown-now [executor]
"Attempts to stop all actively executing tasks, halts the processing of waiting tasks, and returns a list of the tasks that were awaiting execution.

There are no guarantees beyond best-effort attempts to stop processing actively executing tasks. For example, typical implementations will cancel via Thread.interrupt(), so if any tasks mask or fail to respond to interrupts, they may never terminate."
    (do (.shutdownNow executor) executor))

(defn two-phase-shutdown
"Shuts down an ExecutorService in two phases.
Call shutdown to reject incoming tasks.
Calling shutdownNow, if necessary, to cancel any lingering tasks.
From: http://download-llnw.oracle.com/javase/6/docs/api/java/util/concurrent/ExecutorService.html"
[#^ExecutorService pool]
  (do (.shutdown pool)  ;; Disable new tasks from being submitted
      (try ;; Wait a while for existing tasks to terminate
       (if (not (.awaitTermination pool 60 TimeUnit/SECONDS))
	 (.shutdownNow pool) ; // Cancel currently executing tasks
	 ;;wait a while for tasks to respond to being cancelled
	 (if (not (.awaitTermination pool 60 TimeUnit/SECONDS))
           (println "Pool did not terminate" *err*)))
       (catch InterruptedException _ _
	      (do
		;;(Re-)Cancel if current thread also interrupted
		(.shutdownNow pool)
		;; Preserve interrupt status
		(.interrupt (Thread/currentThread)))))))
