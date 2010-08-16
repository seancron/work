(ns work.core-test
 (:use clojure.test)
 (:require [work.core :as work]))

(deftest successfull
  (is (= 10
    (work/retry 5 + 4 6))))

(deftest failure
  (is (= {:fail 1}
    (work/retry 5 / 4 0))))

(defn foo [] 1)

(deftest var-roundtrip
  (is (= 1
	 ((apply work/to-var (work/from-var #'foo)))))) 

(defn add [& args] (apply + args))

(deftest send-and-recieve-clj
  (is (= 6
	 (eval (work/recieve-clj (work/send-clj #'add 1 2 3))))))

(deftest map-work-test
  (is (= (range 10 1010 10)
	 (work/map-work #(* 10 %)
		   (range 1 101 1)
		   10))))

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

(defn times10 [x] (* 10 x))

(deftest fns-over-the-queue
  (let [request-q (work/local-queue (map #(work/send-clj %1 %2)
					 (repeat #'times10)
					 (range 1 101 1)))
	response-q (work/local-queue)
	pool (future
	      (work/queue-work
	       #(work/poll request-q)
	       #(work/offer response-q %) 
	       10))
	_ (Thread/sleep 5000)]
    (is (= (range 10 1010 10)
	   (iterator-seq (.iterator response-q))))))
