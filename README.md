#work

work is a new generalized worker pool core for clojure.

Work has a theme song. [Let's work.](http://www.youtube.com/watch?v=SGHgGCB-rMc&feature=related)

## Usage

Here is an example of blocking workers.

    user> (require '[work.core :as work])
    user> (work/work  [#(+ 1 2)  #(- 1 2)]  2)
    (3  -1)
    user> (doc work/work)
    -------------------------
    work.core/work
    ([fns threads])
      takes a seq of fns executes them in parallel on n threads, blocking until all work is done.

Here is an example of map-work; a parallel blocking fetcher.

    user> (require '[work.core :as work])
    user> (work/map-work client/get ["http://clojure.org" "http://github.com/richhickey/clojure"] 2)
    user> (doc work/map-work)
    -------------------------
    work.core/map-work
    ([f xs threads])
      like clojure's map or pmap, but takes a number of threads, executes eagerly, and blocks.

work is designed for use in large scale distributed worker scenarios, but is not coupled to a particular network transport.  It can be used with various queues like SQS and rabbitmq, or with custom communications layers built on native java networking capabilities.

Here is an example of queue-work from core-test.

    (deftest queue-work-test
      (let [request-q (work/local-queue (range 1 101 1))
        response-q (work/local-queue)
        pool (future
              (work/queue-work
               #(* 10 %)
               #(work/poll request-q)
               #(work/offer response-q %)
               10))
        _ (Thread/sleep 1000)]
      (is (= (range 10 1010 10)
         (iterator-seq (.iterator response-q))))))

The doc string should explain what's going on here.

    user> (doc work/queue-work)
    -------------------------
    work.core/queue-work
    ([f get-work put-done threads])
      schedule-work one worker function f per thread.  f can either be a fn that is directly applied to each task (all workers use the same fn) or f builds and evals a worker from a fn & args passed over the queue (each worker executes an arbitrary fn.)  Examples of the latter are clj-worker and json-wroker.

As you can see from the test, work contains a local-queue for testing, which creates a LinkedBlockingQueue.

The example above shows workers consuming from a queue in an argument-on-the-queue case.  Let's look at a fn&args-on-the-queue case.

    (deftest clj-fns-over-the-queue
      (let [request-q (work/local-queue (map #(work/send-clj %1 %2)
                         (repeat #'times10)
                         (range 1 101 1)))
        response-q (work/local-queue)
        pool (future
              (work/queue-work
               work/clj-worker
               #(work/poll request-q)
               #(work/offer response-q %)
               10))
        _ (Thread/sleep 5000)]
        (is (= (range 10 1010 10)
           (iterator-seq (.iterator response-q))))))

Note that we just inject work/clj-worker - which knows how to get the serialized fn&args from a message on the queue.  Even more flexible, we also have the work/json-worker, which allows other languages to pushing tasks onto Clojure work queues.

    (deftest json-fns-over-the-queue
      (let [request-q (work/local-queue (map #(work/send-json %1 %2)
                         (repeat #'times10)
                         (range 1 101 1)))
        response-q (work/local-queue)
        pool (future
              (work/queue-work
               work/json-worker
               #(work/poll request-q)
               #(work/offer response-q %)
               10))
        _ (Thread/sleep 6000)]
        (is (= (range 10 1010 10)
           (iterator-seq (.iterator response-q))))))

Note that almost nothing has changed.  We just create our messages using work/send-json rather than work/send-clj, and we pass work/json-worker rather than work/clj-worker as the worker constructor to the queue-work function.

work can schedule work as well.

    user> (require '[work.core :as work])
    user> (work/schedule-work [[#(println "foo") 2] [#(println "bar") 5]])

    *
    bar
    foo
    foo
    bar
    foo
    foo

*note: if you are using slime in emacs, you need to look in *inferior-lisp* for these results.

A look at the docstring shows what is going on here.

    user> (doc work/schedule-work)
    -------------------------
    work.core/schedule-work
    ([pool f rate] [jobs])
      schedules work. cron for clojure fns.  Schedule a single fn with a pool to run every n seconds, where n is specified by the rate arg, or supply a vector of fn-rate tuples to schedule a bunch of fns at once.

## How to distributed the work across network boundaries?

Pick your queue.  work is not coupled to any particular distribution mechanism.  It is designed to be testiable, useable locally, and composable with queue serives such as SQS, or other distributed queues like rabbitmq (see rabbitcj.)

## What JVM tuning parameters for work servers?

Be aware that when you start work server JVMs, you should be mindful of tuning parameters.  You probably don't need to get to crazy, but you should at least use the -server option.  Depending on your threads-to-logical-processors ratio, you might also want to adjust stack size to avoid excessive context switching.  You might also want to explore other HotSpot VM options, like -XX:-UseSpinning and -XX:-UseParallelGC.