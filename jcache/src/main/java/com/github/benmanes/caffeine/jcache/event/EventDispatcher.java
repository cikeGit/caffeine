/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.jcache.event;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.cache.Cache;
import javax.cache.configuration.CacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryEvent;
import javax.cache.event.CacheEntryEventFilter;
import javax.cache.event.CacheEntryListener;
import javax.cache.event.EventType;

/**
 * A dispatcher that publishes cache events to listeners for asynchronous execution.
 * <p>
 * A {@link CacheEntryListener} is required to receive events in the order of the actions being
 * performed on the associated entry. This implementation supports this through an actor-like model
 * by using a dispatch queue per listener. A listener is never executed in parallel on different
 * events, but may be executed sequentially on different threads. Batch processing of the dispatch
 * queue is not presently supported.
 * <p>
 * Some listeners may be configured as <tt>synchronous</tt>, meaning that the publishing thread
 * should wait until the listener has processed the event. The calling thread should publish within
 * an atomic block that mutates the entry, and complete the operation by calling
 * {@link #awaitSynchronous()}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class EventDispatcher<K, V> {
  private static final ThreadLocal<List<CompletableFuture<Void>>> pending =
      ThreadLocal.withInitial(ArrayList::new);

  private final Map<Registration<K, V>, CompletableFuture<Void>> dispatchQueues;

  public EventDispatcher() {
    dispatchQueues = new ConcurrentHashMap<>();
  }

  /**
   * Registers a cache entry listener based on the supplied configuration.
   *
   * @param configuration the listener's configuration.
   */
  public void register(CacheEntryListenerConfiguration<K, V> configuration) {
    if (configuration.getCacheEntryListenerFactory() == null) {
      return;
    }
    EventTypeAwareListener<K, V> listener = new EventTypeAwareListener<>(
        configuration.getCacheEntryListenerFactory().create());

    CacheEntryEventFilter<K, V> filter = (event) -> true;
    if (configuration.getCacheEntryEventFilterFactory() != null) {
      filter = new EventTypeFilter<K, V>(listener,
          configuration.getCacheEntryEventFilterFactory().create());
    }

    Registration<K, V> registration = new Registration<>(configuration, filter, listener);
    dispatchQueues.putIfAbsent(registration, CompletableFuture.completedFuture(null));
  }

  /**
   * Deregisters a cache entry listener based on the supplied configuration.
   *
   * @param configuration the listener's configuration.
   */
  public void deregister(CacheEntryListenerConfiguration<K, V> configuration) {
    requireNonNull(configuration);
    dispatchQueues.keySet().removeIf(registration ->
        configuration.equals(registration.getConfiguration()));
  }

  /**
   * Publishes a creation event for the entry to all of the interested listeners.
   *
   * @param cache the cache where the entry was created
   * @param key the entry's key
   * @param value the entry's value
   */
  public void publishCreated(Cache<K, V> cache, K key, V value) {
    CacheEntryEvent<K, V> event = new JCacheEntryEvent<>(
        cache, EventType.CREATED, key, null, value);
    publish(event, listener -> listener.onCreated(Collections.singleton(event)));
  }

  /**
   * Publishes a update event for the entry to all of the interested listeners.
   *
   * @param cache the cache where the entry was updated
   * @param key the entry's key
   * @param oldValue the entry's old value
   * @param newValue the entry's new value
   */
  public void publishUpdated(Cache<K, V> cache, K key, V oldValue, V newValue) {
    CacheEntryEvent<K, V> event = new JCacheEntryEvent<>(
        cache, EventType.UPDATED, key, oldValue, newValue);
    publish(event, listener -> listener.onUpdated(Collections.singleton(event)));
  }

  /**
   * Publishes a remove event for the entry to all of the interested listeners.
   *
   * @param cache the cache where the entry was removed
   * @param key the entry's key
   * @param value the entry's value
   */
  public void publishRemoved(Cache<K, V> cache, K key, V value) {
    CacheEntryEvent<K, V> event = new JCacheEntryEvent<>(
        cache, EventType.REMOVED, key, null, value);
    publish(event, listener -> listener.onRemoved(Collections.singleton(event)));
  }

  /**
   * Publishes a expire event for the entry to all of the interested listeners.
   *
   * @param cache the cache where the entry expired
   * @param key the entry's key
   * @param value the entry's value
   */
  public void publishExpired(Cache<K, V> cache, K key, V value) {
    CacheEntryEvent<K, V> event = new JCacheEntryEvent<>(
        cache, EventType.EXPIRED, key, value, null);
    publish(event, listener -> listener.onExpired(Collections.singleton(event)));
  }

  /**
   * Blocks until all of the synchronous listeners have finished processing the events this thread
   * published.
   */
  public void awaitSynchronous() {
    List<CompletableFuture<Void>> futures = pending.get();
    try {
      if (!futures.isEmpty()) {
        CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[futures.size()])).join();
      }
    } catch (CompletionException e) {
      // ignored
    } finally {
      futures.clear();
    }
  }

  /** Broadcasts the event to all of the interested listener's dispatch queues. */
  private void publish(CacheEntryEvent<K, V> event,
      Consumer<EventTypeAwareListener<K, V>> operation) {
    dispatchQueues.keySet().stream()
        .filter(registration -> registration.getCacheEntryFilter().evaluate(event))
        .map(registration -> {
          Runnable action = () -> operation.accept(registration.getCacheEntryListener());
          CompletableFuture<Void> future = dispatchQueues.computeIfPresent(
              registration, (key, queue) -> queue.thenRunAsync(action));
          return ((future != null) && registration.isSynchronous()) ? future : null;
        }).filter(Objects::nonNull).forEach(pending.get()::add);
  }
}
